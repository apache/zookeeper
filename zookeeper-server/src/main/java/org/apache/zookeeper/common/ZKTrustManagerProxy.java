package org.apache.zookeeper.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public class ZKTrustManagerProxy implements InvocationHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ZKTrustManagerProxy.class);
    private ZKTrustManager zkTrustManager;

    public ZKTrustManagerProxy(ZKTrustManager zkTrustManager) {
        this.zkTrustManager = zkTrustManager;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        LOG.debug("Invoking method {} with arguments {}", method.getName(), args);
        return method.invoke(zkTrustManager, args);
    }
}
