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

package org.apache.zookeeper.server.quorum.auth;

import org.apache.zookeeper.Login;
import org.apache.zookeeper.LoginFactory;
import org.apache.zookeeper.common.ZKConfig;
import org.junit.Before;
import org.junit.Test;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginException;
import javax.security.sasl.SaslException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.Principal;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SaslQuorumAuthLearnerTest {

    private SaslQuorumAuthLearner learner;

    @Before
    public void setUp() throws SaslException, LoginException {
        Configuration configMock = mock(Configuration.class);
        when(configMock.getAppConfigurationEntry(any(String.class))).thenReturn(new AppConfigurationEntry[1]);
        Configuration.setConfiguration(configMock);

        Login loginMock = mock(Login.class);
        Subject subjectMock = new Subject();
        Principal principalMock = mock(Principal.class);
        when(principalMock.getName()).thenReturn("hello");
        subjectMock.getPrincipals().add(principalMock);
        when(loginMock.getSubject()).thenReturn(subjectMock);

        LoginFactory loginFactoryMock = mock(LoginFactory.class);
        when(loginFactoryMock.createLogin(any(String.class), any(CallbackHandler.class), any(ZKConfig.class))).thenReturn(loginMock);

        learner = new SaslQuorumAuthLearner(true, null, "andorContext", loginFactoryMock);
    }

    @Test(expected = SaslException.class)
    public void testNullCheckSc() throws IOException {
        assertThat(learner, is(notNullValue()));

        Socket socketMock = mock(Socket.class);
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(new byte[0]);
        when(socketMock.getOutputStream()).thenReturn(byteArrayOutputStream);
        when(socketMock.getInputStream()).thenReturn(byteArrayInputStream);

        learner.authenticate(socketMock, null);
    }
}
