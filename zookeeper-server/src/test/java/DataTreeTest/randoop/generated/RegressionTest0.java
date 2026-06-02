package DataTreeTest.randoop.generated;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.jupiter.api.Disabled;
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
    public void test003() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test003");
        int int0 = org.apache.zookeeper.server.DataTree.STAT_OVERHEAD_BYTES;
        org.junit.Assert.assertTrue("'" + int0 + "' != '" + 68 + "'", int0 == 68);
    }

    @Test
    public void test004() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test004");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.jute.OutputArchive outputArchive2 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean3 = dataTree0.serializeZxidDigest(outputArchive2);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeLong(long, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
    }

    @Test
    public void test005() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test005");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.txn.TxnHeader txnHeader4 = null;
        org.apache.zookeeper.data.StatPersisted statPersisted8 = org.apache.zookeeper.server.DataTree.createStat((-1L), (-1L), 1L);
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult9 = dataTree0.processTxn(txnHeader4, (org.apache.jute.Record) statPersisted8);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getClientId()\" because \"header\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(statPersisted8);
    }

    @Test
    public void test006() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test006");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.jute.InputArchive inputArchive2 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean3 = dataTree0.deserializeLastProcessedZxid(inputArchive2);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.InputArchive.readLong(String)\" because \"ia\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
    }

    @Test
    public void test007() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test007");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.jute.InputArchive inputArchive1 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean3 = dataTree0.deserializeZxidDigest(inputArchive1, (long) (byte) 10);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.InputArchive.readLong(String)\" because \"ia\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test008() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test008");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.deleteNode("hi!", (long) (short) 100);
            org.junit.Assert.fail("Expected exception of type java.lang.StringIndexOutOfBoundsException; message: Range [0, -1) out of bounds for length 3");
        } catch (StringIndexOutOfBoundsException e) {
            // Expected exception.
        }
    }

    @Test
    public void test009() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test009");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.jute.OutputArchive outputArchive2 = null;
        org.apache.zookeeper.server.DataTree dataTree4 = new org.apache.zookeeper.server.DataTree();
        long long5 = dataTree4.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher7 = null;
        org.apache.zookeeper.data.Stat stat8 = dataTree4.statNode("", watcher7);
        org.apache.zookeeper.server.DataNode dataNode10 = dataTree4.getNode("");
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeNodeData(outputArchive2, "", dataNode10);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeString(String, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + 0L + "'", long5 == 0L);
        org.junit.Assert.assertNotNull(stat8);
        org.junit.Assert.assertNotNull(dataNode10);
    }

    @Test
    public void test010() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test010");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String[] strArray4 = new String[] { "" };
        java.util.ArrayList<String> strList5 = new java.util.ArrayList<String>();
        boolean boolean6 = java.util.Collections.addAll((java.util.Collection<String>) strList5, strArray4);
        String[] strArray8 = new String[] { "hi!" };
        java.util.ArrayList<String> strList9 = new java.util.ArrayList<String>();
        boolean boolean10 = java.util.Collections.addAll((java.util.Collection<String>) strList9, strArray8);
        java.util.List<String> strList11 = null;
        String[] strArray14 = new String[] { "hi!", "" };
        java.util.ArrayList<String> strList15 = new java.util.ArrayList<String>();
        boolean boolean16 = java.util.Collections.addAll((java.util.Collection<String>) strList15, strArray14);
        String[] strArray19 = new String[] { "hi!", "" };
        java.util.ArrayList<String> strList20 = new java.util.ArrayList<String>();
        boolean boolean21 = java.util.Collections.addAll((java.util.Collection<String>) strList20, strArray19);
        org.apache.zookeeper.Watcher watcher22 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.setWatches((long) 0, (java.util.List<String>) strList5, (java.util.List<String>) strList9, strList11, (java.util.List<String>) strList15, (java.util.List<String>) strList20, watcher22);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.util.List.iterator()\" because \"childWatches\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(strArray4);
        org.junit.Assert.assertArrayEquals(strArray4, new String[] { "" });
        org.junit.Assert.assertTrue("'" + boolean6 + "' != '" + true + "'", boolean6 == true);
        org.junit.Assert.assertNotNull(strArray8);
        org.junit.Assert.assertArrayEquals(strArray8, new String[] { "hi!" });
        org.junit.Assert.assertTrue("'" + boolean10 + "' != '" + true + "'", boolean10 == true);
        org.junit.Assert.assertNotNull(strArray14);
        org.junit.Assert.assertArrayEquals(strArray14, new String[] { "hi!", "" });
        org.junit.Assert.assertTrue("'" + boolean16 + "' != '" + true + "'", boolean16 == true);
        org.junit.Assert.assertNotNull(strArray19);
        org.junit.Assert.assertArrayEquals(strArray19, new String[] { "hi!", "" });
        org.junit.Assert.assertTrue("'" + boolean21 + "' != '" + true + "'", boolean21 == true);
    }



    @Test
    public void test012() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test012");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        byte[] byteArray9 = new byte[] { (byte) 100, (byte) 1, (byte) -1 };
        org.apache.zookeeper.data.ACL[] aCLArray10 = new org.apache.zookeeper.data.ACL[] {};
        java.util.ArrayList<org.apache.zookeeper.data.ACL> aCLList11 = new java.util.ArrayList<org.apache.zookeeper.data.ACL>();
        boolean boolean12 = java.util.Collections.addAll((java.util.Collection<org.apache.zookeeper.data.ACL>) aCLList11, aCLArray10);
        org.apache.zookeeper.server.DataTree dataTree17 = new org.apache.zookeeper.server.DataTree();
        long long18 = dataTree17.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher20 = null;
        org.apache.zookeeper.data.Stat stat21 = dataTree17.statNode("", watcher20);
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.createNode("", byteArray9, (java.util.List<org.apache.zookeeper.data.ACL>) aCLList11, (long) (byte) 1, 100, (long) (short) 1, (long) 10, stat21);
            org.junit.Assert.fail("Expected exception of type java.lang.StringIndexOutOfBoundsException; message: Range [0, -1) out of bounds for length 0");
        } catch (StringIndexOutOfBoundsException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNotNull(byteArray9);
        org.junit.Assert.assertArrayEquals(byteArray9, new byte[] { (byte) 100, (byte) 1, (byte) -1 });
        org.junit.Assert.assertNotNull(aCLArray10);
        org.junit.Assert.assertArrayEquals(aCLArray10, new org.apache.zookeeper.data.ACL[] {});
        org.junit.Assert.assertTrue("'" + boolean12 + "' != '" + false + "'", boolean12 == false);
        org.junit.Assert.assertTrue("'" + long18 + "' != '" + 0L + "'", long18 == 0L);
        org.junit.Assert.assertNotNull(stat21);
    }

    @Test
    public void test013() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test013");
        int int0 = org.apache.zookeeper.server.DataTree.DIGEST_LOG_INTERVAL;
        org.junit.Assert.assertTrue("'" + int0 + "' != '" + 128 + "'", int0 == 128);
    }

    @Test
    public void test014() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test014");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        String str6 = dataTree0.getMaxPrefixWithQuota("hi!");
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.compareSnapshotDigests((long) 68);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot read field \"zxid\" because \"this.digestFromLoadedSnapshot\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNull(str6);
    }

    @Test
    public void test015() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test015");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        java.util.Set<String> strSet4 = dataTree0.getContainers();
        org.apache.zookeeper.Watcher.WatcherType watcherType6 = null;
        org.apache.zookeeper.Watcher watcher7 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean8 = dataTree0.removeWatch("hi!", watcherType6, watcher7);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher$WatcherType.ordinal()\" because \"type\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(strSet4);
    }

    @Test
    public void test016() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test016");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        String[] strArray5 = new String[] { "hi!" };
        java.util.ArrayList<String> strList6 = new java.util.ArrayList<String>();
        boolean boolean7 = java.util.Collections.addAll((java.util.Collection<String>) strList6, strArray5);
        String[] strArray9 = new String[] { "hi!" };
        java.util.ArrayList<String> strList10 = new java.util.ArrayList<String>();
        boolean boolean11 = java.util.Collections.addAll((java.util.Collection<String>) strList10, strArray9);
        String[] strArray14 = new String[] { "hi!", "hi!" };
        java.util.ArrayList<String> strList15 = new java.util.ArrayList<String>();
        boolean boolean16 = java.util.Collections.addAll((java.util.Collection<String>) strList15, strArray14);
        String[] strArray19 = new String[] { "", "hi!" };
        java.util.ArrayList<String> strList20 = new java.util.ArrayList<String>();
        boolean boolean21 = java.util.Collections.addAll((java.util.Collection<String>) strList20, strArray19);
        String[] strArray24 = new String[] { "", "" };
        java.util.ArrayList<String> strList25 = new java.util.ArrayList<String>();
        boolean boolean26 = java.util.Collections.addAll((java.util.Collection<String>) strList25, strArray24);
        org.apache.zookeeper.Watcher watcher27 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.setWatches(10L, (java.util.List<String>) strList6, (java.util.List<String>) strList10, (java.util.List<String>) strList15, (java.util.List<String>) strList20, (java.util.List<String>) strList25, watcher27);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher.process(org.apache.zookeeper.WatchedEvent)\" because \"watcher\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertNotNull(strArray5);
        org.junit.Assert.assertArrayEquals(strArray5, new String[] { "hi!" });
        org.junit.Assert.assertTrue("'" + boolean7 + "' != '" + true + "'", boolean7 == true);
        org.junit.Assert.assertNotNull(strArray9);
        org.junit.Assert.assertArrayEquals(strArray9, new String[] { "hi!" });
        org.junit.Assert.assertTrue("'" + boolean11 + "' != '" + true + "'", boolean11 == true);
        org.junit.Assert.assertNotNull(strArray14);
        org.junit.Assert.assertArrayEquals(strArray14, new String[] { "hi!", "hi!" });
        org.junit.Assert.assertTrue("'" + boolean16 + "' != '" + true + "'", boolean16 == true);
        org.junit.Assert.assertNotNull(strArray19);
        org.junit.Assert.assertArrayEquals(strArray19, new String[] { "", "hi!" });
        org.junit.Assert.assertTrue("'" + boolean21 + "' != '" + true + "'", boolean21 == true);
        org.junit.Assert.assertNotNull(strArray24);
        org.junit.Assert.assertArrayEquals(strArray24, new String[] { "", "" });
        org.junit.Assert.assertTrue("'" + boolean26 + "' != '" + true + "'", boolean26 == true);
    }

    @Test
    public void test017() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test017");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        String str6 = dataTree0.getMaxPrefixWithQuota("hi!");
        String[] strArray9 = new String[] { "hi!" };
        java.util.ArrayList<String> strList10 = new java.util.ArrayList<String>();
        boolean boolean11 = java.util.Collections.addAll((java.util.Collection<String>) strList10, strArray9);
        String[] strArray13 = new String[] { "hi!" };
        java.util.ArrayList<String> strList14 = new java.util.ArrayList<String>();
        boolean boolean15 = java.util.Collections.addAll((java.util.Collection<String>) strList14, strArray13);
        String[] strArray17 = new String[] { "hi!" };
        java.util.ArrayList<String> strList18 = new java.util.ArrayList<String>();
        boolean boolean19 = java.util.Collections.addAll((java.util.Collection<String>) strList18, strArray17);
        String[] strArray21 = new String[] { "" };
        java.util.ArrayList<String> strList22 = new java.util.ArrayList<String>();
        boolean boolean23 = java.util.Collections.addAll((java.util.Collection<String>) strList22, strArray21);
        String[] strArray25 = new String[] { "hi!" };
        java.util.ArrayList<String> strList26 = new java.util.ArrayList<String>();
        boolean boolean27 = java.util.Collections.addAll((java.util.Collection<String>) strList26, strArray25);
        org.apache.zookeeper.Watcher watcher28 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.setWatches((long) 68, (java.util.List<String>) strList10, (java.util.List<String>) strList14, (java.util.List<String>) strList18, (java.util.List<String>) strList22, (java.util.List<String>) strList26, watcher28);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher.process(org.apache.zookeeper.WatchedEvent)\" because \"watcher\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNull(str6);
        org.junit.Assert.assertNotNull(strArray9);
        org.junit.Assert.assertArrayEquals(strArray9, new String[] { "hi!" });
        org.junit.Assert.assertTrue("'" + boolean11 + "' != '" + true + "'", boolean11 == true);
        org.junit.Assert.assertNotNull(strArray13);
        org.junit.Assert.assertArrayEquals(strArray13, new String[] { "hi!" });
        org.junit.Assert.assertTrue("'" + boolean15 + "' != '" + true + "'", boolean15 == true);
        org.junit.Assert.assertNotNull(strArray17);
        org.junit.Assert.assertArrayEquals(strArray17, new String[] { "hi!" });
        org.junit.Assert.assertTrue("'" + boolean19 + "' != '" + true + "'", boolean19 == true);
        org.junit.Assert.assertNotNull(strArray21);
        org.junit.Assert.assertArrayEquals(strArray21, new String[] { "" });
        org.junit.Assert.assertTrue("'" + boolean23 + "' != '" + true + "'", boolean23 == true);
        org.junit.Assert.assertNotNull(strArray25);
        org.junit.Assert.assertArrayEquals(strArray25, new String[] { "hi!" });
        org.junit.Assert.assertTrue("'" + boolean27 + "' != '" + true + "'", boolean27 == true);
    }

    @Test
    public void test018() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test018");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        java.util.Set<String> strSet2 = dataTree0.getEphemerals((long) 10);
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList3 = dataTree0.getDigestLog();
        org.junit.Assert.assertNotNull(strSet2);
        org.junit.Assert.assertNotNull(zxidDigestList3);
    }

    @Test
    public void test019() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test019");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        org.apache.jute.OutputArchive outputArchive3 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean4 = dataTree0.serializeZxidDigest(outputArchive3);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeLong(long, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
    }

    @Test
    public void test020() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test020");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String[] strArray5 = new String[] { "", "" };
        java.util.ArrayList<String> strList6 = new java.util.ArrayList<String>();
        boolean boolean7 = java.util.Collections.addAll((java.util.Collection<String>) strList6, strArray5);
        String[] strArray10 = new String[] { "hi!", "" };
        java.util.ArrayList<String> strList11 = new java.util.ArrayList<String>();
        boolean boolean12 = java.util.Collections.addAll((java.util.Collection<String>) strList11, strArray10);
        String[] strArray15 = new String[] { "", "hi!" };
        java.util.ArrayList<String> strList16 = new java.util.ArrayList<String>();
        boolean boolean17 = java.util.Collections.addAll((java.util.Collection<String>) strList16, strArray15);
        String[] strArray19 = new String[] { "" };
        java.util.ArrayList<String> strList20 = new java.util.ArrayList<String>();
        boolean boolean21 = java.util.Collections.addAll((java.util.Collection<String>) strList20, strArray19);
        String[] strArray24 = new String[] { "", "hi!" };
        java.util.ArrayList<String> strList25 = new java.util.ArrayList<String>();
        boolean boolean26 = java.util.Collections.addAll((java.util.Collection<String>) strList25, strArray24);
        org.apache.zookeeper.Watcher watcher27 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.setWatches((long) (byte) -1, (java.util.List<String>) strList6, (java.util.List<String>) strList11, (java.util.List<String>) strList16, (java.util.List<String>) strList20, (java.util.List<String>) strList25, watcher27);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher.process(org.apache.zookeeper.WatchedEvent)\" because \"watcher\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(strArray5);
        org.junit.Assert.assertArrayEquals(strArray5, new String[] { "", "" });
        org.junit.Assert.assertTrue("'" + boolean7 + "' != '" + true + "'", boolean7 == true);
        org.junit.Assert.assertNotNull(strArray10);
        org.junit.Assert.assertArrayEquals(strArray10, new String[] { "hi!", "" });
        org.junit.Assert.assertTrue("'" + boolean12 + "' != '" + true + "'", boolean12 == true);
        org.junit.Assert.assertNotNull(strArray15);
        org.junit.Assert.assertArrayEquals(strArray15, new String[] { "", "hi!" });
        org.junit.Assert.assertTrue("'" + boolean17 + "' != '" + true + "'", boolean17 == true);
        org.junit.Assert.assertNotNull(strArray19);
        org.junit.Assert.assertArrayEquals(strArray19, new String[] { "" });
        org.junit.Assert.assertTrue("'" + boolean21 + "' != '" + true + "'", boolean21 == true);
        org.junit.Assert.assertNotNull(strArray24);
        org.junit.Assert.assertArrayEquals(strArray24, new String[] { "", "hi!" });
        org.junit.Assert.assertTrue("'" + boolean26 + "' != '" + true + "'", boolean26 == true);
    }

    @Test
    public void test021() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test021");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        int int1 = dataTree0.getEphemeralsCount();
        org.apache.zookeeper.server.DataTree dataTree3 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree3.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary5 = dataTree3.getWatchesSummary();
        byte[] byteArray12 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat16 = dataTree3.setData("", byteArray12, (int) (short) -1, (long) (byte) 10, (long) 'a');
        // The following exception was thrown during execution in test generation
        try {
            java.util.List<org.apache.zookeeper.data.ACL> aCLList17 = dataTree0.getACL("hi!", stat16);
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int1 + "' != '" + 0 + "'", int1 == 0);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
        org.junit.Assert.assertNotNull(watchesSummary5);
        org.junit.Assert.assertNotNull(byteArray12);
        org.junit.Assert.assertArrayEquals(byteArray12, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat16);
    }

    @Test
    public void test022() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test022");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        dataTree0.addConfigNode();
        org.apache.zookeeper.Watcher watcher7 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.data.Stat stat8 = dataTree0.statNode("hi!", watcher7);
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
    }

    @Test
    public void test023() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test023");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        java.io.PrintWriter printWriter5 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.dumpWatchesSummary(printWriter5);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.PrintWriter.print(String)\" because \"writer\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
    }

    @Test
    public void test024() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test024");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        byte[] byteArray3 = new byte[] {};
        java.util.List<org.apache.zookeeper.data.ACL> aCLList4 = null;
        org.apache.zookeeper.server.DataTree dataTree9 = new org.apache.zookeeper.server.DataTree();
        long long10 = dataTree9.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher12 = null;
        org.apache.zookeeper.data.Stat stat13 = dataTree9.statNode("", watcher12);
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.createNode("hi!", byteArray3, aCLList4, (long) 68, 0, (long) ' ', (long) 68, stat13);
            org.junit.Assert.fail("Expected exception of type java.lang.StringIndexOutOfBoundsException; message: Range [0, -1) out of bounds for length 3");
        } catch (StringIndexOutOfBoundsException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(byteArray3);
        org.junit.Assert.assertArrayEquals(byteArray3, new byte[] {});
        org.junit.Assert.assertTrue("'" + long10 + "' != '" + 0L + "'", long10 == 0L);
        org.junit.Assert.assertNotNull(stat13);
    }

    @Test
    public void test025() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test025");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        int int3 = dataTree0.getWatchCount();
        org.apache.zookeeper.data.Stat stat5 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList6 = dataTree0.getACL("", stat5);
        java.util.Set<String> strSet8 = dataTree0.getEphemerals((long) (byte) 0);
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertTrue("'" + int3 + "' != '" + 0 + "'", int3 == 0);
        org.junit.Assert.assertNotNull(aCLList6);
        org.junit.Assert.assertNotNull(strSet8);
    }

    @Test
    public void test026() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test026");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        org.apache.zookeeper.server.DataTree dataTree6 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache7 = dataTree6.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary8 = dataTree6.getWatchesSummary();
        byte[] byteArray15 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat19 = dataTree6.setData("", byteArray15, (int) (short) -1, (long) (byte) 10, (long) 'a');
        org.apache.zookeeper.data.ACL[] aCLArray20 = new org.apache.zookeeper.data.ACL[] {};
        java.util.ArrayList<org.apache.zookeeper.data.ACL> aCLList21 = new java.util.ArrayList<org.apache.zookeeper.data.ACL>();
        boolean boolean22 = java.util.Collections.addAll((java.util.Collection<org.apache.zookeeper.data.ACL>) aCLList21, aCLArray20);
        org.apache.zookeeper.server.DataTree dataTree27 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache28 = dataTree27.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary29 = dataTree27.getWatchesSummary();
        byte[] byteArray36 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat40 = dataTree27.setData("", byteArray36, (int) (short) -1, (long) (byte) 10, (long) 'a');
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.createNode("hi!", byteArray15, (java.util.List<org.apache.zookeeper.data.ACL>) aCLList21, (long) 0, (int) (short) -1, (long) '#', (long) (byte) -1, stat40);
            org.junit.Assert.fail("Expected exception of type java.lang.StringIndexOutOfBoundsException; message: Range [0, -1) out of bounds for length 3");
        } catch (StringIndexOutOfBoundsException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNotNull(referenceCountedACLCache7);
        org.junit.Assert.assertNotNull(watchesSummary8);
        org.junit.Assert.assertNotNull(byteArray15);
        org.junit.Assert.assertArrayEquals(byteArray15, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat19);
        org.junit.Assert.assertNotNull(aCLArray20);
        org.junit.Assert.assertArrayEquals(aCLArray20, new org.apache.zookeeper.data.ACL[] {});
        org.junit.Assert.assertTrue("'" + boolean22 + "' != '" + false + "'", boolean22 == false);
        org.junit.Assert.assertNotNull(referenceCountedACLCache28);
        org.junit.Assert.assertNotNull(watchesSummary29);
        org.junit.Assert.assertNotNull(byteArray36);
        org.junit.Assert.assertArrayEquals(byteArray36, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat40);
    }

    @Test
    public void test027() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test027");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.jute.OutputArchive outputArchive2 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeNodes(outputArchive2);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeString(String, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
    }

    @Test
    public void test028() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test028");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.cachedApproximateDataSize();
        org.apache.zookeeper.txn.TxnHeader txnHeader2 = null;
        org.apache.zookeeper.server.DataTree dataTree3 = new org.apache.zookeeper.server.DataTree();
        long long4 = dataTree3.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher6 = null;
        org.apache.zookeeper.data.Stat stat7 = dataTree3.statNode("", watcher6);
        org.apache.zookeeper.server.DataNode dataNode9 = dataTree3.getNode("");
        org.apache.zookeeper.txn.TxnDigest txnDigest10 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult11 = dataTree0.processTxn(txnHeader2, (org.apache.jute.Record) dataNode9, txnDigest10);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getClientId()\" because \"header\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 44L + "'", long1 == 44L);
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + 0L + "'", long4 == 0L);
        org.junit.Assert.assertNotNull(stat7);
        org.junit.Assert.assertNotNull(dataNode9);
    }

    @Test
    public void test029() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test029");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        int int3 = dataTree0.getWatchCount();
        org.apache.jute.OutputArchive outputArchive4 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeAcls(outputArchive4);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeInt(int, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertTrue("'" + int3 + "' != '" + 0 + "'", int3 == 0);
    }

    @Test
    public void test030() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test030");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        byte[] byteArray9 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat13 = dataTree0.setData("", byteArray9, (int) (short) -1, (long) (byte) 10, (long) 'a');
        org.apache.jute.InputArchive inputArchive14 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.deserialize(inputArchive14, "hi!");
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.InputArchive.readInt(String)\" because \"ia\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertNotNull(byteArray9);
        org.junit.Assert.assertArrayEquals(byteArray9, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat13);
    }

    @Test
    public void test031() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test031");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        String str6 = dataTree0.getMaxPrefixWithQuota("hi!");
        org.apache.zookeeper.txn.TxnHeader txnHeader7 = null;
        org.apache.zookeeper.server.DataTree dataTree8 = new org.apache.zookeeper.server.DataTree();
        long long9 = dataTree8.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher11 = null;
        org.apache.zookeeper.data.Stat stat12 = dataTree8.statNode("", watcher11);
        org.apache.zookeeper.txn.TxnDigest txnDigest13 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean14 = dataTree0.compareDigest(txnHeader7, (org.apache.jute.Record) stat12, txnDigest13);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getZxid()\" because \"header\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNull(str6);
        org.junit.Assert.assertTrue("'" + long9 + "' != '" + 0L + "'", long9 == 0L);
        org.junit.Assert.assertNotNull(stat12);
    }

    @Test
    public void test032() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test032");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.txn.TxnHeader txnHeader1 = null;
        org.apache.zookeeper.server.DataTree dataTree2 = new org.apache.zookeeper.server.DataTree();
        long long3 = dataTree2.lastProcessedZxid;
        String str5 = dataTree2.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache6 = dataTree2.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree8 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache9 = dataTree8.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary10 = dataTree8.getWatchesSummary();
        int int11 = dataTree8.getWatchCount();
        org.apache.zookeeper.data.Stat stat13 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList14 = dataTree8.getACL("", stat13);
        org.apache.zookeeper.data.Stat stat16 = dataTree2.setACL("", aCLList14, 100);
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult17 = dataTree0.processTxn(txnHeader1, (org.apache.jute.Record) stat16);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getClientId()\" because \"header\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long3 + "' != '" + 0L + "'", long3 == 0L);
        org.junit.Assert.assertNull(str5);
        org.junit.Assert.assertNotNull(referenceCountedACLCache6);
        org.junit.Assert.assertNotNull(referenceCountedACLCache9);
        org.junit.Assert.assertNotNull(watchesSummary10);
        org.junit.Assert.assertTrue("'" + int11 + "' != '" + 0 + "'", int11 == 0);
        org.junit.Assert.assertNotNull(aCLList14);
        org.junit.Assert.assertNotNull(stat16);
    }

    @Test
    public void test033() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test033");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        byte[] byteArray9 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat13 = dataTree0.setData("", byteArray9, (int) (short) -1, (long) (byte) 10, (long) 'a');
        org.apache.zookeeper.Watcher watcher15 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.addWatch("", watcher15, 10);
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Unsupported mode: 10");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertNotNull(byteArray9);
        org.junit.Assert.assertArrayEquals(byteArray9, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat13);
    }

    @Test
    public void test034() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test034");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        java.util.Set<String> strSet2 = dataTree0.getEphemerals((long) 10);
        byte[] byteArray10 = new byte[] { (byte) 0, (byte) 0, (byte) 10, (byte) 100, (byte) 0, (byte) 0 };
        org.apache.zookeeper.server.DataTree dataTree11 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache12 = dataTree11.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary13 = dataTree11.getWatchesSummary();
        int int14 = dataTree11.getWatchCount();
        org.apache.zookeeper.data.Stat stat16 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList17 = dataTree11.getACL("", stat16);
        org.apache.zookeeper.server.DataTree dataTree22 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache23 = dataTree22.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary24 = dataTree22.getWatchesSummary();
        byte[] byteArray31 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat35 = dataTree22.setData("", byteArray31, (int) (short) -1, (long) (byte) 10, (long) 'a');
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.createNode("", byteArray10, aCLList17, (long) (byte) 0, (int) (byte) 100, 10L, (long) (byte) 1, stat35);
            org.junit.Assert.fail("Expected exception of type java.lang.StringIndexOutOfBoundsException; message: Range [0, -1) out of bounds for length 0");
        } catch (StringIndexOutOfBoundsException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(strSet2);
        org.junit.Assert.assertNotNull(byteArray10);
        org.junit.Assert.assertArrayEquals(byteArray10, new byte[] { (byte) 0, (byte) 0, (byte) 10, (byte) 100, (byte) 0, (byte) 0 });
        org.junit.Assert.assertNotNull(referenceCountedACLCache12);
        org.junit.Assert.assertNotNull(watchesSummary13);
        org.junit.Assert.assertTrue("'" + int14 + "' != '" + 0 + "'", int14 == 0);
        org.junit.Assert.assertNotNull(aCLList17);
        org.junit.Assert.assertNotNull(referenceCountedACLCache23);
        org.junit.Assert.assertNotNull(watchesSummary24);
        org.junit.Assert.assertNotNull(byteArray31);
        org.junit.Assert.assertArrayEquals(byteArray31, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat35);
    }

    @Test
    public void test035() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test035");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        java.util.Set<String> strSet4 = dataTree0.getContainers();
        int int6 = dataTree0.getAllChildrenNumber("hi!");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache7 = dataTree0.getReferenceCountedAclCache();
        org.apache.jute.OutputArchive outputArchive8 = null;
        org.apache.zookeeper.server.DataTree dataTree10 = new org.apache.zookeeper.server.DataTree();
        long long11 = dataTree10.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher13 = null;
        org.apache.zookeeper.data.Stat stat14 = dataTree10.statNode("", watcher13);
        org.apache.zookeeper.server.DataNode dataNode16 = dataTree10.getNode("");
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeNodeData(outputArchive8, "hi!", dataNode16);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeString(String, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(strSet4);
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 0 + "'", int6 == 0);
        org.junit.Assert.assertNotNull(referenceCountedACLCache7);
        org.junit.Assert.assertTrue("'" + long11 + "' != '" + 0L + "'", long11 == 0L);
        org.junit.Assert.assertNotNull(stat14);
        org.junit.Assert.assertNotNull(dataNode16);
    }

    @Test
    public void test036() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test036");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree6 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache7 = dataTree6.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary8 = dataTree6.getWatchesSummary();
        int int9 = dataTree6.getWatchCount();
        org.apache.zookeeper.data.Stat stat11 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList12 = dataTree6.getACL("", stat11);
        org.apache.zookeeper.data.Stat stat14 = dataTree0.setACL("", aCLList12, 100);
        org.apache.jute.OutputArchive outputArchive15 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeAcls(outputArchive15);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeInt(int, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
        org.junit.Assert.assertNotNull(referenceCountedACLCache7);
        org.junit.Assert.assertNotNull(watchesSummary8);
        org.junit.Assert.assertTrue("'" + int9 + "' != '" + 0 + "'", int9 == 0);
        org.junit.Assert.assertNotNull(aCLList12);
        org.junit.Assert.assertNotNull(stat14);
    }



    @Test
    public void test038() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test038");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree6 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache7 = dataTree6.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary8 = dataTree6.getWatchesSummary();
        int int9 = dataTree6.getWatchCount();
        org.apache.zookeeper.data.Stat stat11 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList12 = dataTree6.getACL("", stat11);
        org.apache.zookeeper.data.Stat stat14 = dataTree0.setACL("", aCLList12, 100);
        String[] strArray17 = new String[] { "hi!" };
        java.util.ArrayList<String> strList18 = new java.util.ArrayList<String>();
        boolean boolean19 = java.util.Collections.addAll((java.util.Collection<String>) strList18, strArray17);
        String[] strArray22 = new String[] { "hi!", "hi!" };
        java.util.ArrayList<String> strList23 = new java.util.ArrayList<String>();
        boolean boolean24 = java.util.Collections.addAll((java.util.Collection<String>) strList23, strArray22);
        String[] strArray27 = new String[] { "", "" };
        java.util.ArrayList<String> strList28 = new java.util.ArrayList<String>();
        boolean boolean29 = java.util.Collections.addAll((java.util.Collection<String>) strList28, strArray27);
        String[] strArray31 = new String[] { "" };
        java.util.ArrayList<String> strList32 = new java.util.ArrayList<String>();
        boolean boolean33 = java.util.Collections.addAll((java.util.Collection<String>) strList32, strArray31);
        String[] strArray36 = new String[] { "hi!", "hi!" };
        java.util.ArrayList<String> strList37 = new java.util.ArrayList<String>();
        boolean boolean38 = java.util.Collections.addAll((java.util.Collection<String>) strList37, strArray36);
        org.apache.zookeeper.Watcher watcher39 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.setWatches(0L, (java.util.List<String>) strList18, (java.util.List<String>) strList23, (java.util.List<String>) strList28, (java.util.List<String>) strList32, (java.util.List<String>) strList37, watcher39);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher.process(org.apache.zookeeper.WatchedEvent)\" because \"watcher\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
        org.junit.Assert.assertNotNull(referenceCountedACLCache7);
        org.junit.Assert.assertNotNull(watchesSummary8);
        org.junit.Assert.assertTrue("'" + int9 + "' != '" + 0 + "'", int9 == 0);
        org.junit.Assert.assertNotNull(aCLList12);
        org.junit.Assert.assertNotNull(stat14);
        org.junit.Assert.assertNotNull(strArray17);
        org.junit.Assert.assertArrayEquals(strArray17, new String[] { "hi!" });
        org.junit.Assert.assertTrue("'" + boolean19 + "' != '" + true + "'", boolean19 == true);
        org.junit.Assert.assertNotNull(strArray22);
        org.junit.Assert.assertArrayEquals(strArray22, new String[] { "hi!", "hi!" });
        org.junit.Assert.assertTrue("'" + boolean24 + "' != '" + true + "'", boolean24 == true);
        org.junit.Assert.assertNotNull(strArray27);
        org.junit.Assert.assertArrayEquals(strArray27, new String[] { "", "" });
        org.junit.Assert.assertTrue("'" + boolean29 + "' != '" + true + "'", boolean29 == true);
        org.junit.Assert.assertNotNull(strArray31);
        org.junit.Assert.assertArrayEquals(strArray31, new String[] { "" });
        org.junit.Assert.assertTrue("'" + boolean33 + "' != '" + true + "'", boolean33 == true);
        org.junit.Assert.assertNotNull(strArray36);
        org.junit.Assert.assertArrayEquals(strArray36, new String[] { "hi!", "hi!" });
        org.junit.Assert.assertTrue("'" + boolean38 + "' != '" + true + "'", boolean38 == true);
    }



    @Test
    public void test040() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test040");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        java.io.PrintWriter printWriter5 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.dumpEphemerals(printWriter5);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.PrintWriter.println(String)\" because \"writer\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
    }

    @Test
    public void test041() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test041");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        java.util.Set<String> strSet6 = dataTree0.getEphemerals(10L);
        org.apache.jute.OutputArchive outputArchive7 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serialize(outputArchive7, "");
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeInt(int, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNotNull(strSet6);
    }

    @Test
    public void test042() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test042");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        int int3 = dataTree0.getWatchCount();
        org.apache.zookeeper.data.Stat stat5 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList6 = dataTree0.getACL("", stat5);
        org.apache.zookeeper.Watcher watcher8 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.addWatch("", watcher8, (int) (byte) 100);
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Unsupported mode: 100");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertTrue("'" + int3 + "' != '" + 0 + "'", int3 == 0);
        org.junit.Assert.assertNotNull(aCLList6);
    }

    @Test
    public void test043() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test043");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.cachedApproximateDataSize();
        org.apache.zookeeper.txn.TxnHeader txnHeader2 = null;
        org.apache.zookeeper.server.DataTree dataTree3 = new org.apache.zookeeper.server.DataTree();
        long long4 = dataTree3.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher6 = null;
        org.apache.zookeeper.data.Stat stat7 = dataTree3.statNode("", watcher6);
        org.apache.zookeeper.server.DataNode dataNode9 = dataTree3.getNode("");
        org.apache.zookeeper.Watcher watcher10 = null;
        dataTree3.removeCnxn(watcher10);
        org.apache.zookeeper.server.DataTree dataTree13 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache14 = dataTree13.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary15 = dataTree13.getWatchesSummary();
        byte[] byteArray22 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat26 = dataTree13.setData("", byteArray22, (int) (short) -1, (long) (byte) 10, (long) 'a');
        org.apache.zookeeper.data.Stat stat30 = dataTree3.setData("", byteArray22, (int) (short) 1, 0L, 0L);
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult31 = dataTree0.processTxn(txnHeader2, (org.apache.jute.Record) stat30);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getClientId()\" because \"header\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 44L + "'", long1 == 44L);
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + 0L + "'", long4 == 0L);
        org.junit.Assert.assertNotNull(stat7);
        org.junit.Assert.assertNotNull(dataNode9);
        org.junit.Assert.assertNotNull(referenceCountedACLCache14);
        org.junit.Assert.assertNotNull(watchesSummary15);
        org.junit.Assert.assertNotNull(byteArray22);
        org.junit.Assert.assertArrayEquals(byteArray22, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat26);
        org.junit.Assert.assertNotNull(stat30);
    }


    @Test
    public void test045() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test045");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        java.util.Set<String> strSet4 = dataTree0.getContainers();
        int int6 = dataTree0.getAllChildrenNumber("hi!");
        org.apache.zookeeper.server.DataTree dataTree7 = new org.apache.zookeeper.server.DataTree();
        long long8 = dataTree7.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher10 = null;
        org.apache.zookeeper.data.Stat stat11 = dataTree7.statNode("", watcher10);
        org.apache.zookeeper.server.DataNode dataNode13 = dataTree7.getNode("");
        java.util.List<org.apache.zookeeper.data.ACL> aCLList14 = dataTree0.getACL(dataNode13);
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(strSet4);
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 0 + "'", int6 == 0);
        org.junit.Assert.assertTrue("'" + long8 + "' != '" + 0L + "'", long8 == 0L);
        org.junit.Assert.assertNotNull(stat11);
        org.junit.Assert.assertNotNull(dataNode13);
        org.junit.Assert.assertNotNull(aCLList14);
    }

    @Test
    public void test046() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test046");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.cachedApproximateDataSize();
        org.apache.jute.OutputArchive outputArchive2 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeAcls(outputArchive2);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeInt(int, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 44L + "'", long1 == 44L);
    }

    @Test
    public void test047() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test047");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache5 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree7 = new org.apache.zookeeper.server.DataTree();
        long long8 = dataTree7.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher10 = null;
        org.apache.zookeeper.data.Stat stat11 = dataTree7.statNode("", watcher10);
        org.apache.zookeeper.server.DataNode dataNode13 = dataTree7.getNode("");
        org.apache.zookeeper.Watcher watcher14 = null;
        dataTree7.removeCnxn(watcher14);
        org.apache.zookeeper.server.DataTree dataTree17 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache18 = dataTree17.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary19 = dataTree17.getWatchesSummary();
        byte[] byteArray26 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat30 = dataTree17.setData("", byteArray26, (int) (short) -1, (long) (byte) 10, (long) 'a');
        org.apache.zookeeper.data.Stat stat34 = dataTree7.setData("", byteArray26, (int) (short) 1, 0L, 0L);
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.data.Stat stat38 = dataTree0.setData("hi!", byteArray26, (int) ' ', (long) (byte) 100, (long) (short) 100);
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
        org.junit.Assert.assertNotNull(referenceCountedACLCache5);
        org.junit.Assert.assertTrue("'" + long8 + "' != '" + 0L + "'", long8 == 0L);
        org.junit.Assert.assertNotNull(stat11);
        org.junit.Assert.assertNotNull(dataNode13);
        org.junit.Assert.assertNotNull(referenceCountedACLCache18);
        org.junit.Assert.assertNotNull(watchesSummary19);
        org.junit.Assert.assertNotNull(byteArray26);
        org.junit.Assert.assertArrayEquals(byteArray26, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat30);
        org.junit.Assert.assertNotNull(stat34);
    }

    @Test
    public void test048() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test048");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        java.util.Set<String> strSet2 = dataTree0.getEphemerals((long) 10);
        java.util.Map<Long, java.util.Set<String>> longMap3 = dataTree0.getEphemerals();
        String str5 = dataTree0.getMaxPrefixWithQuota("hi!");
        org.apache.zookeeper.Watcher.WatcherType watcherType7 = null;
        org.apache.zookeeper.Watcher watcher8 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean9 = dataTree0.containsWatcher("", watcherType7, watcher8);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher$WatcherType.ordinal()\" because \"type\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(strSet2);
        org.junit.Assert.assertNotNull(longMap3);
        org.junit.Assert.assertNull(str5);
    }

    @Test
    public void test049() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test049");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        java.util.Set<String> strSet2 = dataTree0.getEphemerals((long) 10);
        java.util.Map<Long, java.util.Set<String>> longMap3 = dataTree0.getEphemerals();
        java.io.PrintWriter printWriter4 = null;
        dataTree0.dumpWatches(printWriter4, false);
        dataTree0.setCversionPzxid("", (int) (short) 0, (long) (byte) -1);
        int int11 = dataTree0.getWatchCount();
        org.apache.jute.InputArchive inputArchive12 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.deserialize(inputArchive12, "hi!");
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.InputArchive.readInt(String)\" because \"ia\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(strSet2);
        org.junit.Assert.assertNotNull(longMap3);
        org.junit.Assert.assertTrue("'" + int11 + "' != '" + 0 + "'", int11 == 0);
    }

    @Test
    public void test050() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test050");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        org.apache.zookeeper.server.DataNode dataNode6 = dataTree0.getNode("");
        java.util.Collection<Long> longCollection7 = dataTree0.getSessions();
        dataTree0.lastProcessedZxid = (short) -1;
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNotNull(dataNode6);
        org.junit.Assert.assertNotNull(longCollection7);
    }

    @Test
    public void test051() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test051");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        long long5 = dataTree0.getTreeDigest();
        org.apache.zookeeper.server.DataTree dataTree7 = new org.apache.zookeeper.server.DataTree();
        long long8 = dataTree7.lastProcessedZxid;
        String str10 = dataTree7.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache11 = dataTree7.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree13 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache14 = dataTree13.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary15 = dataTree13.getWatchesSummary();
        int int16 = dataTree13.getWatchCount();
        org.apache.zookeeper.data.Stat stat18 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList19 = dataTree13.getACL("", stat18);
        org.apache.zookeeper.data.Stat stat21 = dataTree7.setACL("", aCLList19, 100);
        org.apache.zookeeper.data.Stat stat23 = dataTree0.setACL("", aCLList19, (int) (byte) 1);
        dataTree0.shutdownWatcher();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + 1371985504L + "'", long5 == 1371985504L);
        org.junit.Assert.assertTrue("'" + long8 + "' != '" + 0L + "'", long8 == 0L);
        org.junit.Assert.assertNull(str10);
        org.junit.Assert.assertNotNull(referenceCountedACLCache11);
        org.junit.Assert.assertNotNull(referenceCountedACLCache14);
        org.junit.Assert.assertNotNull(watchesSummary15);
        org.junit.Assert.assertTrue("'" + int16 + "' != '" + 0 + "'", int16 == 0);
        org.junit.Assert.assertNotNull(aCLList19);
        org.junit.Assert.assertNotNull(stat21);
        org.junit.Assert.assertNotNull(stat23);
    }

    @Test
    public void test052() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test052");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        java.util.Set<String> strSet2 = dataTree0.getEphemerals((long) 10);
        java.util.Map<Long, java.util.Set<String>> longMap3 = dataTree0.getEphemerals();
        java.io.PrintWriter printWriter4 = null;
        dataTree0.dumpWatches(printWriter4, false);
        dataTree0.setCversionPzxid("", (int) (short) 0, (long) (byte) -1);
        int int11 = dataTree0.getWatchCount();
        org.apache.jute.OutputArchive outputArchive12 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeAcls(outputArchive12);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeInt(int, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(strSet2);
        org.junit.Assert.assertNotNull(longMap3);
        org.junit.Assert.assertTrue("'" + int11 + "' != '" + 0 + "'", int11 == 0);
    }

    @Test
    public void test053() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test053");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        java.util.Map<Long, java.util.Set<String>> longMap4 = dataTree0.getEphemerals();
        org.apache.zookeeper.server.DataNode dataNode6 = dataTree0.getNode("");
        org.apache.zookeeper.server.DataTree dataTree8 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache9 = dataTree8.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary10 = dataTree8.getWatchesSummary();
        byte[] byteArray17 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat21 = dataTree8.setData("", byteArray17, (int) (short) -1, (long) (byte) 10, (long) 'a');
        org.apache.zookeeper.data.ACL[] aCLArray22 = new org.apache.zookeeper.data.ACL[] {};
        java.util.ArrayList<org.apache.zookeeper.data.ACL> aCLList23 = new java.util.ArrayList<org.apache.zookeeper.data.ACL>();
        boolean boolean24 = java.util.Collections.addAll((java.util.Collection<org.apache.zookeeper.data.ACL>) aCLList23, aCLArray22);
        org.apache.zookeeper.server.DataTree dataTree29 = new org.apache.zookeeper.server.DataTree();
        long long30 = dataTree29.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher32 = null;
        org.apache.zookeeper.data.Stat stat33 = dataTree29.statNode("", watcher32);
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.createNode("", byteArray17, (java.util.List<org.apache.zookeeper.data.ACL>) aCLList23, (long) (-1), (int) (short) 10, (long) 0, (long) 100, stat33);
            org.junit.Assert.fail("Expected exception of type java.lang.StringIndexOutOfBoundsException; message: Range [0, -1) out of bounds for length 0");
        } catch (StringIndexOutOfBoundsException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(longMap4);
        org.junit.Assert.assertNotNull(dataNode6);
        org.junit.Assert.assertNotNull(referenceCountedACLCache9);
        org.junit.Assert.assertNotNull(watchesSummary10);
        org.junit.Assert.assertNotNull(byteArray17);
        org.junit.Assert.assertArrayEquals(byteArray17, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat21);
        org.junit.Assert.assertNotNull(aCLArray22);
        org.junit.Assert.assertArrayEquals(aCLArray22, new org.apache.zookeeper.data.ACL[] {});
        org.junit.Assert.assertTrue("'" + boolean24 + "' != '" + false + "'", boolean24 == false);
        org.junit.Assert.assertTrue("'" + long30 + "' != '" + 0L + "'", long30 == 0L);
        org.junit.Assert.assertNotNull(stat33);
    }

    @Test
    public void test054() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test054");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        byte[] byteArray9 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat13 = dataTree0.setData("", byteArray9, (int) (short) -1, (long) (byte) 10, (long) 'a');
        org.apache.zookeeper.server.DataTree.ZxidDigest zxidDigest14 = dataTree0.getDigestFromLoadedSnapshot();
        org.apache.jute.OutputArchive outputArchive15 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean16 = dataTree0.serializeLastProcessedZxid(outputArchive15);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeLong(long, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertNotNull(byteArray9);
        org.junit.Assert.assertArrayEquals(byteArray9, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat13);
        org.junit.Assert.assertNull(zxidDigest14);
    }

    @Test
    public void test055() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test055");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        dataTree0.addConfigNode();
        int int7 = dataTree0.getAllChildrenNumber("hi!");
        org.apache.zookeeper.Watcher.WatcherType watcherType9 = null;
        org.apache.zookeeper.Watcher watcher10 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean11 = dataTree0.containsWatcher("hi!", watcherType9, watcher10);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher$WatcherType.ordinal()\" because \"type\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
        org.junit.Assert.assertTrue("'" + int7 + "' != '" + 0 + "'", int7 == 0);
    }

    @Test
    public void test056() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test056");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache5 = dataTree0.getReferenceCountedAclCache();
        org.apache.jute.InputArchive inputArchive6 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean7 = dataTree0.deserializeLastProcessedZxid(inputArchive6);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.InputArchive.readLong(String)\" because \"ia\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
        org.junit.Assert.assertNotNull(referenceCountedACLCache5);
    }

    @Test
    public void test057() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test057");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        java.util.Map<Long, java.util.Set<String>> longMap4 = dataTree0.getEphemerals();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache5 = dataTree0.getReferenceCountedAclCache();
        java.io.PrintWriter printWriter6 = null;
        dataTree0.dumpWatches(printWriter6, false);
        org.apache.zookeeper.txn.TxnHeader txnHeader9 = null;
        org.apache.zookeeper.server.DataTree dataTree10 = new org.apache.zookeeper.server.DataTree();
        long long11 = dataTree10.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher13 = null;
        org.apache.zookeeper.data.Stat stat14 = dataTree10.statNode("", watcher13);
        org.apache.zookeeper.server.DataNode dataNode16 = dataTree10.getNode("");
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult17 = dataTree0.processTxn(txnHeader9, (org.apache.jute.Record) dataNode16);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getClientId()\" because \"header\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(longMap4);
        org.junit.Assert.assertNotNull(referenceCountedACLCache5);
        org.junit.Assert.assertTrue("'" + long11 + "' != '" + 0L + "'", long11 == 0L);
        org.junit.Assert.assertNotNull(stat14);
        org.junit.Assert.assertNotNull(dataNode16);
    }

    @Test
    public void test058() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test058");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        int int1 = dataTree0.getEphemeralsCount();
        org.apache.jute.InputArchive inputArchive2 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.deserialize(inputArchive2, "hi!");
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.InputArchive.readInt(String)\" because \"ia\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int1 + "' != '" + 0 + "'", int1 == 0);
    }

    @Test
    public void test059() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test059");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        org.apache.zookeeper.server.DataNode dataNode6 = dataTree0.getNode("");
        org.apache.zookeeper.Watcher watcher7 = null;
        dataTree0.removeCnxn(watcher7);
        org.apache.zookeeper.server.DataTree dataTree10 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache11 = dataTree10.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary12 = dataTree10.getWatchesSummary();
        byte[] byteArray19 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat23 = dataTree10.setData("", byteArray19, (int) (short) -1, (long) (byte) 10, (long) 'a');
        org.apache.zookeeper.data.Stat stat27 = dataTree0.setData("", byteArray19, (int) (short) 1, 0L, 0L);
        int int28 = dataTree0.getNodeCount();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNotNull(dataNode6);
        org.junit.Assert.assertNotNull(referenceCountedACLCache11);
        org.junit.Assert.assertNotNull(watchesSummary12);
        org.junit.Assert.assertNotNull(byteArray19);
        org.junit.Assert.assertArrayEquals(byteArray19, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat23);
        org.junit.Assert.assertNotNull(stat27);
        org.junit.Assert.assertTrue("'" + int28 + "' != '" + 5 + "'", int28 == 5);
    }

    @Test
    public void test060() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test060");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        org.apache.zookeeper.server.DataNode dataNode6 = dataTree0.getNode("");
        long long7 = dataTree0.approximateDataSize();
        dataTree0.setCversionPzxid("", (int) 'a', (long) ' ');
        java.io.PrintWriter printWriter12 = null;
        dataTree0.dumpWatches(printWriter12, true);
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNotNull(dataNode6);
        org.junit.Assert.assertTrue("'" + long7 + "' != '" + 44L + "'", long7 == 44L);
    }

    @Test
    public void test061() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test061");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        java.util.Map<Long, java.util.Set<String>> longMap2 = dataTree0.getEphemerals();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(longMap2);
    }

    @Test
    public void test062() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test062");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache5 = dataTree0.getReferenceCountedAclCache();
        org.apache.jute.OutputArchive outputArchive6 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean7 = dataTree0.serializeZxidDigest(outputArchive6);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeLong(long, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
        org.junit.Assert.assertNotNull(referenceCountedACLCache5);
    }

    @Test
    public void test063() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test063");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        org.apache.zookeeper.DigestWatcher digestWatcher3 = null;
        dataTree0.addDigestWatcher(digestWatcher3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
    }

    @Test
    public void test064() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test064");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache5 = dataTree0.getReferenceCountedAclCache();
        org.apache.jute.OutputArchive outputArchive6 = null;
        org.apache.zookeeper.server.DataTree dataTree8 = new org.apache.zookeeper.server.DataTree();
        long long9 = dataTree8.lastProcessedZxid;
        String str11 = dataTree8.getMaxPrefixWithQuota("");
        java.util.Map<Long, java.util.Set<String>> longMap12 = dataTree8.getEphemerals();
        org.apache.zookeeper.server.DataNode dataNode14 = dataTree8.getNode("");
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeNodeData(outputArchive6, "hi!", dataNode14);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeString(String, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
        org.junit.Assert.assertNotNull(referenceCountedACLCache5);
        org.junit.Assert.assertTrue("'" + long9 + "' != '" + 0L + "'", long9 == 0L);
        org.junit.Assert.assertNull(str11);
        org.junit.Assert.assertNotNull(longMap12);
        org.junit.Assert.assertNotNull(dataNode14);
    }

    @Test
    public void test065() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test065");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        java.util.Set<String> strSet2 = dataTree0.getEphemerals((long) 10);
        java.util.Map<Long, java.util.Set<String>> longMap3 = dataTree0.getEphemerals();
        java.io.PrintWriter printWriter4 = null;
        dataTree0.dumpWatches(printWriter4, false);
        dataTree0.setCversionPzxid("", (int) (short) 0, (long) (byte) -1);
        Class<?> wildcardClass11 = dataTree0.getClass();
        org.junit.Assert.assertNotNull(strSet2);
        org.junit.Assert.assertNotNull(longMap3);
        org.junit.Assert.assertNotNull(wildcardClass11);
    }

    @Test
    public void test066() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test066");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        String str5 = dataTree0.getMaxPrefixWithQuota("hi!");
        org.apache.zookeeper.Watcher watcher6 = null;
        dataTree0.removeCnxn(watcher6);
        org.apache.jute.InputArchive inputArchive8 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean9 = dataTree0.deserializeLastProcessedZxid(inputArchive8);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.InputArchive.readLong(String)\" because \"ia\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNull(str5);
    }

    @Test
    public void test067() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test067");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        byte[] byteArray9 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat13 = dataTree0.setData("", byteArray9, (int) (short) -1, (long) (byte) 10, (long) 'a');
        String str15 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.DataNode dataNode17 = dataTree0.getNode("hi!");
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertNotNull(byteArray9);
        org.junit.Assert.assertArrayEquals(byteArray9, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat13);
        org.junit.Assert.assertNull(str15);
        org.junit.Assert.assertNull(dataNode17);
    }

    @Test
    public void test068() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test068");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        java.util.Set<String> strSet2 = dataTree0.getEphemerals((long) 10);
        java.util.Map<Long, java.util.Set<String>> longMap3 = dataTree0.getEphemerals();
        String str5 = dataTree0.getMaxPrefixWithQuota("hi!");
        org.apache.zookeeper.server.DataTree dataTree7 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache8 = dataTree7.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary9 = dataTree7.getWatchesSummary();
        org.apache.zookeeper.server.DataTree dataTree11 = new org.apache.zookeeper.server.DataTree();
        long long12 = dataTree11.lastProcessedZxid;
        String str14 = dataTree11.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache15 = dataTree11.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree17 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache18 = dataTree17.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary19 = dataTree17.getWatchesSummary();
        int int20 = dataTree17.getWatchCount();
        org.apache.zookeeper.data.Stat stat22 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList23 = dataTree17.getACL("", stat22);
        org.apache.zookeeper.data.Stat stat25 = dataTree11.setACL("", aCLList23, 100);
        java.util.List<org.apache.zookeeper.data.ACL> aCLList26 = dataTree7.getACL("", stat25);
        // The following exception was thrown during execution in test generation
        try {
            java.util.List<org.apache.zookeeper.data.ACL> aCLList27 = dataTree0.getACL("hi!", stat25);
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(strSet2);
        org.junit.Assert.assertNotNull(longMap3);
        org.junit.Assert.assertNull(str5);
        org.junit.Assert.assertNotNull(referenceCountedACLCache8);
        org.junit.Assert.assertNotNull(watchesSummary9);
        org.junit.Assert.assertTrue("'" + long12 + "' != '" + 0L + "'", long12 == 0L);
        org.junit.Assert.assertNull(str14);
        org.junit.Assert.assertNotNull(referenceCountedACLCache15);
        org.junit.Assert.assertNotNull(referenceCountedACLCache18);
        org.junit.Assert.assertNotNull(watchesSummary19);
        org.junit.Assert.assertTrue("'" + int20 + "' != '" + 0 + "'", int20 == 0);
        org.junit.Assert.assertNotNull(aCLList23);
        org.junit.Assert.assertNotNull(stat25);
        org.junit.Assert.assertNotNull(aCLList26);
    }

    @Test
    public void test069() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test069");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        dataTree0.addConfigNode();
        int int7 = dataTree0.getAllChildrenNumber("hi!");
        org.apache.zookeeper.server.DataTree dataTree9 = new org.apache.zookeeper.server.DataTree();
        long long10 = dataTree9.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher12 = null;
        org.apache.zookeeper.data.Stat stat13 = dataTree9.statNode("", watcher12);
        // The following exception was thrown during execution in test generation
        try {
            java.util.List<org.apache.zookeeper.data.ACL> aCLList14 = dataTree0.getACL("hi!", stat13);
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
        org.junit.Assert.assertTrue("'" + int7 + "' != '" + 0 + "'", int7 == 0);
        org.junit.Assert.assertTrue("'" + long10 + "' != '" + 0L + "'", long10 == 0L);
        org.junit.Assert.assertNotNull(stat13);
    }

    @Test
    public void test070() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test070");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        java.util.Map<Long, java.util.Set<String>> longMap4 = dataTree0.getEphemerals();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache5 = dataTree0.getReferenceCountedAclCache();
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.setCversionPzxid("hi!", (int) '#', (long) 'a');
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode for hi!");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(longMap4);
        org.junit.Assert.assertNotNull(referenceCountedACLCache5);
    }

    @Test
    public void test071() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test071");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree6 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache7 = dataTree6.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary8 = dataTree6.getWatchesSummary();
        int int9 = dataTree6.getWatchCount();
        org.apache.zookeeper.data.Stat stat11 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList12 = dataTree6.getACL("", stat11);
        org.apache.zookeeper.data.Stat stat14 = dataTree0.setACL("", aCLList12, 100);
        int int15 = dataTree0.getNodeCount();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
        org.junit.Assert.assertNotNull(referenceCountedACLCache7);
        org.junit.Assert.assertNotNull(watchesSummary8);
        org.junit.Assert.assertTrue("'" + int9 + "' != '" + 0 + "'", int9 == 0);
        org.junit.Assert.assertNotNull(aCLList12);
        org.junit.Assert.assertNotNull(stat14);
        org.junit.Assert.assertTrue("'" + int15 + "' != '" + 5 + "'", int15 == 5);
    }

    @Test
    public void test072() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test072");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache5 = dataTree0.getReferenceCountedAclCache();
        dataTree0.shutdownWatcher();
        Class<?> wildcardClass7 = dataTree0.getClass();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
        org.junit.Assert.assertNotNull(referenceCountedACLCache5);
        org.junit.Assert.assertNotNull(wildcardClass7);
    }

    @Test
    public void test073() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test073");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        String str6 = dataTree0.getMaxPrefixWithQuota("hi!");
        int int7 = dataTree0.getWatchCount();
        int int8 = dataTree0.aclCacheSize();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNull(str6);
        org.junit.Assert.assertTrue("'" + int7 + "' != '" + 0 + "'", int7 == 0);
        org.junit.Assert.assertTrue("'" + int8 + "' != '" + 1 + "'", int8 == 1);
    }

    @Test
    public void test074() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test074");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        byte[] byteArray9 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat13 = dataTree0.setData("", byteArray9, (int) (short) -1, (long) (byte) 10, (long) 'a');
        org.apache.zookeeper.server.DataNode dataNode15 = dataTree0.getNode("hi!");
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.deleteNode("hi!", 100L);
            org.junit.Assert.fail("Expected exception of type java.lang.StringIndexOutOfBoundsException; message: Range [0, -1) out of bounds for length 3");
        } catch (StringIndexOutOfBoundsException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertNotNull(byteArray9);
        org.junit.Assert.assertArrayEquals(byteArray9, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat13);
        org.junit.Assert.assertNull(dataNode15);
    }

    @Test
    public void test075() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test075");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        org.apache.zookeeper.server.DataTree dataTree4 = new org.apache.zookeeper.server.DataTree();
        long long5 = dataTree4.lastProcessedZxid;
        String str7 = dataTree4.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache8 = dataTree4.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree10 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache11 = dataTree10.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary12 = dataTree10.getWatchesSummary();
        int int13 = dataTree10.getWatchCount();
        org.apache.zookeeper.data.Stat stat15 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList16 = dataTree10.getACL("", stat15);
        org.apache.zookeeper.data.Stat stat18 = dataTree4.setACL("", aCLList16, 100);
        java.util.List<org.apache.zookeeper.data.ACL> aCLList19 = dataTree0.getACL("", stat18);
        int int20 = dataTree0.getNodeCount();
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + 0L + "'", long5 == 0L);
        org.junit.Assert.assertNull(str7);
        org.junit.Assert.assertNotNull(referenceCountedACLCache8);
        org.junit.Assert.assertNotNull(referenceCountedACLCache11);
        org.junit.Assert.assertNotNull(watchesSummary12);
        org.junit.Assert.assertTrue("'" + int13 + "' != '" + 0 + "'", int13 == 0);
        org.junit.Assert.assertNotNull(aCLList16);
        org.junit.Assert.assertNotNull(stat18);
        org.junit.Assert.assertNotNull(aCLList19);
        org.junit.Assert.assertTrue("'" + int20 + "' != '" + 5 + "'", int20 == 5);
    }

    @Test
    public void test076() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test076");
        int int0 = org.apache.zookeeper.server.DataTree.DIGEST_LOG_LIMIT;
        org.junit.Assert.assertTrue("'" + int0 + "' != '" + 1024 + "'", int0 == 1024);
    }

    @Test
    public void test077() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test077");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.shutdownWatcher();
    }

    @Test
    public void test078() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test078");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        long long5 = dataTree0.getTreeDigest();
        org.apache.zookeeper.server.DataTree dataTree7 = new org.apache.zookeeper.server.DataTree();
        long long8 = dataTree7.lastProcessedZxid;
        String str10 = dataTree7.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache11 = dataTree7.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree13 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache14 = dataTree13.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary15 = dataTree13.getWatchesSummary();
        int int16 = dataTree13.getWatchCount();
        org.apache.zookeeper.data.Stat stat18 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList19 = dataTree13.getACL("", stat18);
        org.apache.zookeeper.data.Stat stat21 = dataTree7.setACL("", aCLList19, 100);
        org.apache.zookeeper.data.Stat stat23 = dataTree0.setACL("", aCLList19, (int) (byte) 1);
        org.apache.zookeeper.server.DataTree dataTree25 = new org.apache.zookeeper.server.DataTree();
        long long26 = dataTree25.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher28 = null;
        org.apache.zookeeper.data.Stat stat29 = dataTree25.statNode("", watcher28);
        long long30 = dataTree25.getTreeDigest();
        org.apache.zookeeper.server.DataTree dataTree32 = new org.apache.zookeeper.server.DataTree();
        long long33 = dataTree32.lastProcessedZxid;
        String str35 = dataTree32.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache36 = dataTree32.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree38 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache39 = dataTree38.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary40 = dataTree38.getWatchesSummary();
        int int41 = dataTree38.getWatchCount();
        org.apache.zookeeper.data.Stat stat43 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList44 = dataTree38.getACL("", stat43);
        org.apache.zookeeper.data.Stat stat46 = dataTree32.setACL("", aCLList44, 100);
        org.apache.zookeeper.data.Stat stat48 = dataTree25.setACL("", aCLList44, (int) (byte) 1);
        java.util.List<org.apache.zookeeper.data.ACL> aCLList49 = dataTree0.getACL("", stat48);
        String str51 = dataTree0.getMaxPrefixWithQuota("hi!");
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + 1371985504L + "'", long5 == 1371985504L);
        org.junit.Assert.assertTrue("'" + long8 + "' != '" + 0L + "'", long8 == 0L);
        org.junit.Assert.assertNull(str10);
        org.junit.Assert.assertNotNull(referenceCountedACLCache11);
        org.junit.Assert.assertNotNull(referenceCountedACLCache14);
        org.junit.Assert.assertNotNull(watchesSummary15);
        org.junit.Assert.assertTrue("'" + int16 + "' != '" + 0 + "'", int16 == 0);
        org.junit.Assert.assertNotNull(aCLList19);
        org.junit.Assert.assertNotNull(stat21);
        org.junit.Assert.assertNotNull(stat23);
        org.junit.Assert.assertTrue("'" + long26 + "' != '" + 0L + "'", long26 == 0L);
        org.junit.Assert.assertNotNull(stat29);
        org.junit.Assert.assertTrue("'" + long30 + "' != '" + 1371985504L + "'", long30 == 1371985504L);
        org.junit.Assert.assertTrue("'" + long33 + "' != '" + 0L + "'", long33 == 0L);
        org.junit.Assert.assertNull(str35);
        org.junit.Assert.assertNotNull(referenceCountedACLCache36);
        org.junit.Assert.assertNotNull(referenceCountedACLCache39);
        org.junit.Assert.assertNotNull(watchesSummary40);
        org.junit.Assert.assertTrue("'" + int41 + "' != '" + 0 + "'", int41 == 0);
        org.junit.Assert.assertNotNull(aCLList44);
        org.junit.Assert.assertNotNull(stat46);
        org.junit.Assert.assertNotNull(stat48);
        org.junit.Assert.assertNotNull(aCLList49);
        org.junit.Assert.assertNull(str51);
    }

    @Test
    public void test079() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test079");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        org.apache.zookeeper.server.DataNode dataNode6 = dataTree0.getNode("");
        long long7 = dataTree0.approximateDataSize();
        String str9 = dataTree0.getMaxPrefixWithQuota("hi!");
        org.apache.zookeeper.Watcher watcher10 = null;
        dataTree0.removeCnxn(watcher10);
        int int12 = dataTree0.getNodeCount();
        org.apache.zookeeper.Watcher.WatcherType watcherType14 = null;
        org.apache.zookeeper.Watcher watcher15 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean16 = dataTree0.containsWatcher("", watcherType14, watcher15);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher$WatcherType.ordinal()\" because \"type\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNotNull(dataNode6);
        org.junit.Assert.assertTrue("'" + long7 + "' != '" + 44L + "'", long7 == 44L);
        org.junit.Assert.assertNull(str9);
        org.junit.Assert.assertTrue("'" + int12 + "' != '" + 5 + "'", int12 == 5);
    }

    @Test
    public void test080() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test080");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        byte[] byteArray9 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat13 = dataTree0.setData("", byteArray9, (int) (short) -1, (long) (byte) 10, (long) 'a');
        String[] strArray17 = new String[] { "", "hi!" };
        java.util.ArrayList<String> strList18 = new java.util.ArrayList<String>();
        boolean boolean19 = java.util.Collections.addAll((java.util.Collection<String>) strList18, strArray17);
        String[] strArray22 = new String[] { "hi!", "hi!" };
        java.util.ArrayList<String> strList23 = new java.util.ArrayList<String>();
        boolean boolean24 = java.util.Collections.addAll((java.util.Collection<String>) strList23, strArray22);
        String[] strArray26 = new String[] { "hi!" };
        java.util.ArrayList<String> strList27 = new java.util.ArrayList<String>();
        boolean boolean28 = java.util.Collections.addAll((java.util.Collection<String>) strList27, strArray26);
        String[] strArray30 = new String[] { "hi!" };
        java.util.ArrayList<String> strList31 = new java.util.ArrayList<String>();
        boolean boolean32 = java.util.Collections.addAll((java.util.Collection<String>) strList31, strArray30);
        String[] strArray35 = new String[] { "", "" };
        java.util.ArrayList<String> strList36 = new java.util.ArrayList<String>();
        boolean boolean37 = java.util.Collections.addAll((java.util.Collection<String>) strList36, strArray35);
        org.apache.zookeeper.Watcher watcher38 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.setWatches((long) 'a', (java.util.List<String>) strList18, (java.util.List<String>) strList23, (java.util.List<String>) strList27, (java.util.List<String>) strList31, (java.util.List<String>) strList36, watcher38);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher.process(org.apache.zookeeper.WatchedEvent)\" because \"watcher\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertNotNull(byteArray9);
        org.junit.Assert.assertArrayEquals(byteArray9, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat13);
        org.junit.Assert.assertNotNull(strArray17);
        org.junit.Assert.assertArrayEquals(strArray17, new String[] { "", "hi!" });
        org.junit.Assert.assertTrue("'" + boolean19 + "' != '" + true + "'", boolean19 == true);
        org.junit.Assert.assertNotNull(strArray22);
        org.junit.Assert.assertArrayEquals(strArray22, new String[] { "hi!", "hi!" });
        org.junit.Assert.assertTrue("'" + boolean24 + "' != '" + true + "'", boolean24 == true);
        org.junit.Assert.assertNotNull(strArray26);
        org.junit.Assert.assertArrayEquals(strArray26, new String[] { "hi!" });
        org.junit.Assert.assertTrue("'" + boolean28 + "' != '" + true + "'", boolean28 == true);
        org.junit.Assert.assertNotNull(strArray30);
        org.junit.Assert.assertArrayEquals(strArray30, new String[] { "hi!" });
        org.junit.Assert.assertTrue("'" + boolean32 + "' != '" + true + "'", boolean32 == true);
        org.junit.Assert.assertNotNull(strArray35);
        org.junit.Assert.assertArrayEquals(strArray35, new String[] { "", "" });
        org.junit.Assert.assertTrue("'" + boolean37 + "' != '" + true + "'", boolean37 == true);
    }

    @Test
    public void test081() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test081");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        long long5 = dataTree0.getTreeDigest();
        org.apache.zookeeper.server.DataTree dataTree7 = new org.apache.zookeeper.server.DataTree();
        long long8 = dataTree7.lastProcessedZxid;
        String str10 = dataTree7.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache11 = dataTree7.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree13 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache14 = dataTree13.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary15 = dataTree13.getWatchesSummary();
        int int16 = dataTree13.getWatchCount();
        org.apache.zookeeper.data.Stat stat18 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList19 = dataTree13.getACL("", stat18);
        org.apache.zookeeper.data.Stat stat21 = dataTree7.setACL("", aCLList19, 100);
        org.apache.zookeeper.data.Stat stat23 = dataTree0.setACL("", aCLList19, (int) (byte) 1);
        org.apache.zookeeper.server.DataTree dataTree25 = new org.apache.zookeeper.server.DataTree();
        long long26 = dataTree25.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher28 = null;
        org.apache.zookeeper.data.Stat stat29 = dataTree25.statNode("", watcher28);
        long long30 = dataTree25.getTreeDigest();
        org.apache.zookeeper.server.DataTree dataTree32 = new org.apache.zookeeper.server.DataTree();
        long long33 = dataTree32.lastProcessedZxid;
        String str35 = dataTree32.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache36 = dataTree32.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree38 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache39 = dataTree38.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary40 = dataTree38.getWatchesSummary();
        int int41 = dataTree38.getWatchCount();
        org.apache.zookeeper.data.Stat stat43 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList44 = dataTree38.getACL("", stat43);
        org.apache.zookeeper.data.Stat stat46 = dataTree32.setACL("", aCLList44, 100);
        org.apache.zookeeper.data.Stat stat48 = dataTree25.setACL("", aCLList44, (int) (byte) 1);
        java.util.List<org.apache.zookeeper.data.ACL> aCLList49 = dataTree0.getACL("", stat48);
        dataTree0.updateQuotaStat("", (long) (byte) 10, (int) (short) 10);
        org.apache.zookeeper.server.DataTree dataTree55 = new org.apache.zookeeper.server.DataTree();
        long long56 = dataTree55.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher58 = null;
        org.apache.zookeeper.data.Stat stat59 = dataTree55.statNode("", watcher58);
        org.apache.zookeeper.server.DataNode dataNode61 = dataTree55.getNode("");
        org.apache.zookeeper.Watcher watcher62 = null;
        dataTree55.removeCnxn(watcher62);
        org.apache.zookeeper.server.DataTree dataTree65 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache66 = dataTree65.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary67 = dataTree65.getWatchesSummary();
        byte[] byteArray74 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat78 = dataTree65.setData("", byteArray74, (int) (short) -1, (long) (byte) 10, (long) 'a');
        org.apache.zookeeper.data.Stat stat82 = dataTree55.setData("", byteArray74, (int) (short) 1, 0L, 0L);
        // The following exception was thrown during execution in test generation
        try {
            java.util.List<org.apache.zookeeper.data.ACL> aCLList83 = dataTree0.getACL("hi!", stat82);
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + 1371985504L + "'", long5 == 1371985504L);
        org.junit.Assert.assertTrue("'" + long8 + "' != '" + 0L + "'", long8 == 0L);
        org.junit.Assert.assertNull(str10);
        org.junit.Assert.assertNotNull(referenceCountedACLCache11);
        org.junit.Assert.assertNotNull(referenceCountedACLCache14);
        org.junit.Assert.assertNotNull(watchesSummary15);
        org.junit.Assert.assertTrue("'" + int16 + "' != '" + 0 + "'", int16 == 0);
        org.junit.Assert.assertNotNull(aCLList19);
        org.junit.Assert.assertNotNull(stat21);
        org.junit.Assert.assertNotNull(stat23);
        org.junit.Assert.assertTrue("'" + long26 + "' != '" + 0L + "'", long26 == 0L);
        org.junit.Assert.assertNotNull(stat29);
        org.junit.Assert.assertTrue("'" + long30 + "' != '" + 1371985504L + "'", long30 == 1371985504L);
        org.junit.Assert.assertTrue("'" + long33 + "' != '" + 0L + "'", long33 == 0L);
        org.junit.Assert.assertNull(str35);
        org.junit.Assert.assertNotNull(referenceCountedACLCache36);
        org.junit.Assert.assertNotNull(referenceCountedACLCache39);
        org.junit.Assert.assertNotNull(watchesSummary40);
        org.junit.Assert.assertTrue("'" + int41 + "' != '" + 0 + "'", int41 == 0);
        org.junit.Assert.assertNotNull(aCLList44);
        org.junit.Assert.assertNotNull(stat46);
        org.junit.Assert.assertNotNull(stat48);
        org.junit.Assert.assertNotNull(aCLList49);
        org.junit.Assert.assertTrue("'" + long56 + "' != '" + 0L + "'", long56 == 0L);
        org.junit.Assert.assertNotNull(stat59);
        org.junit.Assert.assertNotNull(dataNode61);
        org.junit.Assert.assertNotNull(referenceCountedACLCache66);
        org.junit.Assert.assertNotNull(watchesSummary67);
        org.junit.Assert.assertNotNull(byteArray74);
        org.junit.Assert.assertArrayEquals(byteArray74, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat78);
        org.junit.Assert.assertNotNull(stat82);
    }

    @Test
    public void test082() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test082");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        int int1 = dataTree0.getEphemeralsCount();
        java.util.Set<String> strSet2 = dataTree0.getTtls();
        long long3 = dataTree0.lastProcessedZxid;
        org.junit.Assert.assertTrue("'" + int1 + "' != '" + 0 + "'", int1 == 0);
        org.junit.Assert.assertNotNull(strSet2);
        org.junit.Assert.assertTrue("'" + long3 + "' != '" + 0L + "'", long3 == 0L);
    }

    @Test
    public void test083() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test083");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        byte[] byteArray9 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat13 = dataTree0.setData("", byteArray9, (int) (short) -1, (long) (byte) 10, (long) 'a');
        org.apache.zookeeper.server.DataTree.ZxidDigest zxidDigest14 = dataTree0.getDigestFromLoadedSnapshot();
        String str16 = dataTree0.getMaxPrefixWithQuota("hi!");
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertNotNull(byteArray9);
        org.junit.Assert.assertArrayEquals(byteArray9, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat13);
        org.junit.Assert.assertNull(zxidDigest14);
        org.junit.Assert.assertNull(str16);
    }

    @Test
    public void test084() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test084");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        long long4 = dataTree0.cachedApproximateDataSize();
        org.apache.jute.InputArchive inputArchive5 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean6 = dataTree0.deserializeLastProcessedZxid(inputArchive5);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.InputArchive.readLong(String)\" because \"ia\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + 44L + "'", long4 == 44L);
    }

    @Test
    public void test085() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test085");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        org.apache.zookeeper.server.DataTree dataTree4 = new org.apache.zookeeper.server.DataTree();
        long long5 = dataTree4.lastProcessedZxid;
        String str7 = dataTree4.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache8 = dataTree4.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree10 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache11 = dataTree10.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary12 = dataTree10.getWatchesSummary();
        int int13 = dataTree10.getWatchCount();
        org.apache.zookeeper.data.Stat stat15 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList16 = dataTree10.getACL("", stat15);
        org.apache.zookeeper.data.Stat stat18 = dataTree4.setACL("", aCLList16, 100);
        java.util.List<org.apache.zookeeper.data.ACL> aCLList19 = dataTree0.getACL("", stat18);
        long long20 = dataTree0.getTreeDigest();
        org.apache.jute.InputArchive inputArchive21 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean23 = dataTree0.deserializeZxidDigest(inputArchive21, (long) 5);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.InputArchive.readLong(String)\" because \"ia\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + 0L + "'", long5 == 0L);
        org.junit.Assert.assertNull(str7);
        org.junit.Assert.assertNotNull(referenceCountedACLCache8);
        org.junit.Assert.assertNotNull(referenceCountedACLCache11);
        org.junit.Assert.assertNotNull(watchesSummary12);
        org.junit.Assert.assertTrue("'" + int13 + "' != '" + 0 + "'", int13 == 0);
        org.junit.Assert.assertNotNull(aCLList16);
        org.junit.Assert.assertNotNull(stat18);
        org.junit.Assert.assertNotNull(aCLList19);
        org.junit.Assert.assertTrue("'" + long20 + "' != '" + 1371985504L + "'", long20 == 1371985504L);
    }

    @Test
    public void test086() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test086");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        java.util.Set<String> strSet4 = dataTree0.getContainers();
        java.util.Map<Long, java.util.Set<String>> longMap5 = dataTree0.getEphemerals();
        org.apache.jute.InputArchive inputArchive6 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean8 = dataTree0.deserializeZxidDigest(inputArchive6, (long) (byte) -1);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.InputArchive.readLong(String)\" because \"ia\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(strSet4);
        org.junit.Assert.assertNotNull(longMap5);
    }

    @Test
    public void test087() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test087");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        byte[] byteArray9 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat13 = dataTree0.setData("", byteArray9, (int) (short) -1, (long) (byte) 10, (long) 'a');
        org.apache.zookeeper.server.DataTree.ZxidDigest zxidDigest14 = dataTree0.getDigestFromLoadedSnapshot();
        dataTree0.addConfigNode();
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertNotNull(byteArray9);
        org.junit.Assert.assertArrayEquals(byteArray9, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat13);
        org.junit.Assert.assertNull(zxidDigest14);
    }

    @Test
    public void test088() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test088");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        org.apache.zookeeper.server.DataNode dataNode6 = dataTree0.getNode("");
        long long7 = dataTree0.approximateDataSize();
        String str9 = dataTree0.getMaxPrefixWithQuota("hi!");
        org.apache.zookeeper.Watcher watcher10 = null;
        dataTree0.removeCnxn(watcher10);
        byte[] byteArray16 = new byte[] { (byte) 100, (byte) -1, (byte) 10 };
        org.apache.zookeeper.data.Stat stat20 = dataTree0.setData("", byteArray16, (int) (short) -1, 1371985504L, (long) (short) 1);
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNotNull(dataNode6);
        org.junit.Assert.assertTrue("'" + long7 + "' != '" + 44L + "'", long7 == 44L);
        org.junit.Assert.assertNull(str9);
        org.junit.Assert.assertNotNull(byteArray16);
        org.junit.Assert.assertArrayEquals(byteArray16, new byte[] { (byte) 100, (byte) -1, (byte) 10 });
        org.junit.Assert.assertNotNull(stat20);
    }

    @Test
    public void test089() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test089");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        long long4 = dataTree0.cachedApproximateDataSize();
        org.apache.jute.OutputArchive outputArchive5 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean6 = dataTree0.serializeLastProcessedZxid(outputArchive5);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeLong(long, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + 44L + "'", long4 == 44L);
    }

    @Test
    public void test090() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test090");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        int int3 = dataTree0.aclCacheSize();
        String[] strArray7 = new String[] { "hi!", "hi!" };
        java.util.ArrayList<String> strList8 = new java.util.ArrayList<String>();
        boolean boolean9 = java.util.Collections.addAll((java.util.Collection<String>) strList8, strArray7);
        String[] strArray12 = new String[] { "hi!", "hi!" };
        java.util.ArrayList<String> strList13 = new java.util.ArrayList<String>();
        boolean boolean14 = java.util.Collections.addAll((java.util.Collection<String>) strList13, strArray12);
        String[] strArray17 = new String[] { "", "" };
        java.util.ArrayList<String> strList18 = new java.util.ArrayList<String>();
        boolean boolean19 = java.util.Collections.addAll((java.util.Collection<String>) strList18, strArray17);
        java.util.List<String> strList20 = null;
        String[] strArray22 = new String[] { "" };
        java.util.ArrayList<String> strList23 = new java.util.ArrayList<String>();
        boolean boolean24 = java.util.Collections.addAll((java.util.Collection<String>) strList23, strArray22);
        org.apache.zookeeper.Watcher watcher25 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.setWatches((long) (byte) 1, (java.util.List<String>) strList8, (java.util.List<String>) strList13, (java.util.List<String>) strList18, strList20, (java.util.List<String>) strList23, watcher25);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher.process(org.apache.zookeeper.WatchedEvent)\" because \"watcher\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertTrue("'" + int3 + "' != '" + 1 + "'", int3 == 1);
        org.junit.Assert.assertNotNull(strArray7);
        org.junit.Assert.assertArrayEquals(strArray7, new String[] { "hi!", "hi!" });
        org.junit.Assert.assertTrue("'" + boolean9 + "' != '" + true + "'", boolean9 == true);
        org.junit.Assert.assertNotNull(strArray12);
        org.junit.Assert.assertArrayEquals(strArray12, new String[] { "hi!", "hi!" });
        org.junit.Assert.assertTrue("'" + boolean14 + "' != '" + true + "'", boolean14 == true);
        org.junit.Assert.assertNotNull(strArray17);
        org.junit.Assert.assertArrayEquals(strArray17, new String[] { "", "" });
        org.junit.Assert.assertTrue("'" + boolean19 + "' != '" + true + "'", boolean19 == true);
        org.junit.Assert.assertNotNull(strArray22);
        org.junit.Assert.assertArrayEquals(strArray22, new String[] { "" });
        org.junit.Assert.assertTrue("'" + boolean24 + "' != '" + true + "'", boolean24 == true);
    }

    @Test
    public void test091() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test091");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        java.util.Set<String> strSet2 = dataTree0.getEphemerals((long) 10);
        java.util.Map<Long, java.util.Set<String>> longMap3 = dataTree0.getEphemerals();
        String str5 = dataTree0.getMaxPrefixWithQuota("hi!");
        org.apache.zookeeper.server.DataTree.ZxidDigest zxidDigest6 = dataTree0.getLastProcessedZxidDigest();
        org.junit.Assert.assertNotNull(strSet2);
        org.junit.Assert.assertNotNull(longMap3);
        org.junit.Assert.assertNull(str5);
        org.junit.Assert.assertNull(zxidDigest6);
    }

    @Test
    public void test092() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test092");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.txn.TxnHeader txnHeader4 = null;
        org.apache.zookeeper.server.DataTree dataTree5 = new org.apache.zookeeper.server.DataTree();
        long long6 = dataTree5.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher8 = null;
        org.apache.zookeeper.data.Stat stat9 = dataTree5.statNode("", watcher8);
        org.apache.zookeeper.server.DataNode dataNode11 = dataTree5.getNode("");
        org.apache.zookeeper.txn.TxnDigest txnDigest12 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult13 = dataTree0.processTxn(txnHeader4, (org.apache.jute.Record) dataNode11, txnDigest12);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getClientId()\" because \"header\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertTrue("'" + long6 + "' != '" + 0L + "'", long6 == 0L);
        org.junit.Assert.assertNotNull(stat9);
        org.junit.Assert.assertNotNull(dataNode11);
    }

    @Test
    public void test093() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test093");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        byte[] byteArray9 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat13 = dataTree0.setData("", byteArray9, (int) (short) -1, (long) (byte) 10, (long) 'a');
        org.apache.zookeeper.txn.TxnHeader txnHeader14 = null;
        org.apache.zookeeper.server.DataTree dataTree15 = new org.apache.zookeeper.server.DataTree();
        long long16 = dataTree15.lastProcessedZxid;
        String str18 = dataTree15.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache19 = dataTree15.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree21 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache22 = dataTree21.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary23 = dataTree21.getWatchesSummary();
        int int24 = dataTree21.getWatchCount();
        org.apache.zookeeper.data.Stat stat26 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList27 = dataTree21.getACL("", stat26);
        org.apache.zookeeper.data.Stat stat29 = dataTree15.setACL("", aCLList27, 100);
        org.apache.zookeeper.txn.TxnDigest txnDigest30 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean31 = dataTree0.compareDigest(txnHeader14, (org.apache.jute.Record) stat29, txnDigest30);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getZxid()\" because \"header\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertNotNull(byteArray9);
        org.junit.Assert.assertArrayEquals(byteArray9, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat13);
        org.junit.Assert.assertTrue("'" + long16 + "' != '" + 0L + "'", long16 == 0L);
        org.junit.Assert.assertNull(str18);
        org.junit.Assert.assertNotNull(referenceCountedACLCache19);
        org.junit.Assert.assertNotNull(referenceCountedACLCache22);
        org.junit.Assert.assertNotNull(watchesSummary23);
        org.junit.Assert.assertTrue("'" + int24 + "' != '" + 0 + "'", int24 == 0);
        org.junit.Assert.assertNotNull(aCLList27);
        org.junit.Assert.assertNotNull(stat29);
    }

    @Test
    public void test094() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test094");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        long long5 = dataTree0.getTreeDigest();
        int int7 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.txn.TxnHeader txnHeader8 = null;
        org.apache.zookeeper.data.StatPersisted statPersisted12 = org.apache.zookeeper.server.DataTree.createStat((long) (byte) 0, 1L, (-1L));
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult13 = dataTree0.processTxn(txnHeader8, (org.apache.jute.Record) statPersisted12);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getClientId()\" because \"header\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + 1371985504L + "'", long5 == 1371985504L);
        org.junit.Assert.assertTrue("'" + int7 + "' != '" + 4 + "'", int7 == 4);
        org.junit.Assert.assertNotNull(statPersisted12);
    }

    @Test
    public void test095() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test095");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        long long4 = dataTree0.cachedApproximateDataSize();
        int int5 = dataTree0.getNodeCount();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + 44L + "'", long4 == 44L);
        org.junit.Assert.assertTrue("'" + int5 + "' != '" + 5 + "'", int5 == 5);
    }

    @Test
    public void test096() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test096");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        org.apache.zookeeper.Watcher watcher6 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.addWatch("hi!", watcher6, (int) ' ');
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Unsupported mode: 32");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
    }

    @Test
    public void test097() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test097");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        java.util.Set<String> strSet6 = dataTree0.getEphemerals(10L);
        org.apache.jute.OutputArchive outputArchive7 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean8 = dataTree0.serializeLastProcessedZxid(outputArchive7);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeLong(long, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNotNull(strSet6);
    }

    @Test
    public void test098() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test098");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        int int1 = dataTree0.getEphemeralsCount();
        java.util.Set<String> strSet2 = dataTree0.getTtls();
        org.apache.jute.OutputArchive outputArchive3 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serialize(outputArchive3, "");
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeInt(int, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int1 + "' != '" + 0 + "'", int1 == 0);
        org.junit.Assert.assertNotNull(strSet2);
    }

    @Test
    public void test099() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test099");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        String str6 = dataTree0.getMaxPrefixWithQuota("hi!");
        int int7 = dataTree0.getWatchCount();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNull(str6);
        org.junit.Assert.assertTrue("'" + int7 + "' != '" + 0 + "'", int7 == 0);
        org.junit.Assert.assertNotNull(zxidDigestList8);
    }

    @Test
    public void test100() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test100");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        String str5 = dataTree0.getMaxPrefixWithQuota("hi!");
        org.apache.zookeeper.Watcher watcher6 = null;
        dataTree0.removeCnxn(watcher6);
        java.io.PrintWriter printWriter8 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.dumpEphemerals(printWriter8);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.PrintWriter.println(String)\" because \"writer\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNull(str5);
    }

    @Test
    public void test101() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test101");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        java.util.Set<String> strSet4 = dataTree0.getContainers();
        java.util.Map<Long, java.util.Set<String>> longMap5 = dataTree0.getEphemerals();
        long long6 = dataTree0.lastProcessedZxid;
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(strSet4);
        org.junit.Assert.assertNotNull(longMap5);
        org.junit.Assert.assertTrue("'" + long6 + "' != '" + 0L + "'", long6 == 0L);
    }

    @Test
    public void test102() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test102");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        long long5 = dataTree0.getTreeDigest();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList6 = dataTree0.getDigestLog();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + 1371985504L + "'", long5 == 1371985504L);
        org.junit.Assert.assertNotNull(zxidDigestList6);
    }

    @Test
    public void test103() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test103");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        int int3 = dataTree0.getWatchCount();
        org.apache.zookeeper.server.watch.WatchesPathReport watchesPathReport4 = dataTree0.getWatchesByPath();
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertTrue("'" + int3 + "' != '" + 0 + "'", int3 == 0);
        org.junit.Assert.assertNotNull(watchesPathReport4);
    }

    @Test
    public void test104() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test104");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache5 = dataTree0.getReferenceCountedAclCache();
        dataTree0.shutdownWatcher();
        java.io.PrintWriter printWriter7 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.dumpEphemerals(printWriter7);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.PrintWriter.println(String)\" because \"writer\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
        org.junit.Assert.assertNotNull(referenceCountedACLCache5);
    }

    @Test
    public void test105() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test105");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        java.util.Set<String> strSet2 = dataTree0.getEphemerals((long) 10);
        java.util.Map<Long, java.util.Set<String>> longMap3 = dataTree0.getEphemerals();
        String str5 = dataTree0.getMaxPrefixWithQuota("hi!");
        long long6 = dataTree0.getTreeDigest();
        org.apache.jute.OutputArchive outputArchive7 = null;
        org.apache.zookeeper.server.DataTree dataTree9 = new org.apache.zookeeper.server.DataTree();
        long long10 = dataTree9.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher12 = null;
        org.apache.zookeeper.data.Stat stat13 = dataTree9.statNode("", watcher12);
        org.apache.zookeeper.server.DataNode dataNode15 = dataTree9.getNode("");
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeNodeData(outputArchive7, "hi!", dataNode15);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeString(String, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(strSet2);
        org.junit.Assert.assertNotNull(longMap3);
        org.junit.Assert.assertNull(str5);
        org.junit.Assert.assertTrue("'" + long6 + "' != '" + 1371985504L + "'", long6 == 1371985504L);
        org.junit.Assert.assertTrue("'" + long10 + "' != '" + 0L + "'", long10 == 0L);
        org.junit.Assert.assertNotNull(stat13);
        org.junit.Assert.assertNotNull(dataNode15);
    }

    @Test
    public void test106() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test106");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        byte[] byteArray9 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat13 = dataTree0.setData("", byteArray9, (int) (short) -1, (long) (byte) 10, (long) 'a');
        String str15 = dataTree0.getMaxPrefixWithQuota("");
        java.util.Set<String> strSet17 = dataTree0.getEphemerals((long) 'a');
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertNotNull(byteArray9);
        org.junit.Assert.assertArrayEquals(byteArray9, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat13);
        org.junit.Assert.assertNull(str15);
        org.junit.Assert.assertNotNull(strSet17);
    }

    @Test
    public void test107() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test107");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        org.apache.zookeeper.server.DataNode dataNode6 = dataTree0.getNode("");
        long long7 = dataTree0.approximateDataSize();
        String str9 = dataTree0.getMaxPrefixWithQuota("hi!");
        org.apache.jute.OutputArchive outputArchive10 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeAcls(outputArchive10);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeInt(int, String)\" because \"oa\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNotNull(dataNode6);
        org.junit.Assert.assertTrue("'" + long7 + "' != '" + 44L + "'", long7 == 44L);
        org.junit.Assert.assertNull(str9);
    }

    @Test
    public void test108() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test108");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        org.apache.zookeeper.server.DataTree dataTree4 = new org.apache.zookeeper.server.DataTree();
        long long5 = dataTree4.lastProcessedZxid;
        String str7 = dataTree4.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache8 = dataTree4.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree10 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache11 = dataTree10.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary12 = dataTree10.getWatchesSummary();
        int int13 = dataTree10.getWatchCount();
        org.apache.zookeeper.data.Stat stat15 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList16 = dataTree10.getACL("", stat15);
        org.apache.zookeeper.data.Stat stat18 = dataTree4.setACL("", aCLList16, 100);
        java.util.List<org.apache.zookeeper.data.ACL> aCLList19 = dataTree0.getACL("", stat18);
        java.io.PrintWriter printWriter20 = null;
        dataTree0.dumpWatches(printWriter20, false);
        String str24 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.jute.InputArchive inputArchive25 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.deserialize(inputArchive25, "");
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.InputArchive.readInt(String)\" because \"ia\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + 0L + "'", long5 == 0L);
        org.junit.Assert.assertNull(str7);
        org.junit.Assert.assertNotNull(referenceCountedACLCache8);
        org.junit.Assert.assertNotNull(referenceCountedACLCache11);
        org.junit.Assert.assertNotNull(watchesSummary12);
        org.junit.Assert.assertTrue("'" + int13 + "' != '" + 0 + "'", int13 == 0);
        org.junit.Assert.assertNotNull(aCLList16);
        org.junit.Assert.assertNotNull(stat18);
        org.junit.Assert.assertNotNull(aCLList19);
        org.junit.Assert.assertNull(str24);
    }

    @Test
    public void test109() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test109");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        int int5 = dataTree0.getWatchCount();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
        org.junit.Assert.assertTrue("'" + int5 + "' != '" + 0 + "'", int5 == 0);
    }

    @Test
    public void test110() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test110");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        java.util.Set<String> strSet4 = dataTree0.getContainers();
        java.util.Map<Long, java.util.Set<String>> longMap5 = dataTree0.getEphemerals();
        java.io.PrintWriter printWriter6 = null;
        dataTree0.dumpWatches(printWriter6, true);
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList9 = dataTree0.getDigestLog();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(strSet4);
        org.junit.Assert.assertNotNull(longMap5);
        org.junit.Assert.assertNotNull(zxidDigestList9);
    }

    @Test
    public void test111() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test111");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        org.apache.zookeeper.server.DataTree dataTree4 = new org.apache.zookeeper.server.DataTree();
        long long5 = dataTree4.lastProcessedZxid;
        String str7 = dataTree4.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache8 = dataTree4.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree10 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache11 = dataTree10.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary12 = dataTree10.getWatchesSummary();
        int int13 = dataTree10.getWatchCount();
        org.apache.zookeeper.data.Stat stat15 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList16 = dataTree10.getACL("", stat15);
        org.apache.zookeeper.data.Stat stat18 = dataTree4.setACL("", aCLList16, 100);
        java.util.List<org.apache.zookeeper.data.ACL> aCLList19 = dataTree0.getACL("", stat18);
        long long20 = dataTree0.getTreeDigest();
        java.io.PrintWriter printWriter21 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.dumpEphemerals(printWriter21);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.PrintWriter.println(String)\" because \"writer\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + 0L + "'", long5 == 0L);
        org.junit.Assert.assertNull(str7);
        org.junit.Assert.assertNotNull(referenceCountedACLCache8);
        org.junit.Assert.assertNotNull(referenceCountedACLCache11);
        org.junit.Assert.assertNotNull(watchesSummary12);
        org.junit.Assert.assertTrue("'" + int13 + "' != '" + 0 + "'", int13 == 0);
        org.junit.Assert.assertNotNull(aCLList16);
        org.junit.Assert.assertNotNull(stat18);
        org.junit.Assert.assertNotNull(aCLList19);
        org.junit.Assert.assertTrue("'" + long20 + "' != '" + 1371985504L + "'", long20 == 1371985504L);
    }

    @Test
    public void test112() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test112");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        int int1 = dataTree0.getEphemeralsCount();
        org.apache.zookeeper.txn.TxnHeader txnHeader2 = null;
        org.apache.zookeeper.data.StatPersisted statPersisted6 = org.apache.zookeeper.server.DataTree.createStat((long) (byte) 0, 1L, (-1L));
        org.apache.zookeeper.txn.TxnDigest txnDigest7 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult8 = dataTree0.processTxn(txnHeader2, (org.apache.jute.Record) statPersisted6, txnDigest7);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getClientId()\" because \"header\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int1 + "' != '" + 0 + "'", int1 == 0);
        org.junit.Assert.assertNotNull(statPersisted6);
    }

    @Test
    public void test113() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test113");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache1 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary2 = dataTree0.getWatchesSummary();
        int int3 = dataTree0.getWatchCount();
        String str5 = dataTree0.getMaxPrefixWithQuota("hi!");
        dataTree0.setCversionPzxid("", (int) 'a', 100L);
        java.util.Collection<Long> longCollection10 = dataTree0.getSessions();
        org.apache.zookeeper.Watcher.WatcherType watcherType12 = null;
        org.apache.zookeeper.Watcher watcher13 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean14 = dataTree0.removeWatch("", watcherType12, watcher13);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher$WatcherType.ordinal()\" because \"type\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(referenceCountedACLCache1);
        org.junit.Assert.assertNotNull(watchesSummary2);
        org.junit.Assert.assertTrue("'" + int3 + "' != '" + 0 + "'", int3 == 0);
        org.junit.Assert.assertNull(str5);
        org.junit.Assert.assertNotNull(longCollection10);
    }

    @Test
    public void test114() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test114");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        java.util.Set<String> strSet2 = dataTree0.getEphemerals((long) 10);
        java.util.Set<String> strSet4 = dataTree0.getEphemerals((long) 'a');
        org.junit.Assert.assertNotNull(strSet2);
        org.junit.Assert.assertNotNull(strSet4);
    }





    @Test
    public void test117() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test117");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        long long4 = dataTree0.cachedApproximateDataSize();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList5 = dataTree0.getDigestLog();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + 44L + "'", long4 == 44L);
        org.junit.Assert.assertNotNull(zxidDigestList5);
    }

    @Test
    public void test118() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test118");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        org.apache.zookeeper.server.DataNode dataNode6 = dataTree0.getNode("");
        org.apache.zookeeper.Watcher watcher7 = null;
        dataTree0.removeCnxn(watcher7);
        org.apache.zookeeper.server.DataTree dataTree10 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache11 = dataTree10.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary12 = dataTree10.getWatchesSummary();
        byte[] byteArray19 = new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 };
        org.apache.zookeeper.data.Stat stat23 = dataTree10.setData("", byteArray19, (int) (short) -1, (long) (byte) 10, (long) 'a');
        org.apache.zookeeper.data.Stat stat27 = dataTree0.setData("", byteArray19, (int) (short) 1, 0L, 0L);
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList28 = dataTree0.getDigestLog();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertNotNull(dataNode6);
        org.junit.Assert.assertNotNull(referenceCountedACLCache11);
        org.junit.Assert.assertNotNull(watchesSummary12);
        org.junit.Assert.assertNotNull(byteArray19);
        org.junit.Assert.assertArrayEquals(byteArray19, new byte[] { (byte) 1, (byte) 1, (byte) 0, (byte) 1, (byte) 100 });
        org.junit.Assert.assertNotNull(stat23);
        org.junit.Assert.assertNotNull(stat27);
        org.junit.Assert.assertNotNull(zxidDigestList28);
    }

    @Test
    public void test119() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test119");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        dataTree0.addConfigNode();
        org.apache.zookeeper.server.watch.WatchesReport watchesReport6 = dataTree0.getWatches();
        org.apache.zookeeper.server.watch.WatchesPathReport watchesPathReport7 = dataTree0.getWatchesByPath();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
        org.junit.Assert.assertNotNull(watchesReport6);
        org.junit.Assert.assertNotNull(watchesPathReport7);
    }

    @Test
    public void test120() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test120");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        long long5 = dataTree0.getTreeDigest();
        org.apache.zookeeper.server.DataTree dataTree7 = new org.apache.zookeeper.server.DataTree();
        long long8 = dataTree7.lastProcessedZxid;
        String str10 = dataTree7.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache11 = dataTree7.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree13 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache14 = dataTree13.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary15 = dataTree13.getWatchesSummary();
        int int16 = dataTree13.getWatchCount();
        org.apache.zookeeper.data.Stat stat18 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList19 = dataTree13.getACL("", stat18);
        org.apache.zookeeper.data.Stat stat21 = dataTree7.setACL("", aCLList19, 100);
        org.apache.zookeeper.data.Stat stat23 = dataTree0.setACL("", aCLList19, (int) (byte) 1);
        org.apache.zookeeper.server.DataTree dataTree25 = new org.apache.zookeeper.server.DataTree();
        long long26 = dataTree25.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher28 = null;
        org.apache.zookeeper.data.Stat stat29 = dataTree25.statNode("", watcher28);
        long long30 = dataTree25.getTreeDigest();
        org.apache.zookeeper.server.DataTree dataTree32 = new org.apache.zookeeper.server.DataTree();
        long long33 = dataTree32.lastProcessedZxid;
        String str35 = dataTree32.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache36 = dataTree32.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree38 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache39 = dataTree38.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary40 = dataTree38.getWatchesSummary();
        int int41 = dataTree38.getWatchCount();
        org.apache.zookeeper.data.Stat stat43 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList44 = dataTree38.getACL("", stat43);
        org.apache.zookeeper.data.Stat stat46 = dataTree32.setACL("", aCLList44, 100);
        org.apache.zookeeper.data.Stat stat48 = dataTree25.setACL("", aCLList44, (int) (byte) 1);
        java.util.List<org.apache.zookeeper.data.ACL> aCLList49 = dataTree0.getACL("", stat48);
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary50 = dataTree0.getWatchesSummary();
        int int51 = dataTree0.aclCacheSize();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + 1371985504L + "'", long5 == 1371985504L);
        org.junit.Assert.assertTrue("'" + long8 + "' != '" + 0L + "'", long8 == 0L);
        org.junit.Assert.assertNull(str10);
        org.junit.Assert.assertNotNull(referenceCountedACLCache11);
        org.junit.Assert.assertNotNull(referenceCountedACLCache14);
        org.junit.Assert.assertNotNull(watchesSummary15);
        org.junit.Assert.assertTrue("'" + int16 + "' != '" + 0 + "'", int16 == 0);
        org.junit.Assert.assertNotNull(aCLList19);
        org.junit.Assert.assertNotNull(stat21);
        org.junit.Assert.assertNotNull(stat23);
        org.junit.Assert.assertTrue("'" + long26 + "' != '" + 0L + "'", long26 == 0L);
        org.junit.Assert.assertNotNull(stat29);
        org.junit.Assert.assertTrue("'" + long30 + "' != '" + 1371985504L + "'", long30 == 1371985504L);
        org.junit.Assert.assertTrue("'" + long33 + "' != '" + 0L + "'", long33 == 0L);
        org.junit.Assert.assertNull(str35);
        org.junit.Assert.assertNotNull(referenceCountedACLCache36);
        org.junit.Assert.assertNotNull(referenceCountedACLCache39);
        org.junit.Assert.assertNotNull(watchesSummary40);
        org.junit.Assert.assertTrue("'" + int41 + "' != '" + 0 + "'", int41 == 0);
        org.junit.Assert.assertNotNull(aCLList44);
        org.junit.Assert.assertNotNull(stat46);
        org.junit.Assert.assertNotNull(stat48);
        org.junit.Assert.assertNotNull(aCLList49);
        org.junit.Assert.assertNotNull(watchesSummary50);
        org.junit.Assert.assertTrue("'" + int51 + "' != '" + 2 + "'", int51 == 2);
    }

    @Test
    public void test121() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test121");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        String str3 = dataTree0.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache4 = dataTree0.getReferenceCountedAclCache();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache5 = dataTree0.getReferenceCountedAclCache();
        dataTree0.shutdownWatcher();
        org.apache.zookeeper.server.DataTree dataTree8 = new org.apache.zookeeper.server.DataTree();
        long long9 = dataTree8.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher11 = null;
        org.apache.zookeeper.data.Stat stat12 = dataTree8.statNode("", watcher11);
        long long13 = dataTree8.getTreeDigest();
        org.apache.zookeeper.server.DataTree dataTree15 = new org.apache.zookeeper.server.DataTree();
        long long16 = dataTree15.lastProcessedZxid;
        String str18 = dataTree15.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache19 = dataTree15.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree21 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache22 = dataTree21.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary23 = dataTree21.getWatchesSummary();
        int int24 = dataTree21.getWatchCount();
        org.apache.zookeeper.data.Stat stat26 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList27 = dataTree21.getACL("", stat26);
        org.apache.zookeeper.data.Stat stat29 = dataTree15.setACL("", aCLList27, 100);
        org.apache.zookeeper.data.Stat stat31 = dataTree8.setACL("", aCLList27, (int) (byte) 1);
        org.apache.zookeeper.Watcher watcher32 = null;
        byte[] byteArray33 = dataTree0.getData("", stat31, watcher32);
        java.io.PrintWriter printWriter34 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.dumpWatchesSummary(printWriter34);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"java.io.PrintWriter.print(String)\" because \"writer\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNull(str3);
        org.junit.Assert.assertNotNull(referenceCountedACLCache4);
        org.junit.Assert.assertNotNull(referenceCountedACLCache5);
        org.junit.Assert.assertTrue("'" + long9 + "' != '" + 0L + "'", long9 == 0L);
        org.junit.Assert.assertNotNull(stat12);
        org.junit.Assert.assertTrue("'" + long13 + "' != '" + 1371985504L + "'", long13 == 1371985504L);
        org.junit.Assert.assertTrue("'" + long16 + "' != '" + 0L + "'", long16 == 0L);
        org.junit.Assert.assertNull(str18);
        org.junit.Assert.assertNotNull(referenceCountedACLCache19);
        org.junit.Assert.assertNotNull(referenceCountedACLCache22);
        org.junit.Assert.assertNotNull(watchesSummary23);
        org.junit.Assert.assertTrue("'" + int24 + "' != '" + 0 + "'", int24 == 0);
        org.junit.Assert.assertNotNull(aCLList27);
        org.junit.Assert.assertNotNull(stat29);
        org.junit.Assert.assertNotNull(stat31);
        org.junit.Assert.assertNotNull(byteArray33);
        org.junit.Assert.assertArrayEquals(byteArray33, new byte[] {});
    }

    @Test
    public void test122() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test122");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        java.util.Set<String> strSet2 = dataTree0.getEphemerals((long) 10);
        java.util.Map<Long, java.util.Set<String>> longMap3 = dataTree0.getEphemerals();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary4 = dataTree0.getWatchesSummary();
        org.apache.zookeeper.Watcher.WatcherType watcherType6 = null;
        org.apache.zookeeper.Watcher watcher7 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean8 = dataTree0.containsWatcher("", watcherType6, watcher7);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher$WatcherType.ordinal()\" because \"type\" is null");
        } catch (NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(strSet2);
        org.junit.Assert.assertNotNull(longMap3);
        org.junit.Assert.assertNotNull(watchesSummary4);
    }

    @Test
    public void test123() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test123");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        int int1 = dataTree0.getEphemeralsCount();
        long long2 = dataTree0.cachedApproximateDataSize();
        int int4 = dataTree0.getAllChildrenNumber("hi!");
        org.apache.zookeeper.Watcher watcher6 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.data.Stat stat7 = dataTree0.statNode("hi!", watcher6);
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int1 + "' != '" + 0 + "'", int1 == 0);
        org.junit.Assert.assertTrue("'" + long2 + "' != '" + 44L + "'", long2 == 44L);
        org.junit.Assert.assertTrue("'" + int4 + "' != '" + 0 + "'", int4 == 0);
    }

    @Test
    public void test124() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test124");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher3 = null;
        org.apache.zookeeper.data.Stat stat4 = dataTree0.statNode("", watcher3);
        long long5 = dataTree0.getTreeDigest();
        org.apache.zookeeper.server.DataTree dataTree7 = new org.apache.zookeeper.server.DataTree();
        long long8 = dataTree7.lastProcessedZxid;
        String str10 = dataTree7.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache11 = dataTree7.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree13 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache14 = dataTree13.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary15 = dataTree13.getWatchesSummary();
        int int16 = dataTree13.getWatchCount();
        org.apache.zookeeper.data.Stat stat18 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList19 = dataTree13.getACL("", stat18);
        org.apache.zookeeper.data.Stat stat21 = dataTree7.setACL("", aCLList19, 100);
        org.apache.zookeeper.data.Stat stat23 = dataTree0.setACL("", aCLList19, (int) (byte) 1);
        org.apache.zookeeper.server.DataTree dataTree25 = new org.apache.zookeeper.server.DataTree();
        long long26 = dataTree25.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher28 = null;
        org.apache.zookeeper.data.Stat stat29 = dataTree25.statNode("", watcher28);
        long long30 = dataTree25.getTreeDigest();
        org.apache.zookeeper.server.DataTree dataTree32 = new org.apache.zookeeper.server.DataTree();
        long long33 = dataTree32.lastProcessedZxid;
        String str35 = dataTree32.getMaxPrefixWithQuota("");
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache36 = dataTree32.getReferenceCountedAclCache();
        org.apache.zookeeper.server.DataTree dataTree38 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.server.ReferenceCountedACLCache referenceCountedACLCache39 = dataTree38.getReferenceCountedAclCache();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary40 = dataTree38.getWatchesSummary();
        int int41 = dataTree38.getWatchCount();
        org.apache.zookeeper.data.Stat stat43 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList44 = dataTree38.getACL("", stat43);
        org.apache.zookeeper.data.Stat stat46 = dataTree32.setACL("", aCLList44, 100);
        org.apache.zookeeper.data.Stat stat48 = dataTree25.setACL("", aCLList44, (int) (byte) 1);
        java.util.List<org.apache.zookeeper.data.ACL> aCLList49 = dataTree0.getACL("", stat48);
        dataTree0.shutdownWatcher();
        java.util.Set<String> strSet52 = dataTree0.getEphemerals((long) 128);
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 0L + "'", long1 == 0L);
        org.junit.Assert.assertNotNull(stat4);
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + 1371985504L + "'", long5 == 1371985504L);
        org.junit.Assert.assertTrue("'" + long8 + "' != '" + 0L + "'", long8 == 0L);
        org.junit.Assert.assertNull(str10);
        org.junit.Assert.assertNotNull(referenceCountedACLCache11);
        org.junit.Assert.assertNotNull(referenceCountedACLCache14);
        org.junit.Assert.assertNotNull(watchesSummary15);
        org.junit.Assert.assertTrue("'" + int16 + "' != '" + 0 + "'", int16 == 0);
        org.junit.Assert.assertNotNull(aCLList19);
        org.junit.Assert.assertNotNull(stat21);
        org.junit.Assert.assertNotNull(stat23);
        org.junit.Assert.assertTrue("'" + long26 + "' != '" + 0L + "'", long26 == 0L);
        org.junit.Assert.assertNotNull(stat29);
        org.junit.Assert.assertTrue("'" + long30 + "' != '" + 1371985504L + "'", long30 == 1371985504L);
        org.junit.Assert.assertTrue("'" + long33 + "' != '" + 0L + "'", long33 == 0L);
        org.junit.Assert.assertNull(str35);
        org.junit.Assert.assertNotNull(referenceCountedACLCache36);
        org.junit.Assert.assertNotNull(referenceCountedACLCache39);
        org.junit.Assert.assertNotNull(watchesSummary40);
        org.junit.Assert.assertTrue("'" + int41 + "' != '" + 0 + "'", int41 == 0);
        org.junit.Assert.assertNotNull(aCLList44);
        org.junit.Assert.assertNotNull(stat46);
        org.junit.Assert.assertNotNull(stat48);
        org.junit.Assert.assertNotNull(aCLList49);
        org.junit.Assert.assertNotNull(strSet52);
    }
}

