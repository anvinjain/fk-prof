package fk.prof.recorder.e2e;

import com.amazonaws.auth.AnonymousAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectListing;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import fk.prof.recorder.main.Burn20And80PctCpu;
import fk.prof.recorder.utils.AgentRunner;
import fk.prof.recorder.utils.FileResolver;
import io.findify.s3mock.S3Mock;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.KeeperException;
import org.hamcrest.Matchers;
import org.junit.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.core.Is.*;
import static org.junit.Assert.*;

/**
 * Created by gaurav.ashok on 06/03/17.
 */
public class E2ETest {
    private final static Logger logger = LoggerFactory.getLogger(E2ETest.class);
    public final static int s3Port = 13031;
    public final static int zkPort = 2191;
    public final static String baseS3Bucket = "profiles";
    public final static String zkNamespace = "fkprof";
    private static S3Mock s3;
    private static TestingServer zookeeper;

    private static AmazonS3Client client;
    private static CuratorFramework curator;

    private UserapiProcess userapi;
    private BackendProcess[] backends;
    private AgentRunner[] recorders;

    @BeforeClass
    public static void setup() throws Exception {
        // s3
        s3 = S3Mock.create(s3Port, "/tmp/s3");
        s3.start();

        client = new AmazonS3Client(new AnonymousAWSCredentials());
        client.setEndpoint("http://127.0.0.1:" + s3Port);
        ensureS3BaseBucket();

        // zookeeper
        zookeeper = new TestingServer(zkPort, true);

        curator =
            CuratorFrameworkFactory.builder()
                .connectString("127.0.0.1:" + zkPort)
                .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                .connectionTimeoutMs(10000)
                .sessionTimeoutMs(60000)
                .build();
        curator.start();
        curator.blockUntilConnected(10000, TimeUnit.MILLISECONDS);
        assert curator.getState() == CuratorFrameworkState.STARTED;
        ensureZkRootnode();
    }

    @Before
    public void before() throws Exception {
        // clear up all files in s3
        ObjectListing listing = client.listObjects(baseS3Bucket);
        listing.getObjectSummaries().stream().forEach(obj -> {
            client.deleteObject(obj.getBucketName(), obj.getKey());
        });

//         clear up zookeeper
        curator.delete().deletingChildrenIfNeeded().forPath("/" + zkNamespace);
        ensureZkRootnode();
    }

    @After
    public void after() throws Exception {
        // stop all components
        if(userapi != null) {
            userapi.stop();
            userapi = null;
        }

        if(backends != null) {
            for(int i = 0; i < backends.length; ++i) {
                backends[i].stop();
            }
            backends = null;
        }

        if(recorders != null) {
            for(int i = 0; i < recorders.length; ++i) {
                recorders[i].stop();
            }
            recorders = null;
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        curator.close();
        zookeeper.stop();
        s3.stop();
    }

    @Test(timeout = 10_000)
    public void testStartup_userapiServiceShouldStartWithoutFail() throws Exception {
        UserapiProcess userapi = new UserapiProcess(FileResolver.resourceFile("/conf/userapi_1.json"));
        userapi.start();

        try {
            waitForSocket("127.0.0.1", 8082);

            HttpResponse<String> response = Unirest.get("http://127.0.0.1:8082/").asString();
            Assert.assertThat(response.getStatus(), is(200));
        }
        finally {
            userapi.stop();
        }
    }

    @Test(timeout = 10_000)
    public void testStartup_backendServiceShouldStartWithoutFail() throws Exception {
        BackendProcess backend = new BackendProcess(FileResolver.resourceFile("/conf/backend_1.json"));
        backend.start();

        try {
            waitForSocket("127.0.0.1", 2496);
        }
        finally {
            backend.stop();
        }
    }

    @Test(timeout = 10 * 60 * 1_000)
    public void testE2EFlowWithFixPolicy_15SecWorkDuration_1MinAggregationWindow_1Recorder_2Backends() throws Exception {
        // start all components
        userapi = new UserapiProcess(FileResolver.resourceFile("/conf/userapi_1.json"));
        BackendProcess leader = new BackendProcess(FileResolver.resourceFile("/conf/backend_1.json"));
        BackendProcess backend = new BackendProcess(FileResolver.resourceFile("/conf/backend_2.json"));
        AgentRunner recorder = new AgentRunner(Burn20And80PctCpu.class.getCanonicalName(), "service_endpoint=http://127.0.0.1:2492," +
                "ip=10.20.30.40," +
                "host=foo-host," +
                "appid=bar-app," +
                "igrp=baz-grp," +
                "cluster=quux-cluster," +
                "instid=corge-iid," +
                "proc=grault-proc," +
                "vmid=garply-vmid," +
                "zone=waldo-zone," +
                "ityp=c0.small," +
                "backoffStart=2," +
                "backoffMax=5," +
                "logLvl=trace," +
                "pollItvl=10"
        );

        backends = new BackendProcess[] {leader, backend};
        recorders = new AgentRunner[] {recorder};

        userapi.start();
        leader.start();

        waitForSocket("127.0.0.1", 8082);
        waitForSocket("127.0.0.1", 2496);

        backend.start();
        waitForSocket("127.0.0.1", 2492);

        recorder.start();

        System.out.println("All components started, now waiting");

        // expecting a backend and leader handshake, pg association to backend and backend responding to recorder's poll. This should take around 30 - 40 sec.
        // Wait for another 2.5 min to let first 2 window finish.
        Thread.sleep(minToMillis(3, 0)); // 3 min

        ZonedDateTime someTimeFromNearPast = ZonedDateTime.now(Clock.systemUTC()).minusMinutes(30);

        String getProfileUrl = "http://127.0.0.1:8082/profiles/bar-app/quux-cluster/grault-proc?start=" + someTimeFromNearPast.format(DateTimeFormatter.ISO_ZONED_DATE_TIME) + "&duration=3600";

        logger.info("Getting profiles from: " + getProfileUrl);
        HttpResponse<String> httpResponse = Unirest.get(getProfileUrl).asString();

        logger.info(httpResponse.getBody());
        assertThat(httpResponse.getStatus(), is(200));

        Map<String, Object> res = new ObjectMapper().readValue(httpResponse.getBody(), Map.class);

        assertThat(res.keySet(), Matchers.hasItems("failed", "succeeded"));

        List<Map<String, Object>> failed = (List<Map<String, Object>>) res.get("failed");
        List<Map<String, Object>> succeeded = (List<Map<String, Object>>) res.get("succeeded");

        assertThat(failed, Matchers.anyOf(Matchers.nullValue(), Matchers.empty()));

        // there should be 2 aggregation window
        assertThat(succeeded, Matchers.hasSize(2));

        // every succeeded aggregation has following fields
        assertThat(succeeded.stream().map(s -> s.keySet()).collect(Collectors.toList()), Matchers.everyItem(Matchers.hasItems("profiles", "ws_summary", "traces", "start", "duration")));

        // check details of the later aggregation. First 1 is empty for now
        Map<String, Object> aggregation = succeeded.get(
            maxIdx(
                asZDate(get(succeeded.get(0), "start")),
                asZDate(get(succeeded.get(1), "start"))
            )
        );
        assertThat((List<String>)aggregation.get("traces"), Matchers.hasSize(1));
        assertThat((List<String>)aggregation.get("traces"), Matchers.hasItems("inferno"));
        assertThat(aggregation.get("duration"), is(60));

        // we are expecting only 1 work being scheduled. This might change so fix the test accordingly
        assertThat((List<Map<String, Object>>)aggregation.get("profiles"), Matchers.anyOf(Matchers.hasSize(1)));

        // every profile must be Complete
        List<Map<String, Object>> profiles = cast(aggregation.get("profiles"));
        assertThat(profiles.stream().map(p -> p.get("status")).collect(Collectors.toList()), Matchers.everyItem(is("Completed")));

        // check details of first work profile
        Map<String, Object> profile = profiles.get(0);

        assertThat(profile.keySet(), Matchers.hasItems("start_offset", "duration", "recorder_version", "recorder_info", "sample_count", "status", "trace_coverage_map"));
        assertThat((Integer)profile.get("start_offset"), Matchers.lessThan(60));
        assertThat((Integer)profile.get("duration"), Matchers.comparesEqualTo(15));

        // check details of recorder info
        Map<String, String> recorderInfo = cast(profile.get("recorder_info"));
        assertThat(recorderInfo.get("ip"), is("10.20.30.40"));
        assertThat(recorderInfo.get("hostname"), is("foo-host"));
        assertThat(recorderInfo.get("app_id"), is("bar-app"));
        assertThat(recorderInfo.get("instance_group"), is("baz-grp"));
        assertThat(recorderInfo.get("cluster"), is("quux-cluster"));
        assertThat(recorderInfo.get("instace_id"), is("corge-iid"));
        assertThat(recorderInfo.get("process_name"), is("grault-proc"));
        assertThat(recorderInfo.get("vm_id"), is("garply-vmid"));
        assertThat(recorderInfo.get("zone"), is("waldo-zone"));
        assertThat(recorderInfo.get("instance_type"), is("c0.small"));

        // TODO: test for sample count consistency after adding errored stacktraces count in aggregation.
//        // sum up all the profiles sample counts
//        int totalSampleCountsFromProfiles = ((List<Map<String,Object>>) aggregation.get("profiles")).stream().mapToInt(p -> get(p, "sample_count", "cpu_sample_work")).sum();
//        // sum up all the sample counts for cpu_sample traces
//        int totalSampleCountFromSampleAggregation = asList(get(aggregation, "ws_summary", "cpu_sample_work", "traces")).stream().mapToInt((t) -> get(t, "props", "samples")).sum();
//
//        // these 2 should be equal
//        assertThat(totalSampleCountsFromProfiles, is(totalSampleCountFromSampleAggregation));
    }

    @Test(timeout = 10 * 60 * 1_000)
    public void testE2EFlowIncompleteProfileRecorderDies_15SecWorkDuration_1MinAggregationWindow_1Recorder_2Backends() throws Exception {
        // start all components
        userapi = new UserapiProcess(FileResolver.resourceFile("/conf/userapi_1.json"));
        BackendProcess leader = new BackendProcess(FileResolver.resourceFile("/conf/backend_1.json"));
        BackendProcess backend = new BackendProcess(FileResolver.resourceFile("/conf/backend_2.json"));
        AgentRunner recorder = new AgentRunner(Burn20And80PctCpu.class.getCanonicalName(), "service_endpoint=http://127.0.0.1:2492," +
                "ip=10.20.30.40," +
                "host=foo-host," +
                "appid=bar-app," +
                "igrp=baz-grp," +
                "cluster=quux-cluster," +
                "instid=corge-iid," +
                "proc=grault-proc," +
                "vmid=garply-vmid," +
                "zone=waldo-zone," +
                "ityp=c0.small," +
                "backoffStart=2," +
                "backoffMax=5," +
                "logLvl=trace," +
                "pollItvl=10"
        );

        backends = new BackendProcess[] {leader, backend};

        userapi.start();
        leader.start();

        waitForSocket("127.0.0.1", 8082);
        waitForSocket("127.0.0.1", 2496);

        backend.start();
        waitForSocket("127.0.0.1", 2492);

        recorder.start();

        // wait for 1st work to start
        Thread.sleep(minToMillis(1, 10));

        // kill the recorder. Because we are killing the recorder while a work was in flight a profile will be incomplete.
        recorder.stop();

        // wait for the aggregation window to conclude
        Thread.sleep(minToMillis(1, 50)); // 3 min in total

        ZonedDateTime someTimeFromNearPast = ZonedDateTime.now(Clock.systemUTC()).minusMinutes(30);

        String getProfileUrl = "http://127.0.0.1:8082/profiles/bar-app/quux-cluster/grault-proc?start=" + someTimeFromNearPast.format(DateTimeFormatter.ISO_ZONED_DATE_TIME) + "&duration=3600";

        logger.info("Getting profiles from: " + getProfileUrl);
        HttpResponse<String> httpResponse = Unirest.get(getProfileUrl).asString();

        logger.info(httpResponse.getBody());
        assertThat(httpResponse.getStatus(), is(200));

        Map<String, Object> res = new ObjectMapper().readValue(httpResponse.getBody(), Map.class);

        assertThat(res.keySet(), Matchers.hasItems("failed", "succeeded"));

        List<Map<String, Object>> failed = (List<Map<String, Object>>) res.get("failed");
        List<Map<String, Object>> succeeded = (List<Map<String, Object>>) res.get("succeeded");

        assertThat(failed, Matchers.anyOf(Matchers.nullValue(), Matchers.empty()));

        // we are still expecting 2 aggregation windows
        assertThat(succeeded, Matchers.hasSize(2));

        // check details of the later aggregation. First 1 is empty for now
        Map<String, Object> aggregation = succeeded.get(
                maxIdx(
                        asZDate(get(succeeded.get(0), "start")),
                        asZDate(get(succeeded.get(1), "start"))
                )
        );

        // only 1 not 'Completed' profile
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) aggregation.get("profiles");
        assertThat(profiles, Matchers.hasSize(1));

        // check the status
        Map<String, Object> profile = profiles.get(0);
        assertThat(profile.get("status"), Matchers.not("Partial"));
    }

    private static void ensureS3BaseBucket() throws Exception {
        // init a bucket, if not present
        if(!client.listBuckets().stream().anyMatch(b -> b.getName().equals(baseS3Bucket))) {
            client.createBucket(baseS3Bucket);
        }
    }

    private static void ensureZkRootnode() throws Exception {
        try {
            curator.create().forPath("/" + zkNamespace);
        } catch (KeeperException.NodeExistsException ex) {
            // ignore
        }
    }

    public static void waitForSocket(String ip, int port) throws IOException {
        boolean scanning=true;
        while(scanning)
        {
            try
            {
                SocketChannel socketChannel = SocketChannel.open(new InetSocketAddress(ip, port));
                if(socketChannel.isConnected()) {
                    socketChannel.close();
                }
                scanning=false;
            }
            catch(ConnectException e)
            {
                System.out.println("Connect to : " + ip + ":" + port + " failed, waiting and trying again");
                try
                {
                    Thread.sleep(2000);//2 seconds
                }
                catch(InterruptedException ie){
                    ie.printStackTrace();
                }
            }
        }
        System.out.println(ip + ":" + port + " connected");
    }

    private <T> T get(Object m, String... fields) {
        assert fields.length > 0;
        Map<String, Object> temp = (Map<String, Object>) m;
        int i = 0;
        for(; i < fields.length - 1; ++i) {
            temp = (Map<String, Object>)temp.get(fields[i]);
        }

        return (T)temp.get(fields[i]);
    }

    private <T> List<T> asList(Object obj) {
        return (List<T>) obj;
    }

    private <T> T cast(Object obj) {
        return (T)obj;
    }

    private ZonedDateTime asZDate(String str) {
        return ZonedDateTime.parse(str, DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    private <T extends Comparable> int maxIdx(T... items) {
        assert items != null && items.length > 0 : "cannot find index for max item in empty list";

        int maxIdx = 0;

        for(int i = 1; i < items.length; ++i) {
            if(items[maxIdx].compareTo(items[i]) < 0) {
                maxIdx = i;
            }
        }

        return maxIdx;
    }

//    public void test_runForeverS3MockAndZookeeper() throws Exception {
//        Thread.sleep(Integer.MAX_VALUE);
//    }

//    public void testBackendByStartingUserapi() throws Exception {
//        // start all components
//        userapi = new UserapiProcess(FileResolver.resourceFile("/conf/userapi_1.json"));
//        AgentRunner recorder = new AgentRunner(Burn20And80PctCpu.class.getCanonicalName(), "service_endpoint=http://127.0.0.1:2491," +
//                "ip=10.20.30.40," +
//                "host=foo-host," +
//                "appid=bar-app," +
//                "igrp=baz-grp," +
//                "cluster=quux-cluster," +
//                "instid=corge-iid," +
//                "proc=grault-proc," +
//                "vmid=garply-vmid," +
//                "zone=waldo-zone," +
//                "ityp=c0.small," +
//                "backoffStart=2," +
//                "backoffMax=5," +
//                "logLvl=trace," +
//                "pollItvl=10"
//        );
//
//        recorders = new AgentRunner[] {recorder};
//
//        userapi.start();
//
//        waitForSocket("127.0.0.1", 8082);
//
//        Thread.sleep(Integer.MAX_VALUE);
//    }

    private int minToMillis(int min, int sec) {
        return min * 60 * 1000 + sec * 1000;
    }
}