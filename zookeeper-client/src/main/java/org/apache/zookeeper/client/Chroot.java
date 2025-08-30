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

package org.apache.zookeeper.client;

import java.util.Objects;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

@InterfaceAudience.Private
public interface Chroot {
    static Chroot ofNullable(String chroot) {
        if (chroot == null) {
            return new Root();
        }
        return new NotRoot(chroot);
    }

    /**
     * Creates server path by prepending chroot to given client path.
     *
     * @param clientPath client path
     * @return sever path with chroot prepended
     */
    String prepend(String clientPath);

    /**
     * Creates client path by stripping chroot from given sever path.
     *
     * @param serverPath sever path with chroot prepended
     * @return client path with chroot stripped
     * @throws IllegalArgumentException if given server path contains no chroot
     */
    String strip(String serverPath);

    /**
     * Creates a delegating callback to strip chroot from created node name.
     */
    AsyncCallback.StringCallback interceptCallback(AsyncCallback.StringCallback callback);

    /**
     * Creates a delegating callback to strip chroot from created node name.
     */
    AsyncCallback.Create2Callback interceptCallback(AsyncCallback.Create2Callback callback);

    /**
     * Creates a delegating watcher to strip chroot from {@link WatchedEvent#getPath()} for given watcher.
     */
    Watcher interceptWatcher(Watcher watcher);

    final class Root implements Chroot {
        @Override
        public String prepend(String clientPath) {
            return clientPath;
        }

        @Override
        public String strip(String serverPath) {
            return serverPath;
        }

        @Override
        public AsyncCallback.StringCallback interceptCallback(AsyncCallback.StringCallback callback) {
            return callback;
        }

        @Override
        public AsyncCallback.Create2Callback interceptCallback(AsyncCallback.Create2Callback callback) {
            return callback;
        }

        @Override
        public Watcher interceptWatcher(Watcher watcher) {
            return watcher;
        }
    }

    final class NotRoot implements Chroot {
        private final String chroot;

        public NotRoot(String chroot) {
            this.chroot = Objects.requireNonNull(chroot);
        }

        @Override
        public String prepend(String clientPath) {
            // handle clientPath = "/"
            if (clientPath.length() == 1) {
                return chroot;
            }
            return chroot + clientPath;
        }

        @Override
        public String strip(String serverPath) {
            if (!serverPath.startsWith(chroot)) {
                String msg = String.format("server path %s does no start with chroot %s", serverPath, chroot);
                throw new IllegalArgumentException(msg);
            }
            if (chroot.length() == serverPath.length()) {
                return "/";
            } else {
                return serverPath.substring(chroot.length());
            }
        }

        @Override
        public AsyncCallback.StringCallback interceptCallback(AsyncCallback.StringCallback callback) {
            return new ChrootCreateCallback(this, callback);
        }

        @Override
        public AsyncCallback.Create2Callback interceptCallback(AsyncCallback.Create2Callback callback) {
            return new ChrootCreateCallback(this, callback);
        }

        @Override
        public Watcher interceptWatcher(Watcher watcher) {
            return new ChrootWatcher(this, watcher);
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof NotRoot) {
                return Objects.equals(chroot, ((NotRoot) other).chroot);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(chroot);
        }

        @Override
        public String toString() {
            return chroot;
        }
    }
}
