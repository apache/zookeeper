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
    private String path;

    public DeleteContainerRequest() {
    }

    public DeleteContainerRequest(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }

    @Override
    public void serialize(OutputArchive archive, String tag) throws IOException {
        archive.writeBuffer(path.getBytes(StandardCharsets.UTF_8), "path");
    }

    @Override
    public void deserialize(InputArchive archive, String tag) throws IOException {
        byte[] bytes = archive.readBuffer("path");
        path = new String(bytes, StandardCharsets.UTF_8);
    }
}
