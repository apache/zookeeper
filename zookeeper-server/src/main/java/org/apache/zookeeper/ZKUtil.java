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

package org.apache.zookeeper;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.zookeeper.AsyncCallback.MultiCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.data.ACL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZKUtil {

    private static final Logger LOG = LoggerFactory.getLogger(ZKUtil.class);
    private static final Map<Integer, String> permCache = new ConcurrentHashMap<Integer, String>();
    /**
     * Recursively delete the node with the given path.
     * <p>
     * Important: All versions, of all nodes, under the given node are deleted.
     * <p>
     * If there is an error with deleting one of the sub-nodes in the tree,
     * this operation would abort and would be the responsibility of the app to handle the same.
     *
     * @param zk Zookeeper client
     * @param pathRoot path to be deleted
     * @param batchSize number of delete operations to be submitted in one call.
     *                  batchSize is also used to decide sync and async delete API invocation.
     *                  If batchSize>0 then async otherwise sync delete API is invoked. batchSize>0
     *                  gives better performance. batchSize<=0 scenario is handled to preserve
     *                  backward compatibility.
     * @return true if given node and all its sub nodes are deleted successfully otherwise false
     * @throws IllegalArgumentException if an invalid path is specified
     */
    public static boolean deleteRecursive(
        ZooKeeper zk,
        final String pathRoot,
        final int batchSize) throws InterruptedException, KeeperException {
        PathUtils.validatePath(pathRoot);

        List<String> tree = listSubTreeBFS(zk, pathRoot);
        LOG.debug("Deleting tree: {}", tree);

        if (batchSize > 0) {
            return deleteInBatch(zk, tree, batchSize);
        } else {
            for (int i = tree.size() - 1; i >= 0; --i) {
                //Delete the leaves first and eventually get rid of the root
                zk.delete(tree.get(i), -1); //Delete all versions of the node with -1.
            }
            return true;
        }
    }

    /**
     * Same as {@link #deleteRecursive(org.apache.zookeeper.ZooKeeper, java.lang.String, int)}
     * kept here for compatibility with 3.5 clients.
     *
     * @since 3.6.1
     */
    public static void deleteRecursive(
        ZooKeeper zk,
        final String pathRoot) throws InterruptedException, KeeperException {
        // batchSize=0 is passed to preserve the backward compatibility with older clients.
        deleteRecursive(zk, pathRoot, 0);
    }

    private static class BatchedDeleteCbContext {

        public Semaphore sem;
        public AtomicBoolean success;

        public BatchedDeleteCbContext(int rateLimit) {
            sem = new Semaphore(rateLimit);
            success = new AtomicBoolean(true);
        }

    }

    private static boolean deleteInBatch(ZooKeeper zk, List<String> tree, int batchSize) throws InterruptedException {
        int rateLimit = 10;
        List<Op> ops = new ArrayList<>();
        BatchedDeleteCbContext context = new BatchedDeleteCbContext(rateLimit);
        MultiCallback cb = (rc, path, ctx, opResults) -> {
            ((BatchedDeleteCbContext) ctx).sem.release();
            if (rc != Code.OK.intValue()) {
                ((BatchedDeleteCbContext) ctx).success.set(false);
            }
        };

        // Delete the leaves first and eventually get rid of the root
        for (int i = tree.size() - 1; i >= 0; --i) {
            // Create Op to delete all versions of the node with -1.
            ops.add(Op.delete(tree.get(i), -1));

            if (ops.size() == batchSize || i == 0) {
                if (!context.success.get()) {
                    // fail fast
                    break;
                }
                context.sem.acquire();
                zk.multi(ops, cb, context);
                ops = new ArrayList<>();
            }
        }

        // wait for all callbacks to finish
        context.sem.acquire(rateLimit);
        return context.success.get();
    }

    /**
     * Recursively delete the node with the given path. (async version).
     *
     * <p>
     * Important: All versions, of all nodes, under the given node are deleted.
     * <p>
     * If there is an error with deleting one of the sub-nodes in the tree,
     * this operation would abort and would be the responsibility of the app to handle the same.
     * <p>
     * @param zk the zookeeper handle
     * @param pathRoot the path to be deleted
     * @param cb call back method
     * @param ctx the context the callback method is called with
     * @throws IllegalArgumentException if an invalid path is specified
     */
    public static void deleteRecursive(
        ZooKeeper zk,
        final String pathRoot,
        VoidCallback cb,
        Object ctx) throws InterruptedException, KeeperException {
        PathUtils.validatePath(pathRoot);

        List<String> tree = listSubTreeBFS(zk, pathRoot);
        LOG.debug("Deleting tree: {}", tree);
        for (int i = tree.size() - 1; i >= 0; --i) {
            //Delete the leaves first and eventually get rid of the root
            zk.delete(tree.get(i), -1, cb, ctx); //Delete all versions of the node with -1.
        }
    }

    /**
     * @param filePath the file path to be validated
     * @return Returns null if valid otherwise error message
     */
    public static String validateFileInput(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return "File '" + file.getAbsolutePath() + "' does not exist.";
        }
        if (!file.canRead()) {
            return "Read permission is denied on the file '" + file.getAbsolutePath() + "'";
        }
        if (file.isDirectory()) {
            return "'" + file.getAbsolutePath() + "' is a direcory. it must be a file.";
        }
        return null;
    }

    /**
     * BFS Traversal of the system under pathRoot, with the entries in the list, in the
     * same order as that of the traversal.
     * <p>
     * <b>Important:</b> This is <i>not an atomic snapshot</i> of the tree ever, but the
     *  state as it exists across multiple RPCs from zkClient to the ensemble.
     * For practical purposes, it is suggested to bring the clients to the ensemble
     * down (i.e. prevent writes to pathRoot) to 'simulate' a snapshot behavior.
     *
     * @param zk the zookeeper handle
     * @param pathRoot The znode path, for which the entire subtree needs to be listed.
     * @throws InterruptedException
     * @throws KeeperException
     */
    public static List<String> listSubTreeBFS(
        ZooKeeper zk,
        final String pathRoot) throws KeeperException, InterruptedException {
        Queue<String> queue = new ArrayDeque<>();
        List<String> tree = new ArrayList<String>();
        queue.add(pathRoot);
        tree.add(pathRoot);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            List<String> children = zk.getChildren(node, false);
            for (final String child : children) {
                final String childPath = node + "/" + child;
                queue.add(childPath);
                tree.add(childPath);
            }
        }
        return tree;
    }

    /**
     * Visits the subtree with root as given path and calls the passed callback with each znode
     * found during the search. It performs a depth-first, pre-order traversal of the tree.
     * <p>
     * <b>Important:</b> This is <i>not an atomic snapshot</i> of the tree ever, but the
     * state as it exists across multiple RPCs from zkClient to the ensemble.
     * For practical purposes, it is suggested to bring the clients to the ensemble
     * down (i.e. prevent writes to pathRoot) to 'simulate' a snapshot behavior.
     */
    public static void visitSubTreeDFS(
        ZooKeeper zk,
        final String path,
        boolean watch,
        StringCallback cb) throws KeeperException, InterruptedException {
        PathUtils.validatePath(path);

        zk.getData(path, watch, null);
        cb.processResult(Code.OK.intValue(), path, null, path);
        visitSubTreeDFSHelper(zk, path, watch, cb);
    }

    @SuppressWarnings("unchecked")
    private static void visitSubTreeDFSHelper(
        ZooKeeper zk,
        final String path,
        boolean watch,
        StringCallback cb) throws KeeperException, InterruptedException {
        // we've already validated, therefore if the path is of length 1 it's the root
        final boolean isRoot = path.length() == 1;
        try {
            List<String> children = zk.getChildren(path, watch, null);
            Collections.sort(children);

            for (String child : children) {
                String childPath = (isRoot ? path : path + "/") + child;
                cb.processResult(Code.OK.intValue(), childPath, null, child);
            }

            for (String child : children) {
                String childPath = (isRoot ? path : path + "/") + child;
                visitSubTreeDFSHelper(zk, childPath, watch, cb);
            }
        } catch (KeeperException.NoNodeException e) {
            // Handle race condition where a node is listed
            // but gets deleted before it can be queried
            return; // ignore
        }
    }

    /**
     * @param perms
     *            ACL permissions
     * @return string representation of permissions
     */
    public static String getPermString(int perms) {
        return permCache.computeIfAbsent(perms, k -> constructPermString(k));
    }

    private static String constructPermString(int perms) {
        StringBuilder p = new StringBuilder();
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

    public static String aclToString(List<ACL> acls) {
        StringBuilder sb = new StringBuilder();
        for (ACL acl : acls) {
            sb.append(acl.getId().getScheme());
            sb.append(":");
            sb.append(acl.getId().getId());
            sb.append(":");
            sb.append(getPermString(acl.getPerms()));
        }
        return sb.toString();
    }
}