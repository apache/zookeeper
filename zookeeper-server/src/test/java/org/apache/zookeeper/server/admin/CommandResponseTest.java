/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.HashMap;
import java.util.Map;
import org.apache.zookeeper.ZKTestCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CommandResponseTest extends ZKTestCase {

    private CommandResponse r;

    @BeforeEach
    public void setUp() throws Exception {
        r = new CommandResponse("makemeasandwich", "makeityourself");
    }

    @Test
    public void testGetters() {
        assertEquals("makemeasandwich", r.getCommand());
        assertEquals("makeityourself", r.getError());
    }

    @Test
    public void testMap() {
        r.put("missing", "sudo");
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("origin", "xkcd");
        m.put("url", "http://xkcd.com/149/");
        r.putAll(m);

        Map<String, Object> rmap = r.toMap();
        assertEquals(5, rmap.size());
        assertEquals("makemeasandwich", rmap.get(CommandResponse.KEY_COMMAND));
        assertEquals("makeityourself", rmap.get(CommandResponse.KEY_ERROR));
        assertEquals("sudo", rmap.get("missing"));
        assertEquals("xkcd", rmap.get("origin"));
        assertEquals("http://xkcd.com/149/", rmap.get("url"));
    }

}
