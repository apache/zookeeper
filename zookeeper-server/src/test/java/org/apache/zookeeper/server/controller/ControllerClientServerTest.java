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

package org.apache.zookeeper.server.controller;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class ControllerClientServerTest extends ControllerTestBase {
    @Test
    public void verifyPingCommand() {
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.PING));
    }

    @Test
    public void verifyCloseConnectionCommand() {
        // Valid long session ids should be accepted.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.CLOSECONNECTION, "0x1234"));
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.CLOSECONNECTION, "1234"));

        // Invalid session id format should fail.
        Assert.assertFalse(commandClient.trySendCommand(ControlCommand.Action.CLOSECONNECTION, "hanm"));

        // No parameter should be accepted.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.CLOSECONNECTION));
    }

    // TODO (hanm): this depends on the expiration session feature which
    // is not part of this patch. This test will be enabled once that
    // feature is upstreamed.
    @Ignore
    public void verifyExpireSessionCommand() {
        // Valid long session ids should be accepted.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.EXPIRESESSION, "0x1234"));

        // Invalid session id format should fail.
        Assert.assertFalse(commandClient.trySendCommand(ControlCommand.Action.EXPIRESESSION, "hanm"));

        // No parameter should be accepted.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.EXPIRESESSION));
    }

    @Test
    public void verifyAddResetDelayCommands() {
        // Valid longs should be parsed.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.ADDDELAY, "0x1234"));

        // Invalid longs should fail.
        Assert.assertFalse(commandClient.trySendCommand(ControlCommand.Action.ADDDELAY, "hanm"));

        // No parameter should be accepted.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.ADDDELAY));

        // Reset delay should work.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.RESET));
    }

    @Test
    public void verifyBadResponseCommands() {
        // Valid longs should be parsed.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.FAILREQUESTS, "0x1234"));

        // Invalid longs should fail.
        Assert.assertFalse(commandClient.trySendCommand(ControlCommand.Action.FAILREQUESTS, "hanm"));

        // No parameter should be accepted.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.FAILREQUESTS));

        // Reset should work.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.RESET));
    }

    @Test
    public void verifyEatResponseCommands() {
        // Valid longs should be parsed.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.NORESPONSE, "0x1234"));

        // Invalid longs should fail.
        Assert.assertFalse(commandClient.trySendCommand(ControlCommand.Action.NORESPONSE, "hanm"));

        // No parameter should be accepted.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.NORESPONSE));

        // Reset should work.
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.RESET));
    }

    @Test
    public void verifyLeaderElectionCommand() {
        Assert.assertTrue(commandClient.trySendCommand(ControlCommand.Action.ELECTNEWLEADER));
    }

}
