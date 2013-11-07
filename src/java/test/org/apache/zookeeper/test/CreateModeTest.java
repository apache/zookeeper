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

package org.apache.zookeeper.test;

import java.util.EnumSet;

import junit.framework.TestCase;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.junit.Test;

public class CreateModeTest extends TestCase {
    
    @Test
    public void testBasicCreateMode() {
        CreateMode cm = CreateMode.PERSISTENT;
        assertEquals(cm.toFlag(), 0);
        assertFalse(cm.isEphemeral());
        assertFalse(cm.isSequential());
        
        cm = CreateMode.EPHEMERAL;
        assertEquals(cm.toFlag(), 1);
        assertTrue(cm.isEphemeral());
        assertFalse(cm.isSequential());
        
        cm = CreateMode.PERSISTENT_SEQUENTIAL;
        assertEquals(cm.toFlag(), 2);
        assertFalse(cm.isEphemeral());
        assertTrue(cm.isSequential());
        
        cm = CreateMode.EPHEMERAL_SEQUENTIAL;
        assertEquals(cm.toFlag(), 3);
        assertTrue(cm.isEphemeral());
        assertTrue(cm.isSequential());
    }
    
    @Test
    public void testFlagConversion() throws KeeperException {
        // Ensure we get the same value back after round trip conversion
        EnumSet<CreateMode> allModes = EnumSet.allOf(CreateMode.class);

        for(CreateMode cm : allModes) {
            assertEquals(cm, CreateMode.fromFlag( cm.toFlag() ) );
        }
    }

    @Test
    public void testInvalidFlagConversion() throws KeeperException {
        try {
            CreateMode cm = CreateMode.fromFlag(99);
            fail("Shouldn't be able to convert 99 to a CreateMode.");
        } catch(KeeperException ke) {
            assertEquals(Code.BADARGUMENTS, ke.code());
        }

        try {
            CreateMode cm = CreateMode.fromFlag(-1);
            fail("Shouldn't be able to convert -1 to a CreateMode.");
        } catch(KeeperException ke) {
            assertEquals(Code.BADARGUMENTS, ke.code());
        }
    }
}
