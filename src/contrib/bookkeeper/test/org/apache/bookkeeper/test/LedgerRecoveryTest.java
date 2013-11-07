package org.apache.bookkeeper.test;

/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */

import org.junit.*;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.BookKeeper.DigestType;
import org.apache.log4j.Logger;

/**
 * This unit test tests ledger recovery.
 * 
 */

public class LedgerRecoveryTest extends BaseTestCase {
    static Logger LOG = Logger.getLogger(LedgerRecoveryTest.class);

    DigestType digestType;

    public LedgerRecoveryTest(DigestType digestType) {
        super(3);
        this.digestType = digestType;
    }

    private void testInternal(int numEntries) throws Exception {
        /*
         * Create ledger.
         */
        LedgerHandle beforelh = null;
        beforelh = bkc.createLedger(digestType, "".getBytes());

        String tmp = "BookKeeper is cool!";
        for (int i = 0; i < numEntries; i++) {
            beforelh.addEntry(tmp.getBytes());
        }

        long length = (long) (numEntries * tmp.length());
        
        /*
         * Try to open ledger.
         */
        LedgerHandle afterlh = bkc.openLedger(beforelh.getId(), digestType, "".getBytes());

        /*
         * Check if has recovered properly.
         */
        assertTrue("Has not recovered correctly: " + afterlh.getLastAddConfirmed(),
                afterlh.getLastAddConfirmed() == numEntries - 1);       
        assertTrue("Has not set the length correctly: " + afterlh.getLength() + ", " + length, 
                afterlh.getLength() == length);
    }
    
    @Test
    public void testLedgerRecovery() throws Exception {
        testInternal(100);
     
    }

    @Test
    public void testEmptyLedgerRecoveryOne() throws Exception{
        testInternal(1);
    }

    @Test
    public void testEmptyLedgerRecovery() throws Exception{
        testInternal(0);
    }

}
