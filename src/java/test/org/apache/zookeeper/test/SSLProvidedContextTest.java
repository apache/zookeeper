package org.apache.zookeeper.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.common.ZKSSLContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.apache.zookeeper.common.X509Exception.SSLContextException;
import org.apache.zookeeper.server.ServerCnxnFactory;

public class SSLProvidedContextTest extends ClientBase {

	@Before
	public void setUp() throws Exception {
		String testDataPath = System.getProperty("test.data.dir", "build/test/data");
		System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY,
				"org.apache.zookeeper.server.NettyServerCnxnFactory");
		System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
		System.setProperty(ZKClientConfig.SECURE_CLIENT, "true");
		System.setProperty(ZKConfig.CLIENT_SSL_CONTEXT,
				"org.apache.zookeeper.test.SSLProvidedContextTest.TestSSLContext");

		System.setProperty(ZKConfig.SSL_AUTHPROVIDER, "x509");
		System.setProperty(ZKConfig.SSL_KEYSTORE_LOCATION, testDataPath + "/ssl/testKeyStore.jks");
		System.setProperty(ZKConfig.SSL_KEYSTORE_PASSWD, "testpass");
		System.setProperty(ZKConfig.SSL_TRUSTSTORE_LOCATION, testDataPath + "/ssl/testTrustStore.jks");
		System.setProperty(ZKConfig.SSL_TRUSTSTORE_PASSWD, "testpass");
		System.setProperty("javax.net.debug", "ssl");
		System.setProperty("zookeeper.authProvider.x509",
				"org.apache.zookeeper.server.auth.X509AuthenticationProvider");

		String host = "localhost";
		int port = PortAssignment.unique();
		hostPort = host + ":" + port;

		serverFactory = ServerCnxnFactory.createFactory();
		serverFactory.configure(new InetSocketAddress(host, port), maxCnxns, true);

		super.setUp();
	}

	@After
	public void teardown() throws Exception {
		System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
		System.clearProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET);
		System.clearProperty(ZKClientConfig.SECURE_CLIENT);
		System.clearProperty(ZKConfig.CLIENT_SSL_CONTEXT);
		System.clearProperty(ZKConfig.SSL_AUTHPROVIDER);
		System.clearProperty(ZKConfig.SSL_KEYSTORE_LOCATION);
		System.clearProperty(ZKConfig.SSL_KEYSTORE_PASSWD);
		System.clearProperty(ZKConfig.SSL_TRUSTSTORE_LOCATION);
		System.clearProperty(ZKConfig.SSL_TRUSTSTORE_PASSWD);
		System.clearProperty("javax.net.debug");
		System.clearProperty("zookeeper.authProvider.x509");
	}

	@Test
	public void testRejection() throws Exception {
		CountdownWatcher watcher = new CountdownWatcher();

		// Handshake will take place, and then X509AuthenticationProvider should
		// reject the untrusted cert
		new TestableZooKeeper(hostPort, CONNECTION_TIMEOUT, watcher);
		Assert.assertFalse("Untrusted certificate should not result in successful connection",
				watcher.clientConnected.await(1000, TimeUnit.MILLISECONDS));
	}

	public class TestSSLContext implements ZKSSLContext {

		@Override
		public SSLContext getSSLContext() throws SSLContextException {
			String testDataPath = System.getProperty("test.data.dir", "build/test/data");
			FileInputStream inputStream = null;
			try {
				SSLContext sslContext = SSLContext.getDefault();
				KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
				inputStream = new FileInputStream(new File(testDataPath + "/ssl/testUntrustedKeyStore.jks"));
				keystore.load(inputStream, "testpass".toCharArray());
				KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
				kmf.init(keystore, "testpass".toCharArray());
				sslContext.init(kmf.getKeyManagers(), null, null);
				return sslContext;
			} catch (IOException | KeyStoreException | NoSuchAlgorithmException | CertificateException
					| UnrecoverableKeyException | KeyManagementException e) {
				throw new SSLContextException("could not generate default SSLContext", e);
			} finally {
				if (inputStream != null) {
					try {
						inputStream.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}

	}

}
