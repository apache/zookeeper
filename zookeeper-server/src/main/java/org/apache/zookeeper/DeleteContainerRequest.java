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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;

public class DeleteContainerRequest implements Record {
    /**
     * Delete the node at the path whatever its mzxid.
     */
    public static final long ANY_MZXID = -1;

    private String path;
    private long mzxid = ANY_MZXID;

    public DeleteContainerRequest() {
    }

    public DeleteContainerRequest(String path) {
        this.path = path;
    }

    /**
     * @param mzxid only delete the node if it still has this mzxid, so not if it was modified, or
     *              replaced by a node created at the same path, after it was checked for deletion
     */
    public DeleteContainerRequest(String path, long mzxid) {
        this.path = path;
        this.mzxid = mzxid;
    }

    public String getPath() {
        return path;
    }

    public long getMzxid() {
        return mzxid;
    }

    @Override
    public void serialize(OutputArchive archive, String tag) throws IOException {
        archive.writeBuffer(path.getBytes(StandardCharsets.UTF_8), "path");
        archive.writeLong(mzxid, "mzxid");
    }

    @Override
    public void deserialize(InputArchive archive, String tag) throws IOException {
        byte[] bytes = archive.readBuffer("path");
        path = new String(bytes, StandardCharsets.UTF_8);
        mzxid = archive.readLong("mzxid");
    }
}
