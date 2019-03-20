package org.apache.zookeeper.tools.benchmark;

public abstract class BenchmarkClient implements Runnable {

    private static final Logger LOG = Logger.getLogger(BenchmarkClient.class);


    public BenchmarkClient() throws IOException {

    }

    @Override
    public void run() {


    }

    abstract protected void submit(int n, TestType type);

    /**
     * for synchronous requests, to submit more requests only needs to increase the total
     * number of requests, here n can be an arbitrary number
     * for asynchronous requests, to submit more requests means that the client will do
     * both submit and wait
     * @param n
     */
    abstract protected void resubmit(int n);

    abstract protected void finish();