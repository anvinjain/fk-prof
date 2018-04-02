package fk.prof.userapi.model;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.storage.AsyncStorage;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.UserapiConfigManager;
import fk.prof.userapi.api.ProfileStoreAPI;
import fk.prof.userapi.api.ProfileStoreAPIImpl;
import fk.prof.userapi.api.StorageBackedProfileLoader;
import fk.prof.userapi.cache.ClusterAwareCache;
import fk.prof.userapi.model.json.ProtoSerializers;
import io.vertx.core.*;
import io.vertx.core.json.Json;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.internal.util.collections.Sets;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/**
 * Tests for {@link ProfileStoreAPIImpl} using mocked behaviour of listAysnc {@link AsyncStorage} API
 * Created by rohit.patiyal on 24/01/17.
 */

@RunWith(VertxUnitRunner.class)
public class ProfileDiscoveryAPITest {

    private static final String DELIMITER = "/";
    private static final String BASE_DIR = "profiles";

    private ProfileStoreAPI profileDiscoveryAPI;
    private AsyncStorage asyncStorage;
    private Vertx vertx;

    String[] objects = {
            "profiles/v0001/MZXW6===/MJQXE===/NVQWS3Q=/2017-01-20T12:37:20.551+05:30/1500/thread_sample_work/0001",
            "profiles/v0001/MZXW6===/MJQXE===/NVQWS3Q=/2017-01-20T12:37:20.551+05:30/1500/cpu_sample_work/0001",
            "profiles/v0001/MZXW6===/MJQXE===/NVQWS3Q=/2017-01-20T12:37:20.551+05:30/1500/summary/0001",
            "profiles/v0001/MFYHAMI=/MNWHK43UMVZDC===/OBZG6Y3FONZTC===/2017-01-20T12:37:20.551+05:30/1500/monitor_contention_work/0001",
            "profiles/v0001/MFYHAMI=/MNWHK43UMVZDC===/OBZG6Y3FONZTC===/2017-01-20T12:37:20.551+05:30/1500/summary/0001",
            "profiles/v0001/MFYHAMI=/MNWHK43UMVZDC===/OBZG6Y3FONZTC===/2017-01-20T12:37:20.551+05:30/1800/monitor_wait_work/0001",
            "profiles/v0001/MFYHAMI=/MNWHK43UMVZDC===/OBZG6Y3FONZTC===/2017-01-20T12:37:20.551+05:30/1800/summary/0001",
    };

    AggregatedProfileNamingStrategy[] filenames = Stream.of(objects).map(AggregatedProfileNamingStrategy::fromFileName).toArray(AggregatedProfileNamingStrategy[]::new);
    private Configuration config;

    private Set<String> getObjList(String prefix, boolean recursive) {

        Set<String> resultObjects = new HashSet<>();
        for (String obj : objects) {
            if (obj.indexOf(prefix) == 0) {
                if (recursive) {
                    resultObjects.add(obj);
                } else {
                    resultObjects.add(obj.substring(0, prefix.length() + obj.substring(prefix.length()).indexOf(DELIMITER)));
                }
            }
        }
        return resultObjects;
    }

    @BeforeClass
    public static void setup() {
        ProtoSerializers.registerSerializers(Json.mapper);
    }

    @Before
    public void setUp() throws Exception {
        vertx = Vertx.vertx();
        asyncStorage = mock(AsyncStorage.class);
        config = UserapiConfigManager.loadConfig(ParseProfileTest.class.getClassLoader().getResource("userapi-conf.json").getFile());
        WorkerExecutor executor = vertx.createSharedWorkerExecutor(
            config.getBlockingWorkerPool().getName(), config.getBlockingWorkerPool().getSize());
        profileDiscoveryAPI = new ProfileStoreAPIImpl(vertx, asyncStorage, new StorageBackedProfileLoader(asyncStorage),
            mock(ClusterAwareCache.class), executor, config);

        when(asyncStorage.listAsync(anyString(), anyBoolean())).thenAnswer(invocation -> {
            String path1 = invocation.getArgument(0);
            Boolean recursive = invocation.getArgument(1);
            return CompletableFuture.supplyAsync(() -> getObjList(path1, recursive));
        });
    }

    @Test(timeout = 10000)
    public void testGetAppIdsWithPrefix(TestContext context) throws Exception {
        Async async = context.async();
        Map<String, Collection<Object>> appIdTestPairs = new HashMap<String, Collection<Object>>() {
            {
                put("app", Sets.newSet("app1"));
                put("", Sets.newSet("app1", "foo"));
                put(null, Sets.newSet("app1", "foo"));
            }
        };

        List<Future> futures = new ArrayList<>();
        for (Map.Entry<String, Collection<Object>> entry : appIdTestPairs.entrySet()) {
            futures.add(
                profileDiscoveryAPI
                    .getAppIdsWithPrefix(BASE_DIR, entry.getKey())
                    .setHandler(res -> context.assertEquals(entry.getValue(), res.result())));
        }

        CompositeFuture f = CompositeFuture.all(futures);
        f.setHandler(res -> completeTest(res, context, async));
    }

    @Test(timeout = 10000)
    public void testGetClusterIdsWithPrefix(TestContext context) throws Exception {
        Async async = context.async();
        Map<List<String>, Collection<?>> appIdTestPairs = new HashMap<List<String>, Collection<?>>() {
            {
                put(Arrays.asList("app1", "cl"), Sets.newSet("cluster1"));
                put(Arrays.asList("app1", ""), Sets.newSet("cluster1"));
                put(Arrays.asList("foo", "b"), Sets.newSet("bar"));
                put(Arrays.asList("np", "np"), Sets.newSet());
                put(Arrays.asList("app1", "b"), Sets.newSet());
                put(Arrays.asList("", ""), Sets.newSet());
                put(Arrays.asList("app1", null), Sets.newSet("cluster1"));
            }
        };

        List<Future> futures = new ArrayList<>();
        for (Map.Entry<List<String>, Collection<?>> entry : appIdTestPairs.entrySet()) {
            futures.add(
                profileDiscoveryAPI
                    .getClusterIdsWithPrefix(BASE_DIR, entry.getKey().get(0), entry.getKey().get(1))
                    .setHandler(res -> context.assertEquals(entry.getValue(), res.result())));
        }

        CompositeFuture f = CompositeFuture.all(futures);
        f.setHandler(res -> completeTest(res, context, async));
    }

    @Test(timeout = 10000)
    public void testGetProcsWithPrefix(TestContext context) throws Exception {
        Async async = context.async();
        Map<List<String>, Collection<?>> appIdTestPairs = new HashMap<List<String>, Collection<?>>() {
            {
                put(Arrays.asList("app1", "cluster1", "pr"), Sets.newSet("process1"));
                put(Arrays.asList("app1", "cluster1", null), Sets.newSet("process1"));
                put(Arrays.asList("app1", "", ""), Sets.newSet());
                put(Arrays.asList("foo", "bar", ""), Sets.newSet("main"));
                put(Arrays.asList("", "", ""), Sets.newSet());
            }
        };

        List<Future> futures = new ArrayList<>();
        for (Map.Entry<List<String>, Collection<?>> entry : appIdTestPairs.entrySet()) {
            futures.add(
                profileDiscoveryAPI
                    .getProcNamesWithPrefix(BASE_DIR, entry.getKey().get(0), entry.getKey().get(1), entry.getKey().get(2))
                    .setHandler(res -> context.assertEquals(entry.getValue(), res.result())));
        }

        CompositeFuture f = CompositeFuture.all(futures);
        f.setHandler(res -> completeTest(res, context, async));
    }

    @Test(timeout = 10000)
    public void testGetProfilesInTimeWindow(TestContext context) throws Exception {
        Async async = context.async();
        Map<List<Object>, Collection<?>> appIdTestPairs = new HashMap<List<Object>, Collection<?>>() {
            {
                put(Arrays.asList("app1", "cluster1", "process1", ZonedDateTime.parse("2017-01-20T12:37:20.551+05:30"), 1600),
                        Sets.newSet(filenames[4], filenames[6]));
                put(Arrays.asList("app1", "cluster1", "process1", ZonedDateTime.parse("2017-01-20T12:37:20.551+05:30"), 1900),
                        Sets.newSet(filenames[4], filenames[6]));
                put(Arrays.asList("foo", "bar", "main", ZonedDateTime.parse("2017-01-20T12:37:20.551+05:30"), 1900),
                        Sets.newSet(filenames[2]));
            }
        };

        List<Future> futures = new ArrayList<>();
        for (Map.Entry<List<Object>, Collection<?>> entry : appIdTestPairs.entrySet()) {
            futures.add(
                profileDiscoveryAPI
                    .getProfilesInTimeWindow(BASE_DIR, (String)entry.getKey().get(0), (String)entry.getKey().get(1),
                        (String)entry.getKey().get(2), (ZonedDateTime)entry.getKey().get(3),
                        (Integer)entry.getKey().get(4))
                    .setHandler(res -> context.assertEquals(entry.getValue(), Sets.newSet(res.result().toArray()))));
        }

        CompositeFuture f = CompositeFuture.all(futures);
        f.setHandler(res -> completeTest(res, context, async));
    }

    private void completeTest(AsyncResult result, TestContext context, Async async) {
        if(result.failed()) {
            context.fail(result.cause());
        }
        async.complete();
    }
}

