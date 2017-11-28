package fk.prof.userapi.cache;

import com.codahale.metrics.Counter;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.Parser;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.metrics.Util;
import fk.prof.userapi.proto.LoadInfoEntities.NodeLoadInfo;
import fk.prof.userapi.proto.LoadInfoEntities.ProfileResidencyInfo;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Shared storage based on zookeeper to store the mapping profile -> ip:port and maintain resource utilization counters
 * for each node in cluster.
 * It is used by {@link ClusterAwareCache}.
 *
 * zookeeper node structure:
 * /fkprof-userapi                                                                    (session namespace)
 * /fkprof-userapi/nodesInfo/{ip:port} -> {@link NodeLoadInfo}                                (ephemeral)
 * /fkprof-userapi/profilesLoadStatus/{profileName} -> {@link ProfileResidencyInfo}           (ephemeral)
 *
 * For resource utilization counter, currently only cached profile count is stored
 *
 * @see NodeLoadInfo
 * @see ProfileResidencyInfo
 *
 * Created by gaurav.ashok on 01/08/17.
 */
class ZkLoadInfoStore {
    private static final Logger logger = LoggerFactory.getLogger(ZkLoadInfoStore.class);
    private static final String distributedLockPath = "/global_mutex";

    private final String zkNodesInfoPath;
    private final CuratorFramework curatorClient;
    private final InterProcessSemaphoreMutex sharedMutex;

    private final AtomicReference<ConnectionState> connectionState;
    private final AtomicReference<LocalDateTime> lastZkLostTime;
    private final AtomicBoolean recentlyZkConnectionLost;

    private final byte[] myResidencyInfo;
    private Supplier<List<AggregatedProfileNamingStrategy>> cachedProfiles;

    private Counter lockAcquireTimeoutCounter = Util.counter("zk.lock.timeouts");
    private Counter onReconnectFailures = Util.counter("zk.onreconnect.failures");

    ZkLoadInfoStore(CuratorFramework curatorClient, String myIp, int port, Supplier<List<AggregatedProfileNamingStrategy>> cachedProfiles) {
        this.curatorClient = curatorClient;

        this.zkNodesInfoPath = "/nodesInfo/" + myIp + ":" + port;
        this.sharedMutex = new InterProcessSemaphoreMutex(curatorClient, distributedLockPath);
        this.lastZkLostTime = new AtomicReference<>(LocalDateTime.MIN);
        this.recentlyZkConnectionLost = new AtomicBoolean(false);

        this.myResidencyInfo = ProfileResidencyInfo.newBuilder().setIp(myIp).setPort(port).build().toByteArray();

        this.curatorClient.getConnectionStateListenable().addListener(this::zkStateChangeListener,
            Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("curator-state-listener").build()));

        this.cachedProfiles = cachedProfiles;

        this.connectionState = new AtomicReference<>(
            curatorClient.getZookeeperClient().isConnected() ? ConnectionState.Connected : ConnectionState.Disconnected);
    }

    boolean ensureBasePathExists() throws Exception {
        ensureConnected();
        if(curatorClient.checkExists().forPath(zkNodesInfoPath) == null) {
            curatorClient.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(zkNodesInfoPath, buildNodeLoadInfo(0).toByteArray());
        }
        else {
            curatorClient.setData().forPath(zkNodesInfoPath, buildNodeLoadInfo(0).toByteArray());
        }
        if(curatorClient.checkExists().forPath("/profilesLoadStatus") == null) {
            curatorClient.create().withMode(CreateMode.PERSISTENT).forPath("/profilesLoadStatus");
            return true;
        }
        return false;
    }

    boolean profileInfoExists(AggregatedProfileNamingStrategy profileName) throws Exception {
        ensureConnected();
        return pathExists(zkPathForProfile(profileName));
    }

    boolean nodeInfoExists() throws Exception {
        ensureConnected();
        return pathExists(zkNodesInfoPath);
    }

    ProfileResidencyInfo readProfileResidencyInfo(AggregatedProfileNamingStrategy profileName) throws Exception {
        ensureConnected();
        try {
            return readFrom(zkPathForProfile(profileName), ProfileResidencyInfo.parser());
        } catch (KeeperException.NoNodeException e) {
            return null;
        }
    }

    NodeLoadInfo readNodeLoadInfo() throws Exception {
        ensureConnected();
        try {
            return readFrom(zkNodesInfoPath, NodeLoadInfo.parser());
        } catch (KeeperException.NoNodeException e) {
            return null;
        }
    }

    void updateProfileResidencyInfo(AggregatedProfileNamingStrategy profileName, boolean profileNodeExists) throws Exception {
        ensureConnected();
        NodeLoadInfo nodeLoadInfo = readFrom(zkNodesInfoPath, NodeLoadInfo.parser());
        int profileLoadedCount = nodeLoadInfo.getProfilesLoaded() + (profileNodeExists ? 0 : 1);
        CuratorTransactionFinal transaction = curatorClient.inTransaction().setData().forPath(zkNodesInfoPath, buildNodeLoadInfo(profileLoadedCount).toByteArray()).and();
        if(profileNodeExists) {
            transaction = transaction.setData().forPath(zkPathForProfile(profileName), myResidencyInfo).and();
        }
        else {
            transaction = transaction.create().withMode(CreateMode.EPHEMERAL).forPath(zkPathForProfile(profileName), myResidencyInfo).and();
        }
        transaction.commit();
    }

    void removeProfileResidencyInfo(AggregatedProfileNamingStrategy profileName, boolean deleteProfileNode) throws Exception {
        ensureConnected();
        NodeLoadInfo nodeLoadInfo = readFrom(zkNodesInfoPath, NodeLoadInfo.parser());
        byte[] newData = buildNodeLoadInfo(nodeLoadInfo.getProfilesLoaded() - 1).toByteArray();
        if(deleteProfileNode) {
            curatorClient.inTransaction().setData().forPath(zkNodesInfoPath, newData)
                .and().delete().forPath(zkPathForProfile(profileName))
                .and().commit();
        }
        else {
            curatorClient.setData().forPath(zkNodesInfoPath, newData);
        }
    }

    AutoCloseable getLock() throws Exception {
        return getLock(false);
    }

    AutoCloseable getLock(Boolean canExpectAlreadyAcquired) throws Exception {
        ensureConnected();
        return new CloseableSharedLock(canExpectAlreadyAcquired);
    }

    ConnectionState getState() {
        return connectionState.get();
    }

    public enum ConnectionState {
        Disconnected,
        Connected
    }

    private void ensureConnected() throws ZkStoreUnavailableException {
        if(!ConnectionState.Connected.equals(connectionState.get())) {
            throw new ZkStoreUnavailableException("connection lost recently: " + recentlyZkConnectionLost.get() + ", lastTimeOfLostConnection: " + lastZkLostTime.get().toString());
        }
    }

    private boolean pathExists(String path) throws  Exception {
        return curatorClient.checkExists().forPath(path) != null;
    }

    private class CloseableSharedLock implements AutoCloseable {

        boolean lockAlreadyAcquired = false;

        CloseableSharedLock(boolean canExpectAlreadyAcquired) throws Exception {
            if (!canExpectAlreadyAcquired && sharedMutex.isAcquiredInThisProcess()) {
                throw new RuntimeException("Lock already acquired");
            }
            if (sharedMutex.isAcquiredInThisProcess()) {
                lockAlreadyAcquired = true;
            }
            else {
                if(!sharedMutex.acquire(5, TimeUnit.SECONDS)) {
                    lockAcquireTimeoutCounter.inc();
                    throw new ZkStoreUnavailableException("Could not acquire lock in the given time.");
                }
            }
        }

        @Override
        public void close() {
            try {
                if (!lockAlreadyAcquired) {
                    sharedMutex.release();
                }
            }
            catch (Exception e) {
                logger.error("Unable to release lock", e);
            }
        }
    }

    private NodeLoadInfo buildNodeLoadInfo(int profileCount) {
        return NodeLoadInfo.newBuilder().setProfilesLoaded(profileCount).build();
    }

    private <T extends AbstractMessage> T readFrom(String path, Parser<T> parser) throws Exception {
        byte[] bytes = curatorClient.getData().forPath(path);
        return parser.parseFrom(bytes);
    }


    private String zkPathForProfile(AggregatedProfileNamingStrategy profileName) {
        return "/profilesLoadStatus/" +  BaseEncoding.base32().encode(profileName.toString().getBytes(Charset.forName("utf-8")));
    }

    private void zkStateChangeListener(CuratorFramework client, org.apache.curator.framework.state.ConnectionState newState) {
        if(org.apache.curator.framework.state.ConnectionState.RECONNECTED.equals(newState)) {
            try {
                if(recentlyZkConnectionLost.get()) {
                    reInit();
                    recentlyZkConnectionLost.set(false);
                }
                else {
                    if(!pathExists(zkNodesInfoPath)) {
                        // Assuming this should not happen. throwing an error here so that we can be notified if this assumption breaks any time.
                        throw new RuntimeException("After zookeeper reconnection, my node was not found");
                    }
                }
                connectionState.set(ConnectionState.Connected);
            }
            catch (Exception e) {
                onReconnectFailures.inc();
                logger.error("Error while reinitializing zookeeper after reconnection.", e);
            }
        }
        else if(org.apache.curator.framework.state.ConnectionState.LOST.equals(newState)) {
            connectionState.set(ConnectionState.Disconnected);
            lastZkLostTime.set(LocalDateTime.now());
            recentlyZkConnectionLost.set(true);
        }
        else if(org.apache.curator.framework.state.ConnectionState.SUSPENDED.equals(newState) || org.apache.curator.framework.state.ConnectionState.READ_ONLY.equals(newState)) {
            connectionState.set(ConnectionState.Disconnected);
        }
        logger.info("zookeeper state changed to \"{}\"", newState.name());
    }

    /**
     * Reinitialization after recovering from lost zk connection. Updates all mapping in the zk.
     * @throws Exception
     */
    private void reInit() throws Exception {
        logger.info("ReInitializing zkStore");
        List<AggregatedProfileNamingStrategy> cachedProfiles = this.cachedProfiles.get();
        List<AggregatedProfileNamingStrategy> remotelyCachedProfiles = new ArrayList<>();

        long mySessionId = curatorClient.getZookeeperClient().getZooKeeper().getSessionId();

        Stat stat = curatorClient.checkExists().forPath(zkNodesInfoPath);
        Boolean nodeInfoNodeExists = stat != null;

        logger.info("curr sessionId: {}, existing session id: {}", mySessionId, nodeInfoNodeExists ? stat.getEphemeralOwner() : -1);

        // if i am not the owner, wait for it to be deleted
        if(nodeInfoNodeExists && stat.getEphemeralOwner() != mySessionId) {
            int retry = 2 * curatorClient.getZookeeperClient().getZooKeeper().getSessionTimeout() / 1000;
            while(retry > 0) {
                logger.info("Checking loadInfo node presence: {}, retry remaining: {}", nodeInfoNodeExists, retry);
                if((nodeInfoNodeExists = pathExists(zkNodesInfoPath))) {
                    --retry;
                    Thread.sleep(1000);
                }
                else {
                    break;
                }
            }

            if(nodeInfoNodeExists) {
                throw new RuntimeException("even though connection was lost, the nodes weren't deleted");
            }
        }

        if(!nodeInfoNodeExists) {
            for (AggregatedProfileNamingStrategy profileName : cachedProfiles) {
                try {
                    curatorClient.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(zkPathForProfile(profileName), myResidencyInfo);
                }
                catch (KeeperException.NodeExistsException e) {
                    // profile is not cached on some other node
                    logger.info(profileName + " is now possibly cached on other node");
                    remotelyCachedProfiles.add(profileName);
                }
            }

            // now create a nodeInfo node
            NodeLoadInfo loadInfo = buildNodeLoadInfo(cachedProfiles.size() - remotelyCachedProfiles.size());
            logger.info("After reconnection, {} many profiles were loaded, {} were remotely loaded", cachedProfiles.size(), remotelyCachedProfiles.size());
            curatorClient.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(zkNodesInfoPath, loadInfo.toByteArray());
        }
    }
}