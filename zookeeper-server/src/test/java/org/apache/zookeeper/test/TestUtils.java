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

import static org.junit.Assert.fail;
import java.io.File;
import java.util.concurrent.Callable;

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
     * Assert expected return value for Callable object within specified wait time.
     *
     * @param call       callable object
     * @param expected   expected boolean value for callable
     * @param timeout    time out in milliseconds
     * @throws Exception
     */
    public static void assertWithTimeout(final Callable<Boolean> call, final boolean expected,
                                         final long timeout) throws Exception {
        final long delay = 100;
        final long retries = timeout / delay;
        for (int i = 0; i < retries; ++i) {
            if (call.call() == expected) {
                return;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                fail("Test thread is interrupted.");
            }
        }

        if (call.call() != expected) {
            fail(String.format("Test failed after %s msec", timeout));
        }
    }
}
