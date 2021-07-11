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
import java.util.stream.Stream;

import javax.ws.rs.core.MediaType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.sun.jersey.api.client.ClientResponse;


/**
 * Test stand-alone server.
 *
 */
public class DeleteTest extends Base {
    protected static final Logger LOG = LoggerFactory.getLogger(DeleteTest.class);

    public static class MyWatcher implements Watcher {
        public void process(WatchedEvent event) {
            // FIXME ignore for now
        }
    }

    public static Stream<Arguments> data() throws Exception {
        String baseZnode = Base.createBaseZNode();

        return Stream.of(
                Arguments.of(baseZnode, ClientResponse.Status.NO_CONTENT),
                Arguments.of(baseZnode, ClientResponse.Status.NO_CONTENT));
    }

    public void verify(String type, String zpath, ClientResponse.Status expectedStatus) throws Exception {
        if (expectedStatus != ClientResponse.Status.NOT_FOUND) {
            zpath = zk.create(zpath, null, Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT_SEQUENTIAL);
        }

        ClientResponse cr = znodesr.path(zpath).accept(type).type(type)
            .delete(ClientResponse.class);
        Assertions.assertEquals(expectedStatus, cr.getClientResponseStatus());

        // use out-of-band method to verify
        Stat stat = zk.exists(zpath, false);
        Assertions.assertNull(stat);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testDelete(String zpath, ClientResponse.Status expectedStatus) throws Exception {
        verify(MediaType.APPLICATION_OCTET_STREAM, zpath, expectedStatus);
        verify(MediaType.APPLICATION_JSON, zpath, expectedStatus);
        verify(MediaType.APPLICATION_XML, zpath, expectedStatus);
    }
}
