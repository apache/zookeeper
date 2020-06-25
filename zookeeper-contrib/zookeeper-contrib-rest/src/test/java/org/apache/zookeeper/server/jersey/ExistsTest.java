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
public class ExistsTest extends Base {
    protected static final Logger LOG = LoggerFactory.getLogger(ExistsTest.class);

    private String path;
    private ClientResponse.Status expectedStatus;

    public static Stream<Arguments> data() throws Exception {
        String baseZnode = Base.createBaseZNode();

        return Stream.of(
                Arguments.of(baseZnode, ClientResponse.Status.OK),
                Arguments.of(baseZnode + "dkdk38383", ClientResponse.Status.NOT_FOUND));
    }

    public ExistsTest(String path, ClientResponse.Status status) {
        this.path = path;
        this.expectedStatus = status;
    }

    private void verify(String type) {
        ClientResponse cr = znodesr.path(path).accept(type).type(type).head();
        if (type.equals(MediaType.APPLICATION_OCTET_STREAM)
                && expectedStatus == ClientResponse.Status.OK) {
            Assertions.assertEquals(ClientResponse.Status.NO_CONTENT,
                    cr.getClientResponseStatus());
        } else {
            Assertions.assertEquals(expectedStatus, cr.getClientResponseStatus());
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testExists() throws Exception {
        verify(MediaType.APPLICATION_OCTET_STREAM);
        verify(MediaType.APPLICATION_JSON);
        verify(MediaType.APPLICATION_XML);
    }
}
