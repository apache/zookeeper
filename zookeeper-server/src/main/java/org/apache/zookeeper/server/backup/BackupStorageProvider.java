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

package org.apache.zookeeper.server.backup;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Interface for a backup storage provider
 */
public interface BackupStorageProvider {

  /**
   * Get the metadata for a backed-up file with the given name.
   * @param file the file name relative to the root of the backup storage provider
   * @return the metadata for the backed-up file
   * @throws IOException
   */
  BackupFileInfo getBackupFileInfo(File file) throws IOException;

  /**
   * Get the metadata for backedup files with the given prefix.
   * @param path path relative to the root of the backup storage provider
   * @param prefix The file prefix
   * @return the list of files matching the prefix
   * @throws IOException
   */
  List<BackupFileInfo> getBackupFileInfos(File path, String prefix) throws IOException;

  /**
   * Get the list of directories under the given path
   * @param path the location from where to get the list of directories
   * @return list of the full child directory names relative to the root of the storage provider
   * @throws IOException
   */
  List<File> getDirectories(File path) throws IOException;

  /**
   * Open a file
   * @param path the path of the file to open
   * @return stream of the file
   * @throws IOException
   */
  InputStream open(File path) throws IOException;

  /**
   * Copy a local file to backup storage.
   * This method must guarantee a valid and transactional operation, or implement the operation
   * in such a way that the {@link #cleanupInvalidFiles(File path) cleanup} method can cleanup
   * after any interrupted operations.
   * @param srcFile the local file
   * @param destName the name to use in backup storage
   * @throws IOException
   */
  void copyToBackupStorage(File srcFile, File destName) throws IOException;

  /**
   * Copy a file from backup storage to local storage
   * @param srcName the name of the backup file to copy
   * @param destFile the local file to which to copy the file
   * @throws IOException
   */
  void copyToLocalStorage(File srcName, File destFile) throws IOException;

  /**
   * Delete a file from backup storage
   * @param fileToDelete the name of the backup file to delete
   * @throws IOException
   */
  void delete(File fileToDelete) throws IOException;

  /**
   * Cleanup any invalid files that may have been left behind by failed operations on the
   * storage provider
   * @throws IOException
   */
  void cleanupInvalidFiles(File path) throws IOException;
}