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

package org.apache.zookeeper.server.jersey;

import java.util.Arrays;
import java.util.Collection;

import javax.ws.rs.core.MediaType;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.jersey.jaxb.ZStat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.WebResource.Builder;


/**
 * Test stand-alone server.
 *
 */
@RunWith(Parameterized.class)
public class SetTest extends Base {
    protected static final Logger LOG = Logger.getLogger(SetTest.class);

    private String accept;
    private String path;
    private String encoding;
    private ClientResponse.Status expectedStatus;
    private ZStat expectedStat;
    private byte[] data;

    public static class MyWatcher implements Watcher {
        public void process(WatchedEvent event) {
            // FIXME ignore for now
        }
    }

    @Parameters
    public static Collection<Object[]> data() throws Exception {
        String baseZnode = Base.createBaseZNode();

        return Arrays.asList(new Object[][] {
          {MediaType.APPLICATION_JSON, baseZnode + "/s-t1", "utf8",
              ClientResponse.Status.OK,
              new ZStat(baseZnode + "/s-t1", null, null), null },
          {MediaType.APPLICATION_JSON, baseZnode + "/s-t2", "utf8",
              ClientResponse.Status.OK,
              new ZStat(baseZnode + "/s-t2", null, null), new byte[0] },
          {MediaType.APPLICATION_JSON, baseZnode + "/s-t3", "utf8",
              ClientResponse.Status.OK,
              new ZStat(baseZnode + "/s-t3", null, null), "foobar".getBytes() },
          {MediaType.APPLICATION_JSON, baseZnode + "/s-t4", "base64",
              ClientResponse.Status.OK,
              new ZStat(baseZnode + "/s-t4", null, null), null },
          {MediaType.APPLICATION_JSON, baseZnode + "/s-t5", "base64",
              ClientResponse.Status.OK,
              new ZStat(baseZnode + "/s-t5", null, null), new byte[0] },
          {MediaType.APPLICATION_JSON, baseZnode + "/s-t6", "base64",
              ClientResponse.Status.OK,
              new ZStat(baseZnode + "/s-t6", null, null),
              "foobar".getBytes() },
          {MediaType.APPLICATION_JSON, baseZnode + "/dkdkdkd", "utf8",
              ClientResponse.Status.NOT_FOUND, null, null },
          {MediaType.APPLICATION_JSON, baseZnode + "/dkdkdkd", "base64",
              ClientResponse.Status.NOT_FOUND, null, null },
          });
    }

    public SetTest(String accept, String path, String encoding,
            ClientResponse.Status status, ZStat expectedStat, byte[] data)
    {
        this.accept = accept;
        this.path = path;
        this.encoding = encoding;
        this.expectedStatus = status;
        this.expectedStat = expectedStat;
        this.data = data;
    }

    @Test
    public void testSet() throws Exception {
        LOG.info("STARTING " + getName());

        if (expectedStat != null) {
            zk.create(expectedStat.path, "initial".getBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
        }

        WebResource wr = r.path(path).queryParam("dataformat", encoding);
        if (data == null) {
            wr = wr.queryParam("null", "true");
        }

        Builder builder = wr.accept(accept)
            .type(MediaType.APPLICATION_OCTET_STREAM);

        ClientResponse cr;
        if (data == null) {
            cr = builder.put(ClientResponse.class);
        } else {
            // this shouldn't be necessary (wrapping data with string)
            // but without it there are problems on the server - ie it
            // hangs for 30 seconds and doesn't get the data.
            // TODO investigate
            cr = builder.put(ClientResponse.class, new String(data));
        }
        assertEquals(expectedStatus, cr.getClientResponseStatus());

        if (expectedStat == null) {
            return;
        }

        ZStat zstat = cr.getEntity(ZStat.class);
        assertEquals(expectedStat, zstat);

        // use out-of-band method to verify
        byte[] data = zk.getData(zstat.path, false, new Stat());
        if (data == null && this.data == null) {
            return;
        } else if (data == null || this.data == null) {
            fail((data == null ? null : new String(data)) + " == "
                    + (this.data == null ? null : new String(this.data)));
        } else {
            assertTrue(new String(data) + " == " + new String(this.data),
                    Arrays.equals(data, this.data));
        }
    }
}
