package org.apache.zookeeper.benchmark;

import org.apache.log4j.Logger;
import org.apache.zookeeper.server.ZooKeeperThread;


public abstract class BenchmarkClient extends Thread {

    private static final Logger LOG = Logger.getLogger(BenchmarkClient.class);

    private BenchmarkConfig config;

    public BenchmarkClient(BenchmarkConfig config) {
        //super();
        this.config = config;
    }

//    @Override
//    public void run() {
//
//    }

    abstract protected void create();

    //abstract protected void resubmit(int n);

    //abstract protected void finish();
}