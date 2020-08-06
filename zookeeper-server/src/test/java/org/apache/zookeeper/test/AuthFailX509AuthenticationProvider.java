package org.apache.zookeeper.test;

import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.auth.X509AuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthFailX509AuthenticationProvider extends X509AuthenticationProvider {
  private static final Logger LOG = LoggerFactory.getLogger(AuthFailX509AuthenticationProvider.class);

  public AuthFailX509AuthenticationProvider() throws X509Exception {
    super();
  }

  public AuthFailX509AuthenticationProvider(X509TrustManager trustManager, X509KeyManager keyManager) {
    super(trustManager, keyManager);
  }

  @Override
  public KeeperException.Code handleAuthentication(ServerCnxn cnxn, byte[] authData) {
    LOG.info("Authentication failed");
    return KeeperException.Code.AUTHFAILED;
  }

  @Override
  public String getScheme() {
    return "authfail";
  }
}
