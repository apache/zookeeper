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

import java.util.ArrayList;

import com.yahoo.zookeeper.data.ACL;
import com.yahoo.zookeeper.data.Stat;

public interface AsyncCallback {
    interface StatCallback extends AsyncCallback {
        public void processResult(int rc, String path, Object ctx, Stat stat);
    }

    interface DataCallback extends AsyncCallback {
        public void processResult(int rc, String path, Object ctx, byte data[],
                Stat stat);
    }

    interface ACLCallback extends AsyncCallback {
        public void processResult(int rc, String path, Object ctx,
                ArrayList<ACL> acl, Stat stat);
    }

    interface ChildrenCallback extends AsyncCallback {
        public void processResult(int rc, String path, Object ctx,
                ArrayList<String> children);
    }

    interface StringCallback extends AsyncCallback {
        public void processResult(int rc, String path, Object ctx, String name);
    }

    interface VoidCallback extends AsyncCallback {
        public void processResult(int rc, String path, Object ctx);
    }
}
