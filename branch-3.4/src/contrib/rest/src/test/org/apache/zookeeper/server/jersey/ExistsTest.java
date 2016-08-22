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
public class ExistsTest extends Base {
    protected static final Logger LOG = LoggerFactory.getLogger(ExistsTest.class);

    private String path;
    private ClientResponse.Status expectedStatus;

    @Parameters
    public static Collection<Object[]> data() throws Exception {
        String baseZnode = Base.createBaseZNode();

     return Arrays.asList(new Object[][] {
      {baseZnode, ClientResponse.Status.OK },
      {baseZnode + "dkdk38383", ClientResponse.Status.NOT_FOUND }
     });
    }

    public ExistsTest(String path, ClientResponse.Status status) {
        this.path = path;
        this.expectedStatus = status;
    }

    private void verify(String type) {
        ClientResponse cr = znodesr.path(path).accept(type).type(type).head();
        if (type.equals(MediaType.APPLICATION_OCTET_STREAM)
                && expectedStatus == ClientResponse.Status.OK) {
            assertEquals(ClientResponse.Status.NO_CONTENT,
                    cr.getClientResponseStatus());
        } else {
            assertEquals(expectedStatus, cr.getClientResponseStatus());
        }
    }

    @Test
    public void testExists() throws Exception {
        LOG.info("STARTING " + getName());
        verify(MediaType.APPLICATION_OCTET_STREAM);
        verify(MediaType.APPLICATION_JSON);
        verify(MediaType.APPLICATION_XML);
    }
}
