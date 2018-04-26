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
import java.util.Collections;
import java.util.List;

import javax.ws.rs.core.MediaType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.jersey.jaxb.ZChildren;
import org.apache.zookeeper.server.jersey.jaxb.ZChildrenJSON;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.sun.jersey.api.client.ClientResponse;


/**
 * Test stand-alone server.
 *
 */
@RunWith(Parameterized.class)
public class GetChildrenTest extends Base {
    protected static final Logger LOG = LoggerFactory.getLogger(GetChildrenTest.class);

    private String accept;
    private String path;
    private ClientResponse.Status expectedStatus;
    private String expectedPath;
    private List<String> expectedChildren;

    @Parameters
    public static Collection<Object[]> data() throws Exception {
        String baseZnode = Base.createBaseZNode();
        String baseZnode2 = Base.createBaseZNode();
        String baseZnode3 = Base.createBaseZNode();
        String baseZnode4 = Base.createBaseZNode();
        String baseZnode5 = Base.createBaseZNode();
        String baseZnode6 = Base.createBaseZNode();

        return Arrays.asList(new Object[][] {
          {MediaType.APPLICATION_JSON, baseZnode + "abddkdkd",
              ClientResponse.Status.NOT_FOUND, null, null },
          {MediaType.APPLICATION_XML, baseZnode + "abddkdkd",
              ClientResponse.Status.NOT_FOUND, null, null },
          {MediaType.APPLICATION_JSON, baseZnode, ClientResponse.Status.OK,
              baseZnode, Arrays.asList(new String[] {}) },
          {MediaType.APPLICATION_XML, baseZnode, ClientResponse.Status.OK,
              baseZnode, Arrays.asList(new String[] {}) },
          {MediaType.APPLICATION_JSON, baseZnode, ClientResponse.Status.OK,
              baseZnode, Arrays.asList(new String[] {"c1"}) },
          {MediaType.APPLICATION_XML, baseZnode4, ClientResponse.Status.OK,
              baseZnode4, Arrays.asList(new String[] {"c1"}) },
          {MediaType.APPLICATION_JSON, baseZnode2, ClientResponse.Status.OK,
              baseZnode2, Arrays.asList(new String[] {"c1", "c2"}) },
          {MediaType.APPLICATION_XML, baseZnode5, ClientResponse.Status.OK,
              baseZnode5, Arrays.asList(new String[] {"c1", "c2"}) },
          {MediaType.APPLICATION_JSON, baseZnode3, ClientResponse.Status.OK,
              baseZnode3, Arrays.asList(new String[] {"c1", "c2", "c3", "c4"}) },
          {MediaType.APPLICATION_XML, baseZnode6, ClientResponse.Status.OK,
              baseZnode6, Arrays.asList(new String[] {"c1", "c2", "c3", "c4"}) }

          });
    }

    public GetChildrenTest(String accept, String path, ClientResponse.Status status,
            String expectedPath, List<String> expectedChildren)
    {
        this.accept = accept;
        this.path = path;
        this.expectedStatus = status;
        this.expectedPath = expectedPath;
        this.expectedChildren = expectedChildren;
    }

    @Test
    public void testGetChildren() throws Exception {
        if (expectedChildren != null) {
            for(String child : expectedChildren) {
                zk.create(expectedPath + "/" + child, null,
                        Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        }

        ClientResponse cr = znodesr.path(path).queryParam("view", "children")
            .accept(accept).get(ClientResponse.class);
        Assert.assertEquals(expectedStatus, cr.getClientResponseStatus());

        if (expectedChildren == null) {
            return;
        }

        if (accept.equals(MediaType.APPLICATION_JSON)) {
            ZChildrenJSON zchildren = cr.getEntity(ZChildrenJSON.class);
            Collections.sort(expectedChildren);
            Collections.sort(zchildren.children);
            Assert.assertEquals(expectedChildren, zchildren.children);
            Assert.assertEquals(znodesr.path(path).toString(), zchildren.uri);
            Assert.assertEquals(znodesr.path(path).toString() + "/{child}",
                    zchildren.child_uri_template);
        } else if (accept.equals(MediaType.APPLICATION_XML)) {
            ZChildren zchildren = cr.getEntity(ZChildren.class);
            Collections.sort(expectedChildren);
            Collections.sort(zchildren.children);
            Assert.assertEquals(expectedChildren, zchildren.children);
            Assert.assertEquals(znodesr.path(path).toString(), zchildren.uri);
            Assert.assertEquals(znodesr.path(path).toString() + "/{child}",
                    zchildren.child_uri_template);
        } else {
            Assert.fail("unknown accept type");
        }
    }
}
