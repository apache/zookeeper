package org.apache.zookeeper.backup;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.zookeeper.server.persistence.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * Implementation for NFS-based backup storage provider
 */
public class NFSBackupStorage implements BackupStorageProvider {
  private static final Logger LOG = LoggerFactory.getLogger(NFSBackupStorage.class);

  public static final String TMP_FILE_PREFIX = "TMP_";
  private static final String PATH_SEPARATOR = File.separator;
  private final BackupConfig _backupConfig;
  private final String _fileRootPath;

  /**
   * Constructor using BackupConfig to get backup storage info
   * @param backupConfig The information and settings about backup storage, to be set as a part of ZooKeeper server config
   */
  public NFSBackupStorage(BackupConfig backupConfig) {
    _backupConfig = backupConfig;
    _fileRootPath =
        String.join(PATH_SEPARATOR, _backupConfig.getMountPath(), _backupConfig.getNamespace());
  }

  @Override
  public BackupFileInfo getBackupFileInfo(File file) throws IOException {
    String backupFilePath = constructBackupFilePath(file.getName());
    File backupFile = new File(backupFilePath);

    if (!backupFile.exists()) {
      throw new BackupException(
          "Backup file " + file.getName() + " does not exist, could not get file info.");
    }

    BasicFileAttributes fileAttributes =
        Files.readAttributes(Paths.get(backupFilePath), BasicFileAttributes.class);
    return new BackupFileInfo(backupFile, fileAttributes.lastModifiedTime().toMillis(),
        fileAttributes.size());
  }

  @Override
  public List<BackupFileInfo> getBackupFileInfos(File path, String prefix) throws IOException {
    String backupDirPath = constructBackupFilePath(path.getPath());
    File backupDir = new File(backupDirPath);

    if (!backupDir.exists()) {
      throw new BackupException(
          "Backup directory " + path.getPath() + " does not exist, could not get file info.");
    }

    // Get a list of files whose file names start with the provided prefix
    FilenameFilter fileFilter = (dir, name) -> name.startsWith(prefix);
    File[] files = backupDir.listFiles(fileFilter);
    if (files == null) {
      throw new BackupException("The provided directory path " + path.getPath()
          + " is invalid, could not get file info.");
    }

    // Read the file info and add to the list. If an exception is thrown, the entire operation will fail
    List<BackupFileInfo> backupFileInfos = new ArrayList<>();
    for (File file : files) {
      BasicFileAttributes fileAttributes =
          Files.readAttributes(Paths.get(file.getPath()), BasicFileAttributes.class);
      backupFileInfos.add(new BackupFileInfo(file, fileAttributes.lastModifiedTime().toMillis(),
          fileAttributes.size()));
    }
    return backupFileInfos;
  }

  @Override
  public List<File> getDirectories(File path) throws IOException {
    String backupDirPath = constructBackupFilePath(path.getPath());
    File backupDir = new File(backupDirPath);

    if (!backupDir.exists()) {
      throw new BackupException(
          "Backup directory " + path.getPath() + " does not exist, could not get directory list.");
    }

    // Filter out all the files which are directories
    FilenameFilter fileFilter = (dir, name) -> new File(dir, name).isDirectory();
    File[] dirs = backupDir.listFiles(fileFilter);

    if (dirs == null) {
      throw new BackupException("The provided directory path " + path.getPath()
          + " is invalid, could not get directory list.");
    }
    return Arrays.asList(dirs);
  }

  @Override
  public void copyToBackupStorage(File srcFile, File destName) throws IOException {
    InputStream inputStream;
    OutputStream outputStream = null;

    // Create input stream from the source file
    try {
      inputStream = new FileInputStream(srcFile);
    } catch (FileNotFoundException e) {
      if (!srcFile.exists() || srcFile.isDirectory()) {
        throw new BackupException("File " + srcFile.getPath()
            + " does not exist or is a directory, cannot be copied to backup storage.");
      } else {
        throw new IOException("Cannot open source file " + srcFile.getPath()
            + " , cannot be copied to backup storage.");
      }
    }

    // Prepare in the backup storage: create parent dirs if not exist, clean up temporary files under the parent dir
    String backupTempFilePath = constructBackupFilePath(constructTempFileName(destName.getName()));
    String backupFilePath = constructBackupFilePath(destName.getName());
    File backupTempFile = new File(backupTempFilePath);
    File backupFile = new File(backupFilePath);
    File parentDir = backupTempFile.getParentFile();
    parentDir.mkdirs();
    cleanupTempFiles(parentDir.getPath());

    // Create destination temporary file
    if (!backupTempFile.createNewFile()) {
      if (backupFile.exists()) {
        throw new BackupException("A backed up file with the file path " + backupFilePath
            + " already exists, could not create a new one.");
      } else {
        throw new IOException(
            "Failed to create a temp file at destination path: " + backupTempFilePath
                + ", stopping the streaming.");
      }
    }

    // Create output stream on the destination temporary file and start streaming data
    try {
      outputStream = new FileOutputStream(backupTempFile);

      byte[] buffer = new byte[1024];
      int lengthRead;
      while ((lengthRead = inputStream.read(buffer)) > 0) {
        outputStream.write(buffer, 0, lengthRead);
        outputStream.flush();
      }
    } catch (FileNotFoundException e) {
      throw new IOException("The destination temp file " + backupTempFile.getPath()
          + " could not be opened for write.");
    } catch (IOException e) {
      throw new IOException(
          "IOException is caught during data streaming while copying file " + srcFile.getPath()
              + " to backup storage. The message of the IOException is: " + e.getMessage());
    } finally {
      inputStream.close();
      if (outputStream != null) {
        outputStream.close();
      }
    }

    // Rename the destination temporary file to destination file name provided by caller
    try {
      Files.move(Paths.get(backupTempFilePath), Paths.get(backupFilePath),
          StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new IOException(
          "IOException is caught during copying temp file to destination file " + backupFile
              .getPath() + ". The message of the IOException is: " + e.getMessage());
    }
  }

  @Override
  public void copyToLocalStorage(File srcName, File destFile) throws IOException {
    InputStream inputStream;
    OutputStream outputStream = null;

    // Create input stream from the source file in backup storage
    String backupFilePath = constructBackupFilePath(srcName.getName());
    File backupFile = new File(backupFilePath);
    try {
      inputStream = new FileInputStream(backupFile);
    } catch (FileNotFoundException e) {
      if (!backupFile.exists() || backupFile.isDirectory()) {
        throw new BackupException("The backed up file with the file path " + backupFilePath
            + " does exists or is a directory, could not copy to local storage.");
      } else {
        throw new IOException("Cannot open source file in backup storage" + backupFilePath
            + " , cannot be copied to local storage.");
      }
    }

    // Prepare in local storage: create parent dirs if not exist, create the destination file
    destFile.getParentFile().mkdirs();
    if (!destFile.createNewFile()) {
      if (destFile.exists()) {
        throw new BackupException("A local storage file with the file path " + destFile.getPath()
            + " already exists, could not create a new one.");
      } else {
        throw new IOException("Failed to create a file at destination path: " + destFile.getPath()
            + ", stopping the streaming.");
      }
    }

    // Create output stream on the destination file and start streaming data
    try {
      outputStream = new FileOutputStream(destFile);

      byte[] buffer = new byte[1024];
      int lengthRead;
      while ((lengthRead = inputStream.read(buffer)) > 0) {
        outputStream.write(buffer, 0, lengthRead);
        outputStream.flush();
      }
    } catch (FileNotFoundException e) {
      throw new IOException(
          "The destination local file " + destFile.getPath() + " could not be opened for write.");
    } catch (IOException e) {
      throw new IOException(
          "IOException is caught during data streaming while copying file " + backupFilePath
              + " to local storage. The message of the IOException is: " + e.getMessage());
    } finally {
      inputStream.close();
      if (outputStream != null) {
        outputStream.close();
      }
    }
  }

  @Override
  public void delete(File fileToDelete) throws IOException {
    String backupFilePath = constructBackupFilePath(fileToDelete.getPath());
    try {
      if (!Files.deleteIfExists(Paths.get(backupFilePath))) {
        throw new BackupException(
            "File " + fileToDelete.getName() + "does not exist, cannot delete this file.");
      }
    } catch (DirectoryNotEmptyException e) {
      throw new BackupException(
          "Directory " + fileToDelete.getName() + "is not empty, cannot be deleted.");
    }
  }

  /**
   * Construct the file path in backup storage using file name
   * @param fileName The file name for the file
   * @return The file path to the location where the file will be stored in backup storage
   */
  private String constructBackupFilePath(String fileName) {
    String backupFileName = fileName;

    //Remove the temporary file name prefix in order to determine file type
    if (fileName.startsWith(TMP_FILE_PREFIX)) {
      backupFileName = fileName.replace(TMP_FILE_PREFIX, "");
    }

    // Get the directory name where the file would be stored based on the file type: snapshot or transaction log
    String fileTypeDirName;
    if (backupFileName.startsWith(Util.SNAP_PREFIX)) {
      fileTypeDirName = Util.SNAP_PREFIX;
    } else if (backupFileName.startsWith(Util.TXLOG_PREFIX)) {
      fileTypeDirName = Util.TXLOG_PREFIX;
    } else {
      throw new IllegalArgumentException("No matching base file type for file " + fileName);
    }
    return String.join(PATH_SEPARATOR, _fileRootPath, fileTypeDirName, fileName);
  }

  /**
   * Delete all the files whose file names starts with temporary file name prefix
   * @param path The directory path for clean up
   * @throws IOException
   */
  private void cleanupTempFiles(String path) throws IOException {
    List<BackupFileInfo> tmpFiles = getBackupFileInfos(new File(path), TMP_FILE_PREFIX);
    tmpFiles.forEach(fileInfo -> {
      try {
        delete(fileInfo.getBackedUpFile());
      } catch (IOException e) {
        LOG.warn("Failed to delete temporary file {} from the backup storage.",
            fileInfo.getBackedUpFile().getName());
      }
    });
  }

  /**
   * Construct temporary file name using backup file name
   * @param fileName A backup file name: log.lowzxid-highzxid, snapshot.lowzxid-highzxid
   * @return A temporary backup file name: TMP_log.lowzxid-highzxid, TMP_snapshot.lowzxid-highzxid
   */
  private String constructTempFileName(String fileName) {
    return TMP_FILE_PREFIX + fileName;
  }
}
