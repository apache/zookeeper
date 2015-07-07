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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.jersey.jaxb.ZPath;
import org.junit.Test;
import org.junit.Assert;
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
public class CreateTest extends Base {
    protected static final Logger LOG = LoggerFactory.getLogger(CreateTest.class);

    private String accept;
    private String path;
    private String name;
    private String encoding;
    private ClientResponse.Status expectedStatus;
    private ZPath expectedPath;
    private byte[] data;
    private boolean sequence;

    public static class MyWatcher implements Watcher {
        public void process(WatchedEvent event) {
            // FIXME ignore for now
        }
    }

    @Parameters
    public static Collection<Object[]> data() throws Exception {
        String baseZnode = Base.createBaseZNode();

        return Arrays.asList(new Object[][] {
          {MediaType.APPLICATION_JSON,
              baseZnode, "foo bar", "utf8",
              ClientResponse.Status.CREATED,
              new ZPath(baseZnode + "/foo bar"), null,
              false },
          {MediaType.APPLICATION_JSON, baseZnode, "c-t1", "utf8",
              ClientResponse.Status.CREATED, new ZPath(baseZnode + "/c-t1"),
              null, false },
          {MediaType.APPLICATION_JSON, baseZnode, "c-t1", "utf8",
              ClientResponse.Status.CONFLICT, null, null, false },
          {MediaType.APPLICATION_JSON, baseZnode, "c-t2", "utf8",
              ClientResponse.Status.CREATED, new ZPath(baseZnode + "/c-t2"),
              "".getBytes(), false },
          {MediaType.APPLICATION_JSON, baseZnode, "c-t2", "utf8",
              ClientResponse.Status.CONFLICT, null, null, false },
          {MediaType.APPLICATION_JSON, baseZnode, "c-t3", "utf8",
              ClientResponse.Status.CREATED, new ZPath(baseZnode + "/c-t3"),
              "foo".getBytes(), false },
          {MediaType.APPLICATION_JSON, baseZnode, "c-t3", "utf8",
              ClientResponse.Status.CONFLICT, null, null, false },
          {MediaType.APPLICATION_JSON, baseZnode, "c-t4", "base64",
              ClientResponse.Status.CREATED, new ZPath(baseZnode + "/c-t4"),
              "foo".getBytes(), false },
          {MediaType.APPLICATION_JSON, baseZnode, "c-", "utf8",
              ClientResponse.Status.CREATED, new ZPath(baseZnode + "/c-"), null,
              true },
          {MediaType.APPLICATION_JSON, baseZnode, "c-", "utf8",
              ClientResponse.Status.CREATED, new ZPath(baseZnode + "/c-"), null,
              true }
          });
    }

    public CreateTest(String accept, String path, String name, String encoding,
            ClientResponse.Status status, ZPath expectedPath, byte[] data,
            boolean sequence)
    {
        this.accept = accept;
        this.path = path;
        this.name = name;
        this.encoding = encoding;
        this.expectedStatus = status;
        this.expectedPath = expectedPath;
        this.data = data;
        this.sequence = sequence;
    }

    @Test
    public void testCreate() throws Exception {
        WebResource wr = znodesr.path(path).queryParam("dataformat", encoding)
            .queryParam("name", name);
        if (data == null) {
            wr = wr.queryParam("null", "true");
        }
        if (sequence) {
            wr = wr.queryParam("sequence", "true");
        }

        Builder builder = wr.accept(accept);

        ClientResponse cr;
        if (data == null) {
            cr = builder.post(ClientResponse.class);
        } else {
            cr = builder.post(ClientResponse.class, data);
        }
        Assert.assertEquals(expectedStatus, cr.getClientResponseStatus());

        if (expectedPath == null) {
            return;
        }

        ZPath zpath = cr.getEntity(ZPath.class);
        if (sequence) {
            Assert.assertTrue(zpath.path.startsWith(expectedPath.path));
            Assert.assertTrue(zpath.uri.startsWith(znodesr.path(path).toString()));
        } else {
            Assert.assertEquals(expectedPath, zpath);
            Assert.assertEquals(znodesr.path(path).toString(), zpath.uri);
        }

        // use out-of-band method to verify
        byte[] data = zk.getData(zpath.path, false, new Stat());
        if (data == null && this.data == null) {
            return;
        } else if (data == null || this.data == null) {
            Assert.assertEquals(data, this.data);
        } else {
            Assert.assertTrue(new String(data) + " == " + new String(this.data),
                    Arrays.equals(data, this.data));
        }
    }
}
