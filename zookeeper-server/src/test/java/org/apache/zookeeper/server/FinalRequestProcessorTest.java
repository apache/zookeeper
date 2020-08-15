/*
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

package org.apache.zookeeper.server;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.GetACLRequest;
import org.apache.zookeeper.proto.GetACLResponse;
import org.apache.zookeeper.proto.ReplyHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class FinalRequestProcessorTest {

    private List<ACL> testACLs = new ArrayList<ACL>();
    private final Record[] responseRecord = new Record[1];
    private final ReplyHeader[] replyHeaders = new ReplyHeader[1];

    private ServerCnxn cnxn;
    private ByteBuffer bb;
    private FinalRequestProcessor processor;

    @BeforeEach
    public void setUp() throws KeeperException.NoNodeException, IOException {
        testACLs.clear();
        testACLs.addAll(Arrays.asList(new ACL(ZooDefs.Perms.ALL, new Id("digest", "user:secrethash")), new ACL(ZooDefs.Perms.ADMIN, new Id("digest", "adminuser:adminsecret")), new ACL(ZooDefs.Perms.READ, new Id("world", "anyone"))));

        ZooKeeperServer zks = new ZooKeeperServer();
        ZKDatabase db = mock(ZKDatabase.class);
        String testPath = "/testPath";
        when(db.getNode(eq(testPath))).thenReturn(new DataNode());
        when(db.getACL(eq(testPath), any(Stat.class))).thenReturn(testACLs);
        when(db.aclForNode(any(DataNode.class))).thenReturn(testACLs);
        zks.setZKDatabase(db);
        processor = new FinalRequestProcessor(zks);

        cnxn = mock(ServerCnxn.class);
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) {
                replyHeaders[0] = invocationOnMock.getArgument(0);
                responseRecord[0] = invocationOnMock.getArgument(1);
                return null;
            }
        }).when(cnxn).sendResponse(any(), any(), anyString());

        GetACLRequest getACLRequest = new GetACLRequest();
        getACLRequest.setPath(testPath);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        getACLRequest.serialize(boa, "request");
        baos.close();
        bb = ByteBuffer.wrap(baos.toByteArray());
    }

    @Test
    public void testACLDigestHashHiding_NoAuth_WorldCanRead() {
        // Arrange

        // Act
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getACL, bb, new ArrayList<Id>());
        processor.processRequest(r);

        // Assert
        assertMasked(true);
    }

    @Test
    public void testACLDigestHashHiding_NoAuth_NoWorld() {
        // Arrange
        testACLs.remove(2);

        // Act
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getACL, bb, new ArrayList<Id>());
        processor.processRequest(r);

        // Assert
        assertThat(KeeperException.Code.get(replyHeaders[0].getErr()), equalTo(KeeperException.Code.NOAUTH));
    }

    @Test
    public void testACLDigestHashHiding_UserCanRead() {
        // Arrange
        List<Id> authInfo = new ArrayList<Id>();
        authInfo.add(new Id("digest", "otheruser:somesecrethash"));

        // Act
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getACL, bb, authInfo);
        processor.processRequest(r);

        // Assert
        assertMasked(true);
    }

    @Test
    public void testACLDigestHashHiding_UserCanAll() {
        // Arrange
        List<Id> authInfo = new ArrayList<Id>();
        authInfo.add(new Id("digest", "user:secrethash"));

        // Act
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getACL, bb, authInfo);
        processor.processRequest(r);

        // Assert
        assertMasked(false);
    }

    @Test
    public void testACLDigestHashHiding_AdminUser() {
        // Arrange
        List<Id> authInfo = new ArrayList<Id>();
        authInfo.add(new Id("digest", "adminuser:adminsecret"));

        // Act
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getACL, bb, authInfo);
        processor.processRequest(r);

        // Assert
        assertMasked(false);
    }

    @Test
    public void testACLDigestHashHiding_OnlyAdmin() {
        // Arrange
        testACLs.clear();
        testACLs.addAll(Arrays.asList(new ACL(ZooDefs.Perms.READ, new Id("digest", "user:secrethash")), new ACL(ZooDefs.Perms.ADMIN, new Id("digest", "adminuser:adminsecret"))));
        List<Id> authInfo = new ArrayList<Id>();
        authInfo.add(new Id("digest", "adminuser:adminsecret"));

        // Act
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getACL, bb, authInfo);
        processor.processRequest(r);

        // Assert
        assertTrue(responseRecord[0] instanceof GetACLResponse, "Not a GetACL response. Auth failed?");
        GetACLResponse rsp = (GetACLResponse) responseRecord[0];
        assertThat("Number of ACLs in the response are different", rsp.getAcl().size(), equalTo(2));

        // Verify ACLs in the response
        assertThat("Password hash mismatch in the response", rsp.getAcl().get(0).getId().getId(), equalTo("user:secrethash"));
        assertThat("Password hash mismatch in the response", rsp.getAcl().get(1).getId().getId(), equalTo("adminuser:adminsecret"));
    }

    private void assertMasked(boolean masked) {
        assertTrue(responseRecord[0] instanceof GetACLResponse, "Not a GetACL response. Auth failed?");
        GetACLResponse rsp = (GetACLResponse) responseRecord[0];
        assertThat("Number of ACLs in the response are different", rsp.getAcl().size(), equalTo(3));

        // Verify ACLs in the response
        assertThat("Invalid ACL list in the response", rsp.getAcl().get(0).getPerms(), equalTo(ZooDefs.Perms.ALL));
        assertThat("Invalid ACL list in the response", rsp.getAcl().get(0).getId().getScheme(), equalTo("digest"));
        if (masked) {
            assertThat("Password hash is not masked in the response", rsp.getAcl().get(0).getId().getId(), equalTo("user:x"));
        } else {
            assertThat("Password hash mismatch in the response", rsp.getAcl().get(0).getId().getId(), equalTo("user:secrethash"));
        }

        assertThat("Invalid ACL list in the response", rsp.getAcl().get(1).getPerms(), equalTo(ZooDefs.Perms.ADMIN));
        assertThat("Invalid ACL list in the response", rsp.getAcl().get(1).getId().getScheme(), equalTo("digest"));
        if (masked) {
            assertThat("Password hash is not masked in the response", rsp.getAcl().get(1).getId().getId(), equalTo("adminuser:x"));
        } else {
            assertThat("Password hash mismatch in the response", rsp.getAcl().get(1).getId().getId(), equalTo("adminuser:adminsecret"));
        }

        assertThat("Invalid ACL list in the response", rsp.getAcl().get(2).getPerms(), equalTo(ZooDefs.Perms.READ));
        assertThat("Invalid ACL list in the response", rsp.getAcl().get(2).getId().getScheme(), equalTo("world"));
        assertThat("Invalid ACL list in the response", rsp.getAcl().get(2).getId().getId(), equalTo("anyone"));

        // Verify that FinalRequestProcessor hasn't changed the original ACL objects
        assertThat("Original ACL list has been modified", testACLs.get(0).getPerms(), equalTo(ZooDefs.Perms.ALL));
        assertThat("Original ACL list has been modified", testACLs.get(0).getId().getScheme(), equalTo("digest"));
        assertThat("Original ACL list has been modified", testACLs.get(0).getId().getId(), equalTo("user:secrethash"));

        assertThat("Original ACL list has been modified", testACLs.get(1).getPerms(), equalTo(ZooDefs.Perms.ADMIN));
        assertThat("Original ACL list has been modified", testACLs.get(1).getId().getScheme(), equalTo("digest"));
        assertThat("Original ACL list has been modified", testACLs.get(1).getId().getId(), equalTo("adminuser:adminsecret"));

        assertThat("Original ACL list has been modified", testACLs.get(2).getPerms(), equalTo(ZooDefs.Perms.READ));
        assertThat("Original ACL list has been modified", testACLs.get(2).getId().getScheme(), equalTo("world"));
        assertThat("Original ACL list has been modified", testACLs.get(2).getId().getId(), equalTo("anyone"));
    }

}
