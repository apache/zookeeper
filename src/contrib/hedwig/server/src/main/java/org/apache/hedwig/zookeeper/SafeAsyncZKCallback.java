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
package org.apache.hedwig.zookeeper;

import java.util.List;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

public class SafeAsyncZKCallback extends SafeAsyncCallback{
    public static abstract class StatCallback implements AsyncCallback.StatCallback {
        public void processResult(int rc, String path, Object ctx, Stat stat) {
            try {
                safeProcessResult(rc, path, ctx, stat);
            } catch (Throwable t) {
                invokeUncaughtExceptionHandler(t);
            }
        }

        public abstract void safeProcessResult(int rc, String path, Object ctx, Stat stat);
    }

    public static abstract class DataCallback implements AsyncCallback.DataCallback {
        public void processResult(int rc, String path, Object ctx, byte data[], Stat stat) {
            try {
                safeProcessResult(rc, path, ctx, data, stat);
            } catch (Throwable t) {
                invokeUncaughtExceptionHandler(t);
            }
        }

        public abstract void safeProcessResult(int rc, String path, Object ctx, byte data[], Stat stat);
    }

    public static abstract class ACLCallback implements AsyncCallback.ACLCallback {
        public void processResult(int rc, String path, Object ctx, List<ACL> acl, Stat stat) {
            try {
                safeProcessResult(rc, path, ctx, acl, stat);
            } catch (Throwable t) {
                invokeUncaughtExceptionHandler(t);
            }
        }

        public abstract void safeProcessResult(int rc, String path, Object ctx, List<ACL> acl, Stat stat);
    }

    public static abstract class ChildrenCallback implements AsyncCallback.ChildrenCallback {
        public void processResult(int rc, String path, Object ctx, List<String> children) {
            try {
                safeProcessResult(rc, path, ctx, children);
            } catch (Throwable t) {
                invokeUncaughtExceptionHandler(t);
            }
        }

        public abstract void safeProcessResult(int rc, String path, Object ctx, List<String> children);
    }

    public static abstract class StringCallback implements AsyncCallback.StringCallback {
        public void processResult(int rc, String path, Object ctx, String name) {
            try {
                safeProcessResult(rc, path, ctx, name);
            } catch (Throwable t) {
                invokeUncaughtExceptionHandler(t);
            }
        }

        public abstract void safeProcessResult(int rc, String path, Object ctx, String name);
    }

    public static abstract class VoidCallback implements AsyncCallback.VoidCallback {
        public void processResult(int rc, String path, Object ctx) {
            try {
                safeProcessResult(rc, path, ctx);
            } catch (Throwable t) {
                invokeUncaughtExceptionHandler(t);
            }
        }

        public abstract void safeProcessResult(int rc, String path, Object ctx);
    }
}
