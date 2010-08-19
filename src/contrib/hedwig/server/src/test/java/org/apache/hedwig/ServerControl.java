/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hedwig;

import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.bookkeeper.proto.BookieServer;
import org.apache.hedwig.server.netty.PubSubServer;
import java.net.ConnectException;
import java.io.File;
import java.io.IOException;

public class ServerControl {
    public class TestException extends Exception {
	public TestException(String str) {
	    super(str);
	}
    };

    public interface TestServer {
	public String getAddress();
	public void kill();
    }

    private class BookKeeperServer extends BookieServer implements TestServer {
	private String address;

	public BookKeeperServer(int port, TestServer zkserver, String journal, String ledger) throws IOException {
	    super(port, zkserver.getAddress(), new File(journal), new File[] { new File(ledger) });
	    
	    address = "localhost:"+port;
	    start();
	} 

	public String getAddress() {
	    return address;
	}
	
	public void kill() {
	    try {
		shutdown();
	    } catch (Exception e) {
	    }
	}
    }

    private class ZookeeperServer extends ZooKeeperServerMain implements TestServer {
	public String address;
	public Thread serverThread;
	String path;
	public ZookeeperServer(int port, String path) throws TestException {
	    super(); 

	    this.path = path;
	    final String[] args = { Integer.toString(port), path};
	    address = "localhost:" + port;
	    serverThread = new Thread() {
		    public void run() {
			try {
			    initializeAndRun(args);
			} catch (Exception e) {
			}
		    };
		};
	    serverThread.start();
	}

	public String getAddress() {
	    return address;
	}

	public void kill() {
	    shutdown();
	    serverThread.interrupt();
	}
    }

    private class HedwigServer implements TestServer {
	private PubSubServer server;
	private String address;

	public HedwigServer(int port, String region, TestServer zk) throws TestException {
	    class MyServerConfiguration extends ServerConfiguration {
		MyServerConfiguration(int port, TestServer zk, String region) {
		    conf.setProperty(ServerConfiguration.SERVER_PORT, port);
		    conf.setProperty(ServerConfiguration.ZK_HOST, zk.getAddress());
		    conf.setProperty(ServerConfiguration.REGION, region);
		}
	    };
	    
	    address = "localhost:" + port;
	    
	    try {
		server = new PubSubServer(new MyServerConfiguration(port, zk, region));
	    } catch (Exception e) {
		throw new TestException("Couldn't create pub sub server : " + e);
	    }
	}

	public String getAddress() {
	    return address;
	}

	public void kill() {
	    server.shutdown();
	}
    }

    private String createTempDirectory(String suffix) throws IOException {
	String dir = System.getProperty("java.io.tmpdir") + File.separator + System.currentTimeMillis() + suffix;
	final File dirf = new File(dir);
	boolean good = dirf.mkdir();
	if (!good) {
	    throw new IOException("Unable to create directory " + dir);
	}
	
	Runtime.getRuntime().addShutdownHook(new Thread() {
		public void delete(File f) {
		    File[] subfiles = f.listFiles();
		    if (subfiles != null) {
			for (File subf : subfiles) {
			    delete(subf);
			}
		    }
		    f.delete();
		}

		public void run() {
		    delete(dirf);
		}
	    });
	return dir;
    }

    public TestServer startZookeeperServer(int port) throws IOException, TestException {
	String dir = createTempDirectory("-zookeeper-" + port);
	ZookeeperServer server =  new ZookeeperServer(port, dir);
	
	return server;
    }
    
    public TestServer startBookieServer(int port, TestServer zookeeperServer) throws IOException, TestException {
	int tries = 4;
	while (true) {
	    try {
		tries--;
		ZooKeeper zk = new ZooKeeper(zookeeperServer.getAddress(), 1000, new Watcher() { public void process(WatchedEvent event) { /* do nothing */ } });
		if (zk.exists("/ledgers/available", false) == null) {
		    byte[] data = new byte[1];
		    data[0] = 0;
		    zk.create("/ledgers", data, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
		    zk.create("/ledgers/available", data, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
		}
		zk.close();
		break;
	    } catch (KeeperException.ConnectionLossException ce) {
		if (tries > 0) {
		    try { 
			Thread.sleep(3);
		    } catch (Exception e) {
			throw new TestException("Can't even sleep. Fix your machine: " + e);
		    }
		    continue;
		} else {
		    throw new TestException("Error connecting to zookeeper: " + ce);
		}
	    } catch (Exception e) {
		throw new TestException("Error initialising bookkeeper ledgers: " +  e);
	    } 
	}
	String journal = createTempDirectory("-bookie-" + port + "-journal");
	String ledger = createTempDirectory("-bookie-" + port + "-ledger");
	System.out.println(journal);
	BookKeeperServer bookie = new BookKeeperServer(port, zookeeperServer, journal, ledger);
	return bookie;
    }
    
    public TestServer startPubSubServer(int port, String region, TestServer zookeeperServer) throws IOException, TestException {
	return new HedwigServer(port, region, zookeeperServer);
    }    

    public ServerControl() {
    }

    public static void main(String[] args) throws Exception {
	ServerControl control = new ServerControl();

	TestServer zk = control.startZookeeperServer(12345);
	TestServer bk1 = control.startBookieServer(12346, zk);
	TestServer bk2 = control.startBookieServer(12347, zk);
	TestServer bk3 = control.startBookieServer(12348, zk);

	TestServer hw1 = control.startPubSubServer(12349, "foobar", zk);
	TestServer hw2 = control.startPubSubServer(12350, "foobar", zk);
	TestServer hw3 = control.startPubSubServer(12351, "foobar", zk);
	TestServer hw4 = control.startPubSubServer(12352, "barfoo", zk);
	System.out.println("Started " + zk.getAddress());
	System.out.println("Sleeping for 10 seconds");
	Thread.sleep(10000);
	bk3.kill();
	bk2.kill();
	bk1.kill();
	zk.kill();
	hw1.kill();
	hw2.kill();
	hw3.kill();
	hw4.kill();
    }
}