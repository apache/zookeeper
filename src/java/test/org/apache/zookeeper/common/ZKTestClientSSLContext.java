package org.apache.zookeeper.common;

import javax.net.ssl.SSLContext;

public class ZKTestClientSSLContext implements ZKClientSSLContext {

    @Override
    public SSLContext getSSLContext() {
        return null;
    }

}
