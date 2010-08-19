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
package org.apache.hedwig.util;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.apache.log4j.Logger;

public class FileUtils {

    static DirDeleterThred dirDeleterThread;
    static Logger log = Logger.getLogger(FileUtils.class);

    static {
        dirDeleterThread = new DirDeleterThred();
        Runtime.getRuntime().addShutdownHook(dirDeleterThread);
    }

    public static File createTempDirectory(String prefix) throws IOException {
        return createTempDirectory(prefix, null);
    }

    public static File createTempDirectory(String prefix, String suffix) throws IOException {
        File tempDir = File.createTempFile(prefix, suffix);
        if (!tempDir.delete()) {
            throw new IOException("Could not delete temp file: " + tempDir.getAbsolutePath());
        }

        if (!tempDir.mkdir()) {
            throw new IOException("Could not create temp directory: " + tempDir.getAbsolutePath());
        }

        dirDeleterThread.addDirToDelete(tempDir);
        return tempDir;

    }

    static class DirDeleterThred extends Thread {
        List<File> dirsToDelete = new LinkedList<File>();

        public synchronized void addDirToDelete(File dir) {
            dirsToDelete.add(dir);
        }

        @Override
        public void run() {
            synchronized (this) {
                for (File dir : dirsToDelete) {
                    deleteDirectory(dir);
                }
            }
        }

        protected void deleteDirectory(File dir) {
            if (dir.isFile()) {
                if (!dir.delete()) {
                    log.error("Could not delete " + dir.getAbsolutePath());
                }
                return;
            }

            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }

            for (File f : files) {
                deleteDirectory(f);
            }

            if (!dir.delete()) {
                log.error("Could not delete directory: " + dir.getAbsolutePath());
            }

        }

    }

}
