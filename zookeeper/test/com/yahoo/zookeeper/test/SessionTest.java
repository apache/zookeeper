package com.yahoo.zookeeper.test;

import java.io.File;
import java.io.IOException;
import org.junit.Test;
import com.yahoo.zookeeper.KeeperException;
import com.yahoo.zookeeper.Watcher;
import com.yahoo.zookeeper.ZooKeeper;
import com.yahoo.zookeeper.ZooDefs.CreateFlags;
import com.yahoo.zookeeper.ZooDefs.Ids;
import com.yahoo.zookeeper.data.Stat;
import com.yahoo.zookeeper.proto.WatcherEvent;
import com.yahoo.zookeeper.server.NIOServerCnxn;
import com.yahoo.zookeeper.server.ServerStats;
import com.yahoo.zookeeper.server.ZooKeeperServer;
import junit.framework.TestCase;

public class SessionTest extends TestCase implements Watcher {
    static File baseTest = new File(System.getProperty("build.test.dir",
            "build"));

	protected void setUp() throws Exception {
    	ServerStats.registerAsConcrete();
	}
	protected void tearDown() throws Exception {
		ServerStats.unregister();
	}
	/**
     * this test checks to see if the sessionid that was created for the 
     * first zookeeper client can be reused for the second one immidiately 
     * after the first client closes and the new client resues them.
     * @throws IOException
     * @throws InterruptedException
     * @throws KeeperException
     */
    public void testSessionReuse() throws IOException, InterruptedException,
            KeeperException {
        File tmpDir = File.createTempFile("test", ".junit", baseTest);
        tmpDir = new File(tmpDir + ".dir");
        tmpDir.mkdirs();
        ZooKeeperServer zs = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        NIOServerCnxn.Factory f = new NIOServerCnxn.Factory(33299);
        f.startup(zs);
        Thread.sleep(4000);
        ZooKeeper zk = new ZooKeeper("127.0.0.1:33299", 3000, this);
        
        long sessionId = zk.getSessionId();
        byte[] passwd = zk.getSessionPasswd();
        zk.close();
        zk = new ZooKeeper("127.0.0.1:33299", 3000, this, sessionId, passwd);
        assertEquals(sessionId, zk.getSessionId());
        zk.close();
        zs.shutdown();
        f.shutdown();
        
    }
    @Test
    public void testSession() throws IOException, InterruptedException,
            KeeperException {
        File tmpDir = File.createTempFile("test", ".junit", baseTest);
        tmpDir = new File(tmpDir + ".dir");
        tmpDir.mkdirs();
        ZooKeeperServer zs = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        NIOServerCnxn.Factory f = new NIOServerCnxn.Factory(33299);
        f.startup(zs);
        Thread.sleep(2000);
        ZooKeeper zk = new ZooKeeper("127.0.0.1:33299", 30000, this);
        zk
                .create("/e", new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateFlags.EPHEMERAL);
        System.out.println("zk with session id " + zk.getSessionId()
                + " was destroyed!");
        // zk.close();
        Stat stat = new Stat();
        try {
            zk = new ZooKeeper("127.0.0.1:33299", 30000, this, zk
                    .getSessionId(), zk.getSessionPasswd());
            System.out.println("zk with session id " + zk.getSessionId()
                    + " was created!");
            zk.getData("/e", false, stat);
            System.out.println("After get data /e");
        } catch (KeeperException e) {
            // the zk.close() above if uncommented will close the session on the
            // server
            // in such case we get an exception here because we've tried joining
            // a closed session
        }
        zk.close();
        Thread.sleep(10000);
        zk = new ZooKeeper("127.0.0.1:33299", 30000, this);
        assertEquals(null, zk.exists("/e", false));
        System.out.println("before close zk with session id "
                + zk.getSessionId() + "!");
        zk.close();
        System.out.println("before shutdown zs!");
        zs.shutdown();
        System.out.println("after shutdown zs!");
    }

    public void process(WatcherEvent event) {
    }

}
