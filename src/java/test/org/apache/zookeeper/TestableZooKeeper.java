package org.apache.zookeeper;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.List;

public class TestableZooKeeper extends ZooKeeper {

    public TestableZooKeeper(String host, int sessionTimeout,
            Watcher watcher) throws IOException {
        super(host, sessionTimeout, watcher);
    }
    
    @Override
    public List<String> getChildWatches() {
        return super.getChildWatches();
    }


    @Override
    public List<String> getDataWatches() {
        return super.getDataWatches();
    }


    @Override
    public List<String> getExistWatches() {
        return super.getExistWatches();
    }


    /**
     * Cause this ZooKeeper object to stop receiving from the ZooKeeperServer
     * for the given number of milliseconds.
     * @param ms the number of milliseconds to pause.
     */
    public void pauseCnxn(final long ms) {
        new Thread() {
            public void run() {
                synchronized(cnxn) {
                    try {
                        try {
                            ((SocketChannel)cnxn.sendThread.sockKey.channel()).socket().close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        Thread.sleep(ms);
                    } catch (InterruptedException e) {
                    }
                }
            }
        }.start();
    }
}
