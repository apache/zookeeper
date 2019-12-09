package org.apache.zookeeper.common;

import org.apache.zookeeper.common.NetUtils;
import org.apache.zookeeper.ZKTestCase;
import org.hamcrest.core.AnyOf;
import org.hamcrest.core.IsEqual;
import org.junit.Assert;
import org.junit.Test;
import java.net.InetSocketAddress;

public class NetUtilsTest extends ZKTestCase {

    private Integer port = 1234;
    private String v4addr = "127.0.0.1";
    private String v6addr = "[0:0:0:0:0:0:0:1]";
    private String v6addr2 = "[2600:0:0:0:0:0:0:0]";
    private String v4local = v4addr + ":" + port.toString();
    private String v6local = v6addr + ":" + port.toString();
    private String v6ext = v6addr2 + ":" + port.toString();

    @Test
    public void testFormatInetAddrGoodIpv4() {
        InetSocketAddress isa = new InetSocketAddress(v4addr, port);
        Assert.assertEquals("127.0.0.1:1234", NetUtils.formatInetAddr(isa));
    }

    @Test
    public void testFormatInetAddrGoodIpv6Local() {
        // Have to use the expanded address here, hence not using v6addr in instantiation
        InetSocketAddress isa = new InetSocketAddress("::1", port);
        Assert.assertEquals(v6local, NetUtils.formatInetAddr(isa));
    }

    @Test
    public void testFormatInetAddrGoodIpv6Ext() {
        // Have to use the expanded address here, hence not using v6addr in instantiation
        InetSocketAddress isa = new InetSocketAddress("2600::", port);
        Assert.assertEquals(v6ext, NetUtils.formatInetAddr(isa));
    }

    @Test
    public void testFormatInetAddrGoodHostname() {
        InetSocketAddress isa = new InetSocketAddress("localhost", 1234);

        Assert.assertThat(NetUtils.formatInetAddr(isa),
                AnyOf.anyOf(IsEqual.equalTo(v4local), IsEqual.equalTo(v6local)
                ));
    }

    @Test
    public void testFormatAddrUnresolved() {
        InetSocketAddress isa = InetSocketAddress.createUnresolved("doesnt.exist.com", 1234);
        Assert.assertEquals("doesnt.exist.com:1234", NetUtils.formatInetAddr(isa));
    }
}