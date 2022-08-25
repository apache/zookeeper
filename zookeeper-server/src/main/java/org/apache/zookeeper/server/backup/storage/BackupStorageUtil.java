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
package org.apache.zookeeper.server.backup.storage;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;
import org.apache.zookeeper.server.backup.exception.BackupException;
import org.apache.zookeeper.server.persistence.Util;

/**
 * Util methods for backup storage
 */
public class BackupStorageUtil {
  public static final String TMP_FILE_PREFIX = "TMP_";
  private static final File[] NO_FILE = new File[0];

  /**
   * Parse the prefix from a file name, also works for temporary file names in backup storage
   * @param fileName The file name to be parsed
   * @return "log" for ZK transaction log files or "snapshot" for ZK snapshots
   */
  public static String getFileTypePrefix(String fileName) {
    String backupFileName = fileName;

    //Remove the temporary file name prefix in order to determine file type
    if (fileName.startsWith(TMP_FILE_PREFIX)) {
      backupFileName = fileName.substring(TMP_FILE_PREFIX.length());
    }

    String fileTypePrefix;
    if (backupFileName.startsWith(Util.SNAP_PREFIX)) {
      fileTypePrefix = Util.SNAP_PREFIX;
    } else if (backupFileName.startsWith(Util.TXLOG_PREFIX)) {
      fileTypePrefix = Util.TXLOG_PREFIX;
    } else {
      throw new BackupException("No matching base file type found for file " + fileName);
    }

    return fileTypePrefix;
  }

  /**
   * Construct the path of a backup file in the backup storage
   * @param fileName The name of the file
   * @param parentDir The path to the parent directory of the backup file.
   * @return The path of the backup file in the format of:
   * 1. parentDir path is not supplied: {fileName} or {fileName}
   * 2. parentDir path is provided: {parentDir}/{fileName} or {parentDir}/{fileName}
   */
  public static String constructBackupFilePath(String fileName, String parentDir) {
    //TODO: store snapshots and Txlogs in different subfolders for better organization
    if (parentDir != null) {
      return String.valueOf(Paths.get(parentDir, fileName));
    }
    return fileName;
  }

  /**
   * Construct temporary file name using backup file name
   * @param fileName A backup file name: log.lowzxid-highzxid, snapshot.lowzxid-highzxid
   * @return A temporary backup file name: TMP_log.lowzxid-highzxid, TMP_snapshot.lowzxid-highzxid
   */
  public static String constructTempFileName(String fileName) {
    return TMP_FILE_PREFIX + fileName;
  }

  /**
   * A basic method for streaming data from an input stream to an output stream
   * @param inputStream The stream to read from
   * @param outputStream The stream to write to
   * @throws IOException
   */
  public static void streamData(InputStream inputStream, OutputStream outputStream)
      throws IOException {
    byte[] buffer = new byte[1024];
    int lengthRead;
    while ((lengthRead = inputStream.read(buffer)) > 0) {
      outputStream.write(buffer, 0, lengthRead);
      outputStream.flush();
    }
  }

  /**
   * Create a new file in a specified path, create the parent directories if they do not exist.
   * @param file The path to create the file.
   * @param overwriteIfExist If a file already exists in the location,
   *                         1. true: delete the existing file and retry the creation of the new file,
   *                         or 2. false: keep the existing file.
   * @throws IOException
   */
  public static void createFile(File file, boolean overwriteIfExist) throws IOException {
    if (!file.getParentFile().mkdirs()) {
      System.err.println("Failed to create dirs");
    }
    if (!file.getParentFile().exists()) {
      throw new BackupException("Failed to create parent directories for file " + file.getName());
    }

    boolean retry = true;
    while (retry) {
      retry = overwriteIfExist;
      if (!file.createNewFile()) {
        if (file.exists()) {
          if (retry && !file.delete()) {
            throw new BackupException("A file with the file path " + file.getPath()
                + " already exists, and failed to be overwritten.");
          }
        } else {
          throw new BackupException("Failed to create a file at path: " + file.getPath());
        }
      }
      retry = false;
    }
  }

  /**
   * Get a list of all files whose file name starts with a certain prefix under a directory
   * @param directory The directory to search for the files
   * @param prefix The prefix of file name
   * @return
   */
  public static File[] getFilesWithPrefix(File directory, String prefix) {
    if (directory == null) {
      return NO_FILE;
    }
    FilenameFilter fileFilter = (dir, name) -> name.startsWith(prefix);
    File[] files = directory.listFiles(fileFilter);
    return files == null ? NO_FILE : files;
  }

  /**
   * Delete all the files whose file names starts with temporary file name prefix
   * @param directory The directory to search for temporary files
   * @throws IOException
   */
  public static void cleanUpTempFiles(File directory) throws IOException {
    File[] tempFiles = getFilesWithPrefix(directory, TMP_FILE_PREFIX);
    for (File tempFile : tempFiles) {
      Files.delete(Paths.get(tempFile.getPath()));
    }
  }

  /**
   * Delete a directory and all files inside it
   * @param directory The path to the directory
   * @throws IOException
   */
  public static void deleteDirectoryRecursively(File directory) throws IOException {
    Stream<Path> files = Files.walk(Paths.get(directory.getPath()));
    files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    files.close();
  }
}
