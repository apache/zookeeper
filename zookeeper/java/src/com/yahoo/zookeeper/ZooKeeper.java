/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;

import com.yahoo.zookeeper.AsyncCallback.ACLCallback;
import com.yahoo.zookeeper.AsyncCallback.ChildrenCallback;
import com.yahoo.zookeeper.AsyncCallback.DataCallback;
import com.yahoo.zookeeper.AsyncCallback.StatCallback;
import com.yahoo.zookeeper.AsyncCallback.StringCallback;
import com.yahoo.zookeeper.AsyncCallback.VoidCallback;
import com.yahoo.zookeeper.KeeperException.Code;
import com.yahoo.zookeeper.ZooDefs.Ids;
import com.yahoo.zookeeper.data.ACL;
import com.yahoo.zookeeper.data.Id;
import com.yahoo.zookeeper.data.Stat;
import com.yahoo.zookeeper.proto.CreateRequest;
import com.yahoo.zookeeper.proto.CreateResponse;
import com.yahoo.zookeeper.proto.DeleteRequest;
import com.yahoo.zookeeper.proto.ExistsRequest;
import com.yahoo.zookeeper.proto.GetACLRequest;
import com.yahoo.zookeeper.proto.GetACLResponse;
import com.yahoo.zookeeper.proto.GetChildrenRequest;
import com.yahoo.zookeeper.proto.GetChildrenResponse;
import com.yahoo.zookeeper.proto.GetDataRequest;
import com.yahoo.zookeeper.proto.GetDataResponse;
import com.yahoo.zookeeper.proto.ReplyHeader;
import com.yahoo.zookeeper.proto.RequestHeader;
import com.yahoo.zookeeper.proto.SetACLRequest;
import com.yahoo.zookeeper.proto.SetACLResponse;
import com.yahoo.zookeeper.proto.SetDataRequest;
import com.yahoo.zookeeper.proto.SetDataResponse;
import com.yahoo.zookeeper.proto.WatcherEvent;
import com.yahoo.zookeeper.server.DataTree;

/**
 * This is the main class of ZooKeeper client library. To use a ZooKeeper
 * service, an application must first instantiate an object of ZooKeeper class.
 * All the iterations will be done by calling the methods of ZooKeeper class.
 * <p>
 * To create a client(ZooKeeper) object, the application needs to pass a string
 * containing a list of host:port pairs, each corresponding to a ZooKeeper
 * server; a sessionTimeout; and an object of Watcher type.
 * <p>
 * The client object will pick an arbitrary server and try to connect to it. If
 * failed, it will try the next one in the list, until a connection is
 * established, or all the servers have been tried.
 * <p>
 * Once a connection to a server is established, a session ID is assigned to the
 * client. The client will send heart beats to the server periodically to keep
 * the session valid.
 * <p>
 * The application can call ZooKeeper APIs through a client as long as the
 * session ID of the client remains valid.
 * <p>
 * If for some reason, the client fails to send heart beats to the server for a
 * prolonged period of time (exceeding the sessionTimeout value, for instance),
 * the server will expire the session, and the session ID will become invalid.
 * The client object will no longer be usable. To make ZooKeeper API calls, the
 * application must create a new client object.
 * <p>
 * If the ZooKeeper server the client currently connects to fails or otherwise
 * does not respond, the client will automatically try to connect to another
 * server before its session ID expires. If successful, the application can
 * continue to use the client.
 * <p>
 * Some successful ZooKeeper API calls can leave watches on the "data nodes" in
 * the ZooKeeper server. Other successful ZooKeeper API calls can trigger those
 * watches. Once a watch is triggered, an event will be delivered to the client
 * which left the watch at the first place. Each watch can be triggered only
 * once. Thus, up to one event will be delivered to a client for every watch it
 * leaves.
 * <p>
 * A client needs an object of a class implementing Watcher interface for
 * processing the events delivered to the client.
 * 
 * When a client drops current connection and re-connects to a server, all the
 * existing watches are considered as being triggered but the undelivered events
 * are lost. To emulate this, the client will generate a special event to tell
 * the event handler a connection has been dropped. This special event has type
 * EventNone and state sKeeperStateDisconnected.
 * 
 */
public class ZooKeeper {
    Watcher watcher;

    enum States {
        CONNECTING, ASSOCIATING, CONNECTED, CLOSED, AUTH_FAILED;

        public boolean isAlive() {
            return this != CLOSED && this != AUTH_FAILED;
        }
    }

    States state;

    ClientCnxn cnxn;

    public ZooKeeper(String host, int sessionTimeout, Watcher watcher)
            throws KeeperException, IOException {
        cnxn = new ClientCnxn(host, sessionTimeout, this);
        this.watcher = watcher;
    }

    public ZooKeeper(String host, int sessionTimeout, Watcher watcher,
            long sessionId, byte[] sessionPasswd) throws KeeperException,
            IOException {
        cnxn = new ClientCnxn(host, sessionTimeout, this, sessionId,
                sessionPasswd);
        this.watcher = watcher;
    }

    public long getSessionId() {
        return cnxn.getSessionId();
    }

    public byte[] getSessionPasswd() {
        return cnxn.getSessionPasswd();
    }

    public void addAuthInfo(String scheme, byte auth[]) {
        cnxn.addAuthInfo(scheme, auth);
    }

    public String describeCNXN() {
        return cnxn.toString();
    }

    public synchronized void register(Watcher watcher) {
        this.watcher = watcher;
    }

    /**
     * Close this client object. Once the client is closed, its session becomes
     * invalid. All the ephemeral nodes in the ZooKeeper server associated with
     * the session will be removed. The watches left on those nodes (and on
     * their parents) will be triggered.
     * 
     * @throws InterruptedException
     * 
     * @throws IOException
     * @throws InterruptedException
     * @throws KeeperException
     */
    public synchronized void close() throws InterruptedException {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.closeSession);
        cnxn.submitRequest(h, null, null);
        try {
            cnxn.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Create a node with the given path. The node data will be the given data,
     * and node acl will be the given acl.
     * <p>
     * The flags argument specifies whether the created node will be ephemeral
     * or not.
     * <p>
     * An ephemeral node will be removed by the ZooKeeper automatically when the
     * session associated with the creation of the node expires.
     * <p>
     * The flags argument can also specify to create a sequential node. The
     * actual path name of a sequential node will be the given path plus a
     * suffix "_i" where i is the current sequential number of the node. Once
     * such a node is created, the sequential number will be incremented by one.
     * <p>
     * If a node with the same actual path already exists in the ZooKeeper, a
     * KeeperException with error code KeeperException.NodeExists will be
     * thrown. Note that since a different actual path is used for each
     * invocation of creating sequential node with the same path argument, the
     * call will never throw "file exists" KeeperException.
     * <p>
     * If the parent node does not exist in the ZooKeeper, a KeeperException
     * with error code KeeperException.NoNode will be thrown.
     * <p>
     * An ephemeral node cannot have children. If the parent node of the given
     * path is ephemeral, a KeeperException with error code
     * KeeperException.NoChildrenForEphemerals will be thrown.
     * <p>
     * This operation, if successful, will trigger all the watches left on the
     * node of the given path by exists and getData API calls, and the watches
     * left on the parent node by getChildren API calls.
     * <p>
     * If a node is created successfully, the ZooKeeper server will trigger the
     * watches on the path left by exists calls, and the watches on the parent
     * of the node by getChildren calls.
     * 
     * @param path
     *                the path for the node
     * @param data
     *                the initial data for the node
     * @param acl
     *                the acl for the node
     * @param flags
     *                specifying whether the node to be created is ephemeral
     *                and/or sequential
     * @return the actual path of the created node
     * @throws KeeperException
     *                 an exception with appropriate error code defined in
     *                 KeeperException class..
     * @throws IOException
     * @throws InterruptedException
     */
    public String create(String path, byte data[], ArrayList<ACL> acl, int flags)
            throws KeeperException, InterruptedException {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.create);
        CreateRequest request = new CreateRequest();
        CreateResponse response = new CreateResponse();
        request.setData(data);
        request.setFlags(flags);
        request.setPath(path);
        if (acl != null && acl.size() == 0) {
            throw new KeeperException(Code.InvalidACL);
        }
        request.setAcl(acl);
        ReplyHeader r = cnxn.submitRequest(h, request, response);
        if (r.getErr() != 0) {
            throw new KeeperException(r.getErr(), path);
        }
        return response.getPath();
    }

    /**
     * The Asynchronous version of create. The request doesn't actually until
     * the asynchronous callback is called.
     * 
     * @see #create(String, byte[], ArrayList, int)
     */

    public void create(String path, byte data[], ArrayList<ACL> acl, int flags,
            StringCallback cb, Object ctx) {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.create);
        CreateRequest request = new CreateRequest();
        CreateResponse response = new CreateResponse();
        ReplyHeader r = new ReplyHeader();
        request.setData(data);
        request.setFlags(flags);
        request.setPath(path);
        request.setAcl(acl);
        cnxn.queuePacket(h, r, request, response, cb, path, ctx);
    }

    /**
     * Delete the node with the given path. The call will succeed if such a node
     * exists, and the given version matches the node's version (if the given
     * version is -1, it matches any node's versions).
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if the nodes does not exist.
     * <p>
     * A KeeperException with error code KeeperException.BadVersion will be
     * thrown if the given version does not match the node's version.
     * <p>
     * A KeeperException with error code KeeperException.NotEmpty will be thrown
     * if the node has children.
     * <p>
     * This operation, if successful, will trigger all the watches on the node
     * of the given path left by exists API calls, and the watches on the parent
     * node left by getChildren API calls.
     * 
     * @param path
     *                the path of the node to be deleted.
     * @param version
     *                the expected node version.
     * @throws KeeperException
     *                 an KeeperException with appropriate error code.
     * @throws IOException
     * @throws InterruptedException
     */
    public void delete(String path, int version) throws KeeperException,
            InterruptedException {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.delete);
        DeleteRequest request = new DeleteRequest();
        request.setPath(path);
        request.setVersion(version);
        ReplyHeader r = cnxn.submitRequest(h, request, null);
        if (r.getErr() != 0) {
            throw new KeeperException(r.getErr());
        }
    }

    /**
     * The Asynchronous version of delete. The request doesn't actually until
     * the asynchronous callback is called.
     * 
     * @see #delete(String, int)
     */
    public void delete(String path, int version, VoidCallback cb, Object ctx) {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.delete);
        DeleteRequest request = new DeleteRequest();
        request.setPath(path);
        request.setVersion(version);
        cnxn.queuePacket(h, new ReplyHeader(), request, null, cb, path, ctx);
    }

    /**
     * Return the stat of the node of the given path. Return null if no such a
     * node exists.
     * <p>
     * If the watch is true and the call is successful (no exception is thrown),
     * a watch will be left on the node with the given path. The watch will be
     * triggered by a successful operation that creates/delete the node or sets
     * the data on the node.
     * 
     * @param path
     *                the node path
     * @param watch
     *                whether need to watch this node
     * @return the stat of the node of the given path; return null if no such a
     *         node exists.
     * @throws KeeperException
     * @throws IOException
     * @throws InterruptedException
     */
    public Stat exists(String path, boolean watch) throws KeeperException,
            InterruptedException {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.exists);
        ExistsRequest request = new ExistsRequest();
        request.setPath(path);
        request.setWatch(watch);
        SetDataResponse response = new SetDataResponse();
        ReplyHeader r = cnxn.submitRequest(h, request, response);
        if (r.getErr() != 0) {
            if (r.getErr() == KeeperException.Code.NoNode) {
                return null;
            }
            throw new KeeperException(r.getErr());
        }
        return response.getStat().getCzxid() == -1 ? null : response.getStat();
    }

    /**
     * The Asynchronous version of exists. The request doesn't actually until
     * the asynchronous callback is called.
     * 
     * @see #exists(String, boolean)
     */
    public void exists(String path, boolean watch, StatCallback cb, Object ctx) {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.exists);
        ExistsRequest request = new ExistsRequest();
        request.setPath(path);
        request.setWatch(watch);
        SetDataResponse response = new SetDataResponse();
        cnxn
                .queuePacket(h, new ReplyHeader(), request, response, cb, path,
                        ctx);
    }

    /**
     * Return the data and the stat of the node of the given path.
     * <p>
     * If the watch is true and the call is successfull (no exception is
     * thrown), a watch will be left on the node with the given path. The watch
     * will be triggered by a sucessful operation that sets data on the node, or
     * deletes the node.
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     * 
     * @param path
     *                the given path
     * @param watch
     *                whether need to watch this node
     * @param stat
     *                teh stat of the node
     * @return the data of the node
     * @throws IOException
     * @throws KeeperException
     * @throws InterruptedException
     */
    public byte[] getData(String path, boolean watch, Stat stat)
            throws KeeperException, InterruptedException {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.getData);
        GetDataRequest request = new GetDataRequest();
        request.setPath(path);
        request.setWatch(watch);
        GetDataResponse response = new GetDataResponse();
        ReplyHeader r = cnxn.submitRequest(h, request, response);
        if (r.getErr() != 0) {
            throw new KeeperException(r.getErr());
        }
        if (stat != null) {
            DataTree.copyStat(response.getStat(), stat);
        }
        return response.getData();
    }

    /**
     * The Asynchronous version of getData. The request doesn't actually until
     * the asynchronous callback is called.
     * 
     * @see #getData(String, boolean, Stat)
     */

    public void getData(String path, boolean watch, DataCallback cb, Object ctx) {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.getData);
        GetDataRequest request = new GetDataRequest();
        request.setPath(path);
        request.setWatch(watch);
        GetDataResponse response = new GetDataResponse();
        cnxn
                .queuePacket(h, new ReplyHeader(), request, response, cb, path,
                        ctx);
    }

    /**
     * Set the data for the node of the given path if such a node exists and the
     * given version matches the version of the node (if the given version is
     * -1, it matches any node's versions). Return the stat of the node.
     * <p>
     * This operation, if successful, will trigger all the watches on the node
     * of the given path left by getData calls.
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     * 
     * A KeeperException with error code KeeperException.BadVersion will be
     * thrown if the given version does not match the node's version.
     * 
     * @param path
     *                the path of the node
     * @param data
     *                the data to set
     * @param version
     *                the expected matching version
     * @return the state of the node
     * @throws KeeperException
     * @throws IOException
     * @throws InterruptedException
     */
    public Stat setData(String path, byte data[], int version)
            throws KeeperException, InterruptedException {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.setData);
        SetDataRequest request = new SetDataRequest();
        request.setPath(path);
        request.setData(data);
        request.setVersion(version);
        SetDataResponse response = new SetDataResponse();
        ReplyHeader r = cnxn.submitRequest(h, request, response);
        if (r.getErr() != 0) {
            throw new KeeperException(r.getErr());
        }
        return response.getStat();
    }

    /**
     * The Asynchronous version of setData. The request doesn't actually until
     * the asynchronous callback is called.
     * 
     * @see #setData(String, byte[], int)
     */
    public void setData(String path, byte data[], int version, StatCallback cb,
            Object ctx) {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.setData);
        SetDataRequest request = new SetDataRequest();
        request.setPath(path);
        request.setData(data);
        request.setVersion(version);
        SetDataResponse response = new SetDataResponse();
        cnxn
                .queuePacket(h, new ReplyHeader(), request, response, cb, path,
                        ctx);
    }

    /**
     * 
     * Return the ACL and stat of the node of the given path.
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     * 
     * @param path
     *                the given path for the node
     * @param stat
     *                the stat of the node will be copied to this parameter.
     * @return the ACL array of the given node.
     * @throws KeeperException
     * @throws IOException
     * @throws InterruptedException
     */
    public ArrayList<ACL> getACL(String path, Stat stat)
            throws KeeperException, InterruptedException {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.getACL);
        GetACLRequest request = new GetACLRequest();
        request.setPath(path);
        GetACLResponse response = new GetACLResponse();
        ReplyHeader r = cnxn.submitRequest(h, request, response);
        if (r.getErr() != 0) {
            throw new KeeperException(r.getErr());
        }
        DataTree.copyStat(response.getStat(), stat);
        return response.getAcl();
    }

    /**
     * The Asynchronous version of getACL. The request doesn't actually until
     * the asynchronous callback is called.
     * 
     * @see #getACL(String, Stat)
     */
    public void getACL(String path, Stat stat, ACLCallback cb, Object ctx) {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.getACL);
        GetACLRequest request = new GetACLRequest();
        request.setPath(path);
        GetACLResponse response = new GetACLResponse();
        cnxn
                .queuePacket(h, new ReplyHeader(), request, response, cb, path,
                        ctx);
    }

    /**
     * Set the ACL for the node of the given path if such a node exists and the
     * given version matches the version of the node. Return the stat of the
     * node.
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     * <p>
     * A KeeperException with error code KeeperException.BadVersion will be
     * thrown if the given version does not match the node's version.
     * 
     * @param path
     * @param acl
     * @param version
     * @return the stat of the node.
     * @throws KeeperException
     * @throws IOException
     * @throws InterruptedException
     */
    public Stat setACL(String path, ArrayList<ACL> acl, int version)
            throws KeeperException, InterruptedException {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.setACL);
        SetACLRequest request = new SetACLRequest();
        request.setPath(path);
        if (acl != null && acl.size() == 0) {
            throw new KeeperException(Code.InvalidACL);
        }
        request.setAcl(acl);
        request.setVersion(version);
        SetACLResponse response = new SetACLResponse();
        ReplyHeader r = cnxn.submitRequest(h, request, response);
        if (r.getErr() != 0) {
            throw new KeeperException(r.getErr());
        }
        return response.getStat();
    }

    /**
     * The Asynchronous version of setACL. The request doesn't actually until
     * the asynchronous callback is called.
     * 
     * @see #setACL(String, ArrayList, int)
     */
    public void setACL(String path, ArrayList<ACL> acl, int version,
            StatCallback cb, Object ctx) {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.setACL);
        SetACLRequest request = new SetACLRequest();
        request.setPath(path);
        request.setAcl(acl);
        request.setVersion(version);
        SetACLResponse response = new SetACLResponse();
        cnxn
                .queuePacket(h, new ReplyHeader(), request, response, cb, path,
                        ctx);
    }

    /**
     * Return the list of the children of the node of the given path.
     * <p>
     * If the watch is true and the call is successful (no exception is thrown),
     * a watch will be left on the node with the given path. The watch willbe
     * triggered by a sucessful operation that deletes the node of the given
     * path or creates/delete a child under the node.
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     * 
     * @param path
     * @param watch
     * @return an array of children of the node with the given path
     * @throws IOException
     * @throws KeeperException
     * @throws InterruptedException
     */
    public ArrayList<String> getChildren(String path, boolean watch)
            throws KeeperException, InterruptedException {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.getChildren);
        GetChildrenRequest request = new GetChildrenRequest();
        request.setPath(path);
        request.setWatch(watch);
        GetChildrenResponse response = new GetChildrenResponse();
        ReplyHeader r = cnxn.submitRequest(h, request, response);
        if (r.getErr() != 0) {
            throw new KeeperException(r.getErr());
        }
        return response.getChildren();
    }

    /**
     * The Asynchronous version of getChildren. The request doesn't actually
     * until the asynchronous callback is called.
     * 
     * @see #getChildren(String, boolean)
     */
    public void getChildren(String path, boolean watch, ChildrenCallback cb,
            Object ctx) {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.getChildren);
        GetChildrenRequest request = new GetChildrenRequest();
        request.setPath(path);
        request.setWatch(watch);
        GetChildrenResponse response = new GetChildrenResponse();
        cnxn
                .queuePacket(h, new ReplyHeader(), request, response, cb, path,
                        ctx);
    }

    public States getState() {
        return state;
    }

    // Everything below this line is for testing!

    static void usage() {
        System.err.println("ZooKeeper host:port cmd args");
        System.err.println("\tcreate path data acl");
        System.err.println("\tdelete path [version]");
        System.err.println("\tset path data [version]");
        System.err.println("\tget path [watch]");
        System.err.println("\tls path [watch]");
        System.err.println("\tgetAcl path");
        System.err.println("\tsetAcl path acl");
        System.err.println("\tstat path [watch]");
    }

    static private class MyWatcher implements Watcher {
        public void process(WatcherEvent event) {
            System.err.println(event.getPath() + ": " + event.getState() + "-"
                    + event.getType());
        }
    }

    static private int getPermFromString(String permString) {
        int perm = 0;
        for (int i = 0; i < permString.length(); i++) {
            switch (permString.charAt(i)) {
            case 'r':
                perm |= ZooDefs.Perms.READ;
                break;
            case 'w':
                perm |= ZooDefs.Perms.WRITE;
                break;
            case 'c':
                perm |= ZooDefs.Perms.CREATE;
                break;
            case 'd':
                perm |= ZooDefs.Perms.DELETE;
                break;
            case 'a':
                perm |= ZooDefs.Perms.ADMIN;
                break;
            default:
                System.err
                        .println("Unknown perm type: " + permString.charAt(i));
            }
        }
        return perm;
    }

    private static void printStat(Stat stat) {
        System.err.println("ctime = " + new Date(stat.getCtime()).toString());
        System.err.println("ctime = " + new Date(stat.getMtime()).toString());
        System.err.println("cversion = " + stat.getCversion());
        System.err.println("cZxid = " + stat.getCzxid());
        System.err.println("mZxid = " + stat.getMzxid());
        System.err.println("dataVersion = " + stat.getVersion());
        System.err.println("aclVersion = " + stat.getAversion());
    }

    public static void main(String args[]) throws NumberFormatException,
            KeeperException, IOException, InterruptedException {
        if (args.length == 1) {
            ZooKeeper zooKeeper = new ZooKeeper(args[0], 5000, new MyWatcher());
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    System.in));
            String line;
            while ((line = br.readLine()) != null) {
                line = "ignore " + line;
                args = line.split(" ");
                processCmd(args, zooKeeper);
            }
        } else if (args.length < 3) {
            usage();
        }
        ZooKeeper zooKeeper = new ZooKeeper(args[0], 5000, new MyWatcher());
        boolean watch = processCmd(args, zooKeeper);
        if (!watch) {
            System.exit(0);
        }
    }

    private static DataCallback dataCallback = new DataCallback() {

        public void processResult(int rc, String path, Object ctx, byte[] data,
                Stat stat) {
            System.out.println("rc = " + rc + " path = " + path + " data = "
                    + (data == null ? "null" : new String(data)) + " stat = ");
            printStat(stat);
        }

    };

    private static boolean processCmd(String[] args, ZooKeeper zooKeeper)
            throws KeeperException, IOException, InterruptedException {
        Stat stat = new Stat();
        if (args.length < 2) {
            return false;
        }
        if (args.length < 3) {
            usage();
            return false;
        }
        String cmd = args[1];
        boolean watch = args.length > 3;
        String path = args[2];
        ArrayList<ACL> acl = Ids.OPEN_ACL_UNSAFE;
        System.out.println("Processing " + cmd);
        if (cmd.equals("create") && args.length >= 4) {
            if (args.length == 5) {
                acl = parseACLs(args[4]);
            }
            String newPath = zooKeeper.create(path, args[3].getBytes(), acl, 0);
            System.err.println("Created " + newPath);
        } else if (cmd.equals("delete") && args.length >= 3) {
            zooKeeper.delete(path, watch ? Integer.parseInt(args[3]) : -1);
        } else if (cmd.equals("set") && args.length >= 4) {
            stat = zooKeeper.setData(path, args[3].getBytes(),
                    args.length > 4 ? Integer.parseInt(args[4]) : -1);
            printStat(stat);
        } else if (cmd.equals("aget") && args.length >= 3) {
            zooKeeper.getData(path, watch, dataCallback, path);
        } else if (cmd.equals("get") && args.length >= 3) {
            byte data[] = zooKeeper.getData(path, watch, stat);
            System.out.println(new String(data));
            printStat(stat);
        } else if (cmd.equals("ls") && args.length >= 3) {
            ArrayList<String> children = zooKeeper.getChildren(path, watch);
            System.out.println(children);
        } else if (cmd.equals("getAcl") && args.length >= 2) {
            acl = zooKeeper.getACL(path, stat);
            for (ACL a : acl) {
                System.out.println(a.getId() + ": "
                        + getPermString(a.getPerms()));
            }
        } else if (cmd.equals("setAcl") && args.length >= 4) {

            stat = zooKeeper.setACL(path, parseACLs(args[3]),
                    args.length > 4 ? Integer.parseInt(args[4]) : -1);
            printStat(stat);
        } else if (cmd.equals("stat") && args.length >= 3) {
            stat = zooKeeper.exists(path, watch);
            printStat(stat);
        } else {
            usage();
        }
        return watch;
    }

    private static String getPermString(int perms) {
        StringBuffer p = new StringBuffer();
        if ((perms & ZooDefs.Perms.CREATE) != 0) {
            p.append('c');
        }
        if ((perms & ZooDefs.Perms.DELETE) != 0) {
            p.append('d');
        }
        if ((perms & ZooDefs.Perms.READ) != 0) {
            p.append('r');
        }
        if ((perms & ZooDefs.Perms.WRITE) != 0) {
            p.append('w');
        }
        if ((perms & ZooDefs.Perms.ADMIN) != 0) {
            p.append('a');
        }
        return p.toString();
    }

    private static ArrayList<ACL> parseACLs(String aclString) {
        ArrayList<ACL> acl;
        String acls[] = aclString.split(",");
        acl = new ArrayList<ACL>();
        for (String a : acls) {
            int firstColon = a.indexOf(':');
            int lastColon = a.indexOf(':');
            if (firstColon == -1 || lastColon == -1 || firstColon == lastColon) {
                System.err
                        .println(a + " does not have the form scheme:id:perm");
                continue;
            }
            ACL newAcl = new ACL();
            newAcl.setId(new Id(a.substring(0, firstColon), a.substring(
                    firstColon + 1, lastColon)));
            newAcl.setPerms(getPermFromString(a.substring(lastColon + 1)));
            acl.add(newAcl);
        }
        return acl;
    }

    public void disconnect() throws IOException {
        cnxn.close();
    }
}
