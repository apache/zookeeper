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

package org.apache.zookeeper.server.backup.storage.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.zookeeper.server.backup.BackupConfig;
import org.apache.zookeeper.server.backup.BackupFileInfo;
import org.apache.zookeeper.server.backup.exception.BackupException;
import org.apache.zookeeper.server.backup.storage.BackupStorageProvider;
import org.apache.zookeeper.server.backup.storage.BackupStorageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation for backup storage provider for file systems that store files in a tree (hierarchical) structure
 * To use this class for different file systems, use appropriate address for backupStoragePath in BackupConfig
 * For example:
 * 1. hard disk drive & solid-state drive: /mountPoint/relativePathToMountPoint
 * 2. NFS: /nfsClientMountPoint/relativePathToMountPoint
 * 3. local disk: an absolute path to a directory
 */
public class FileSystemBackupStorage implements BackupStorageProvider {
  private static final Logger LOG = LoggerFactory.getLogger(FileSystemBackupStorage.class);
  private final String fileRootPath;
  private final ReadWriteLock rwLock;
  private final Lock sharedLock;
  private final Lock exclusiveLock;

  /**
   * Constructor using BackupConfig to get backup storage info
   * @param backupConfig The information and settings about backup storage, to be set as a part of ZooKeeper server config
   */
  public FileSystemBackupStorage(BackupConfig backupConfig) {
    if (!new File(backupConfig.getBackupStoragePath()).exists()) {
      throw new BackupException(
          "The backup storage is not ready, please check the path: " + backupConfig
              .getBackupStoragePath());
    }
    fileRootPath = String
        .join(File.separator, backupConfig.getBackupStoragePath(), backupConfig.getNamespace());
    rwLock = new ReentrantReadWriteLock();
    sharedLock = rwLock.readLock();
    exclusiveLock = rwLock.writeLock();
  }

  @Override
  public BackupFileInfo getBackupFileInfo(File file) throws IOException {
    String backupFilePath = BackupStorageUtil.constructBackupFilePath(file.getName(), fileRootPath);
    File backupFile = new File(backupFilePath);

    if (!backupFile.exists()) {
      return new BackupFileInfo(backupFile, BackupFileInfo.NOT_SET, BackupFileInfo.NOT_SET);
    }

    BasicFileAttributes fileAttributes =
        Files.readAttributes(Paths.get(backupFilePath), BasicFileAttributes.class);
    return new BackupFileInfo(backupFile, fileAttributes.lastModifiedTime().toMillis(),
        fileAttributes.size());
  }

  @Override
  public List<BackupFileInfo> getBackupFileInfos(File path, String prefix) throws IOException {
    String filePath = path == null ? "" : path.getPath();
    String backupDirPath = Paths.get(fileRootPath, filePath).toString();
    File backupDir = new File(backupDirPath);

    if (!backupDir.exists()) {
      return new ArrayList<>();
    }

    File[] files = BackupStorageUtil.getFilesWithPrefix(backupDir, prefix);

    // Read the file info and add to the list. If an exception is thrown, the entire operation will fail
    List<BackupFileInfo> backupFileInfos = new ArrayList<>();
    for (File file : files) {
      backupFileInfos.add(getBackupFileInfo(file));
    }
    return backupFileInfos;
  }

  @Override
  public List<File> getDirectories(File path) {
    String filePath = path == null ? "" : path.getPath();
    String backupDirPath = BackupStorageUtil.constructBackupFilePath(filePath, fileRootPath);
    File backupDir = new File(backupDirPath);

    if (!backupDir.exists()) {
      throw new BackupException(
          "Backup directory " + filePath + " does not exist, could not get directory list.");
    }

    // Filter out all the files which are directories
    FilenameFilter fileFilter = (dir, name) -> new File(dir, name).isDirectory();
    File[] dirs = backupDir.listFiles(fileFilter);

    if (dirs == null) {
      return new ArrayList<>();
    }
    return Arrays.asList(dirs);
  }

  @Override
  public InputStream open(File path) throws IOException {
    if (!path.exists() || path.isDirectory()) {
      throw new BackupException("The file with the file path " + path
          + " does not exist or is a directory, could not open the file.");
    }
    return new FileInputStream(path);
  }

  @Override
  public void copyToBackupStorage(File srcFile, File destName) throws IOException {
    InputStream inputStream = null;
    OutputStream outputStream = null;
    String backupTempFilePath;
    File backupTempFile;

    sharedLock.lock();
    try {
      inputStream = open(srcFile);

      backupTempFilePath = BackupStorageUtil
          .constructBackupFilePath(BackupStorageUtil.constructTempFileName(destName.getName()),
              fileRootPath);
      backupTempFile = new File(backupTempFilePath);

      BackupStorageUtil.createFile(backupTempFile, true);
      outputStream = new FileOutputStream(backupTempFile);

      BackupStorageUtil.streamData(inputStream, outputStream);

      Files.move(Paths.get(backupTempFilePath),
          Paths.get(BackupStorageUtil.constructBackupFilePath(destName.getName(), fileRootPath)),
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      try {
        if (inputStream != null) {
          inputStream.close();
        }
        if (outputStream != null) {
          outputStream.close();
        }
      } catch (Exception e) {
        // empty catch block. This is added incase exception occurs before releasing sharedLock.
      } finally {
        sharedLock.unlock();
      }
    }
  }

  @Override
  public void copyToLocalStorage(File srcName, File destFile) throws IOException {
    InputStream inputStream = null;
    OutputStream outputStream = null;

    // Create input stream from the source file in backup storage
    String backupFilePath =
        BackupStorageUtil.constructBackupFilePath(srcName.getName(), fileRootPath);
    File backupFile = new File(backupFilePath);

    try {
      inputStream = open(backupFile);

      BackupStorageUtil.createFile(destFile, true);
      outputStream = new FileOutputStream(destFile);

      BackupStorageUtil.streamData(inputStream, outputStream);
    } finally {
      if (inputStream != null) {
        inputStream.close();
      }
      if (outputStream != null) {
        outputStream.close();
      }
    }
  }

  @Override
  public void delete(File fileToDelete) throws IOException {
    String fileName = fileToDelete == null ? "" : fileToDelete.getName();
    String backupFilePath = BackupStorageUtil.constructBackupFilePath(fileName, fileRootPath);
    Files.deleteIfExists(Paths.get(backupFilePath));
  }

  @Override
  public void cleanupInvalidFiles(File path) throws IOException {
    if (path == null) {
      path = new File(fileRootPath);
    }
    exclusiveLock.lock();
    try {
      BackupStorageUtil.cleanUpTempFiles(path);
    } finally {
      exclusiveLock.unlock();
    }
  }
}