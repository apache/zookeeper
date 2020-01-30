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

package org.apache.zookeeper.common;

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