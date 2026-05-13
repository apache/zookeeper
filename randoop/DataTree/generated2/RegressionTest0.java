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
        int int0 = org.apache.zookeeper.server.DataTree.DIGEST_LOG_LIMIT;
        org.junit.Assert.assertTrue("'" + int0 + "' != '" + 1024 + "'", int0 == 1024);
    }

    @Test
    public void test002() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test002");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        boolean boolean6 = processTxnResult0.equals((java.lang.Object) 0);
        java.lang.Class<?> wildcardClass7 = processTxnResult0.getClass();
        org.junit.Assert.assertTrue("'" + boolean6 + "' != '" + false + "'", boolean6 == false);
        org.junit.Assert.assertNotNull(wildcardClass7);
    }

    @Test
    public void test003() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test003");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.zxid = (short) 10;
        processTxnResult0.path = "";
    }

    @Test
    public void test004() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test004");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        java.lang.Class<?> wildcardClass5 = processTxnResult0.getClass();
        org.junit.Assert.assertNotNull(wildcardClass5);
    }

    @Test
    public void test005() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test005");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        long long5 = processTxnResult0.clientId;
        org.junit.Assert.assertTrue("'" + long5 + "' != '" + 0L + "'", long5 == 0L);
    }

    @Test
    public void test006() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test006");
        int int0 = org.apache.zookeeper.server.DataTree.DIGEST_LOG_INTERVAL;
        org.junit.Assert.assertTrue("'" + int0 + "' != '" + 128 + "'", int0 == 128);
    }

    @Test
    public void test007() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test007");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult5 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat6 = null;
        processTxnResult5.stat = stat6;
        processTxnResult5.type = ' ';
        boolean boolean11 = processTxnResult5.equals((java.lang.Object) 0);
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult12 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat13 = null;
        processTxnResult12.stat = stat13;
        processTxnResult12.type = ' ';
        org.apache.zookeeper.server.DataTree.ProcessTxnResult[] processTxnResultArray17 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult[] { processTxnResult12 };
        java.util.ArrayList<org.apache.zookeeper.server.DataTree.ProcessTxnResult> processTxnResultList18 = new java.util.ArrayList<org.apache.zookeeper.server.DataTree.ProcessTxnResult>();
        boolean boolean19 = java.util.Collections.addAll((java.util.Collection<org.apache.zookeeper.server.DataTree.ProcessTxnResult>) processTxnResultList18, processTxnResultArray17);
        processTxnResult5.multiResult = processTxnResultList18;
        boolean boolean21 = processTxnResult0.equals((java.lang.Object) processTxnResultList18);
        int int22 = processTxnResult0.err;
        org.junit.Assert.assertTrue("'" + boolean11 + "' != '" + false + "'", boolean11 == false);
        org.junit.Assert.assertNotNull(processTxnResultArray17);
        org.junit.Assert.assertTrue("'" + boolean19 + "' != '" + true + "'", boolean19 == true);
        org.junit.Assert.assertTrue("'" + boolean21 + "' != '" + false + "'", boolean21 == false);
        org.junit.Assert.assertTrue("'" + int22 + "' != '" + 0 + "'", int22 == 0);
    }

    @Test
    public void test008() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test008");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        int int5 = processTxnResult0.type;
        org.apache.zookeeper.data.Stat stat6 = null;
        processTxnResult0.stat = stat6;
        processTxnResult0.cxid = 'a';
        org.junit.Assert.assertTrue("'" + int5 + "' != '" + 1 + "'", int5 == 1);
    }

    @Test
    public void test009() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test009");
        int int0 = org.apache.zookeeper.server.DataTree.STAT_OVERHEAD_BYTES;
        org.junit.Assert.assertTrue("'" + int0 + "' != '" + 68 + "'", int0 == 68);
    }

    @Test
    public void test010() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test010");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        int int3 = processTxnResult0.err;
        org.junit.Assert.assertTrue("'" + int3 + "' != '" + 0 + "'", int3 == 0);
    }

    @Test
    public void test011() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test011");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        org.apache.zookeeper.data.Stat stat5 = null;
        processTxnResult0.stat = stat5;
        int int7 = processTxnResult0.type;
        org.junit.Assert.assertTrue("'" + int7 + "' != '" + 1 + "'", int7 == 1);
    }

    @Test
    public void test012() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test012");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        int int5 = processTxnResult0.type;
        org.apache.zookeeper.data.Stat stat6 = null;
        processTxnResult0.stat = stat6;
        org.apache.zookeeper.data.Stat stat8 = processTxnResult0.stat;
        org.junit.Assert.assertTrue("'" + int5 + "' != '" + 1 + "'", int5 == 1);
        org.junit.Assert.assertNull(stat8);
    }

    @Test
    public void test013() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test013");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult5 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat6 = null;
        processTxnResult5.stat = stat6;
        processTxnResult5.type = ' ';
        boolean boolean10 = processTxnResult0.equals((java.lang.Object) ' ');
        int int11 = processTxnResult0.err;
        processTxnResult0.type = '4';
        processTxnResult0.type = (short) 1;
        org.junit.Assert.assertTrue("'" + boolean10 + "' != '" + false + "'", boolean10 == false);
        org.junit.Assert.assertTrue("'" + int11 + "' != '" + 0 + "'", int11 == 0);
    }

    @Test
    public void test014() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test014");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        org.apache.zookeeper.data.Stat stat5 = null;
        processTxnResult0.stat = stat5;
        java.lang.String str7 = processTxnResult0.path;
        processTxnResult0.err = 'a';
        org.junit.Assert.assertNull(str7);
    }

    @Test
    public void test015() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test015");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        boolean boolean6 = processTxnResult0.equals((java.lang.Object) 0);
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult7 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat8 = null;
        processTxnResult7.stat = stat8;
        processTxnResult7.type = ' ';
        org.apache.zookeeper.server.DataTree.ProcessTxnResult[] processTxnResultArray12 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult[] { processTxnResult7 };
        java.util.ArrayList<org.apache.zookeeper.server.DataTree.ProcessTxnResult> processTxnResultList13 = new java.util.ArrayList<org.apache.zookeeper.server.DataTree.ProcessTxnResult>();
        boolean boolean14 = java.util.Collections.addAll((java.util.Collection<org.apache.zookeeper.server.DataTree.ProcessTxnResult>) processTxnResultList13, processTxnResultArray12);
        processTxnResult0.multiResult = processTxnResultList13;
        long long16 = processTxnResult0.zxid;
        org.junit.Assert.assertTrue("'" + boolean6 + "' != '" + false + "'", boolean6 == false);
        org.junit.Assert.assertNotNull(processTxnResultArray12);
        org.junit.Assert.assertTrue("'" + boolean14 + "' != '" + true + "'", boolean14 == true);
        org.junit.Assert.assertTrue("'" + long16 + "' != '" + 0L + "'", long16 == 0L);
    }

    @Test
    public void test016() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test016");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult5 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat6 = null;
        processTxnResult5.stat = stat6;
        processTxnResult5.type = ' ';
        boolean boolean11 = processTxnResult5.equals((java.lang.Object) 0);
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult12 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat13 = null;
        processTxnResult12.stat = stat13;
        processTxnResult12.type = ' ';
        org.apache.zookeeper.server.DataTree.ProcessTxnResult[] processTxnResultArray17 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult[] { processTxnResult12 };
        java.util.ArrayList<org.apache.zookeeper.server.DataTree.ProcessTxnResult> processTxnResultList18 = new java.util.ArrayList<org.apache.zookeeper.server.DataTree.ProcessTxnResult>();
        boolean boolean19 = java.util.Collections.addAll((java.util.Collection<org.apache.zookeeper.server.DataTree.ProcessTxnResult>) processTxnResultList18, processTxnResultArray17);
        processTxnResult5.multiResult = processTxnResultList18;
        boolean boolean21 = processTxnResult0.equals((java.lang.Object) processTxnResultList18);
        processTxnResult0.path = "";
        org.junit.Assert.assertTrue("'" + boolean11 + "' != '" + false + "'", boolean11 == false);
        org.junit.Assert.assertNotNull(processTxnResultArray17);
        org.junit.Assert.assertTrue("'" + boolean19 + "' != '" + true + "'", boolean19 == true);
        org.junit.Assert.assertTrue("'" + boolean21 + "' != '" + false + "'", boolean21 == false);
    }

    @Test
    public void test017() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test017");
        org.apache.zookeeper.data.StatPersisted statPersisted3 = org.apache.zookeeper.server.DataTree.createStat((long) (short) 100, (long) (-1), (long) 1024);
        java.lang.Class<?> wildcardClass4 = statPersisted3.getClass();
        org.junit.Assert.assertNotNull(statPersisted3);
        org.junit.Assert.assertNotNull(wildcardClass4);
    }

    @Test
    public void test018() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test018");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        org.apache.zookeeper.data.Stat stat5 = null;
        processTxnResult0.stat = stat5;
    }

    @Test
    public void test019() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test019");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        org.apache.zookeeper.data.Stat stat5 = null;
        processTxnResult0.stat = stat5;
        java.lang.String str7 = processTxnResult0.path;
        java.lang.String str8 = processTxnResult0.path;
        processTxnResult0.clientId = (byte) -1;
        org.junit.Assert.assertNull(str7);
        org.junit.Assert.assertNull(str8);
    }

    @Test
    public void test020() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test020");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        processTxnResult0.err = (byte) -1;
    }

    @Test
    public void test021() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test021");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        int int5 = processTxnResult0.err;
        org.junit.Assert.assertTrue("'" + int5 + "' != '" + 0 + "'", int5 == 0);
    }

    @Test
    public void test022() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test022");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult5 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat6 = null;
        processTxnResult5.stat = stat6;
        processTxnResult5.type = ' ';
        boolean boolean10 = processTxnResult0.equals((java.lang.Object) ' ');
        org.apache.zookeeper.data.Stat stat11 = processTxnResult0.stat;
        org.apache.zookeeper.data.Stat stat12 = null;
        processTxnResult0.stat = stat12;
        org.junit.Assert.assertTrue("'" + boolean10 + "' != '" + false + "'", boolean10 == false);
        org.junit.Assert.assertNull(stat11);
    }

    @Test
    public void test023() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test023");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        int int5 = processTxnResult0.type;
        processTxnResult0.clientId = 10L;
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult8 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat9 = null;
        processTxnResult8.stat = stat9;
        processTxnResult8.type = ' ';
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult13 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat14 = null;
        processTxnResult13.stat = stat14;
        processTxnResult13.type = (short) 1;
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult18 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat19 = null;
        processTxnResult18.stat = stat19;
        processTxnResult18.type = ' ';
        boolean boolean24 = processTxnResult18.equals((java.lang.Object) 0);
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult25 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat26 = null;
        processTxnResult25.stat = stat26;
        processTxnResult25.type = ' ';
        org.apache.zookeeper.server.DataTree.ProcessTxnResult[] processTxnResultArray30 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult[] { processTxnResult25 };
        java.util.ArrayList<org.apache.zookeeper.server.DataTree.ProcessTxnResult> processTxnResultList31 = new java.util.ArrayList<org.apache.zookeeper.server.DataTree.ProcessTxnResult>();
        boolean boolean32 = java.util.Collections.addAll((java.util.Collection<org.apache.zookeeper.server.DataTree.ProcessTxnResult>) processTxnResultList31, processTxnResultArray30);
        processTxnResult18.multiResult = processTxnResultList31;
        boolean boolean34 = processTxnResult13.equals((java.lang.Object) processTxnResultList31);
        processTxnResult8.multiResult = processTxnResultList31;
        processTxnResult0.multiResult = processTxnResultList31;
        org.junit.Assert.assertTrue("'" + int5 + "' != '" + 1 + "'", int5 == 1);
        org.junit.Assert.assertTrue("'" + boolean24 + "' != '" + false + "'", boolean24 == false);
        org.junit.Assert.assertNotNull(processTxnResultArray30);
        org.junit.Assert.assertTrue("'" + boolean32 + "' != '" + true + "'", boolean32 == true);
        org.junit.Assert.assertTrue("'" + boolean34 + "' != '" + false + "'", boolean34 == false);
    }

    @Test
    public void test024() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test024");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        org.apache.zookeeper.data.Stat stat5 = null;
        processTxnResult0.stat = stat5;
        java.util.List<org.apache.zookeeper.server.DataTree.ProcessTxnResult> processTxnResultList7 = null;
        processTxnResult0.multiResult = processTxnResultList7;
    }

    @Test
    public void test025() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test025");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        int int5 = processTxnResult0.type;
        org.apache.zookeeper.data.Stat stat6 = null;
        processTxnResult0.stat = stat6;
        java.util.List<org.apache.zookeeper.server.DataTree.ProcessTxnResult> processTxnResultList8 = processTxnResult0.multiResult;
        org.junit.Assert.assertTrue("'" + int5 + "' != '" + 1 + "'", int5 == 1);
        org.junit.Assert.assertNull(processTxnResultList8);
    }

    @Test
    public void test026() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test026");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        int int1 = processTxnResult0.cxid;
        int int2 = processTxnResult0.err;
        org.junit.Assert.assertTrue("'" + int1 + "' != '" + 0 + "'", int1 == 0);
        org.junit.Assert.assertTrue("'" + int2 + "' != '" + 0 + "'", int2 == 0);
    }

    @Test
    public void test027() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test027");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        org.apache.zookeeper.data.Stat stat5 = processTxnResult0.stat;
        org.apache.zookeeper.data.Stat stat6 = processTxnResult0.stat;
        processTxnResult0.cxid = ' ';
        processTxnResult0.cxid = 1024;
        processTxnResult0.err = (byte) 0;
        org.junit.Assert.assertNull(stat5);
        org.junit.Assert.assertNull(stat6);
    }

    @Test
    public void test028() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test028");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.server.DataNode dataNode6 = dataTree0.getNode("");
        java.lang.String[] strArray10 = new java.lang.String[] { "hi!", "" };
        java.util.ArrayList<java.lang.String> strList11 = new java.util.ArrayList<java.lang.String>();
        boolean boolean12 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList11, strArray10);
        java.lang.String[] strArray15 = new java.lang.String[] { "hi!", "" };
        java.util.ArrayList<java.lang.String> strList16 = new java.util.ArrayList<java.lang.String>();
        boolean boolean17 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList16, strArray15);
        java.lang.String[] strArray19 = new java.lang.String[] { "" };
        java.util.ArrayList<java.lang.String> strList20 = new java.util.ArrayList<java.lang.String>();
        boolean boolean21 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList20, strArray19);
        java.lang.String[] strArray23 = new java.lang.String[] { "" };
        java.util.ArrayList<java.lang.String> strList24 = new java.util.ArrayList<java.lang.String>();
        boolean boolean25 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList24, strArray23);
        java.lang.String[] strArray27 = new java.lang.String[] { "hi!" };
        java.util.ArrayList<java.lang.String> strList28 = new java.util.ArrayList<java.lang.String>();
        boolean boolean29 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList28, strArray27);
        org.apache.zookeeper.Watcher watcher30 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.setWatches((long) (byte) -1, (java.util.List<java.lang.String>) strList11, (java.util.List<java.lang.String>) strList16, (java.util.List<java.lang.String>) strList20, (java.util.List<java.lang.String>) strList24, (java.util.List<java.lang.String>) strList28, watcher30);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher.process(org.apache.zookeeper.WatchedEvent)\" because \"watcher\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(dataNode6);
        org.junit.Assert.assertNotNull(strArray10);
        org.junit.Assert.assertArrayEquals(strArray10, new java.lang.String[] { "hi!", "" });
        org.junit.Assert.assertTrue("'" + boolean12 + "' != '" + true + "'", boolean12 == true);
        org.junit.Assert.assertNotNull(strArray15);
        org.junit.Assert.assertArrayEquals(strArray15, new java.lang.String[] { "hi!", "" });
        org.junit.Assert.assertTrue("'" + boolean17 + "' != '" + true + "'", boolean17 == true);
        org.junit.Assert.assertNotNull(strArray19);
        org.junit.Assert.assertArrayEquals(strArray19, new java.lang.String[] { "" });
        org.junit.Assert.assertTrue("'" + boolean21 + "' != '" + true + "'", boolean21 == true);
        org.junit.Assert.assertNotNull(strArray23);
        org.junit.Assert.assertArrayEquals(strArray23, new java.lang.String[] { "" });
        org.junit.Assert.assertTrue("'" + boolean25 + "' != '" + true + "'", boolean25 == true);
        org.junit.Assert.assertNotNull(strArray27);
        org.junit.Assert.assertArrayEquals(strArray27, new java.lang.String[] { "hi!" });
        org.junit.Assert.assertTrue("'" + boolean29 + "' != '" + true + "'", boolean29 == true);
    }

    @Test
    public void test029() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test029");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.Watcher watcher8 = null;
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.data.Stat stat9 = dataTree0.statNode("hi!", watcher8);
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
    }

    @Test
    public void test030() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test030");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        int int1 = processTxnResult0.cxid;
        int int2 = processTxnResult0.type;
        java.lang.String str3 = processTxnResult0.path;
        org.junit.Assert.assertTrue("'" + int1 + "' != '" + 0 + "'", int1 == 0);
        org.junit.Assert.assertTrue("'" + int2 + "' != '" + 0 + "'", int2 == 0);
        org.junit.Assert.assertNull(str3);
    }

    @Test
    public void test031() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test031");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.Watcher.WatcherType watcherType2 = null;
        org.apache.zookeeper.Watcher watcher3 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean4 = dataTree0.removeWatch("", watcherType2, watcher3);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher$WatcherType.ordinal()\" because \"type\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
    }

    @Test
    public void test032() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test032");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.server.DataNode dataNode6 = dataTree0.getNode("");
        org.apache.jute.OutputArchive outputArchive7 = null;
        org.apache.zookeeper.server.DataTree dataTree9 = new org.apache.zookeeper.server.DataTree();
        dataTree9.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.server.DataNode dataNode15 = dataTree9.getNode("");
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeNodeData(outputArchive7, "hi!", dataNode15);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeString(String, String)\" because \"oa\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(dataNode6);
        org.junit.Assert.assertNotNull(dataNode15);
    }

    @Test
    public void test033() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test033");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("hi!");
        java.util.Set<java.lang.String> strSet7 = dataTree0.getContainers();
        org.apache.jute.OutputArchive outputArchive8 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeNodes(outputArchive8);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeString(String, String)\" because \"oa\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 0 + "'", int6 == 0);
        org.junit.Assert.assertNotNull(strSet7);
    }

    @Test
    public void test034() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test034");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        org.apache.zookeeper.server.DataTree.ZxidDigest zxidDigest8 = dataTree0.getDigestFromLoadedSnapshot();
        org.apache.zookeeper.Watcher watcher10 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.addWatch("", watcher10, 1024);
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Unsupported mode: 1024");
        } catch (java.lang.IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNull(zxidDigest8);
    }

    @Test
    public void test035() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test035");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        org.apache.zookeeper.data.Stat stat5 = processTxnResult0.stat;
        processTxnResult0.err = 0;
        org.junit.Assert.assertNull(stat5);
    }

    @Test
    public void test036() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test036");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        int int5 = processTxnResult0.type;
        processTxnResult0.path = "";
        org.junit.Assert.assertTrue("'" + int5 + "' != '" + 1 + "'", int5 == 1);
    }

    @Test
    public void test037() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test037");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.data.Stat stat6 = null;
        org.apache.zookeeper.Watcher watcher7 = null;
        // The following exception was thrown during execution in test generation
        try {
            byte[] byteArray8 = dataTree0.getData("hi!", stat6, watcher7);
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
    }

    @Test
    public void test038() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test038");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.zxid = (short) 10;
        processTxnResult0.cxid = (short) 0;
    }

    @Test
    public void test039() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test039");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        int int8 = dataTree0.getEphemeralsCount();
        org.apache.zookeeper.txn.TxnHeader txnHeader9 = null;
        org.apache.zookeeper.data.StatPersisted statPersisted13 = org.apache.zookeeper.server.DataTree.createStat(0L, (long) (byte) 10, (long) 128);
        org.apache.zookeeper.txn.TxnDigest txnDigest14 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean15 = dataTree0.compareDigest(txnHeader9, (org.apache.jute.Record) statPersisted13, txnDigest14);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getZxid()\" because \"header\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertTrue("'" + int8 + "' != '" + 0 + "'", int8 == 0);
        org.junit.Assert.assertNotNull(statPersisted13);
    }

    @Test
    public void test040() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test040");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        int int8 = dataTree0.getEphemeralsCount();
        org.apache.zookeeper.txn.TxnHeader txnHeader9 = null;
        org.apache.zookeeper.data.StatPersisted statPersisted13 = org.apache.zookeeper.server.DataTree.createStat((long) (byte) 100, (long) 1, (long) (short) 1);
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult15 = dataTree0.processTxn(txnHeader9, (org.apache.jute.Record) statPersisted13, false);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getClientId()\" because \"header\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertTrue("'" + int8 + "' != '" + 0 + "'", int8 == 0);
        org.junit.Assert.assertNotNull(statPersisted13);
    }

    @Test
    public void test041() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test041");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("hi!");
        java.util.Set<java.lang.String> strSet7 = dataTree0.getContainers();
        org.apache.zookeeper.data.ACL[] aCLArray9 = new org.apache.zookeeper.data.ACL[] {};
        java.util.ArrayList<org.apache.zookeeper.data.ACL> aCLList10 = new java.util.ArrayList<org.apache.zookeeper.data.ACL>();
        boolean boolean11 = java.util.Collections.addAll((java.util.Collection<org.apache.zookeeper.data.ACL>) aCLList10, aCLArray9);
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.data.Stat stat13 = dataTree0.setACL("hi!", (java.util.List<org.apache.zookeeper.data.ACL>) aCLList10, (-1));
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 0 + "'", int6 == 0);
        org.junit.Assert.assertNotNull(strSet7);
        org.junit.Assert.assertNotNull(aCLArray9);
        org.junit.Assert.assertArrayEquals(aCLArray9, new org.apache.zookeeper.data.ACL[] {});
        org.junit.Assert.assertTrue("'" + boolean11 + "' != '" + false + "'", boolean11 == false);
    }

    @Test
    public void test042() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test042");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        int int5 = processTxnResult0.type;
        processTxnResult0.clientId = 10L;
        processTxnResult0.type = 10;
        org.junit.Assert.assertTrue("'" + int5 + "' != '" + 1 + "'", int5 == 1);
    }

    @Test
    public void test043() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test043");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("hi!");
        java.util.Set<java.lang.String> strSet7 = dataTree0.getContainers();
        org.apache.jute.OutputArchive outputArchive8 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean9 = dataTree0.serializeLastProcessedZxid(outputArchive8);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeLong(long, String)\" because \"oa\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 0 + "'", int6 == 0);
        org.junit.Assert.assertNotNull(strSet7);
    }

    @Test
    public void test044() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test044");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.server.DataNode dataNode6 = dataTree0.getNode("");
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.deleteNode("hi!", (long) (byte) 100);
            org.junit.Assert.fail("Expected exception of type java.lang.StringIndexOutOfBoundsException; message: Range [0, -1) out of bounds for length 3");
        } catch (java.lang.StringIndexOutOfBoundsException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(dataNode6);
    }

    @Test
    public void test045() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test045");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        long long9 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher.WatcherType watcherType11 = null;
        org.apache.zookeeper.Watcher watcher12 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean13 = dataTree0.containsWatcher("", watcherType11, watcher12);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher$WatcherType.ordinal()\" because \"type\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertTrue("'" + long9 + "' != '" + 0L + "'", long9 == 0L);
    }

    @Test
    public void test046() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test046");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        org.apache.zookeeper.Watcher watcher2 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.addWatch("hi!", watcher2, 128);
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Unsupported mode: 128");
        } catch (java.lang.IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test047() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test047");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        int int8 = dataTree0.getEphemeralsCount();
        org.apache.jute.OutputArchive outputArchive9 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serialize(outputArchive9, "");
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeInt(int, String)\" because \"oa\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertTrue("'" + int8 + "' != '" + 0 + "'", int8 == 0);
    }

    @Test
    public void test048() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test048");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet11 = dataTree0.getContainers();
        org.apache.zookeeper.Watcher.WatcherType watcherType13 = null;
        org.apache.zookeeper.Watcher watcher14 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean15 = dataTree0.removeWatch("hi!", watcherType13, watcher14);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher$WatcherType.ordinal()\" because \"type\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(strSet11);
    }

    @Test
    public void test049() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test049");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.setCversionPzxid("hi!", (int) (short) 0, (long) (short) 0);
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode for hi!");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
    }

    @Test
    public void test050() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test050");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        org.apache.zookeeper.server.DataTree.ZxidDigest zxidDigest8 = dataTree0.getDigestFromLoadedSnapshot();
        byte[] byteArray12 = new byte[] { (byte) 100, (byte) 10 };
        org.apache.zookeeper.data.ACL[] aCLArray13 = new org.apache.zookeeper.data.ACL[] {};
        java.util.ArrayList<org.apache.zookeeper.data.ACL> aCLList14 = new java.util.ArrayList<org.apache.zookeeper.data.ACL>();
        boolean boolean15 = java.util.Collections.addAll((java.util.Collection<org.apache.zookeeper.data.ACL>) aCLList14, aCLArray13);
        org.apache.zookeeper.data.Stat stat20 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.createNode("", byteArray12, (java.util.List<org.apache.zookeeper.data.ACL>) aCLList14, (long) (-1), (int) (short) 100, (long) (short) 0, (long) 1024, stat20);
            org.junit.Assert.fail("Expected exception of type java.lang.StringIndexOutOfBoundsException; message: Range [0, -1) out of bounds for length 0");
        } catch (java.lang.StringIndexOutOfBoundsException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNull(zxidDigest8);
        org.junit.Assert.assertNotNull(byteArray12);
        org.junit.Assert.assertArrayEquals(byteArray12, new byte[] { (byte) 100, (byte) 10 });
        org.junit.Assert.assertNotNull(aCLArray13);
        org.junit.Assert.assertArrayEquals(aCLArray13, new org.apache.zookeeper.data.ACL[] {});
        org.junit.Assert.assertTrue("'" + boolean15 + "' != '" + false + "'", boolean15 == false);
    }

    @Test
    public void test051() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test051");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult5 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat6 = null;
        processTxnResult5.stat = stat6;
        processTxnResult5.type = ' ';
        boolean boolean10 = processTxnResult0.equals((java.lang.Object) ' ');
        org.apache.zookeeper.data.Stat stat11 = processTxnResult0.stat;
        processTxnResult0.err = (byte) 1;
        processTxnResult0.cxid = 'a';
        java.lang.String str16 = processTxnResult0.path;
        org.junit.Assert.assertTrue("'" + boolean10 + "' != '" + false + "'", boolean10 == false);
        org.junit.Assert.assertNull(stat11);
        org.junit.Assert.assertNull(str16);
    }

    @Test
    public void test052() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test052");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        int int8 = dataTree0.getEphemeralsCount();
        org.apache.jute.OutputArchive outputArchive9 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean10 = dataTree0.serializeLastProcessedZxid(outputArchive9);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeLong(long, String)\" because \"oa\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertTrue("'" + int8 + "' != '" + 0 + "'", int8 == 0);
    }

    @Test
    public void test053() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test053");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet11 = dataTree0.getContainers();
        org.apache.zookeeper.data.Stat stat13 = null;
        org.apache.zookeeper.Watcher watcher14 = null;
        // The following exception was thrown during execution in test generation
        try {
            byte[] byteArray15 = dataTree0.getData("", stat13, watcher14);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.data.Stat.setAversion(int)\" because \"to\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(strSet11);
    }

    @Test
    public void test054() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test054");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.server.DataNode dataNode6 = dataTree0.getNode("");
        org.apache.zookeeper.Watcher.WatcherType watcherType8 = null;
        org.apache.zookeeper.Watcher watcher9 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean10 = dataTree0.removeWatch("hi!", watcherType8, watcher9);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher$WatcherType.ordinal()\" because \"type\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNotNull(dataNode6);
    }

    @Test
    public void test055() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test055");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        org.apache.zookeeper.server.DataTree.ZxidDigest zxidDigest8 = dataTree0.getDigestFromLoadedSnapshot();
        dataTree0.updateQuotaStat("hi!", (long) (short) 1, (int) (short) -1);
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList13 = dataTree0.getDigestLog();
        org.apache.zookeeper.txn.TxnHeader txnHeader14 = null;
        org.apache.zookeeper.data.StatPersisted statPersisted18 = org.apache.zookeeper.server.DataTree.createStat((long) (short) 0, (long) 1024, (long) (short) 100);
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult20 = dataTree0.processTxn(txnHeader14, (org.apache.jute.Record) statPersisted18, true);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getClientId()\" because \"header\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNull(zxidDigest8);
        org.junit.Assert.assertNotNull(zxidDigestList13);
        org.junit.Assert.assertNotNull(statPersisted18);
    }

    @Test
    public void test056() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test056");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        boolean boolean6 = processTxnResult0.equals((java.lang.Object) 0);
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult7 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat8 = null;
        processTxnResult7.stat = stat8;
        processTxnResult7.type = ' ';
        org.apache.zookeeper.server.DataTree.ProcessTxnResult[] processTxnResultArray12 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult[] { processTxnResult7 };
        java.util.ArrayList<org.apache.zookeeper.server.DataTree.ProcessTxnResult> processTxnResultList13 = new java.util.ArrayList<org.apache.zookeeper.server.DataTree.ProcessTxnResult>();
        boolean boolean14 = java.util.Collections.addAll((java.util.Collection<org.apache.zookeeper.server.DataTree.ProcessTxnResult>) processTxnResultList13, processTxnResultArray12);
        processTxnResult0.multiResult = processTxnResultList13;
        processTxnResult0.zxid = 1L;
        java.lang.Class<?> wildcardClass18 = processTxnResult0.getClass();
        org.junit.Assert.assertTrue("'" + boolean6 + "' != '" + false + "'", boolean6 == false);
        org.junit.Assert.assertNotNull(processTxnResultArray12);
        org.junit.Assert.assertTrue("'" + boolean14 + "' != '" + true + "'", boolean14 == true);
        org.junit.Assert.assertNotNull(wildcardClass18);
    }

    @Test
    public void test057() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test057");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        long long9 = dataTree0.lastProcessedZxid;
        org.apache.jute.OutputArchive outputArchive10 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean11 = dataTree0.serializeLastProcessedZxid(outputArchive10);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeLong(long, String)\" because \"oa\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertTrue("'" + long9 + "' != '" + 0L + "'", long9 == 0L);
    }

    @Test
    public void test058() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test058");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.io.PrintWriter printWriter11 = null;
        dataTree0.dumpWatches(printWriter11, false);
        org.apache.zookeeper.server.watch.WatchesPathReport watchesPathReport14 = dataTree0.getWatchesByPath();
        org.apache.jute.OutputArchive outputArchive15 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeAcls(outputArchive15);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeInt(int, String)\" because \"oa\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(watchesPathReport14);
    }

    @Test
    public void test059() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test059");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        boolean boolean6 = processTxnResult0.equals((java.lang.Object) 0);
        processTxnResult0.type = '4';
        int int9 = processTxnResult0.err;
        org.junit.Assert.assertTrue("'" + boolean6 + "' != '" + false + "'", boolean6 == false);
        org.junit.Assert.assertTrue("'" + int9 + "' != '" + 0 + "'", int9 == 0);
    }

    @Test
    public void test060() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test060");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        long long9 = dataTree0.lastProcessedZxid;
        org.apache.zookeeper.Watcher watcher11 = null;
        dataTree0.addWatch("hi!", watcher11, 1);
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertTrue("'" + long9 + "' != '" + 0L + "'", long9 == 0L);
    }

    @Test
    public void test061() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test061");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        int int1 = processTxnResult0.cxid;
        processTxnResult0.clientId = (byte) 0;
        long long4 = processTxnResult0.clientId;
        org.junit.Assert.assertTrue("'" + int1 + "' != '" + 0 + "'", int1 == 0);
        org.junit.Assert.assertTrue("'" + long4 + "' != '" + 0L + "'", long4 == 0L);
    }

    @Test
    public void test062() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test062");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        org.apache.zookeeper.server.DataTree.ZxidDigest zxidDigest8 = dataTree0.getDigestFromLoadedSnapshot();
        dataTree0.updateQuotaStat("hi!", (long) (short) 1, (int) (short) -1);
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.setCversionPzxid("hi!", 4, (long) (short) 0);
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode for hi!");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNull(zxidDigest8);
    }

    @Test
    public void test063() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test063");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        org.apache.zookeeper.server.DataTree.ZxidDigest zxidDigest8 = dataTree0.getDigestFromLoadedSnapshot();
        dataTree0.updateQuotaStat("hi!", (long) (short) 1, (int) (short) -1);
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList13 = dataTree0.getDigestLog();
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.deleteNode("hi!", 1L);
            org.junit.Assert.fail("Expected exception of type java.lang.StringIndexOutOfBoundsException; message: Range [0, -1) out of bounds for length 3");
        } catch (java.lang.StringIndexOutOfBoundsException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNull(zxidDigest8);
        org.junit.Assert.assertNotNull(zxidDigestList13);
    }

    @Test
    public void test064() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test064");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        org.apache.zookeeper.data.Stat stat5 = null;
        processTxnResult0.stat = stat5;
        java.lang.String str7 = processTxnResult0.path;
        int int8 = processTxnResult0.cxid;
        org.apache.zookeeper.data.Stat stat9 = null;
        processTxnResult0.stat = stat9;
        org.junit.Assert.assertNull(str7);
        org.junit.Assert.assertTrue("'" + int8 + "' != '" + 0 + "'", int8 == 0);
    }

    @Test
    public void test065() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test065");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        int int8 = dataTree0.getEphemeralsCount();
        java.lang.String[] strArray12 = new java.lang.String[] { "hi!", "hi!" };
        java.util.ArrayList<java.lang.String> strList13 = new java.util.ArrayList<java.lang.String>();
        boolean boolean14 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList13, strArray12);
        java.util.List<java.lang.String> strList15 = null;
        java.lang.String[] strArray18 = new java.lang.String[] { "", "" };
        java.util.ArrayList<java.lang.String> strList19 = new java.util.ArrayList<java.lang.String>();
        boolean boolean20 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList19, strArray18);
        java.lang.String[] strArray22 = new java.lang.String[] { "" };
        java.util.ArrayList<java.lang.String> strList23 = new java.util.ArrayList<java.lang.String>();
        boolean boolean24 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList23, strArray22);
        java.lang.String[] strArray27 = new java.lang.String[] { "", "hi!" };
        java.util.ArrayList<java.lang.String> strList28 = new java.util.ArrayList<java.lang.String>();
        boolean boolean29 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList28, strArray27);
        org.apache.zookeeper.Watcher watcher30 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.setWatches(0L, (java.util.List<java.lang.String>) strList13, strList15, (java.util.List<java.lang.String>) strList19, (java.util.List<java.lang.String>) strList23, (java.util.List<java.lang.String>) strList28, watcher30);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher.process(org.apache.zookeeper.WatchedEvent)\" because \"watcher\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertTrue("'" + int8 + "' != '" + 0 + "'", int8 == 0);
        org.junit.Assert.assertNotNull(strArray12);
        org.junit.Assert.assertArrayEquals(strArray12, new java.lang.String[] { "hi!", "hi!" });
        org.junit.Assert.assertTrue("'" + boolean14 + "' != '" + true + "'", boolean14 == true);
        org.junit.Assert.assertNotNull(strArray18);
        org.junit.Assert.assertArrayEquals(strArray18, new java.lang.String[] { "", "" });
        org.junit.Assert.assertTrue("'" + boolean20 + "' != '" + true + "'", boolean20 == true);
        org.junit.Assert.assertNotNull(strArray22);
        org.junit.Assert.assertArrayEquals(strArray22, new java.lang.String[] { "" });
        org.junit.Assert.assertTrue("'" + boolean24 + "' != '" + true + "'", boolean24 == true);
        org.junit.Assert.assertNotNull(strArray27);
        org.junit.Assert.assertArrayEquals(strArray27, new java.lang.String[] { "", "hi!" });
        org.junit.Assert.assertTrue("'" + boolean29 + "' != '" + true + "'", boolean29 == true);
    }

    @Test
    public void test066() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test066");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet11 = dataTree0.getContainers();
        int int12 = dataTree0.getNodeCount();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList13 = dataTree0.getDigestLog();
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(strSet11);
        org.junit.Assert.assertTrue("'" + int12 + "' != '" + 5 + "'", int12 == 5);
        org.junit.Assert.assertNotNull(zxidDigestList13);
    }

    @Test
    public void test067() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test067");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        org.apache.zookeeper.server.DataTree.ZxidDigest zxidDigest8 = dataTree0.getDigestFromLoadedSnapshot();
        long long9 = dataTree0.lastProcessedZxid;
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNull(zxidDigest8);
        org.junit.Assert.assertTrue("'" + long9 + "' != '" + 0L + "'", long9 == 0L);
    }

    @Test
    public void test068() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test068");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult5 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat6 = null;
        processTxnResult5.stat = stat6;
        processTxnResult5.type = ' ';
        boolean boolean10 = processTxnResult0.equals((java.lang.Object) ' ');
        int int11 = processTxnResult0.type;
        processTxnResult0.type = '#';
        org.junit.Assert.assertTrue("'" + boolean10 + "' != '" + false + "'", boolean10 == false);
        org.junit.Assert.assertTrue("'" + int11 + "' != '" + 32 + "'", int11 == 32);
    }

    @Test
    public void test069() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test069");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.txn.TxnHeader txnHeader7 = null;
        org.apache.zookeeper.server.DataTree dataTree8 = new org.apache.zookeeper.server.DataTree();
        dataTree8.updateQuotaStat("", 100L, 1024);
        int int14 = dataTree8.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary15 = dataTree8.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList16 = dataTree8.getDigestLog();
        java.util.Set<java.lang.String> strSet18 = dataTree8.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet19 = dataTree8.getContainers();
        org.apache.zookeeper.server.DataTree dataTree20 = new org.apache.zookeeper.server.DataTree();
        dataTree20.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.server.DataNode dataNode26 = dataTree20.getNode("");
        java.util.List<org.apache.zookeeper.data.ACL> aCLList27 = dataTree8.getACL(dataNode26);
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult29 = dataTree0.processTxn(txnHeader7, (org.apache.jute.Record) dataNode26, true);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getClientId()\" because \"header\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertTrue("'" + int14 + "' != '" + 4 + "'", int14 == 4);
        org.junit.Assert.assertNotNull(watchesSummary15);
        org.junit.Assert.assertNotNull(zxidDigestList16);
        org.junit.Assert.assertNotNull(strSet18);
        org.junit.Assert.assertNotNull(strSet19);
        org.junit.Assert.assertNotNull(dataNode26);
        org.junit.Assert.assertNotNull(aCLList27);
    }

    @Test
    public void test070() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test070");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet11 = dataTree0.getContainers();
        org.apache.zookeeper.server.DataTree dataTree12 = new org.apache.zookeeper.server.DataTree();
        dataTree12.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.server.DataNode dataNode18 = dataTree12.getNode("");
        java.util.List<org.apache.zookeeper.data.ACL> aCLList19 = dataTree0.getACL(dataNode18);
        java.util.Set<java.lang.String> strSet20 = dataTree0.getContainers();
        org.apache.zookeeper.txn.TxnHeader txnHeader21 = null;
        org.apache.zookeeper.data.StatPersisted statPersisted25 = org.apache.zookeeper.server.DataTree.createStat((long) '4', (-1L), (long) 100);
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult26 = dataTree0.processTxn(txnHeader21, (org.apache.jute.Record) statPersisted25);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getClientId()\" because \"header\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(strSet11);
        org.junit.Assert.assertNotNull(dataNode18);
        org.junit.Assert.assertNotNull(aCLList19);
        org.junit.Assert.assertNotNull(strSet20);
        org.junit.Assert.assertNotNull(statPersisted25);
    }

    @Test
    public void test071() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test071");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        org.apache.zookeeper.data.Stat stat5 = processTxnResult0.stat;
        java.lang.String str6 = processTxnResult0.path;
        org.junit.Assert.assertNull(stat5);
        org.junit.Assert.assertNull(str6);
    }

    @Test
    public void test072() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test072");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        org.apache.zookeeper.server.DataTree.ZxidDigest zxidDigest8 = dataTree0.getDigestFromLoadedSnapshot();
        dataTree0.updateQuotaStat("hi!", (long) (short) 1, (int) (short) -1);
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList13 = dataTree0.getDigestLog();
        dataTree0.shutdownWatcher();
        byte[] byteArray16 = new byte[] {};
        org.apache.zookeeper.data.ACL[] aCLArray17 = new org.apache.zookeeper.data.ACL[] {};
        java.util.ArrayList<org.apache.zookeeper.data.ACL> aCLList18 = new java.util.ArrayList<org.apache.zookeeper.data.ACL>();
        boolean boolean19 = java.util.Collections.addAll((java.util.Collection<org.apache.zookeeper.data.ACL>) aCLList18, aCLArray17);
        org.apache.zookeeper.data.Stat stat24 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.createNode("hi!", byteArray16, (java.util.List<org.apache.zookeeper.data.ACL>) aCLList18, (long) '#', 128, (long) 100, (long) (byte) 0, stat24);
            org.junit.Assert.fail("Expected exception of type java.lang.StringIndexOutOfBoundsException; message: Range [0, -1) out of bounds for length 3");
        } catch (java.lang.StringIndexOutOfBoundsException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNull(zxidDigest8);
        org.junit.Assert.assertNotNull(zxidDigestList13);
        org.junit.Assert.assertNotNull(byteArray16);
        org.junit.Assert.assertArrayEquals(byteArray16, new byte[] {});
        org.junit.Assert.assertNotNull(aCLArray17);
        org.junit.Assert.assertArrayEquals(aCLArray17, new org.apache.zookeeper.data.ACL[] {});
        org.junit.Assert.assertTrue("'" + boolean19 + "' != '" + false + "'", boolean19 == false);
    }

    @Test
    public void test073() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test073");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        int int5 = processTxnResult0.type;
        processTxnResult0.clientId = 10L;
        int int8 = processTxnResult0.cxid;
        processTxnResult0.zxid = (short) 0;
        org.junit.Assert.assertTrue("'" + int5 + "' != '" + 1 + "'", int5 == 1);
        org.junit.Assert.assertTrue("'" + int8 + "' != '" + 0 + "'", int8 == 0);
    }

    @Test
    public void test074() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test074");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        dataTree0.lastProcessedZxid = 4;
        java.util.Map<java.lang.Long, java.util.Set<java.lang.String>> longMap11 = dataTree0.getEphemerals();
        java.lang.String[] strArray14 = new java.lang.String[] { "" };
        java.util.ArrayList<java.lang.String> strList15 = new java.util.ArrayList<java.lang.String>();
        boolean boolean16 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList15, strArray14);
        java.lang.String[] strArray18 = new java.lang.String[] { "" };
        java.util.ArrayList<java.lang.String> strList19 = new java.util.ArrayList<java.lang.String>();
        boolean boolean20 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList19, strArray18);
        java.lang.String[] strArray23 = new java.lang.String[] { "hi!", "" };
        java.util.ArrayList<java.lang.String> strList24 = new java.util.ArrayList<java.lang.String>();
        boolean boolean25 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList24, strArray23);
        java.lang.String[] strArray28 = new java.lang.String[] { "", "" };
        java.util.ArrayList<java.lang.String> strList29 = new java.util.ArrayList<java.lang.String>();
        boolean boolean30 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList29, strArray28);
        java.util.List<java.lang.String> strList31 = null;
        org.apache.zookeeper.Watcher watcher32 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.setWatches(10L, (java.util.List<java.lang.String>) strList15, (java.util.List<java.lang.String>) strList19, (java.util.List<java.lang.String>) strList24, (java.util.List<java.lang.String>) strList29, strList31, watcher32);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher.process(org.apache.zookeeper.WatchedEvent)\" because \"watcher\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(longMap11);
        org.junit.Assert.assertNotNull(strArray14);
        org.junit.Assert.assertArrayEquals(strArray14, new java.lang.String[] { "" });
        org.junit.Assert.assertTrue("'" + boolean16 + "' != '" + true + "'", boolean16 == true);
        org.junit.Assert.assertNotNull(strArray18);
        org.junit.Assert.assertArrayEquals(strArray18, new java.lang.String[] { "" });
        org.junit.Assert.assertTrue("'" + boolean20 + "' != '" + true + "'", boolean20 == true);
        org.junit.Assert.assertNotNull(strArray23);
        org.junit.Assert.assertArrayEquals(strArray23, new java.lang.String[] { "hi!", "" });
        org.junit.Assert.assertTrue("'" + boolean25 + "' != '" + true + "'", boolean25 == true);
        org.junit.Assert.assertNotNull(strArray28);
        org.junit.Assert.assertArrayEquals(strArray28, new java.lang.String[] { "", "" });
        org.junit.Assert.assertTrue("'" + boolean30 + "' != '" + true + "'", boolean30 == true);
    }

    @Test
    public void test075() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test075");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary9 = dataTree0.getWatchesSummary();
        java.lang.String[] strArray12 = new java.lang.String[] { "" };
        java.util.ArrayList<java.lang.String> strList13 = new java.util.ArrayList<java.lang.String>();
        boolean boolean14 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList13, strArray12);
        java.lang.String[] strArray16 = new java.lang.String[] { "hi!" };
        java.util.ArrayList<java.lang.String> strList17 = new java.util.ArrayList<java.lang.String>();
        boolean boolean18 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList17, strArray16);
        java.lang.String[] strArray21 = new java.lang.String[] { "", "hi!" };
        java.util.ArrayList<java.lang.String> strList22 = new java.util.ArrayList<java.lang.String>();
        boolean boolean23 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList22, strArray21);
        java.lang.String[] strArray25 = new java.lang.String[] { "hi!" };
        java.util.ArrayList<java.lang.String> strList26 = new java.util.ArrayList<java.lang.String>();
        boolean boolean27 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList26, strArray25);
        java.lang.String[] strArray29 = new java.lang.String[] { "" };
        java.util.ArrayList<java.lang.String> strList30 = new java.util.ArrayList<java.lang.String>();
        boolean boolean31 = java.util.Collections.addAll((java.util.Collection<java.lang.String>) strList30, strArray29);
        org.apache.zookeeper.Watcher watcher32 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.setWatches(0L, (java.util.List<java.lang.String>) strList13, (java.util.List<java.lang.String>) strList17, (java.util.List<java.lang.String>) strList22, (java.util.List<java.lang.String>) strList26, (java.util.List<java.lang.String>) strList30, watcher32);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.Watcher.process(org.apache.zookeeper.WatchedEvent)\" because \"watcher\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(watchesSummary9);
        org.junit.Assert.assertNotNull(strArray12);
        org.junit.Assert.assertArrayEquals(strArray12, new java.lang.String[] { "" });
        org.junit.Assert.assertTrue("'" + boolean14 + "' != '" + true + "'", boolean14 == true);
        org.junit.Assert.assertNotNull(strArray16);
        org.junit.Assert.assertArrayEquals(strArray16, new java.lang.String[] { "hi!" });
        org.junit.Assert.assertTrue("'" + boolean18 + "' != '" + true + "'", boolean18 == true);
        org.junit.Assert.assertNotNull(strArray21);
        org.junit.Assert.assertArrayEquals(strArray21, new java.lang.String[] { "", "hi!" });
        org.junit.Assert.assertTrue("'" + boolean23 + "' != '" + true + "'", boolean23 == true);
        org.junit.Assert.assertNotNull(strArray25);
        org.junit.Assert.assertArrayEquals(strArray25, new java.lang.String[] { "hi!" });
        org.junit.Assert.assertTrue("'" + boolean27 + "' != '" + true + "'", boolean27 == true);
        org.junit.Assert.assertNotNull(strArray29);
        org.junit.Assert.assertArrayEquals(strArray29, new java.lang.String[] { "" });
        org.junit.Assert.assertTrue("'" + boolean31 + "' != '" + true + "'", boolean31 == true);
    }

    @Test
    public void test076() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test076");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet11 = dataTree0.getContainers();
        byte[] byteArray15 = new byte[] { (byte) 100, (byte) 10 };
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.data.Stat stat19 = dataTree0.setData("hi!", byteArray15, 5, (long) ' ', (long) (byte) 10);
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(strSet11);
        org.junit.Assert.assertNotNull(byteArray15);
        org.junit.Assert.assertArrayEquals(byteArray15, new byte[] { (byte) 100, (byte) 10 });
    }

    @Test
    public void test077() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test077");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        org.apache.zookeeper.data.Stat stat5 = processTxnResult0.stat;
        processTxnResult0.type = (short) -1;
        org.junit.Assert.assertNull(stat5);
    }

    @Test
    public void test078() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test078");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        org.apache.zookeeper.server.DataTree.ZxidDigest zxidDigest8 = dataTree0.getDigestFromLoadedSnapshot();
        dataTree0.updateQuotaStat("hi!", (long) (short) 1, (int) (short) -1);
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList13 = dataTree0.getDigestLog();
        dataTree0.shutdownWatcher();
        org.apache.jute.InputArchive inputArchive15 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.deserialize(inputArchive15, "hi!");
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.InputArchive.readInt(String)\" because \"ia\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNull(zxidDigest8);
        org.junit.Assert.assertNotNull(zxidDigestList13);
    }

    @Test
    public void test079() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test079");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        org.apache.zookeeper.data.Stat stat5 = null;
        processTxnResult0.stat = stat5;
        int int7 = processTxnResult0.err;
        java.lang.String str8 = processTxnResult0.path;
        boolean boolean10 = processTxnResult0.equals((java.lang.Object) (short) 0);
        org.junit.Assert.assertTrue("'" + int7 + "' != '" + 0 + "'", int7 == 0);
        org.junit.Assert.assertNull(str8);
        org.junit.Assert.assertTrue("'" + boolean10 + "' != '" + false + "'", boolean10 == false);
    }

    @Test
    public void test080() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test080");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        dataTree0.shutdownWatcher();
        org.apache.zookeeper.data.Stat stat10 = null;
        // The following exception was thrown during execution in test generation
        try {
            java.util.List<org.apache.zookeeper.data.ACL> aCLList11 = dataTree0.getACL("hi!", stat10);
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
    }

    @Test
    public void test081() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test081");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet11 = dataTree0.getContainers();
        org.apache.zookeeper.server.DataTree dataTree12 = new org.apache.zookeeper.server.DataTree();
        dataTree12.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.server.DataNode dataNode18 = dataTree12.getNode("");
        java.util.List<org.apache.zookeeper.data.ACL> aCLList19 = dataTree0.getACL(dataNode18);
        org.apache.jute.InputArchive inputArchive20 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean21 = dataTree0.deserializeLastProcessedZxid(inputArchive20);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.InputArchive.readLong(String)\" because \"ia\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(strSet11);
        org.junit.Assert.assertNotNull(dataNode18);
        org.junit.Assert.assertNotNull(aCLList19);
    }

    @Test
    public void test082() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test082");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.io.PrintWriter printWriter11 = null;
        dataTree0.dumpWatches(printWriter11, false);
        org.apache.zookeeper.data.Stat stat15 = null;
        java.util.List<org.apache.zookeeper.data.ACL> aCLList16 = dataTree0.getACL("", stat15);
        int int17 = dataTree0.getEphemeralsCount();
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(aCLList16);
        org.junit.Assert.assertTrue("'" + int17 + "' != '" + 0 + "'", int17 == 0);
    }

    @Test
    public void test083() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test083");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult5 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat6 = null;
        processTxnResult5.stat = stat6;
        processTxnResult5.type = ' ';
        boolean boolean10 = processTxnResult0.equals((java.lang.Object) ' ');
        int int11 = processTxnResult0.type;
        processTxnResult0.err = 32;
        org.junit.Assert.assertTrue("'" + boolean10 + "' != '" + false + "'", boolean10 == false);
        org.junit.Assert.assertTrue("'" + int11 + "' != '" + 32 + "'", int11 == 32);
    }

    @Test
    public void test084() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test084");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet11 = dataTree0.getContainers();
        org.apache.zookeeper.server.DataTree dataTree12 = new org.apache.zookeeper.server.DataTree();
        dataTree12.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.server.DataNode dataNode18 = dataTree12.getNode("");
        java.util.List<org.apache.zookeeper.data.ACL> aCLList19 = dataTree0.getACL(dataNode18);
        java.util.Set<java.lang.String> strSet20 = dataTree0.getContainers();
        int int21 = dataTree0.getEphemeralsCount();
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(strSet11);
        org.junit.Assert.assertNotNull(dataNode18);
        org.junit.Assert.assertNotNull(aCLList19);
        org.junit.Assert.assertNotNull(strSet20);
        org.junit.Assert.assertTrue("'" + int21 + "' != '" + 0 + "'", int21 == 0);
    }

    @Test
    public void test085() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test085");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        org.apache.zookeeper.data.Stat stat5 = null;
        processTxnResult0.stat = stat5;
        java.lang.String str7 = processTxnResult0.path;
        int int8 = processTxnResult0.cxid;
        processTxnResult0.path = "hi!";
        int int11 = processTxnResult0.err;
        org.junit.Assert.assertNull(str7);
        org.junit.Assert.assertTrue("'" + int8 + "' != '" + 0 + "'", int8 == 0);
        org.junit.Assert.assertTrue("'" + int11 + "' != '" + 0 + "'", int11 == 0);
    }

    @Test
    public void test086() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test086");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.server.DataNode dataNode6 = dataTree0.getNode("");
        org.apache.zookeeper.server.DataTree dataTree8 = new org.apache.zookeeper.server.DataTree();
        dataTree8.updateQuotaStat("", 100L, 1024);
        int int14 = dataTree8.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary15 = dataTree8.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList16 = dataTree8.getDigestLog();
        org.apache.zookeeper.DigestWatcher digestWatcher17 = null;
        dataTree8.addDigestWatcher(digestWatcher17);
        byte[] byteArray25 = new byte[] { (byte) 1, (byte) 10, (byte) 10, (byte) 1, (byte) 0 };
        org.apache.zookeeper.data.Stat stat29 = dataTree8.setData("", byteArray25, (int) (short) 0, (long) (-1), (long) (short) -1);
        java.util.List<org.apache.zookeeper.data.ACL> aCLList30 = dataTree0.getACL("", stat29);
        org.junit.Assert.assertNotNull(dataNode6);
        org.junit.Assert.assertTrue("'" + int14 + "' != '" + 4 + "'", int14 == 4);
        org.junit.Assert.assertNotNull(watchesSummary15);
        org.junit.Assert.assertNotNull(zxidDigestList16);
        org.junit.Assert.assertNotNull(byteArray25);
        org.junit.Assert.assertArrayEquals(byteArray25, new byte[] { (byte) 1, (byte) 10, (byte) 10, (byte) 1, (byte) 0 });
        org.junit.Assert.assertNotNull(stat29);
        org.junit.Assert.assertNotNull(aCLList30);
    }

    @Test
    public void test087() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test087");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        org.apache.zookeeper.data.Stat stat5 = processTxnResult0.stat;
        processTxnResult0.zxid = (-1L);
        java.lang.String str8 = processTxnResult0.path;
        org.junit.Assert.assertNull(stat5);
        org.junit.Assert.assertNull(str8);
    }

    @Test
    public void test088() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test088");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.txn.TxnHeader txnHeader7 = null;
        org.apache.zookeeper.data.StatPersisted statPersisted11 = org.apache.zookeeper.server.DataTree.createStat((long) (byte) 100, (long) 1, (long) (short) 1);
        org.apache.zookeeper.txn.TxnDigest txnDigest12 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean13 = dataTree0.compareDigest(txnHeader7, (org.apache.jute.Record) statPersisted11, txnDigest12);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getZxid()\" because \"header\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(statPersisted11);
    }

    @Test
    public void test089() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test089");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        int int5 = processTxnResult0.type;
        processTxnResult0.clientId = 10L;
        long long8 = processTxnResult0.clientId;
        java.util.List<org.apache.zookeeper.server.DataTree.ProcessTxnResult> processTxnResultList9 = processTxnResult0.multiResult;
        org.junit.Assert.assertTrue("'" + int5 + "' != '" + 1 + "'", int5 == 1);
        org.junit.Assert.assertTrue("'" + long8 + "' != '" + 10L + "'", long8 == 10L);
        org.junit.Assert.assertNull(processTxnResultList9);
    }

    @Test
    public void test090() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test090");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        dataTree0.lastProcessedZxid = 4;
        org.apache.zookeeper.server.watch.WatchesPathReport watchesPathReport11 = dataTree0.getWatchesByPath();
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(watchesPathReport11);
    }

    @Test
    public void test091() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test091");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        int int11 = dataTree0.getWatchCount();
        org.apache.jute.OutputArchive outputArchive12 = null;
        org.apache.zookeeper.server.DataTree dataTree14 = new org.apache.zookeeper.server.DataTree();
        dataTree14.updateQuotaStat("", 100L, 1024);
        int int20 = dataTree14.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary21 = dataTree14.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList22 = dataTree14.getDigestLog();
        java.util.Set<java.lang.String> strSet24 = dataTree14.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet25 = dataTree14.getContainers();
        org.apache.zookeeper.server.DataTree dataTree26 = new org.apache.zookeeper.server.DataTree();
        dataTree26.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.server.DataNode dataNode32 = dataTree26.getNode("");
        java.util.List<org.apache.zookeeper.data.ACL> aCLList33 = dataTree14.getACL(dataNode32);
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeNodeData(outputArchive12, "", dataNode32);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeString(String, String)\" because \"oa\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertTrue("'" + int11 + "' != '" + 0 + "'", int11 == 0);
        org.junit.Assert.assertTrue("'" + int20 + "' != '" + 4 + "'", int20 == 4);
        org.junit.Assert.assertNotNull(watchesSummary21);
        org.junit.Assert.assertNotNull(zxidDigestList22);
        org.junit.Assert.assertNotNull(strSet24);
        org.junit.Assert.assertNotNull(strSet25);
        org.junit.Assert.assertNotNull(dataNode32);
        org.junit.Assert.assertNotNull(aCLList33);
    }

    @Test
    public void test092() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test092");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.setCversionPzxid("", (int) (byte) 100, (long) (-1));
        org.apache.zookeeper.server.DataTree.ZxidDigest zxidDigest5 = dataTree0.getLastProcessedZxidDigest();
        org.apache.zookeeper.server.watch.WatchesPathReport watchesPathReport6 = dataTree0.getWatchesByPath();
        org.junit.Assert.assertNull(zxidDigest5);
        org.junit.Assert.assertNotNull(watchesPathReport6);
    }

    @Test
    public void test093() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test093");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet11 = dataTree0.getContainers();
        org.apache.jute.OutputArchive outputArchive12 = null;
        org.apache.zookeeper.server.DataNode dataNode14 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeNodeData(outputArchive12, "hi!", dataNode14);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeString(String, String)\" because \"oa\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(strSet11);
    }

    @Test
    public void test094() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test094");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        org.apache.zookeeper.server.DataTree.ZxidDigest zxidDigest8 = dataTree0.getDigestFromLoadedSnapshot();
        dataTree0.updateQuotaStat("hi!", (long) (short) 1, (int) (short) -1);
        long long13 = dataTree0.lastProcessedZxid;
        dataTree0.lastProcessedZxid = 1L;
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNull(zxidDigest8);
        org.junit.Assert.assertTrue("'" + long13 + "' != '" + 0L + "'", long13 == 0L);
    }

    @Test
    public void test095() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test095");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        int int8 = dataTree0.getEphemeralsCount();
        dataTree0.setCversionPzxid("", 10, (long) 4);
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertTrue("'" + int8 + "' != '" + 0 + "'", int8 == 0);
    }

    @Test
    public void test096() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test096");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        processTxnResult0.zxid = 0;
    }

    @Test
    public void test097() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test097");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.io.PrintWriter printWriter11 = null;
        dataTree0.dumpWatches(printWriter11, false);
        org.apache.zookeeper.server.watch.WatchesPathReport watchesPathReport14 = dataTree0.getWatchesByPath();
        org.apache.zookeeper.txn.TxnHeader txnHeader15 = null;
        org.apache.zookeeper.data.StatPersisted statPersisted19 = org.apache.zookeeper.server.DataTree.createStat((long) 0, (long) (byte) 100, (long) (short) 10);
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult20 = dataTree0.processTxn(txnHeader15, (org.apache.jute.Record) statPersisted19);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getClientId()\" because \"header\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(watchesPathReport14);
        org.junit.Assert.assertNotNull(statPersisted19);
    }

    @Test
    public void test098() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test098");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        dataTree0.shutdownWatcher();
        org.apache.zookeeper.txn.TxnHeader txnHeader9 = null;
        org.apache.zookeeper.server.DataTree dataTree10 = new org.apache.zookeeper.server.DataTree();
        dataTree10.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.server.DataNode dataNode16 = dataTree10.getNode("");
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult18 = dataTree0.processTxn(txnHeader9, (org.apache.jute.Record) dataNode16, true);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getClientId()\" because \"header\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(dataNode16);
    }

    @Test
    public void test099() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test099");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        org.apache.zookeeper.DigestWatcher digestWatcher9 = null;
        dataTree0.addDigestWatcher(digestWatcher9);
        dataTree0.updateQuotaStat("hi!", (long) 4, 0);
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
    }

    @Test
    public void test100() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test100");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        int int8 = dataTree0.getEphemeralsCount();
        int int9 = dataTree0.getEphemeralsCount();
        java.lang.String str11 = dataTree0.getMaxPrefixWithQuota("");
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertTrue("'" + int8 + "' != '" + 0 + "'", int8 == 0);
        org.junit.Assert.assertTrue("'" + int9 + "' != '" + 0 + "'", int9 == 0);
        org.junit.Assert.assertNull(str11);
    }

    @Test
    public void test101() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test101");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet11 = dataTree0.getContainers();
        int int12 = dataTree0.getNodeCount();
        java.util.Set<java.lang.String> strSet13 = dataTree0.getContainers();
        org.apache.jute.OutputArchive outputArchive14 = null;
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean15 = dataTree0.serializeZxidDigest(outputArchive14);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeLong(long, String)\" because \"oa\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(strSet11);
        org.junit.Assert.assertTrue("'" + int12 + "' != '" + 5 + "'", int12 == 5);
        org.junit.Assert.assertNotNull(strSet13);
    }

    @Test
    public void test102() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test102");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult5 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat6 = null;
        processTxnResult5.stat = stat6;
        processTxnResult5.type = ' ';
        boolean boolean10 = processTxnResult0.equals((java.lang.Object) ' ');
        org.apache.zookeeper.data.Stat stat11 = processTxnResult0.stat;
        processTxnResult0.err = (byte) 1;
        int int14 = processTxnResult0.type;
        org.apache.zookeeper.server.DataTree dataTree15 = new org.apache.zookeeper.server.DataTree();
        dataTree15.updateQuotaStat("", 100L, 1024);
        int int21 = dataTree15.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary22 = dataTree15.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList23 = dataTree15.getDigestLog();
        org.apache.zookeeper.DigestWatcher digestWatcher24 = null;
        dataTree15.addDigestWatcher(digestWatcher24);
        byte[] byteArray32 = new byte[] { (byte) 1, (byte) 10, (byte) 10, (byte) 1, (byte) 0 };
        org.apache.zookeeper.data.Stat stat36 = dataTree15.setData("", byteArray32, (int) (short) 0, (long) (-1), (long) (short) -1);
        processTxnResult0.stat = stat36;
        org.junit.Assert.assertTrue("'" + boolean10 + "' != '" + false + "'", boolean10 == false);
        org.junit.Assert.assertNull(stat11);
        org.junit.Assert.assertTrue("'" + int14 + "' != '" + 32 + "'", int14 == 32);
        org.junit.Assert.assertTrue("'" + int21 + "' != '" + 4 + "'", int21 == 4);
        org.junit.Assert.assertNotNull(watchesSummary22);
        org.junit.Assert.assertNotNull(zxidDigestList23);
        org.junit.Assert.assertNotNull(byteArray32);
        org.junit.Assert.assertArrayEquals(byteArray32, new byte[] { (byte) 1, (byte) 10, (byte) 10, (byte) 1, (byte) 0 });
        org.junit.Assert.assertNotNull(stat36);
    }

    @Test
    public void test103() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test103");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = (short) 1;
        org.apache.zookeeper.data.Stat stat5 = processTxnResult0.stat;
        org.apache.zookeeper.data.Stat stat6 = processTxnResult0.stat;
        processTxnResult0.err = (byte) -1;
        org.junit.Assert.assertNull(stat5);
        org.junit.Assert.assertNull(stat6);
    }

    @Test
    public void test104() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test104");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        org.apache.zookeeper.data.Stat stat5 = processTxnResult0.stat;
        // The following exception was thrown during execution in test generation
        try {
            java.lang.Class<?> wildcardClass6 = stat5.getClass();
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertNull(stat5);
    }

    @Test
    public void test105() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test105");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet11 = dataTree0.getContainers();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList12 = dataTree0.getDigestLog();
        org.apache.zookeeper.server.DataTree.ZxidDigest zxidDigest13 = dataTree0.getDigestFromLoadedSnapshot();
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(strSet11);
        org.junit.Assert.assertNotNull(zxidDigestList12);
        org.junit.Assert.assertNull(zxidDigest13);
    }

    @Test
    public void test106() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test106");
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult0 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat1 = null;
        processTxnResult0.stat = stat1;
        processTxnResult0.type = ' ';
        org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult5 = new org.apache.zookeeper.server.DataTree.ProcessTxnResult();
        org.apache.zookeeper.data.Stat stat6 = null;
        processTxnResult5.stat = stat6;
        processTxnResult5.type = ' ';
        boolean boolean10 = processTxnResult0.equals((java.lang.Object) ' ');
        int int11 = processTxnResult0.err;
        org.apache.zookeeper.data.Stat stat12 = processTxnResult0.stat;
        org.junit.Assert.assertTrue("'" + boolean10 + "' != '" + false + "'", boolean10 == false);
        org.junit.Assert.assertTrue("'" + int11 + "' != '" + 0 + "'", int11 == 0);
        org.junit.Assert.assertNull(stat12);
    }

    @Test
    public void test107() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test107");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet11 = dataTree0.getContainers();
        org.apache.zookeeper.server.DataTree dataTree12 = new org.apache.zookeeper.server.DataTree();
        dataTree12.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.server.DataNode dataNode18 = dataTree12.getNode("");
        java.util.List<org.apache.zookeeper.data.ACL> aCLList19 = dataTree0.getACL(dataNode18);
        java.util.Set<java.lang.String> strSet20 = dataTree0.getContainers();
        org.apache.zookeeper.server.DataNode dataNode22 = dataTree0.getNode("");
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(strSet11);
        org.junit.Assert.assertNotNull(dataNode18);
        org.junit.Assert.assertNotNull(aCLList19);
        org.junit.Assert.assertNotNull(strSet20);
        org.junit.Assert.assertNotNull(dataNode22);
    }

    @Test
    public void test108() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test108");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        dataTree0.shutdownWatcher();
        org.apache.jute.OutputArchive outputArchive9 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeAcls(outputArchive9);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeInt(int, String)\" because \"oa\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
    }

    @Test
    public void test109() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test109");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet11 = dataTree0.getContainers();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList12 = dataTree0.getDigestLog();
        org.apache.zookeeper.server.DataTree dataTree14 = new org.apache.zookeeper.server.DataTree();
        dataTree14.updateQuotaStat("", 100L, 1024);
        int int20 = dataTree14.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary21 = dataTree14.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList22 = dataTree14.getDigestLog();
        org.apache.zookeeper.DigestWatcher digestWatcher23 = null;
        dataTree14.addDigestWatcher(digestWatcher23);
        byte[] byteArray31 = new byte[] { (byte) 1, (byte) 10, (byte) 10, (byte) 1, (byte) 0 };
        org.apache.zookeeper.data.Stat stat35 = dataTree14.setData("", byteArray31, (int) (short) 0, (long) (-1), (long) (short) -1);
        org.apache.zookeeper.Watcher watcher36 = null;
        // The following exception was thrown during execution in test generation
        try {
            java.util.List<java.lang.String> strList37 = dataTree0.getChildren("hi!", stat35, watcher36);
            org.junit.Assert.fail("Expected exception of type org.apache.zookeeper.KeeperException.NoNodeException; message: KeeperErrorCode = NoNode");
        } catch (org.apache.zookeeper.KeeperException.NoNodeException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(strSet11);
        org.junit.Assert.assertNotNull(zxidDigestList12);
        org.junit.Assert.assertTrue("'" + int20 + "' != '" + 4 + "'", int20 == 4);
        org.junit.Assert.assertNotNull(watchesSummary21);
        org.junit.Assert.assertNotNull(zxidDigestList22);
        org.junit.Assert.assertNotNull(byteArray31);
        org.junit.Assert.assertArrayEquals(byteArray31, new byte[] { (byte) 1, (byte) 10, (byte) 10, (byte) 1, (byte) 0 });
        org.junit.Assert.assertNotNull(stat35);
    }

    @Test
    public void test110() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test110");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet11 = dataTree0.getContainers();
        org.apache.zookeeper.server.DataTree dataTree12 = new org.apache.zookeeper.server.DataTree();
        dataTree12.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.server.DataNode dataNode18 = dataTree12.getNode("");
        java.util.List<org.apache.zookeeper.data.ACL> aCLList19 = dataTree0.getACL(dataNode18);
        int int20 = dataTree0.aclCacheSize();
        org.apache.zookeeper.Watcher watcher21 = null;
        dataTree0.removeCnxn(watcher21);
        org.apache.jute.OutputArchive outputArchive23 = null;
        // The following exception was thrown during execution in test generation
        try {
            dataTree0.serializeNodes(outputArchive23);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.jute.OutputArchive.writeString(String, String)\" because \"oa\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(strSet11);
        org.junit.Assert.assertNotNull(dataNode18);
        org.junit.Assert.assertNotNull(aCLList19);
        org.junit.Assert.assertTrue("'" + int20 + "' != '" + 1 + "'", int20 == 1);
    }

    @Test
    public void test111() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test111");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        long long1 = dataTree0.approximateDataSize();
        java.util.Set<java.lang.String> strSet2 = dataTree0.getContainers();
        org.junit.Assert.assertTrue("'" + long1 + "' != '" + 44L + "'", long1 == 44L);
        org.junit.Assert.assertNotNull(strSet2);
    }

    @Test
    public void test112() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test112");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("hi!");
        java.util.Set<java.lang.String> strSet7 = dataTree0.getContainers();
        org.apache.zookeeper.txn.TxnHeader txnHeader8 = null;
        org.apache.zookeeper.data.StatPersisted statPersisted12 = org.apache.zookeeper.server.DataTree.createStat((long) (byte) 100, (long) 1, (long) (short) 1);
        // The following exception was thrown during execution in test generation
        try {
            org.apache.zookeeper.server.DataTree.ProcessTxnResult processTxnResult14 = dataTree0.processTxn(txnHeader8, (org.apache.jute.Record) statPersisted12, false);
            org.junit.Assert.fail("Expected exception of type java.lang.NullPointerException; message: Cannot invoke \"org.apache.zookeeper.txn.TxnHeader.getClientId()\" because \"header\" is null");
        } catch (java.lang.NullPointerException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 0 + "'", int6 == 0);
        org.junit.Assert.assertNotNull(strSet7);
        org.junit.Assert.assertNotNull(statPersisted12);
    }

    @Test
    public void test113() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test113");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.updateQuotaStat("", 100L, 1024);
        int int6 = dataTree0.getAllChildrenNumber("");
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary7 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.server.DataTree.ZxidDigest> zxidDigestList8 = dataTree0.getDigestLog();
        java.util.Set<java.lang.String> strSet10 = dataTree0.getEphemerals((long) (byte) -1);
        java.util.Set<java.lang.String> strSet11 = dataTree0.getContainers();
        org.apache.zookeeper.server.DataTree dataTree12 = new org.apache.zookeeper.server.DataTree();
        dataTree12.updateQuotaStat("", 100L, 1024);
        org.apache.zookeeper.server.DataNode dataNode18 = dataTree12.getNode("");
        java.util.List<org.apache.zookeeper.data.ACL> aCLList19 = dataTree0.getACL(dataNode18);
        org.apache.zookeeper.server.watch.WatchesSummary watchesSummary20 = dataTree0.getWatchesSummary();
        java.util.List<org.apache.zookeeper.data.ACL> aCLList22 = null;
        org.apache.zookeeper.data.Stat stat24 = dataTree0.setACL("", aCLList22, 1024);
        org.junit.Assert.assertTrue("'" + int6 + "' != '" + 4 + "'", int6 == 4);
        org.junit.Assert.assertNotNull(watchesSummary7);
        org.junit.Assert.assertNotNull(zxidDigestList8);
        org.junit.Assert.assertNotNull(strSet10);
        org.junit.Assert.assertNotNull(strSet11);
        org.junit.Assert.assertNotNull(dataNode18);
        org.junit.Assert.assertNotNull(aCLList19);
        org.junit.Assert.assertNotNull(watchesSummary20);
        org.junit.Assert.assertNotNull(stat24);
    }
}

