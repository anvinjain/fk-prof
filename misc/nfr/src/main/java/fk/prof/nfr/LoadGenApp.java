package fk.prof.nfr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fk.prof.ClosablePerfCtx;
import fk.prof.PerfCtx;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by gaurav.ashok on 03/04/17.
 */
public class LoadGenApp {

    public  static final ObjectMapper om = new ObjectMapper();

    static PerfCtx serdeCtx = new PerfCtx("json-ser-de-ctx", 20);
    static PerfCtx multiplyCtx = new PerfCtx("matrix-mult-ctx", 20);
    public static void main(String[] args) throws Exception {

        if(args.length < 2) {
            System.err.println("too few params");
            return;
        }
        int jsonSerDeThrdCount = Integer.parseInt(args[0]);
        int multiplicationThrdCount = Integer.parseInt(args[1]);

        om.registerModule(new Jdk8Module());
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ExecutorService execSvc = Executors.newCachedThreadPool();

        // json ser/de workload
        WorkCounter jsonWorkCounter = new WorkCounter("json ser/de");
        startJsonSerDeWorkLoad(jsonSerDeThrdCount, execSvc, jsonWorkCounter);

        // matrix multiplication
        WorkCounter matrixMultCounter = new WorkCounter("matrix mult");
        startMultiplicationWorkLoad(multiplicationThrdCount, execSvc, matrixMultCounter);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("Stopping");
                execSvc.shutdownNow();
                execSvc.awaitTermination(5000, TimeUnit.MILLISECONDS);
                System.out.println("Stopped");
                System.out.println(jsonWorkCounter);
                System.out.println(matrixMultCounter);
            }
            catch (Exception e) {
                System.err.println("Issue in shutdown hook: " + e.getMessage());
                e.printStackTrace();
            }
        }));
    }

    private static void startJsonSerDeWorkLoad(int threadCount, ExecutorService execSvc, WorkCounter counter) {
        final JsonGenerator jsonGen = new JsonGenerator(1000);

        for(int i = 0; i < threadCount; ++i) {
            execSvc.submit(() -> {
                try(ClosablePerfCtx ctx = serdeCtx.open()) {
                    while (true) {
                        try {
                            Map<String, Object> json = jsonGen.genJsonMap(8, 0.35f, 0.15f);
                            String str = om.writeValueAsString(json);
                            Map<String, Object> obj = om.readValue(str, Map.class);
                            counter.increment();

                            if (Thread.currentThread().isInterrupted()) {
                                return;
                            }
                        } catch (Exception e) {
                            System.err.println("Error while ser/de using jackosn ObjectMapper: " + e.getMessage());
                        }
                    }
                }
            });
        }
    }

    private static void startMultiplicationWorkLoad(int threadCount, ExecutorService execSvc, WorkCounter counter) {
        for(int i = 0; i < threadCount; ++i) {
            execSvc.submit(() -> {
                try(ClosablePerfCtx ctx = multiplyCtx.open()) {
                    MatrixMultiplicationLoad matMul = new MatrixMultiplicationLoad(512);
                    matMul.reset();
                    while (true) {
                        matMul.multiply();
                        counter.increment();
                        if (Thread.currentThread().isInterrupted()) {
                            return;
                        }
                    }
                }
            });
        }
    }

    private static class WorkCounter {
        final AtomicLong counter = new AtomicLong();
        final String tag;
        public WorkCounter(String tag) {
            this.tag = tag;
        }

        @Override
        public String toString() {
            return "did " + tag + " " + counter + "times.";
        }

        public void increment() {
            counter.incrementAndGet();
        }
    }
}