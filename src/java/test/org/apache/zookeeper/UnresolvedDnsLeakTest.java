package org.apache.zookeeper;

import java.io.IOException;

import org.junit.Ignore;
import org.junit.Test;

public class UnresolvedDnsLeakTest {

    @Ignore
    @Test
    public void test() throws IOException, InterruptedException {
        ZooKeeper zooKeeper = new ZooKeeper("i.dont.known", 60000, null);
        // previously it would leak fd on a unknown host or dns resolved failed after every retry.
        // call lsof -p to confirm it, while it hard to write test case coz it need expose SocketSelector with a private field.
        Thread.sleep(24 * 60 * 1000);
    }
}
