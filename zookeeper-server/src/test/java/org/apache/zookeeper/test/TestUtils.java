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

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import org.apache.zookeeper.WatchedEvent;

/**
 * This class contains test utility methods
 */
public class TestUtils {

    /**
     * deletes a folder recursively
     *
     * @param file
     *            folder to be deleted
     * @param failOnError
     *            if true file deletion success is ensured
     */
    public static boolean deleteFileRecursively(
            File file, final boolean failOnError) {
        if (file != null) {
            if (file.isDirectory()) {
                File[] files = file.listFiles();
                int size = files.length;
                for (int i = 0; i < size; i++) {
                    File f = files[i];
                    boolean deleted = deleteFileRecursively(files[i], failOnError);
                    if (!deleted && failOnError) {
                        fail("file '" + f.getAbsolutePath() + "' deletion failed");
                    }
                }
            }
            return file.delete();
        }
        return true;
    }

    public static boolean deleteFileRecursively(File file) {
        return deleteFileRecursively(file, false);
    }

    /**
     * Asserts that the given {@link WatchedEvent} are semantically equal, i.e. they have the same EventType, path and
     * zxid.
     */
    public static void assertWatchedEventEquals(WatchedEvent expected, WatchedEvent actual) {
        // TODO: .hashCode and .equals cannot be added to WatchedEvent without potentially breaking consumers. This
        //  can be changed to `assertEquals(expected, actual)` once WatchedEvent has those methods. Until then,
        //  compare the lists manually.
        assertEquals(expected.getType(), actual.getType());
        assertEquals(expected.getPath(), actual.getPath());
        assertEquals(expected.getZxid(), actual.getZxid());
    }
}
