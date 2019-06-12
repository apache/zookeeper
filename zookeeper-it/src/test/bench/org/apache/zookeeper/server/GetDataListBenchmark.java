package org.apache.zookeeper.server;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.test.GetDataListTest;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

public class GetDataListBenchmark {

    private static int NUM_TOP_LEVEL_ZNODES = 3;
    private static int NUM_SECOND_LEVEL_ZNODES = 10000;

    @State(Scope.Benchmark)
    public static class ClientState extends GetDataListTest {

        @Param( { "sync", "async" })
        String syncOrAsync;

        @Param( { "0", "100", "1000", "10000" })
        String batchSize;

        @Param( { "true", "false" })
        String useMulti;

        @Setup(Level.Trial)
        public void setup() throws Exception {

            System.out.println("Setup benchmark");
            super.setUp();

            createTestDataLarge(0, NUM_TOP_LEVEL_ZNODES, NUM_SECOND_LEVEL_ZNODES);

        }

        @TearDown(Level.Trial)
        public void stop() throws Exception {

            System.out.println("Teardown benchmark");

            tearDown();
        }

        public void registerWatcher(Watcher watcher) {
            zooKeeper.register(watcher);
        }
    }

    @Benchmark
    @Warmup(iterations = 1)
    @Measurement(iterations = 5)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(value = 3)
    public void measureGetDataListNoWatch(GetDataListBenchmark.ClientState state) throws KeeperException, InterruptedException {
        state.testReadNodesNoWatch(NUM_TOP_LEVEL_ZNODES, Integer.parseInt(state.batchSize), state.syncOrAsync, Boolean.parseBoolean(state.useMulti),
                NUM_SECOND_LEVEL_ZNODES * NUM_TOP_LEVEL_ZNODES + NUM_TOP_LEVEL_ZNODES);
        // TODO: Blackhole results?
    }


    @Benchmark
    @Warmup(iterations = 1)
    @Measurement(iterations = 5)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(value = 3)
    public void measureGetDataListWithWatch(GetDataListBenchmark.ClientState state) throws KeeperException, InterruptedException {

        GetDataListTest.WatcherCounter watcherCounter = new GetDataListTest.WatcherCounter();
        state.registerWatcher(watcherCounter.watcher);

        state.testReadNodesAndWatch(NUM_TOP_LEVEL_ZNODES,
                Integer.parseInt(state.batchSize),
                state.syncOrAsync,
                NUM_SECOND_LEVEL_ZNODES * NUM_TOP_LEVEL_ZNODES + NUM_TOP_LEVEL_ZNODES,
                watcherCounter.numNodeDataChanged, 100);
        // TODO: Blackhole results?
    }

}
