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
package org.apache.hedwig.server.persistence;

import junit.framework.Test;
import junit.framework.TestSuite;

import org.apache.hedwig.server.persistence.LocalDBPersistenceManager;
import org.apache.hedwig.server.persistence.PersistenceManager;

public class TestLocalDBPersistenceManagerBlackBox extends TestPersistenceManagerBlackBox {

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        ((LocalDBPersistenceManager) persistenceManager).reset();
    }

    @Override
    long getLowestSeqId() {
        return 1;
    }

    @Override
    PersistenceManager instantiatePersistenceManager() {
        return LocalDBPersistenceManager.instance();
    }

    @Override
    public long getExpectedSeqId(int numPublished) {
        return numPublished;
    }

    public static Test suite() {
        return new TestSuite(TestLocalDBPersistenceManagerBlackBox.class);
    }

}
