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

import java.io.IOException;

import javax.ws.rs.core.MediaType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.jersey.jaxb.ZSession;
import org.codehaus.jettison.json.JSONException;
import org.junit.Assert;
import org.junit.Test;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.WebResource.Builder;

public class SessionTest extends Base {
    protected static final Logger LOG = LoggerFactory.getLogger(SessionTest.class);

    private ZSession createSession() {
        return createSession("30");
    }

    private ZSession createSession(String expire) {
        WebResource wr = sessionsr.queryParam("op", "create")
            .queryParam("expire", expire);
        Builder b = wr.accept(MediaType.APPLICATION_JSON);

        ClientResponse cr = b.post(ClientResponse.class, null);
        Assert.assertEquals(ClientResponse.Status.CREATED, cr
                .getClientResponseStatus());

        return cr.getEntity(ZSession.class);
    }

    @Test
    public void testCreateNewSession() throws JSONException {
        ZSession session = createSession();
        Assert.assertEquals(session.id.length(), 36);

        // use out-of-band method to verify
        Assert.assertTrue(ZooKeeperService.isConnected(CONTEXT_PATH, session.id));
    }

    @Test
    public void testSessionExpires() throws InterruptedException {
        ZSession session = createSession("1");

        // use out-of-band method to verify
        Assert.assertTrue(ZooKeeperService.isConnected(CONTEXT_PATH, session.id));

        // wait for the session to be closed
        Thread.sleep(1500);
        Assert.assertFalse(ZooKeeperService.isConnected(CONTEXT_PATH, session.id));
    }

    @Test
    public void testDeleteSession() {
        ZSession session = createSession("30");

        WebResource wr = sessionsr.path(session.id);
        Builder b = wr.accept(MediaType.APPLICATION_JSON);

        Assert.assertTrue(ZooKeeperService.isConnected(CONTEXT_PATH, session.id));
        ClientResponse cr = b.delete(ClientResponse.class, null);
        Assert.assertEquals(ClientResponse.Status.NO_CONTENT,
                cr.getClientResponseStatus());

        Assert.assertFalse(ZooKeeperService.isConnected(CONTEXT_PATH, session.id));
    }
    
    @Test
    public void testSendHeartbeat() throws InterruptedException {
        ZSession session = createSession("2");
        
        Thread.sleep(1000);
        WebResource wr = sessionsr.path(session.id);
        Builder b = wr.accept(MediaType.APPLICATION_JSON);
        
        ClientResponse cr = b.put(ClientResponse.class, null);
        Assert.assertEquals(ClientResponse.Status.OK, cr.getClientResponseStatus());
        
        Thread.sleep(1500);
        Assert.assertTrue(ZooKeeperService.isConnected(CONTEXT_PATH, session.id));
        
        Thread.sleep(1000);
        Assert.assertFalse(ZooKeeperService.isConnected(CONTEXT_PATH, session.id));
    }
    
    @Test
    public void testCreateEphemeralZNode() 
    throws KeeperException, InterruptedException, IOException {
        ZSession session = createSession("30");
        
        WebResource wr = znodesr.path("/")
            .queryParam("op", "create")
            .queryParam("name", "ephemeral-test")
            .queryParam("ephemeral", "true")
            .queryParam("session", session.id)
            .queryParam("null", "true");
        
        Builder b = wr.accept(MediaType.APPLICATION_JSON);
        ClientResponse cr = b.post(ClientResponse.class);
        Assert.assertEquals(ClientResponse.Status.CREATED, cr.getClientResponseStatus());
        
        Stat stat = new Stat();
        zk.getData("/ephemeral-test", false, stat);
        
        ZooKeeper sessionZK = ZooKeeperService.getClient(CONTEXT_PATH, session.id);
        Assert.assertEquals(stat.getEphemeralOwner(), sessionZK.getSessionId());
    }
}
