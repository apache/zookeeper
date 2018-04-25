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

package org.apache.zookeeper.server.persistence;

import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class TxnLogToolkitCliParserTest {

    private TxnLogToolkitCliParser parser;

    @Before
    public void setUp() {
        parser = new TxnLogToolkitCliParser();
    }

    @Test(expected = TxnLogToolkit.TxnLogToolkitParseException.class)
    public void testParseWithNoArguments() throws TxnLogToolkit.TxnLogToolkitParseException {
        parser.parse(null);
    }

    @Test(expected = TxnLogToolkit.TxnLogToolkitParseException.class)
    public void testParseWithEmptyArgs() throws TxnLogToolkit.TxnLogToolkitParseException {
        parser.parse(new String[0]);
    }

    @Test(expected = TxnLogToolkit.TxnLogToolkitParseException.class)
    public void testParseWith2Filenames() throws TxnLogToolkit.TxnLogToolkitParseException {
        parser.parse(new String[] { "file1.log", "file2.log "});
    }

    @Test(expected = TxnLogToolkit.TxnLogToolkitParseException.class)
    public void testParseWithInvalidShortSwitch() throws TxnLogToolkit.TxnLogToolkitParseException {
        parser.parse(new String[] { "-v", "-i", "txnlog.txt" });
    }

    @Test(expected = TxnLogToolkit.TxnLogToolkitParseException.class)
    public void testParseWithInvalidLongSwitch() throws TxnLogToolkit.TxnLogToolkitParseException {
        parser.parse(new String[] { "-v", "--invalid", "txnlog.txt" });
    }

    @Test
    public void testParseRecoveryModeSwitchShort() throws TxnLogToolkit.TxnLogToolkitParseException {
        parser.parse(new String[] { "-r", "txnlog.txt"});
        assertThat("Recovery short switch should turn on recovery mode", parser.isRecoveryMode(), is(true));
    }

    @Test
    public void testParseRecoveryModeSwitchLong() throws TxnLogToolkit.TxnLogToolkitParseException {
        parser.parse(new String[] { "--recover", "txnlog.txt"});
        assertThat("Recovery long switch should turn on recovery mode", parser.isRecoveryMode(), is(true));
    }

    @Test
    public void testParseVerboseModeSwitchShort() throws TxnLogToolkit.TxnLogToolkitParseException {
        parser.parse(new String[] { "-v", "txnlog.txt"});
        assertThat("Verbose short switch should turn on verbose mode", parser.isVerbose(), is(true));
    }

    @Test
    public void testParseVerboseModeSwitchLong() throws TxnLogToolkit.TxnLogToolkitParseException {
        parser.parse(new String[] { "--verbose", "txnlog.txt"});
        assertThat("Verbose long switch should turn on verbose mode", parser.isVerbose(), is(true));
    }

    @Test
    public void testParseDumpModeSwitchShort() throws TxnLogToolkit.TxnLogToolkitParseException {
        parser.parse(new String[] { "-r", "txnlog.txt"}); // turn on
        parser.parse(new String[] { "-d", "txnlog.txt"}); // turn off
        assertThat("Dump short switch should turn off recover mode", parser.isRecoveryMode(), is(false));
    }

    @Test
    public void testParseDumpModeSwitchLong() throws TxnLogToolkit.TxnLogToolkitParseException {
        parser.parse(new String[] { "-r", "txnlog.txt"}); // turn on
        parser.parse(new String[] { "--dump", "txnlog.txt"}); // turn off
        assertThat("Dump long switch should turn off recovery mode", parser.isRecoveryMode(), is(false));
    }

    @Test
    public void testParseForceModeSwitchShort() throws TxnLogToolkit.TxnLogToolkitParseException {
        parser.parse(new String[] { "-y", "txnlog.txt"});
        assertThat("Force short switch should turn on force mode", parser.isForce(), is(true));
    }

    @Test
    public void testParseForceModeSwitchLong() throws TxnLogToolkit.TxnLogToolkitParseException {
        parser.parse(new String[] { "--yes", "txnlog.txt"});
        assertThat("Force long switch should turn on force mode", parser.isForce(), is(true));
    }
}
