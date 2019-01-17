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
package org.apache.zookeeper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.common.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZKUtil {
    private static final Logger LOG = LoggerFactory.getLogger(ZKUtil.class);
    /**
     * Recursively delete the node with the given path.
     * <p>
     * Important: All versions, of all nodes, under the given node are deleted.
     * <p>
     * If there is an error with deleting one of the sub-nodes in the tree,
     * this operation would abort and would be the responsibility of the app to handle the same.
     *
     * See {@link #delete(String, int)} for more details.
     *
     * @throws IllegalArgumentException if an invalid path is specified
     */
    public static void deleteRecursive(ZooKeeper zk, final String pathRoot)
        throws InterruptedException, KeeperException
    {
        PathUtils.validatePath(pathRoot);

        List<String> tree = listSubTreeBFS(zk, pathRoot);
        LOG.debug("Deleting " + tree);
        LOG.debug("Deleting " + tree.size() + " subnodes ");
        for (int i = tree.size() - 1; i >= 0 ; --i) {
            //Delete the leaves first and eventually get rid of the root
            zk.delete(tree.get(i), -1); //Delete all versions of the node with -1.
        }
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
    public static void deleteRecursive(ZooKeeper zk, final String pathRoot, VoidCallback cb,
        Object ctx)
        throws InterruptedException, KeeperException
    {
        PathUtils.validatePath(pathRoot);

        List<String> tree = listSubTreeBFS(zk, pathRoot);
        LOG.debug("Deleting " + tree);
        LOG.debug("Deleting " + tree.size() + " subnodes ");
        for (int i = tree.size() - 1; i >= 0 ; --i) {
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
    public static List<String> listSubTreeBFS(ZooKeeper zk, final String pathRoot) throws
        KeeperException, InterruptedException {
        Deque<String> queue = new LinkedList<String>();
        List<String> tree = new ArrayList<String>();
        queue.add(pathRoot);
        tree.add(pathRoot);
        while (true) {
            String node = queue.pollFirst();
            if (node == null) {
                break;
            }
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
    public static void visitSubTreeDFS(ZooKeeper zk, final String path, boolean watch,
        StringCallback cb) throws KeeperException, InterruptedException {
        PathUtils.validatePath(path);

        zk.getData(path, watch, null);
        cb.processResult(Code.OK.intValue(), path, null, path);
        visitSubTreeDFSHelper(zk, path, watch, cb);
    }

    @SuppressWarnings("unchecked")
    private static void visitSubTreeDFSHelper(ZooKeeper zk, final String path,
        boolean watch, StringCallback cb)
            throws KeeperException, InterruptedException {
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
        }
        catch (KeeperException.NoNodeException e) {
            // Handle race condition where a node is listed
            // but gets deleted before it can be queried
            return; // ignore
        }
    }
}