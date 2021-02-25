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
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.common.ConfigException;
import org.apache.zookeeper.server.backup.BackupConfig;
import org.apache.zookeeper.server.backup.BackupFileInfo;
import org.apache.zookeeper.server.backup.storage.BackupStorageProvider;
import org.apache.zookeeper.server.backup.storage.BackupStorageUtil;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class FileSystemBackupStorageTest {
  private static String testFolderPath;
  private static String testFileDirPath;
  private static String backupFileDirPath;
  private static List<String> testFileNames =
      new ArrayList<>(Arrays.asList("log.testFile_0", "snapshot.testFile_1", "log.testFile_2"));
  private static BackupStorageProvider backupStorage;
  private static List<File> testFiles;
  private static List<File> backupFiles;
  private static final String NAMESPACE = "test";
  private static final String BACKUP_STORAGE_PATH = "./localBackupStorageTest";

  @BeforeClass
  public static void beforeClass() throws ConfigException, IOException {
    // Build backup config that point the backup storage to a local directory
    new File(BACKUP_STORAGE_PATH).mkdir();
    BackupConfig backupConfig = new BackupConfig.Builder().setEnabled(true)
        .setStatusDir(new File("./localBackupStorageTest/backup/status")).
            setTmpDir(new File("./localBackupStorageTest/tmp/backup"))
        .setStorageProviderClassName(FileSystemBackupStorage.class.getName())
        .setBackupStoragePath(BACKUP_STORAGE_PATH).setNamespace(NAMESPACE).build().get();

    backupStorage = new FileSystemBackupStorage(backupConfig);
    // The path to the directory that contains all directories and files created for this test
    testFolderPath =
        Paths.get(Paths.get("").toAbsolutePath().toString(), backupConfig.getBackupStoragePath())
            .toString();
    // The path to the directory that backup files will be stored
    backupFileDirPath = String.join(File.separator, testFolderPath, NAMESPACE);
    // The path to the directory that contains the test files representing logs/snapshots in ZK dataDir
    testFileDirPath = String.join(File.separator, testFolderPath, "testFiles");
    testFiles = new ArrayList<>();
    backupFiles = new ArrayList<>();

    // Create the test files in the testFileDir, and add reference to testFiles list for easy reference
    for (String testFileName : testFileNames) {
      String testFilePath = String.join(File.separator, testFileDirPath, testFileName);
      File testFile = new File(testFilePath);
      testFiles.add(testFile);
      BackupStorageUtil.createFile(testFile, false);
      FileWriter fileWriter = new FileWriter(testFile);
      for (int i = 0; i < 1000; i++) {
        fileWriter.write(testFileName);
        fileWriter.write('\n');
      }
      fileWriter.close();
      Assert.assertTrue(testFile.exists());
      Assert.assertTrue(sizeOf(testFile) > 0);
    }
  }

  @AfterClass
  public static void afterClass() throws IOException {
    FileUtils.deleteDirectory(new File(testFolderPath));
  }

  @Test
  public void test0_CopyToBackupStorage() throws IOException {
    for (File testFile : testFiles) {
      backupStorage.copyToBackupStorage(testFile, testFile);
      String backupFilePath =
          BackupStorageUtil.constructBackupFilePath(testFile.getName(), backupFileDirPath);
      File backupFile = new File(backupFilePath);
      backupFiles.add(backupFile);
      Assert.assertEquals(sizeOf(testFile), sizeOf(backupFile));
    }
  }

  @Test
  public void test1_GetBackupFileInfo() throws IOException {
    BackupFileInfo validFileInfo = backupStorage.getBackupFileInfo(testFiles.get(0));
    BackupFileInfo invalidFileInfo =
        backupStorage.getBackupFileInfo(new File("log.invalidBackupFile"));
    Assert.assertEquals(validFileInfo.getSize(), sizeOf(testFiles.get(0)));
    Assert.assertEquals(invalidFileInfo.getSize(), BackupFileInfo.NOT_SET);
  }

  @Test
  public void test2_GetBackupFileInfos() throws IOException {
    List<BackupFileInfo> fileInfos = backupStorage.getBackupFileInfos(new File(""), "log");
    Assert.assertEquals(2, fileInfos.size());
  }

  @Test
  public void test3_CopyToLocalStorage() throws IOException {
    for (File testFile : testFiles) {
      Files.delete(Paths.get(testFile.getPath()));
      Assert.assertFalse(testFile.exists());
      backupStorage.copyToLocalStorage(testFile,
          new File(String.join(File.separator, testFileDirPath, testFile.getName())));
      Assert.assertTrue(testFile.exists());
    }
  }

  @Test
  public void test4_Delete() throws IOException {
    for (int i = 0; i < testFiles.size(); i++) {
      backupStorage.delete(testFiles.get(i));
      Assert.assertFalse(backupFiles.get(i).exists());
    }
  }

  private static long sizeOf(File file) throws IOException {
    return Files.readAttributes(Paths.get(file.getPath()), BasicFileAttributes.class).size();
  }
}
