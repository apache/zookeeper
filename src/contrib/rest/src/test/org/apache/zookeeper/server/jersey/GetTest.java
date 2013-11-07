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
import org.apache.zookeeper.server.jersey.jaxb.ZStat;
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
public class GetTest extends Base {
    protected static final Logger LOG = Logger.getLogger(GetTest.class);

    private String accept;
    private String path;
    private String encoding;
    private ClientResponse.Status expectedStatus;
    private ZStat expectedStat;

    @Parameters
    public static Collection<Object[]> data() throws Exception {
        String baseZnode = Base.createBaseZNode();

     return Arrays.asList(new Object[][] {
      {MediaType.APPLICATION_JSON, baseZnode, "utf8",
          ClientResponse.Status.OK, new ZStat(baseZnode, null, null) },
      {MediaType.APPLICATION_JSON, baseZnode, "utf8",
          ClientResponse.Status.OK, new ZStat(baseZnode, null, "") },
      {MediaType.APPLICATION_JSON, baseZnode, "utf8",
          ClientResponse.Status.OK, new ZStat(baseZnode, null, "foo") },
      {MediaType.APPLICATION_JSON, baseZnode, "base64",
          ClientResponse.Status.OK, new ZStat(baseZnode, null, null) },
      {MediaType.APPLICATION_JSON, baseZnode, "base64",
          ClientResponse.Status.OK, new ZStat(baseZnode, "".getBytes(), null) },
      {MediaType.APPLICATION_JSON, baseZnode, "base64",
          ClientResponse.Status.OK, new ZStat(baseZnode, "".getBytes(), null) },
      {MediaType.APPLICATION_JSON, baseZnode, "base64",
              ClientResponse.Status.OK, new ZStat(baseZnode, "foo".getBytes(), null) },
      {MediaType.APPLICATION_JSON, baseZnode + "abaddkdk", "utf8",
                      ClientResponse.Status.NOT_FOUND, null },
      {MediaType.APPLICATION_JSON, baseZnode + "abaddkdk", "base64",
              ClientResponse.Status.NOT_FOUND, null },

      {MediaType.APPLICATION_XML, baseZnode, "utf8",
                  ClientResponse.Status.OK, new ZStat(baseZnode, null, "foo") },
      {MediaType.APPLICATION_XML, baseZnode, "base64",
                      ClientResponse.Status.OK,
                      new ZStat(baseZnode, "foo".getBytes(), null) },
      {MediaType.APPLICATION_XML, baseZnode + "abaddkdk", "utf8",
                      ClientResponse.Status.NOT_FOUND, null },
      {MediaType.APPLICATION_XML, baseZnode + "abaddkdk", "base64",
              ClientResponse.Status.NOT_FOUND, null }

     });
    }

    public GetTest(String accept, String path, String encoding,
            ClientResponse.Status status, ZStat stat)
    {
        this.accept = accept;
        this.path = path;
        this.encoding = encoding;
        this.expectedStatus = status;
        this.expectedStat = stat;
    }

    @Test
    public void testGet() throws Exception {
        LOG.info("STARTING " + getName());

        if (expectedStat != null) {
            if (expectedStat.data64 != null || expectedStat.dataUtf8 == null) {
                zk.setData(expectedStat.path, expectedStat.data64, -1);
            } else {
                zk.setData(expectedStat.path,
                        expectedStat.dataUtf8.getBytes(), -1);
            }
        }

        ClientResponse cr = r.path(path).queryParam("dataformat", encoding)
            .accept(accept).get(ClientResponse.class);
        assertEquals(expectedStatus, cr.getClientResponseStatus());

        if (expectedStat == null) {
            return;
        }

        ZStat zstat = cr.getEntity(ZStat.class);
        assertEquals(expectedStat, zstat);
        assertEquals(r.path(path).toString(), zstat.uri);
    }
}
