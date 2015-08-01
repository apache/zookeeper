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

import java.util.List;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

@InterfaceAudience.Public
public interface AsyncCallback {
    @InterfaceAudience.Public
    interface StatCallback extends AsyncCallback {
        public void processResult(int rc, String path, Object ctx, Stat stat);
    }
    
    @InterfaceAudience.Public
    interface DataCallback extends AsyncCallback {
        public void processResult(int rc, String path, Object ctx, byte data[],
                Stat stat);
    }
    
    @InterfaceAudience.Public
    interface ACLCallback extends AsyncCallback {
        public void processResult(int rc, String path, Object ctx,
                List<ACL> acl, Stat stat);
    }
    
    @InterfaceAudience.Public
    interface ChildrenCallback extends AsyncCallback {
        public void processResult(int rc, String path, Object ctx,
                List<String> children);
    }
    
    @InterfaceAudience.Public
    interface Children2Callback extends AsyncCallback {
        public void processResult(int rc, String path, Object ctx,
                List<String> children, Stat stat);
    }
    
    @InterfaceAudience.Public
    interface StringCallback extends AsyncCallback {
        public void processResult(int rc, String path, Object ctx, String name);
    }
    
    @InterfaceAudience.Public
    interface VoidCallback extends AsyncCallback {
        public void processResult(int rc, String path, Object ctx);
    }

    /**
     * This callback is used to process the multiple results from
     * a single multi call.
     * See {@link org.apache.zookeeper.ZooKeeper#multi} for more information.
     * @since 3.4.7
     */
    interface MultiCallback extends AsyncCallback {
        /**
         * Process the result of the asynchronous call.
         * <p/>
         * On success, rc is
         * {@link org.apache.zookeeper.KeeperException.Code#OK}.
         * All opResults are
         * non-{@link org.apache.zookeeper.OpResult.ErrorResult},
         *
         * <p/>
         * On failure, rc is a failure code in
         * {@link org.apache.zookeeper.KeeperException.Code}.
         * All opResults are
         * {@link org.apache.zookeeper.OpResult.ErrorResult}.
         * All operations will be rollback-ed even if operations
         * before the failing one were successful.
         *
         * @param rc   The return code or the result of the call.
         * @param path The path that we passed to asynchronous calls.
         * @param ctx  Whatever context object that we passed to
         *             asynchronous calls.
         * @param opResults The list of results.
         *                  One result for each operation,
         *                  and the order matches that of input.
         */
        public void processResult(int rc, String path, Object ctx,
                List<OpResult> opResults);
    }
}
