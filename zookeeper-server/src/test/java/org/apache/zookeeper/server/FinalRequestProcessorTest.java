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

package org.apache.zookeeper.server;

import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.GetACLRequest;
import org.apache.zookeeper.proto.GetACLResponse;
import org.apache.zookeeper.proto.GetChildrenListRequest;
import org.apache.zookeeper.proto.GetChildrenListResponse;
import org.apache.zookeeper.proto.ReplyHeader;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FinalRequestProcessorTest {
    private ZKDatabase db = mock(ZKDatabase.class);
    private final Record[] responseRecord = new Record[1];
    private final ReplyHeader[] replyHeaders = new ReplyHeader[1];
    // Adding a specific path for testing - getNode returns a default created, empty DataNode.
    private String testPath = "/testPath";

    private ServerCnxn cnxn;
    private ByteBuffer bb;
    private FinalRequestProcessor processor;

    @Before
    public void setUp() throws IOException {
        ZooKeeperServer zks = new ZooKeeperServer();
        when(db.getNode(eq(testPath))).thenReturn(new DataNode());
        zks.setZKDatabase(db);
        processor = new FinalRequestProcessor(zks);

        cnxn = mock(ServerCnxn.class);
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) {
                replyHeaders[0] = (ReplyHeader) invocationOnMock.getArguments()[0];
                responseRecord[0] = (Record) invocationOnMock.getArguments()[1];
                return null;
            }
        }).when(cnxn).sendResponse(any(ReplyHeader.class), any(Record.class), anyString());

    }

    private ByteBuffer createACLRequestByteBuffer() throws IOException {
        GetACLRequest getACLRequest = new GetACLRequest();
        getACLRequest.setPath(testPath);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        getACLRequest.serialize(boa, "request");
        baos.close();
        return ByteBuffer.wrap(baos.toByteArray());
    }

    @Test
    public void testACLDigestHashHiding_NoAuth_WorldCanRead() throws IOException, KeeperException.NoNodeException {
        // Arrange
        List<ACL> testACLs = Arrays.asList(new ACL(ZooDefs.Perms.ALL, new Id("digest", "user:secrethash")),
                              new ACL(ZooDefs.Perms.ADMIN, new Id("digest", "adminuser:adminsecret")),
                              new ACL(ZooDefs.Perms.READ, new Id("world", "anyone")));
        when(db.getACL(eq(testPath), any(Stat.class))).thenReturn(testACLs);
        when(db.aclForNode(any(DataNode.class))).thenReturn(testACLs);

        // Act
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getACL, createACLRequestByteBuffer(), new ArrayList<Id>());
        processor.processRequest(r);

        // Assert
        assertMasked(true, testACLs);
    }

    @Test
    public void testACLDigestHashHiding_NoAuth_NoWorld() throws IOException, KeeperException.NoNodeException {
        // Arrange
        List<ACL> testACLs = Arrays.asList(new ACL(ZooDefs.Perms.ALL, new Id("digest", "user:secrethash")),
                new ACL(ZooDefs.Perms.READ, new Id("digest", "adminuser:adminsecret")));
        when(db.getACL(eq(testPath), any(Stat.class))).thenReturn(testACLs);
        when(db.aclForNode(any(DataNode.class))).thenReturn(testACLs);

        // Act
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getACL, createACLRequestByteBuffer(), new ArrayList<Id>());
        processor.processRequest(r);

        // Assert
        assertThat(KeeperException.Code.get(replyHeaders[0].getErr()), equalTo(KeeperException.Code.NOAUTH));
    }

    @Test
    public void testACLDigestHashHiding_UserCanRead() throws IOException, KeeperException.NoNodeException {
        // Arrange
        List<ACL> testACLs = Arrays.asList(new ACL(ZooDefs.Perms.ALL, new Id("digest", "user:secrethash")),
                new ACL(ZooDefs.Perms.ADMIN, new Id("digest", "adminuser:adminsecret")),
                new ACL(ZooDefs.Perms.READ, new Id("world", "anyone")));
        when(db.getACL(eq(testPath), any(Stat.class))).thenReturn(testACLs);
        when(db.aclForNode(any(DataNode.class))).thenReturn(testACLs);

        List<Id> authInfo = new ArrayList<Id>();
        authInfo.add(new Id("digest", "otheruser:somesecrethash"));

        // Act
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getACL, createACLRequestByteBuffer(), authInfo);
        processor.processRequest(r);

        // Assert
        assertMasked(true, testACLs);
    }

    @Test
    public void testACLDigestHashHiding_UserCanAll() throws IOException, KeeperException.NoNodeException {
        // Arrange
        List<ACL> testACLs = Arrays.asList(new ACL(ZooDefs.Perms.ALL, new Id("digest", "user:secrethash")),
                new ACL(ZooDefs.Perms.ADMIN, new Id("digest", "adminuser:adminsecret")),
                new ACL(ZooDefs.Perms.READ, new Id("world", "anyone")));
        when(db.getACL(eq(testPath), any(Stat.class))).thenReturn(testACLs);
        when(db.aclForNode(any(DataNode.class))).thenReturn(testACLs);

        List<Id> authInfo = new ArrayList<Id>();
        authInfo.add(new Id("digest", "user:secrethash"));

        // Act
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getACL, createACLRequestByteBuffer(), authInfo);
        processor.processRequest(r);

        // Assert
        assertMasked(false, testACLs);
    }

    @Test
    public void testACLDigestHashHiding_AdminUser() throws IOException, KeeperException.NoNodeException {
        // Arrange
        List<ACL> testACLs = Arrays.asList(new ACL(ZooDefs.Perms.ALL, new Id("digest", "user:secrethash")),
                new ACL(ZooDefs.Perms.ADMIN, new Id("digest", "adminuser:adminsecret")),
                new ACL(ZooDefs.Perms.READ, new Id("world", "anyone")));
        when(db.getACL(eq(testPath), any(Stat.class))).thenReturn(testACLs);
        when(db.aclForNode(any(DataNode.class))).thenReturn(testACLs);

        List<Id> authInfo = new ArrayList<Id>();
        authInfo.add(new Id("digest", "adminuser:adminsecret"));

        // Act
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getACL, createACLRequestByteBuffer(), authInfo);
        processor.processRequest(r);

        // Assert
        assertMasked(false, testACLs);
    }

    @Test
    public void testACLDigestHashHiding_OnlyAdmin() throws IOException, KeeperException.NoNodeException {
        // Arrange
        List<ACL> testACLs = Arrays.asList(new ACL(ZooDefs.Perms.ALL, new Id("digest", "user:secrethash")),
                new ACL(ZooDefs.Perms.ADMIN, new Id("digest", "adminuser:adminsecret")));
        when(db.getACL(eq(testPath), any(Stat.class))).thenReturn(testACLs);
        when(db.aclForNode(any(DataNode.class))).thenReturn(testACLs);

        List<Id> authInfo = new ArrayList<Id>();
        authInfo.add(new Id("digest", "adminuser:adminsecret"));

        // Act
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getACL, createACLRequestByteBuffer(), authInfo);
        processor.processRequest(r);

        // Assert
        assertTrue("Not a GetACL response. Auth failed?", responseRecord[0] instanceof GetACLResponse);
        GetACLResponse rsp = (GetACLResponse)responseRecord[0];
        assertThat("Number of ACLs in the response are different", rsp.getAcl().size(), equalTo(2));

        // Verify ACLs in the response
        assertThat("Password hash mismatch in the response", rsp.getAcl().get(0).getId().getId(), equalTo("user:secrethash"));
        assertThat("Password hash mismatch in the response", rsp.getAcl().get(1).getId().getId(), equalTo("adminuser:adminsecret"));
    }

    private void assertMasked(boolean masked, final List<ACL> testACLs) {
        assertTrue("Not a GetACL response. Auth failed?", responseRecord[0] instanceof GetACLResponse);
        GetACLResponse rsp = (GetACLResponse)responseRecord[0];
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

    ByteBuffer createGetChildrenListRequestByteBuffer(final List<String> nodeNames) throws IOException {
        GetChildrenListRequest getChildrenListRequest = new GetChildrenListRequest();
        getChildrenListRequest.setPathList(nodeNames);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        getChildrenListRequest.serialize(boa, "request");
        baos.close();
        return ByteBuffer.wrap(baos.toByteArray());
    }

    @Test
    public void testGetChildrenList_Empty_List() throws IOException {
        // Arrange

        // Act
        ByteBuffer bb = createGetChildrenListRequestByteBuffer(new ArrayList<>());
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getChildrenList, bb, new ArrayList<Id>());
        processor.processRequest(r);

        // Assert
        GetChildrenListResponse rsp = (GetChildrenListResponse)responseRecord[0];
        assertTrue(rsp.getChildren().isEmpty());
    }

    @Test
    public void testGetChildrenList_Normal() throws IOException, KeeperException.NoNodeException {
        // Arrange
        when(db.getNode((any(String.class)))).thenReturn(new DataNode());
        when(db.getChildren(eq("/path1"), any(Stat.class), any(Watcher.class)))
                .thenReturn(Arrays.asList("child1", "child2", "child3"));
        when(db.getChildren(eq("/path2"), any(Stat.class), any(Watcher.class)))
                .thenReturn(Arrays.asList("child4", "child5"));

        // Act
        ByteBuffer bb = createGetChildrenListRequestByteBuffer(Arrays.asList("/path1", "/path2"));
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getChildrenList, bb, new ArrayList<Id>());
        processor.processRequest(r);

        // Assert
        GetChildrenListResponse rsp = (GetChildrenListResponse)responseRecord[0];
        assertThat(rsp.getChildren().size(), equalTo(2));
        assertThat(rsp.getChildren().get(0), equalTo(Arrays.asList("child1", "child2", "child3")));
        assertThat(rsp.getChildren().get(1), equalTo(Arrays.asList("child4", "child5")));
    }

    @Test
    public void testGetChildrenList_Same_Node() throws IOException, KeeperException.NoNodeException {
        // Arrange
        when(db.getChildren(eq(testPath), any(Stat.class), any(Watcher.class)))
                .thenReturn(Arrays.asList("child1", "child2", "child3"));

        // Act
        ByteBuffer bb = createGetChildrenListRequestByteBuffer(Arrays.asList("/testPath", "/testPath"));
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getChildrenList, bb, new ArrayList<Id>());
        processor.processRequest(r);

        // Assert
        GetChildrenListResponse rsp = (GetChildrenListResponse)responseRecord[0];
        assertThat(rsp.getChildren().size(), equalTo(2));
        assertThat(rsp.getChildren().get(0), equalTo(Arrays.asList("child1", "child2", "child3")));
        assertThat(rsp.getChildren().get(1), equalTo(Arrays.asList("child1", "child2", "child3")));
    }

    @Test
    public void testGetChildrenList_No_Auth() throws IOException, KeeperException.NoNodeException {
        // Arrange
        List<ACL> testACLs = Arrays.asList(new ACL(ZooDefs.Perms.ALL, new Id("digest", "user:secrethash")),
                new ACL(ZooDefs.Perms.WRITE, new Id("digest", "writeuser:secrethash")));
        when(db.aclForNode(any(DataNode.class))).thenReturn(testACLs);
        List<Id> authInfo = new ArrayList<Id>();
        authInfo.add(new Id("digest", "writeuser:secrethash"));

        // Act
        ByteBuffer bb = createGetChildrenListRequestByteBuffer(Arrays.asList("/testPath", "/testPath"));
        Request r = new Request(cnxn, 0, 0, ZooDefs.OpCode.getChildrenList, bb, authInfo);
        processor.processRequest(r);

        // Assert
        assertThat(KeeperException.Code.get(replyHeaders[0].getErr()), equalTo(KeeperException.Code.NOAUTH));
    }
}
