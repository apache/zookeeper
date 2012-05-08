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

import javax.ws.rs.core.MediaType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.jersey.jaxb.ZPath;
import org.junit.Test;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.WebResource.Builder;


/**
 * Test stand-alone server.
 *
 */
public class RootTest extends Base {
    protected static final Logger LOG = LoggerFactory.getLogger(RootTest.class);

    @Test
    public void testCreate() throws Exception {
        LOG.info("STARTING " + getName());
        
        String path = "/";
        String name = "roottest-create";
        byte[] data = "foo".getBytes();

        WebResource wr = znodesr.path(path).queryParam("dataformat", "utf8")
            .queryParam("name", name);
        Builder builder = wr.accept(MediaType.APPLICATION_JSON);

        ClientResponse cr;
        cr = builder.post(ClientResponse.class, data);
        assertEquals(ClientResponse.Status.CREATED, cr.getClientResponseStatus());

        ZPath zpath = cr.getEntity(ZPath.class);
        assertEquals(new ZPath(path + name), zpath);
        assertEquals(znodesr.path(path).toString(), zpath.uri);

        // use out-of-band method to verify
        byte[] rdata = zk.getData(zpath.path, false, new Stat());
        assertTrue(new String(rdata) + " == " + new String(data),
                Arrays.equals(rdata, data));
    }
}
