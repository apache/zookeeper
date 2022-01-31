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
import org.apache.zookeeper.server.jersey.jaxb.ZStat;
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
public class GetTest extends Base {
    protected static final Logger LOG = LoggerFactory.getLogger(GetTest.class);

    public static Stream<Arguments> data() throws Exception {
        String baseZnode = Base.createBaseZNode();

        return Stream.of(
                Arguments.of(MediaType.APPLICATION_JSON, baseZnode, "utf8",
                        ClientResponse.Status.OK, new ZStat(baseZnode, null, null)),
                Arguments.of(MediaType.APPLICATION_JSON, baseZnode, "utf8",
                        ClientResponse.Status.OK, new ZStat(baseZnode, null, "")),
                Arguments.of(MediaType.APPLICATION_JSON, baseZnode, "utf8",
                        ClientResponse.Status.OK, new ZStat(baseZnode, null, "foo")),
                Arguments.of(MediaType.APPLICATION_JSON, baseZnode, "base64",
                        ClientResponse.Status.OK, new ZStat(baseZnode, null, null)),
                Arguments.of(MediaType.APPLICATION_JSON, baseZnode, "base64",
                        ClientResponse.Status.OK, new ZStat(baseZnode, "".getBytes(), null)),
                Arguments.of(MediaType.APPLICATION_JSON, baseZnode, "base64",
                        ClientResponse.Status.OK, new ZStat(baseZnode, "".getBytes(), null)),
                Arguments.of(MediaType.APPLICATION_JSON, baseZnode, "base64",
                        ClientResponse.Status.OK, new ZStat(baseZnode, "foo".getBytes(), null)),
                Arguments.of(MediaType.APPLICATION_JSON, baseZnode + "abaddkdk", "utf8",
                        ClientResponse.Status.NOT_FOUND, null),
                Arguments.of(MediaType.APPLICATION_JSON, baseZnode + "abaddkdk", "base64",
                        ClientResponse.Status.NOT_FOUND, null),
                Arguments.of(MediaType.APPLICATION_XML, baseZnode, "utf8",
                        ClientResponse.Status.OK, new ZStat(baseZnode, null, "foo")),
                Arguments.of(MediaType.APPLICATION_XML, baseZnode, "base64",
                        ClientResponse.Status.OK,
                        new ZStat(baseZnode, "foo".getBytes(), null)),
                Arguments.of(MediaType.APPLICATION_XML, baseZnode + "abaddkdk", "utf8",
                        ClientResponse.Status.NOT_FOUND, null),
                Arguments.of(MediaType.APPLICATION_XML, baseZnode + "abaddkdk", "base64",
                        ClientResponse.Status.NOT_FOUND, null));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testGet(String accept, String path, String encoding,
                        ClientResponse.Status expectedStatus, ZStat expectedStat) throws Exception {
        if (expectedStat != null) {
            if (expectedStat.data64 != null || expectedStat.dataUtf8 == null) {
                zk.setData(expectedStat.path, expectedStat.data64, -1);
            } else {
                zk.setData(expectedStat.path,
                        expectedStat.dataUtf8.getBytes(), -1);
            }
        }

        ClientResponse cr = znodesr.path(path).queryParam("dataformat", encoding)
            .accept(accept).get(ClientResponse.class);
        Assertions.assertEquals(expectedStatus, cr.getClientResponseStatus());

        if (expectedStat == null) {
            return;
        }

        ZStat zstat = cr.getEntity(ZStat.class);
        Assertions.assertEquals(expectedStat, zstat);
        Assertions.assertEquals(znodesr.path(path).toString(), zstat.uri);
    }
}
