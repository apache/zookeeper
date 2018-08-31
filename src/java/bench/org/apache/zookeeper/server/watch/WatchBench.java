package org.apache.zookeeper.server;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.server.watch.IWatchManager;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.IterationParams;

import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;

import org.apache.jute.Record;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.data.Stat;

@Fork(3)
public class WatchBench {

    static final String pathPrefix = "/reasonably/long/path/";
    static final EventType event = EventType.NodeDataChanged;

    static IWatchManager createWatchManager(String className) throws Exception {
        Class clazz = Class.forName(
                "org.apache.zookeeper.server.watch." + className);
        return (IWatchManager) clazz.newInstance();
    }

    static void forceGC() {
        int gcTimes = 3;
        for (int i = 0; i < gcTimes; i++) {
            try {
                System.gc();
                Thread.currentThread().sleep(1000);

                System.runFinalization();
                Thread.currentThread().sleep(1000);
            } catch (InterruptedException ex) { /* ignore */ }
        }
    }

    static long getMemoryUse() {
        forceGC();
        long totalMem = Runtime.getRuntime().totalMemory();

        forceGC();
        long freeMem = Runtime.getRuntime().freeMemory();
        return totalMem - freeMem;
    }

    @State(Scope.Benchmark)
    public static class IterationState {

        @Param({"WatchManager", "WatchManagerOptimized"})
        public String watchManagerClass;

        @Param({"10000"})
        public int pathCount;

        String[] paths;

        long watchesAdded = 0;
        IWatchManager watchManager;

        long memWhenSetup = 0;

        @Setup(Level.Iteration)
        public void setup() throws Exception {
            paths = new String[pathCount];
            for (int i = 0; i < paths.length; i++) {
                paths[i] = pathPrefix + i;
            }

            watchesAdded = 0;
            watchManager = createWatchManager(watchManagerClass);

            memWhenSetup = getMemoryUse();
        }

        @TearDown(Level.Iteration)
        public void tearDown() {
            long memUsed = getMemoryUse() - memWhenSetup;
            System.out.println("Memory used: " + watchesAdded + " " + memUsed);

            double memPerMillionWatchesMB = memUsed * 1.0 / watchesAdded ;
            System.out.println(
                    "Memory used per million watches " +
                    String.format("%.2f", memPerMillionWatchesMB) + "MB");
        }
    }

    /**
     * Test concenrate watch case where the watcher watches all paths.
     *
     * The output of this test will be the average time used to add the
     * watch to all paths.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Warmup(iterations = 1, time = 10, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
    public void testAddConcentrateWatch(IterationState state) throws Exception {
        Watcher watcher = new DumbWatcher();

        // watch all paths
        for (String path : state.paths) {
            if (state.watchManager.addWatch(path, watcher)) {
                state.watchesAdded++;
            }
        }
    }

    @State(Scope.Benchmark)
    public static class InvocationState {

        @Param({"WatchManager", "WatchManagerOptimized"})
        public String watchManagerClass;

        @Param({"1", "1000"})
        public int pathCount;

        @Param({"1", "1000"})
        public int watcherCount;

        String[] paths;
        Watcher[] watchers;

        IWatchManager watchManager;

        @Setup(Level.Invocation)
        public void setup() throws Exception {
            initialize();
            prepare();
        }

        void initialize() throws Exception {
            if (paths == null || paths.length != pathCount) {
                paths = new String[pathCount];
                for (int i = 0; i < pathCount; i++) {
                    paths[i] = pathPrefix + i;
                }
            }

            if (watchers == null || watchers.length != watcherCount) {
                watchers = new Watcher[watcherCount];
                for (int i = 0; i < watcherCount; i++) {
                    watchers[i] = new DumbWatcher();
                }
            }
            if (watchManager == null ||
                    !watchManager.getClass().getSimpleName().contains(
                            watchManagerClass)) {
                watchManager = createWatchManager(watchManagerClass);
            }
        }

        void prepare() {
            for (String path : paths) {
                for (Watcher watcher : watchers) {
                    watchManager.addWatch(path, watcher);
                }
            }
        }
    }

    /**
     * Test trigger watches in concenrate case.
     *
     * The output of this test is the time used to trigger those watches on
     * all paths.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Warmup(iterations = 1, time = 10, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
    public void testTriggerConcentrateWatch(InvocationState state) throws Exception {
        for (String path : state.paths) {
            state.watchManager.triggerWatch(path, event);
        }
    }

    @State(Scope.Benchmark)
    public static class AddSparseWatchState extends InvocationState {

        @Param({"10000"})
        public int pathCount;

        @Param({"10000"})
        public int watcherCount;

        long watchesAdded = 0;
        long memWhenSetup = 0;

        @Override
        public void prepare() {
            watchesAdded = 0;
            memWhenSetup = getMemoryUse();
        }

        @TearDown(Level.Invocation)
        public void tearDown() {
            long memUsed = getMemoryUse() - memWhenSetup;
            System.out.println("Memory used: " + watchesAdded + " " + memUsed);

            double memPerMillionWatchesMB = memUsed * 1.0 / watchesAdded ;
            System.out.println(
                    "Memory used per million sparse watches " +
                    String.format("%.2f", memPerMillionWatchesMB) + "MB");

            // clear all the watches
            for (String path : paths) {
                watchManager.triggerWatch(path, event);
            }
        }
    }

    /**
     * Test sparse watch case where only one watcher watches all paths, and
     * only one path being watched by all watchers.
     *
     * The output of this test will be the average time used to add those
     * sparse watches.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Warmup(iterations = 1, time = 10, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
    public void testAddSparseWatch(AddSparseWatchState state) throws Exception {
        // All watchers are watching the 1st path
        for (Watcher watcher : state.watchers) {
            if (state.watchManager.addWatch(state.paths[0], watcher)) {
                state.watchesAdded++;
            }
        }
        // The 1st watcher is watching all paths
        for (String path : state.paths) {
            if (state.watchManager.addWatch(path, state.watchers[0])) {
                state.watchesAdded++;
            }
        }
    }

    @State(Scope.Benchmark)
    public static class TriggerSparseWatchState extends InvocationState {

        @Param({"10000"})
        public int pathCount;

        @Param({"10000"})
        public int watcherCount;

        @Override
        public void prepare() {
            // All watchers are watching the 1st path
            for (Watcher watcher : watchers) {
                watchManager.addWatch(paths[0], watcher);
            }

            // The 1st watcher is watching all paths
            for (String path : paths) {
                watchManager.addWatch(path, watchers[0]);
            }
        }
    }


    /**
     * Test trigger watches in sparse case.
     *
     * The output of this test is the time used to trigger those watches on
     * all paths.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Warmup(iterations = 1, time = 10, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
    public void testTriggerSparseWatch(TriggerSparseWatchState state) throws Exception {
        for (String path : state.paths) {
            state.watchManager.triggerWatch(path, event);
        }
    }

    static class DumbWatcher extends ServerCnxn {

        private long sessionId;

        public DumbWatcher() {
            this(0);
        }

        public DumbWatcher(long sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void process(WatchedEvent event) { }

        @Override
        int getSessionTimeout() { return 0; }

        @Override
        public void close() { }

        @Override
        public void sendResponse(ReplyHeader h, Record r, String tag) throws IOException { }

        @Override
        public void sendCloseSession() { }

        @Override
        public long getSessionId() { return sessionId; }

        @Override
        void setSessionId(long sessionId) { }

        @Override
        void sendBuffer(ByteBuffer closeConn) { }

        @Override
        void enableRecv() { }

        @Override
        void disableRecv() { }

        @Override
        void setSessionTimeout(int sessionTimeout) { }

        @Override
        protected ServerStats serverStats() { return null; }

        @Override
        public long getOutstandingRequests() { return 0; }

        @Override
        public InetSocketAddress getRemoteSocketAddress() { return null; }

        @Override
        public int getInterestOps() { return 0; }

        @Override
        public boolean isSecure() { return false; }

        @Override
        public Certificate[] getClientCertificateChain() { return null; }

        @Override
        public void setClientCertificateChain(Certificate[] chain) { }
    }
}
