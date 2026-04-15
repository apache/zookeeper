package org.apache.zookeeper.randoop;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class RegressionTest0 {

    public static boolean debug = false;

    public void assertBooleanArrayEquals(boolean[] expectedArray, boolean[] actualArray) {
        if (expectedArray.length != actualArray.length) {
            throw new AssertionError("Array lengths differ: " + expectedArray.length + " != " + actualArray.length);
        }
        for (int i = 0; i < expectedArray.length; i++) {
            if (expectedArray[i] != actualArray[i]) {
                throw new AssertionError("Arrays differ at index " + i + ": " + expectedArray[i] + " != " + actualArray[i]);
            }
        }
    }

    @Test
    public void test001() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test001");
        int int0 = org.apache.zookeeper.server.persistence.FileTxnLog.VERSION;
        org.junit.Assert.assertTrue("'" + int0 + "' != '" + 2 + "'", int0 == 2);
    }

    @Test
    public void test002() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test002");
        org.apache.zookeeper.server.persistence.FileTxnLog.setPreallocSize(10L);
    }

    @Test
    public void test003() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test003");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (java.lang.IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test004() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test004");
        int int0 = org.apache.zookeeper.server.persistence.FileTxnLog.TXNLOG_MAGIC;
        org.junit.Assert.assertTrue("'" + int0 + "' != '" + 1514884167 + "'", int0 == 1514884167);
    }

    @Test
    public void test005() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test005");
        java.lang.String str0 = org.apache.zookeeper.server.persistence.FileTxnLog.LOG_FILE_PREFIX;
        org.junit.Assert.assertEquals("'" + str0 + "' != '" + "log" + "'", str0, "log");
    }

    @Test
    public void test006() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test006");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.addPath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (java.lang.IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test007() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test007");
        org.apache.zookeeper.server.persistence.FileTxnLog.setTxnLogSizeLimit((long) (byte) 1);
    }

    @Test
    public void test008() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test008");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean4 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (java.lang.IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test009() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test009");
        org.apache.zookeeper.server.persistence.FileTxnLog.setPreallocSize((long) 1);
    }

    @Test
    public void test010() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test010");
        org.apache.zookeeper.server.persistence.FileTxnLog.setTxnLogSizeLimit((long) (byte) -1);
    }

    @Test
    public void test011() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test011");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        // The following exception was thrown during execution in test generation
        try {
            long long4 = fileTxnLog1.getLastLoggedZxid();
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test012() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test012");
        org.apache.zookeeper.server.persistence.FileTxnLog.setTxnLogSizeLimit((long) (byte) 0);
    }

    @Test
    public void test013() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test013");
        org.apache.zookeeper.server.persistence.FileTxnLog.setPreallocSize((long) (byte) 0);
    }

    @Test
    public void test014() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test014");
        java.io.File file0 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator fileTxnIterator3 = new org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator(file0, (long) 100, true);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test015() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test015");
        java.io.File[] fileArray0 = new java.io.File[] {};
        java.io.File[] fileArray2 = org.apache.zookeeper.server.persistence.FileTxnLog.getLogFiles(fileArray0, (long) (short) -1);
        java.lang.Class<?> wildcardClass3 = fileArray2.getClass();
        org.junit.Assert.assertNotNull(fileArray0);
        org.junit.Assert.assertArrayEquals(fileArray0, new java.io.File[] {});
        org.junit.Assert.assertNotNull(fileArray2);
        org.junit.Assert.assertArrayEquals(fileArray2, new java.io.File[] {});
        org.junit.Assert.assertNotNull(wildcardClass3);
    }

    @Test
    public void test016() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test016");
        long long0 = org.apache.zookeeper.server.persistence.FileTxnLog.getTxnLogSizeLimit();
        org.junit.Assert.assertTrue("'" + long0 + "' != '" + 0L + "'", long0 == 0L);
    }

    @Test
    public void test017() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test017");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        org.apache.zookeeper.server.Request request4 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean5 = fileTxnLog1.append(request4);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.server.Request.getHdr()\" because \"request\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test018() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test018");
        org.apache.zookeeper.server.persistence.FileTxnLog.setTxnLogSizeLimit((long) (short) 0);
    }

    @Test
    public void test019() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test019");
        org.apache.zookeeper.server.persistence.FileTxnLog.setPreallocSize((long) (byte) 1);
    }

    @Test
    public void test020() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test020");
        java.io.File file0 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator fileTxnIterator3 = new org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator(file0, (long) (-1), false);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test021() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test021");
        java.io.File file0 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator fileTxnIterator2 = new org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator(file0, 100L);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test022() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test022");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 'a');
        // The following exception was thrown during execution in test generation
        try {
            long long6 = fileTxnLog1.getLastLoggedZxid();
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test023() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test023");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        // The following exception was thrown during execution in test generation
        try {
            long long5 = fileTxnLog1.getLastLoggedZxid();
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
    }

    @Test
    public void test024() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test024");
        java.io.File file0 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator fileTxnIterator3 = new org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator(file0, (long) (byte) -1, false);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test025() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test025");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        java.lang.Class<?> wildcardClass6 = pathTrie0.getClass();
        org.junit.Assert.assertNotNull(wildcardClass6);
    }

    @Test
    public void test026() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test026");
        java.io.File[] fileArray0 = new java.io.File[] {};
        java.io.File[] fileArray2 = org.apache.zookeeper.server.persistence.FileTxnLog.getLogFiles(fileArray0, (long) (short) -1);
        java.io.File[] fileArray4 = org.apache.zookeeper.server.persistence.FileTxnLog.getLogFiles(fileArray2, (long) 0);
        java.io.File[] fileArray6 = org.apache.zookeeper.server.persistence.FileTxnLog.getLogFiles(fileArray4, (long) (byte) 100);
        org.junit.Assert.assertNotNull(fileArray0);
        org.junit.Assert.assertArrayEquals(fileArray0, new java.io.File[] {});
        org.junit.Assert.assertNotNull(fileArray2);
        org.junit.Assert.assertArrayEquals(fileArray2, new java.io.File[] {});
        org.junit.Assert.assertNotNull(fileArray4);
        org.junit.Assert.assertArrayEquals(fileArray4, new java.io.File[] {});
        org.junit.Assert.assertNotNull(fileArray6);
        org.junit.Assert.assertArrayEquals(fileArray6, new java.io.File[] {});
    }

    @Test
    public void test027() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test027");
        java.io.File file0 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator fileTxnIterator2 = new org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator(file0, (long) (byte) -1);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test028() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test028");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        org.apache.zookeeper.server.Request request5 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean6 = fileTxnLog1.append(request5);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.server.Request.getHdr()\" because \"request\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
    }

    @Test
    public void test029() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test029");
        java.io.File file0 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator fileTxnIterator3 = new org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator(file0, 10L, true);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test030() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test030");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        java.lang.String str7 = pathTrie0.findMaxPrefix("log");
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
    }

    @Test
    public void test031() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test031");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.rollLog();
        java.lang.Class<?> wildcardClass5 = fileTxnLog1.getClass();
        org.junit.Assert.assertNotNull(wildcardClass5);
    }

    @Test
    public void test032() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test032");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.commit();
        fileTxnLog1.rollLog();
        java.lang.Class<?> wildcardClass7 = fileTxnLog1.getClass();
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
        org.junit.Assert.assertNotNull(wildcardClass7);
    }

    @Test
    public void test033() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test033");
        org.apache.zookeeper.server.persistence.FileTxnLog.setTxnLogSizeLimit(100L);
    }

    @Test
    public void test034() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test034");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.close();
        org.apache.zookeeper.server.Request request6 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean7 = fileTxnLog1.append(request6);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.server.Request.getHdr()\" because \"request\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
    }

    @Test
    public void test035() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test035");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.commit();
        fileTxnLog1.rollLog();
        org.apache.zookeeper.server.Request request7 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean8 = fileTxnLog1.append(request7);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.server.Request.getHdr()\" because \"request\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
    }

    @Test
    public void test036() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test036");
        org.apache.zookeeper.server.persistence.FileTxnLog.setPreallocSize((long) (short) 100);
    }

    @Test
    public void test037() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test037");
        java.io.File file0 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator fileTxnIterator2 = new org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator(file0, (long) '4');
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test038() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test038");
        java.io.File file0 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator fileTxnIterator2 = new org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator(file0, (long) 100);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test039() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test039");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean3 = fileTxnLog1.truncate((long) '#');
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test040() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test040");
        java.io.File[] fileArray0 = new java.io.File[] {};
        java.io.File[] fileArray2 = org.apache.zookeeper.server.persistence.FileTxnLog.getLogFiles(fileArray0, (long) (short) -1);
        java.io.File[] fileArray4 = org.apache.zookeeper.server.persistence.FileTxnLog.getLogFiles(fileArray0, (long) 1);
        org.junit.Assert.assertNotNull(fileArray0);
        org.junit.Assert.assertArrayEquals(fileArray0, new java.io.File[] {});
        org.junit.Assert.assertNotNull(fileArray2);
        org.junit.Assert.assertArrayEquals(fileArray2, new java.io.File[] {});
        org.junit.Assert.assertNotNull(fileArray4);
        org.junit.Assert.assertArrayEquals(fileArray4, new java.io.File[] {});
    }

    @Test
    public void test041() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test041");
        java.io.File[] fileArray0 = new java.io.File[] {};
        java.io.File[] fileArray2 = org.apache.zookeeper.server.persistence.FileTxnLog.getLogFiles(fileArray0, (long) (short) -1);
        java.io.File[] fileArray4 = org.apache.zookeeper.server.persistence.FileTxnLog.getLogFiles(fileArray2, (long) 0);
        java.lang.Class<?> wildcardClass5 = fileArray2.getClass();
        org.junit.Assert.assertNotNull(fileArray0);
        org.junit.Assert.assertArrayEquals(fileArray0, new java.io.File[] {});
        org.junit.Assert.assertNotNull(fileArray2);
        org.junit.Assert.assertArrayEquals(fileArray2, new java.io.File[] {});
        org.junit.Assert.assertNotNull(fileArray4);
        org.junit.Assert.assertArrayEquals(fileArray4, new java.io.File[] {});
        org.junit.Assert.assertNotNull(wildcardClass5);
    }

    @Test
    public void test042() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test042");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        boolean boolean5 = pathTrie0.existsNode("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.deletePath("log");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (java.lang.IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + false + "'", boolean5 == false);
    }

    @Test
    public void test043() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test043");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 'a');
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean7 = fileTxnLog1.truncate((long) 100);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test044() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test044");
        org.apache.zookeeper.server.persistence.FileTxnLog.setPreallocSize((long) (short) -1);
    }

    @Test
    public void test045() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test045");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.deletePath("log");
        pathTrie0.addPath("/");
        java.lang.Class<?> wildcardClass7 = pathTrie0.getClass();
        org.junit.Assert.assertNotNull(wildcardClass7);
    }

    @Test
    public void test046() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test046");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.close();
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean8 = fileTxnLog1.truncate((long) (short) 0);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test047() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test047");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.rollLog();
        org.apache.zookeeper.server.Request request5 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean6 = fileTxnLog1.append(request5);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.server.Request.getHdr()\" because \"request\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test048() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test048");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.close();
        fileTxnLog1.setTotalLogSize((long) 1);
        // The following exception was thrown during execution in test generation
        try {
            long long8 = fileTxnLog1.getDbId();
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
    }

    @Test
    public void test049() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test049");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        boolean boolean5 = pathTrie0.existsNode("hi!");
        java.lang.String str7 = pathTrie0.findMaxPrefix("log");
        pathTrie0.deletePath("log");
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + false + "'", boolean5 == false);
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
    }

    @Test
    public void test050() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test050");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.clear();
        java.lang.String str6 = pathTrie0.findMaxPrefix("/");
        org.junit.Assert.assertEquals("'" + str6 + "' != '" + "/" + "'", str6, "/");
    }

    @Test
    public void test051() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test051");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        // The following exception was thrown during execution in test generation
        try {
            long long5 = fileTxnLog1.getDbId();
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
    }

    @Test
    public void test052() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test052");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.rollLog();
        org.apache.zookeeper.server.Request request7 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean8 = fileTxnLog1.append(request7);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.server.Request.getHdr()\" because \"request\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test053() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test053");
        java.io.File file0 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator fileTxnIterator2 = new org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator(file0, 1L);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test054() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test054");
        java.io.File[] fileArray0 = new java.io.File[] {};
        java.io.File[] fileArray2 = org.apache.zookeeper.server.persistence.FileTxnLog.getLogFiles(fileArray0, (long) (short) -1);
        java.lang.Class<?> wildcardClass3 = fileArray0.getClass();
        org.junit.Assert.assertNotNull(fileArray0);
        org.junit.Assert.assertArrayEquals(fileArray0, new java.io.File[] {});
        org.junit.Assert.assertNotNull(fileArray2);
        org.junit.Assert.assertArrayEquals(fileArray2, new java.io.File[] {});
        org.junit.Assert.assertNotNull(wildcardClass3);
    }

    @Test
    public void test055() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test055");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.rollLog();
        // The following exception was thrown during execution in test generation
        try {
            long long7 = fileTxnLog1.getLastLoggedZxid();
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test056() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test056");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.close();
        long long3 = fileTxnLog1.getTxnLogSyncElapsedTime();
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.TxnLog.TxnIterator txnIterator6 = fileTxnLog1.read((long) (byte) 1, false);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long3 + "' != '" + (-1L) + "'", long3 == (-1L));
    }

    @Test
    public void test057() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test057");
        org.apache.zookeeper.server.persistence.FileTxnLog.setPreallocSize((long) (short) 10);
    }

    @Test
    public void test058() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test058");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        boolean boolean5 = pathTrie0.existsNode("hi!");
        java.lang.String str7 = pathTrie0.findMaxPrefix("log");
        pathTrie0.addPath("/");
        pathTrie0.addPath("/");
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + false + "'", boolean5 == false);
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
    }

    @Test
    public void test059() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test059");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        // The following exception was thrown during execution in test generation
        try {
            long long2 = fileTxnLog1.getDbId();
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test060() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test060");
        java.io.File file0 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator fileTxnIterator2 = new org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator(file0, (long) 0);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test061() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test061");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.rollLog();
        org.apache.zookeeper.server.ServerStats serverStats7 = null;
        fileTxnLog1.setServerStats(serverStats7);
        fileTxnLog1.rollLog();
    }

    @Test
    public void test062() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test062");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.rollLog();
        org.apache.zookeeper.server.ServerStats serverStats7 = null;
        fileTxnLog1.setServerStats(serverStats7);
        boolean boolean9 = fileTxnLog1.isForceSync();
        long long10 = fileTxnLog1.getTotalLogSize();
        org.junit.Assert.assertTrue("'" + boolean9 + "' != '" + true + "'", boolean9 == true);
        org.junit.Assert.assertTrue("'" + long10 + "' != '" + 97L + "'", long10 == 97L);
    }

    @Test
    public void test063() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test063");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.rollLog();
        fileTxnLog1.setTotalLogSize((long) (short) 100);
    }

    @Test
    public void test064() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test064");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.TxnLog.TxnIterator txnIterator4 = fileTxnLog1.read((long) (byte) -1, false);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test065() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test065");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.close();
        long long3 = fileTxnLog1.getTxnLogSyncElapsedTime();
        // The following exception was thrown during execution in test generation
        try {
            long long4 = fileTxnLog1.getLastLoggedZxid();
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long3 + "' != '" + (-1L) + "'", long3 == (-1L));
    }

    @Test
    public void test066() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test066");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.close();
        fileTxnLog1.commit();
        java.lang.Class<?> wildcardClass8 = fileTxnLog1.getClass();
        org.junit.Assert.assertNotNull(wildcardClass8);
    }

    @Test
    public void test067() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test067");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        boolean boolean5 = pathTrie0.existsNode("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.deletePath("log");
        java.lang.Class<?> wildcardClass11 = pathTrie0.getClass();
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + false + "'", boolean5 == false);
        org.junit.Assert.assertNotNull(wildcardClass11);
    }

    @Test
    public void test068() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test068");
        java.io.File file0 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator fileTxnIterator2 = new org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator(file0, (long) (short) 0);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test069() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test069");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        boolean boolean5 = pathTrie0.existsNode("hi!");
        java.lang.String str7 = pathTrie0.findMaxPrefix("log");
        pathTrie0.clear();
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + false + "'", boolean5 == false);
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
    }

    @Test
    public void test070() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test070");
        org.apache.zookeeper.server.persistence.FileTxnLog.setTxnLogSizeLimit((long) (byte) 10);
    }

    @Test
    public void test071() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test071");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        long long5 = fileTxnLog1.getTxnLogSyncElapsedTime();
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.TxnLog.TxnIterator txnIterator7 = fileTxnLog1.read(100L);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + (-1L) + "'", long5 == (-1L));
    }

    @Test
    public void test072() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test072");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        boolean boolean5 = pathTrie0.existsNode("hi!");
        java.lang.String str7 = pathTrie0.findMaxPrefix("log");
        java.lang.String str9 = pathTrie0.findMaxPrefix("");
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + false + "'", boolean5 == false);
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
        org.junit.Assert.assertEquals("'" + str9 + "' != '" + "/" + "'", str9, "/");
    }

    @Test
    public void test073() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test073");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        boolean boolean5 = pathTrie0.existsNode("hi!");
        pathTrie0.deletePath("hi!");
        java.lang.String str9 = pathTrie0.findMaxPrefix("");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean11 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (java.lang.IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + false + "'", boolean5 == false);
        org.junit.Assert.assertEquals("'" + str9 + "' != '" + "/" + "'", str9, "/");
    }

    @Test
    public void test074() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test074");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean6 = fileTxnLog1.truncate((long) (short) 10);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
    }

    @Test
    public void test075() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test075");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.close();
        fileTxnLog1.commit();
        fileTxnLog1.close();
        long long9 = fileTxnLog1.getTxnLogSyncElapsedTime();
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.TxnLog.TxnIterator txnIterator12 = fileTxnLog1.read((long) 2, true);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long9 + "' != '" + (-1L) + "'", long9 == (-1L));
    }

    @Test
    public void test076() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test076");
        java.io.File[] fileArray0 = new java.io.File[] {};
        java.io.File[] fileArray2 = org.apache.zookeeper.server.persistence.FileTxnLog.getLogFiles(fileArray0, (long) (short) -1);
        java.io.File[] fileArray4 = org.apache.zookeeper.server.persistence.FileTxnLog.getLogFiles(fileArray2, (long) 0);
        java.io.File[] fileArray6 = org.apache.zookeeper.server.persistence.FileTxnLog.getLogFiles(fileArray2, 0L);
        org.junit.Assert.assertNotNull(fileArray0);
        org.junit.Assert.assertArrayEquals(fileArray0, new java.io.File[] {});
        org.junit.Assert.assertNotNull(fileArray2);
        org.junit.Assert.assertArrayEquals(fileArray2, new java.io.File[] {});
        org.junit.Assert.assertNotNull(fileArray4);
        org.junit.Assert.assertArrayEquals(fileArray4, new java.io.File[] {});
        org.junit.Assert.assertNotNull(fileArray6);
        org.junit.Assert.assertArrayEquals(fileArray6, new java.io.File[] {});
    }

    @Test
    public void test077() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test077");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.close();
        org.apache.zookeeper.server.ServerStats serverStats3 = null;
        fileTxnLog1.setServerStats(serverStats3);
        org.apache.zookeeper.server.ServerStats serverStats5 = null;
        fileTxnLog1.setServerStats(serverStats5);
    }

    @Test
    public void test078() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test078");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 10);
        fileTxnLog1.rollLog();
    }

    @Test
    public void test079() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test079");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 'a');
        // The following exception was thrown during execution in test generation
        try {
            long long6 = fileTxnLog1.getDbId();
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test080() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test080");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) (byte) 1);
        fileTxnLog1.rollLog();
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.TxnLog.TxnIterator txnIterator6 = fileTxnLog1.read((-1L));
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test081() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test081");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) (byte) 1);
        fileTxnLog1.rollLog();
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean6 = fileTxnLog1.truncate((long) (byte) 100);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test082() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test082");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        boolean boolean5 = pathTrie0.existsNode("hi!");
        java.lang.String str7 = pathTrie0.findMaxPrefix("log");
        java.lang.Class<?> wildcardClass8 = pathTrie0.getClass();
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + false + "'", boolean5 == false);
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
        org.junit.Assert.assertNotNull(wildcardClass8);
    }

    @Test
    public void test083() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test083");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (java.lang.IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test084() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test084");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.commit();
        fileTxnLog1.rollLog();
        long long7 = fileTxnLog1.getTxnLogSyncElapsedTime();
        org.apache.zookeeper.server.ServerStats serverStats8 = null;
        fileTxnLog1.setServerStats(serverStats8);
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
        org.junit.Assert.assertTrue("'" + long7 + "' != '" + (-1L) + "'", long7 == (-1L));
    }

    @Test
    public void test085() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test085");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.commit();
        fileTxnLog1.rollLog();
        long long7 = fileTxnLog1.getTxnLogSyncElapsedTime();
        java.lang.Class<?> wildcardClass8 = fileTxnLog1.getClass();
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
        org.junit.Assert.assertTrue("'" + long7 + "' != '" + (-1L) + "'", long7 == (-1L));
        org.junit.Assert.assertNotNull(wildcardClass8);
    }

    @Test
    public void test086() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test086");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        boolean boolean5 = pathTrie0.existsNode("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.clear();
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + false + "'", boolean5 == false);
    }

    @Test
    public void test087() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test087");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) (byte) 1);
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
    }

    @Test
    public void test088() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test088");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.close();
        fileTxnLog1.setTotalLogSize((long) 1);
        fileTxnLog1.commit();
        org.apache.zookeeper.server.ServerStats serverStats9 = null;
        fileTxnLog1.setServerStats(serverStats9);
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
    }

    @Test
    public void test089() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test089");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        java.lang.Class<?> wildcardClass4 = fileTxnLog1.getClass();
        org.junit.Assert.assertNotNull(wildcardClass4);
    }

    @Test
    public void test090() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test090");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.close();
        org.apache.zookeeper.server.ServerStats serverStats3 = null;
        fileTxnLog1.setServerStats(serverStats3);
        long long5 = fileTxnLog1.getCurrentLogSize();
        long long6 = fileTxnLog1.getCurrentLogSize();
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + 0L + "'", long5 == 0L);
        org.junit.Assert.assertTrue("'" + long6 + "' != '" + 0L + "'", long6 == 0L);
    }

    @Test
    public void test091() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test091");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 100);
        // The following exception was thrown during execution in test generation
        try {
            long long6 = fileTxnLog1.getDbId();
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test092() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test092");
        java.io.File[] fileArray0 = new java.io.File[] {};
        java.io.File[] fileArray2 = org.apache.zookeeper.server.persistence.FileTxnLog.getLogFiles(fileArray0, (long) (short) -1);
        java.io.File[] fileArray4 = org.apache.zookeeper.server.persistence.FileTxnLog.getLogFiles(fileArray0, (long) 'a');
        java.io.File[] fileArray6 = org.apache.zookeeper.server.persistence.FileTxnLog.getLogFiles(fileArray4, 10L);
        org.junit.Assert.assertNotNull(fileArray0);
        org.junit.Assert.assertArrayEquals(fileArray0, new java.io.File[] {});
        org.junit.Assert.assertNotNull(fileArray2);
        org.junit.Assert.assertArrayEquals(fileArray2, new java.io.File[] {});
        org.junit.Assert.assertNotNull(fileArray4);
        org.junit.Assert.assertArrayEquals(fileArray4, new java.io.File[] {});
        org.junit.Assert.assertNotNull(fileArray6);
        org.junit.Assert.assertArrayEquals(fileArray6, new java.io.File[] {});
    }

    @Test
    public void test093() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test093");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.deletePath("log");
        pathTrie0.addPath("/");
        boolean boolean8 = pathTrie0.existsNode("/");
        java.lang.String str10 = pathTrie0.findMaxPrefix("");
        pathTrie0.clear();
        org.junit.Assert.assertTrue("'" + boolean8 + "' != '" + true + "'", boolean8 == true);
        org.junit.Assert.assertEquals("'" + str10 + "' != '" + "/" + "'", str10, "/");
    }

    @Test
    public void test094() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test094");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.deletePath("log");
        pathTrie0.addPath("/");
        boolean boolean8 = pathTrie0.existsNode("/");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (java.lang.IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean8 + "' != '" + true + "'", boolean8 == true);
    }

    @Test
    public void test095() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test095");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        long long5 = fileTxnLog1.getTxnLogSyncElapsedTime();
        long long6 = fileTxnLog1.getTotalLogSize();
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + (-1L) + "'", long5 == (-1L));
        org.junit.Assert.assertTrue("'" + long6 + "' != '" + 97L + "'", long6 == 97L);
    }

    @Test
    public void test096() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test096");
        org.apache.zookeeper.server.persistence.FileTxnLog.setPreallocSize((long) (byte) 10);
    }

    @Test
    public void test097() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test097");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.deletePath("log");
        pathTrie0.addPath("/");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.addPath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (java.lang.IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test098() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test098");
        java.io.File file0 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator fileTxnIterator3 = new org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator(file0, (long) (short) 100, false);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test099() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test099");
        org.apache.zookeeper.server.persistence.FileTxnLog.setTxnLogSizeLimit(10L);
    }

    @Test
    public void test100() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test100");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.rollLog();
        long long6 = fileTxnLog1.getCurrentLogSize();
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
        org.junit.Assert.assertTrue("'" + long6 + "' != '" + 0L + "'", long6 == 0L);
    }

    @Test
    public void test101() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test101");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.commit();
        org.apache.zookeeper.server.Request request3 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean4 = fileTxnLog1.append(request3);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.server.Request.getHdr()\" because \"request\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test102() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test102");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        org.apache.zookeeper.server.ServerStats serverStats4 = null;
        fileTxnLog1.setServerStats(serverStats4);
    }

    @Test
    public void test103() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test103");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.close();
        fileTxnLog1.commit();
        fileTxnLog1.close();
        // The following exception was thrown during execution in test generation
        try {
            long long9 = fileTxnLog1.getDbId();
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test104() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test104");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        boolean boolean5 = pathTrie0.existsNode("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (java.lang.IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + false + "'", boolean5 == false);
    }

    @Test
    public void test105() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test105");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.commit();
        fileTxnLog1.rollLog();
        long long7 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.close();
        fileTxnLog1.commit();
        // The following exception was thrown during execution in test generation
        try {
            long long10 = fileTxnLog1.getLastLoggedZxid();
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
        org.junit.Assert.assertTrue("'" + long7 + "' != '" + (-1L) + "'", long7 == (-1L));
    }

    @Test
    public void test106() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test106");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 100);
        fileTxnLog1.setTotalLogSize((long) 100);
    }

    @Test
    public void test107() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test107");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.close();
        fileTxnLog1.setTotalLogSize((long) 1);
        fileTxnLog1.commit();
        fileTxnLog1.rollLog();
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.TxnLog.TxnIterator txnIterator11 = fileTxnLog1.read((long) (short) 0);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
    }

    @Test
    public void test108() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test108");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.rollLog();
        org.apache.zookeeper.server.ServerStats serverStats7 = null;
        fileTxnLog1.setServerStats(serverStats7);
        org.apache.zookeeper.server.Request request9 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean10 = fileTxnLog1.append(request9);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.server.Request.getHdr()\" because \"request\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test109() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test109");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) (byte) 1);
        fileTxnLog1.rollLog();
        fileTxnLog1.close();
    }

    @Test
    public void test110() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test110");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.deletePath("log");
        pathTrie0.deletePath("log");
        pathTrie0.clear();
        pathTrie0.clear();
    }

    @Test
    public void test111() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test111");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.deletePath("log");
        pathTrie0.addPath("/");
        boolean boolean8 = pathTrie0.existsNode("/");
        java.lang.String str10 = pathTrie0.findMaxPrefix("");
        boolean boolean12 = pathTrie0.existsNode("log");
        org.junit.Assert.assertTrue("'" + boolean8 + "' != '" + true + "'", boolean8 == true);
        org.junit.Assert.assertEquals("'" + str10 + "' != '" + "/" + "'", str10, "/");
        org.junit.Assert.assertTrue("'" + boolean12 + "' != '" + false + "'", boolean12 == false);
    }

    @Test
    public void test112() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test112");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.close();
        org.apache.zookeeper.server.ServerStats serverStats3 = null;
        fileTxnLog1.setServerStats(serverStats3);
        org.apache.zookeeper.server.Request request5 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean6 = fileTxnLog1.append(request5);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.server.Request.getHdr()\" because \"request\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test113() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test113");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.commit();
        fileTxnLog1.rollLog();
        long long7 = fileTxnLog1.getTxnLogSyncElapsedTime();
        org.apache.zookeeper.server.Request request8 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean9 = fileTxnLog1.append(request8);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.server.Request.getHdr()\" because \"request\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
        org.junit.Assert.assertTrue("'" + long7 + "' != '" + (-1L) + "'", long7 == (-1L));
    }

    @Test
    public void test114() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test114");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.commit();
        fileTxnLog1.rollLog();
        long long7 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.close();
        fileTxnLog1.commit();
        fileTxnLog1.close();
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
        org.junit.Assert.assertTrue("'" + long7 + "' != '" + (-1L) + "'", long7 == (-1L));
    }

    @Test
    public void test115() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test115");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.close();
        fileTxnLog1.commit();
        java.lang.Class<?> wildcardClass4 = fileTxnLog1.getClass();
        org.junit.Assert.assertNotNull(wildcardClass4);
    }

    @Test
    public void test116() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test116");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        long long5 = fileTxnLog1.getTxnLogSyncElapsedTime();
        long long6 = fileTxnLog1.getTxnLogSyncElapsedTime();
        boolean boolean7 = fileTxnLog1.isForceSync();
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + (-1L) + "'", long5 == (-1L));
        org.junit.Assert.assertTrue("'" + long6 + "' != '" + (-1L) + "'", long6 == (-1L));
        org.junit.Assert.assertTrue("'" + boolean7 + "' != '" + true + "'", boolean7 == true);
    }

    @Test
    public void test117() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test117");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 10);
        fileTxnLog1.commit();
        // The following exception was thrown during execution in test generation
        try {
            long long5 = fileTxnLog1.getLastLoggedZxid();
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test118() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test118");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 10);
        org.apache.zookeeper.server.Request request4 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean5 = fileTxnLog1.append(request4);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.server.Request.getHdr()\" because \"request\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test119() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test119");
        java.io.File file0 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator fileTxnIterator3 = new org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator(file0, (long) 10, true);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test120() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test120");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.close();
        fileTxnLog1.commit();
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.TxnLog.TxnIterator txnIterator9 = fileTxnLog1.read((long) 'a');
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test121() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test121");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.close();
        boolean boolean3 = fileTxnLog1.isForceSync();
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.commit();
        org.junit.Assert.assertTrue("'" + boolean3 + "' != '" + true + "'", boolean3 == true);
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
    }

    @Test
    public void test122() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test122");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.close();
        fileTxnLog1.close();
    }

    @Test
    public void test123() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test123");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.rollLog();
        long long6 = fileTxnLog1.getTotalLogSize();
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
        org.junit.Assert.assertTrue("'" + long6 + "' != '" + 97L + "'", long6 == 97L);
    }

    @Test
    public void test124() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test124");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        boolean boolean5 = pathTrie0.existsNode("hi!");
        pathTrie0.deletePath("hi!");
        java.lang.String str9 = pathTrie0.findMaxPrefix("");
        pathTrie0.addPath("log");
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + false + "'", boolean5 == false);
        org.junit.Assert.assertEquals("'" + str9 + "' != '" + "/" + "'", str9, "/");
    }

    @Test
    public void test125() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test125");
        java.io.File file0 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator fileTxnIterator3 = new org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator(file0, (long) '#', true);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test126() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test126");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        boolean boolean5 = pathTrie0.existsNode("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.deletePath("log");
        pathTrie0.clear();
        boolean boolean13 = pathTrie0.existsNode("log");
        java.lang.String str15 = pathTrie0.findMaxPrefix("hi!");
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + false + "'", boolean5 == false);
        org.junit.Assert.assertTrue("'" + boolean13 + "' != '" + false + "'", boolean13 == false);
        org.junit.Assert.assertEquals("'" + str15 + "' != '" + "/" + "'", str15, "/");
    }

    @Test
    public void test127() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test127");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.close();
        fileTxnLog1.commit();
        long long8 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.setTotalLogSize((-1L));
        org.junit.Assert.assertTrue("'" + long8 + "' != '" + (-1L) + "'", long8 == (-1L));
    }

    @Test
    public void test128() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test128");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) (byte) 1);
        fileTxnLog1.rollLog();
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.TxnLog.TxnIterator txnIterator7 = fileTxnLog1.read((long) 1514884167, true);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test129() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test129");
        java.io.File file0 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator fileTxnIterator3 = new org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator(file0, 100L, false);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.File.listFiles()\" because \"this.logDir\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test130() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test130");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 10);
        fileTxnLog1.commit();
        fileTxnLog1.commit();
    }

    @Test
    public void test131() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test131");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        java.lang.String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.addPath("/");
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
    }

    @Test
    public void test132() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test132");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        long long4 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.commit();
        long long6 = fileTxnLog1.getCurrentLogSize();
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + (-1L) + "'", long4 == (-1L));
        org.junit.Assert.assertTrue("'" + long6 + "' != '" + 0L + "'", long6 == 0L);
    }

    @Test
    public void test133() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test133");
        java.io.File file0 = null;
        org.apache.zookeeper.server.persistence.FileTxnLog fileTxnLog1 = new org.apache.zookeeper.server.persistence.FileTxnLog(file0);
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.setTotalLogSize((long) 'a');
        fileTxnLog1.rollLog();
        org.apache.zookeeper.server.ServerStats serverStats7 = null;
        fileTxnLog1.setServerStats(serverStats7);
        long long9 = fileTxnLog1.getTxnLogSyncElapsedTime();
        fileTxnLog1.commit();
        org.junit.Assert.assertTrue("'" + long9 + "' != '" + (-1L) + "'", long9 == (-1L));
    }

    @Test
    public void test134() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test134");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        boolean boolean5 = pathTrie0.existsNode("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.deletePath("log");
        java.lang.String str11 = pathTrie0.findMaxPrefix("");
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + false + "'", boolean5 == false);
        org.junit.Assert.assertEquals("'" + str11 + "' != '" + "/" + "'", str11, "/");
    }
}

