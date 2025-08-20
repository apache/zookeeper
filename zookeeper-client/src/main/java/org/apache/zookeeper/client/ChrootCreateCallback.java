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

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.data.Stat;

@InterfaceAudience.Private
class ChrootCreateCallback implements AsyncCallback.StringCallback, AsyncCallback.Create2Callback {
    private final Chroot.NotRoot chroot;
    private final AsyncCallback callback;

    public ChrootCreateCallback(Chroot.NotRoot chroot, StringCallback callback) {
        this.chroot = chroot;
        this.callback = callback;
    }

    public ChrootCreateCallback(Chroot.NotRoot chroot, Create2Callback callback) {
        this.chroot = chroot;
        this.callback = callback;
    }

    @Override
    public void processResult(int rc, String path, Object ctx, String name) {
        StringCallback cb = (StringCallback) callback;
        cb.processResult(rc, path, ctx, name == null ? null : chroot.strip(name));
    }

    @Override
    public void processResult(int rc, String path, Object ctx, String name, Stat stat) {
        Create2Callback cb = (Create2Callback) callback;
        cb.processResult(rc, path, ctx, name == null ? null : chroot.strip(name), stat);
    }
}
