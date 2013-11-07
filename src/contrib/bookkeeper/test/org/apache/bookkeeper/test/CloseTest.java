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
 * This unit test tests closing ledgers sequentially. It creates 4 ledgers, then
 * write 1000 entries to each ledger and close it.
 * 
 */

public class CloseTest extends BaseTestCase{
    static Logger LOG = Logger.getLogger(LedgerRecoveryTest.class);
    DigestType digestType;

    public CloseTest(DigestType digestType) {
        super(3);
        this.digestType = digestType;
    }

    @Test
    public void testClose() throws Exception {

        /*
         * Create 4 ledgers.
         */
        int numLedgers = 4;
        int numMsgs = 100;

        LedgerHandle[] lh = new LedgerHandle[numLedgers];
        for (int i = 0; i < numLedgers; i++) {
            lh[i] = bkc.createLedger(digestType, "".getBytes());
        }

        String tmp = "BookKeeper is cool!";

        /*
         * Write 1000 entries to lh1.
         */
        for (int i = 0; i < numMsgs; i++) {
            for (int j = 0; j < numLedgers; j++) {
                lh[j].addEntry(tmp.getBytes());
            }
        }

        for (int i = 0; i < numLedgers; i++) {

            lh[i].close();
        }
    }
}
