package org.apache.zookeeper.benchmark;

import org.apache.log4j.Logger;


public abstract class BenchmarkClient implements Runnable {

    private static final Logger LOG = Logger.getLogger(BenchmarkClient.class);

    private BenchmarkConfig config;

    public BenchmarkClient(BenchmarkConfig config) {
        this.config = config;
    }

    @Override
    public void run() {

    }

    abstract protected void create();

    //abstract protected void resubmit(int n);

    //abstract protected void finish();
}