package PathTrieTest.randoop;

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
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.addPath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test002() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test002");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        String str3 = pathTrie0.findMaxPrefix("");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.addPath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str3 + "' != '" + "/" + "'", str3, "/");
    }

    @Test
    public void test003() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test003");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.addPath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test004() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test004");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean2 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test005() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test005");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean4 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test006() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test006");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test007() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test007");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.addPath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
    }

    @Test
    public void test008() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test008");
        Object obj0 = new Object();
        Class<?> wildcardClass1 = obj0.getClass();
        org.junit.Assert.assertNotNull(wildcardClass1);
    }

    @Test
    public void test009() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test009");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean5 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test010() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test010");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test011() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test011");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        String str3 = pathTrie0.findMaxPrefix("");
        Class<?> wildcardClass4 = pathTrie0.getClass();
        org.junit.Assert.assertEquals("'" + str3 + "' != '" + "/" + "'", str3, "/");
        org.junit.Assert.assertNotNull(wildcardClass4);
    }

    @Test
    public void test012() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test012");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        String str3 = pathTrie0.findMaxPrefix("");
        String str5 = pathTrie0.findMaxPrefix("hi!");
        Class<?> wildcardClass6 = pathTrie0.getClass();
        org.junit.Assert.assertEquals("'" + str3 + "' != '" + "/" + "'", str3, "/");
        org.junit.Assert.assertEquals("'" + str5 + "' != '" + "/" + "'", str5, "/");
        org.junit.Assert.assertNotNull(wildcardClass6);
    }

    @Test
    public void test013() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test013");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean5 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test014() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test014");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        boolean boolean2 = pathTrie0.existsNode("/");
        Class<?> wildcardClass3 = pathTrie0.getClass();
        org.junit.Assert.assertTrue("'" + boolean2 + "' != '" + true + "'", boolean2 == true);
        org.junit.Assert.assertNotNull(wildcardClass3);
    }

    @Test
    public void test015() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test015");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        String str3 = pathTrie0.findMaxPrefix("");
        String str5 = pathTrie0.findMaxPrefix("hi!");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.addPath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str3 + "' != '" + "/" + "'", str3, "/");
        org.junit.Assert.assertEquals("'" + str5 + "' != '" + "/" + "'", str5, "/");
    }

    @Test
    public void test016() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test016");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        String str3 = pathTrie0.findMaxPrefix("");
        String str5 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        Class<?> wildcardClass8 = pathTrie0.getClass();
        org.junit.Assert.assertEquals("'" + str3 + "' != '" + "/" + "'", str3, "/");
        org.junit.Assert.assertEquals("'" + str5 + "' != '" + "/" + "'", str5, "/");
        org.junit.Assert.assertNotNull(wildcardClass8);
    }

    @Test
    public void test017() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test017");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        String str3 = pathTrie0.findMaxPrefix("");
        pathTrie0.addPath("/");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean7 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str3 + "' != '" + "/" + "'", str3, "/");
    }

    @Test
    public void test018() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test018");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        Class<?> wildcardClass5 = pathTrie0.getClass();
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
        org.junit.Assert.assertNotNull(wildcardClass5);
    }

    @Test
    public void test019() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test019");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        String str6 = pathTrie0.findMaxPrefix("hi!");
        Class<?> wildcardClass7 = pathTrie0.getClass();
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
        org.junit.Assert.assertEquals("'" + str6 + "' != '" + "/" + "'", str6, "/");
        org.junit.Assert.assertNotNull(wildcardClass7);
    }

    @Test
    public void test020() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test020");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        String str6 = pathTrie0.findMaxPrefix("");
        Class<?> wildcardClass7 = pathTrie0.getClass();
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
        org.junit.Assert.assertEquals("'" + str6 + "' != '" + "/" + "'", str6, "/");
        org.junit.Assert.assertNotNull(wildcardClass7);
    }

    @Test
    public void test021() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test021");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        boolean boolean5 = pathTrie0.existsNode("/");
        Class<?> wildcardClass6 = pathTrie0.getClass();
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + true + "'", boolean5 == true);
        org.junit.Assert.assertNotNull(wildcardClass6);
    }

    @Test
    public void test022() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test022");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.addPath("hi!");
        pathTrie0.clear();
        Class<?> wildcardClass4 = pathTrie0.getClass();
        org.junit.Assert.assertNotNull(wildcardClass4);
    }

    @Test
    public void test023() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test023");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test024() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test024");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        boolean boolean6 = pathTrie0.existsNode("/");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean8 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean6 + "' != '" + true + "'", boolean6 == true);
    }

    @Test
    public void test025() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test025");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        String str3 = pathTrie0.findMaxPrefix("");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean5 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str3 + "' != '" + "/" + "'", str3, "/");
    }

    @Test
    public void test026() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test026");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        boolean boolean2 = pathTrie0.existsNode("hi!");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean4 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean2 + "' != '" + false + "'", boolean2 == false);
    }

    @Test
    public void test027() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test027");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        String str3 = pathTrie0.findMaxPrefix("");
        String str5 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.addPath("hi!");
        Class<?> wildcardClass8 = pathTrie0.getClass();
        org.junit.Assert.assertEquals("'" + str3 + "' != '" + "/" + "'", str3, "/");
        org.junit.Assert.assertEquals("'" + str5 + "' != '" + "/" + "'", str5, "/");
        org.junit.Assert.assertNotNull(wildcardClass8);
    }

    @Test
    public void test028() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test028");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.addPath("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.addPath("/");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test029() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test029");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        Class<?> wildcardClass5 = pathTrie0.getClass();
        org.junit.Assert.assertNotNull(wildcardClass5);
    }

    @Test
    public void test030() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test030");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.addPath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
    }

    @Test
    public void test031() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test031");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        boolean boolean4 = pathTrie0.existsNode("hi!");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean4 + "' != '" + false + "'", boolean4 == false);
    }

    @Test
    public void test032() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test032");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        boolean boolean3 = pathTrie0.existsNode("/");
        Class<?> wildcardClass4 = pathTrie0.getClass();
        org.junit.Assert.assertTrue("'" + boolean3 + "' != '" + true + "'", boolean3 == true);
        org.junit.Assert.assertNotNull(wildcardClass4);
    }

    @Test
    public void test033() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test033");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        pathTrie0.deletePath("hi!");
    }

    @Test
    public void test034() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test034");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        String str7 = pathTrie0.findMaxPrefix("hi!");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean9 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
    }

    @Test
    public void test035() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test035");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        String str4 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.addPath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str4 + "' != '" + "/" + "'", str4, "/");
    }

    @Test
    public void test036() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test036");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("hi!");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean5 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test037() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test037");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        boolean boolean8 = pathTrie0.existsNode("hi!");
        String str10 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        org.junit.Assert.assertTrue("'" + boolean8 + "' != '" + false + "'", boolean8 == false);
        org.junit.Assert.assertEquals("'" + str10 + "' != '" + "/" + "'", str10, "/");
    }

    @Test
    public void test038() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test038");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        pathTrie0.clear();
        pathTrie0.clear();
        boolean boolean7 = pathTrie0.existsNode("/");
        Class<?> wildcardClass8 = pathTrie0.getClass();
        org.junit.Assert.assertTrue("'" + boolean7 + "' != '" + true + "'", boolean7 == true);
        org.junit.Assert.assertNotNull(wildcardClass8);
    }

    @Test
    public void test039() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test039");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        pathTrie0.addPath("/");
        pathTrie0.deletePath("hi!");
    }

    @Test
    public void test040() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test040");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        String str3 = pathTrie0.findMaxPrefix("");
        String str5 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.clear();
        org.junit.Assert.assertEquals("'" + str3 + "' != '" + "/" + "'", str3, "/");
        org.junit.Assert.assertEquals("'" + str5 + "' != '" + "/" + "'", str5, "/");
    }

    @Test
    public void test041() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test041");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("hi!");
        String str5 = pathTrie0.findMaxPrefix("/");
        org.junit.Assert.assertEquals("'" + str5 + "' != '" + "/" + "'", str5, "/");
    }

    @Test
    public void test042() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test042");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        boolean boolean2 = pathTrie0.existsNode("/");
        boolean boolean4 = pathTrie0.existsNode("/");
        boolean boolean6 = pathTrie0.existsNode("hi!");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.addPath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean2 + "' != '" + true + "'", boolean2 == true);
        org.junit.Assert.assertTrue("'" + boolean4 + "' != '" + true + "'", boolean4 == true);
        org.junit.Assert.assertTrue("'" + boolean6 + "' != '" + false + "'", boolean6 == false);
    }

    @Test
    public void test043() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test043");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.addPath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
    }

    @Test
    public void test044() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test044");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        String str5 = pathTrie0.findMaxPrefix("");
        pathTrie0.addPath("hi!");
        org.junit.Assert.assertEquals("'" + str5 + "' != '" + "/" + "'", str5, "/");
    }

    @Test
    public void test045() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test045");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        boolean boolean2 = pathTrie0.existsNode("hi!");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean2 + "' != '" + false + "'", boolean2 == false);
    }

    @Test
    public void test046() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test046");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        String str6 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.addPath("hi!");
        pathTrie0.clear();
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
        org.junit.Assert.assertEquals("'" + str6 + "' != '" + "/" + "'", str6, "/");
    }

    @Test
    public void test047() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test047");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        boolean boolean4 = pathTrie0.existsNode("hi!");
        pathTrie0.clear();
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean7 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean4 + "' != '" + false + "'", boolean4 == false);
    }

    @Test
    public void test048() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test048");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        boolean boolean4 = pathTrie0.existsNode("hi!");
        pathTrie0.clear();
        pathTrie0.clear();
        Class<?> wildcardClass7 = pathTrie0.getClass();
        org.junit.Assert.assertTrue("'" + boolean4 + "' != '" + false + "'", boolean4 == false);
        org.junit.Assert.assertNotNull(wildcardClass7);
    }

    @Test
    public void test049() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test049");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        pathTrie0.clear();
        pathTrie0.clear();
        Class<?> wildcardClass6 = pathTrie0.getClass();
        org.junit.Assert.assertNotNull(wildcardClass6);
    }

    @Test
    public void test050() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test050");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        String str5 = pathTrie0.findMaxPrefix("hi!");
        org.junit.Assert.assertEquals("'" + str5 + "' != '" + "/" + "'", str5, "/");
    }

    @Test
    public void test051() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test051");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        pathTrie0.addPath("/");
        Class<?> wildcardClass6 = pathTrie0.getClass();
        org.junit.Assert.assertNotNull(wildcardClass6);
    }

    @Test
    public void test052() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test052");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        String str7 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.clear();
        String str10 = pathTrie0.findMaxPrefix("hi!");
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
        org.junit.Assert.assertEquals("'" + str10 + "' != '" + "/" + "'", str10, "/");
    }

    @Test
    public void test053() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test053");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean3 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test054() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test054");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.addPath("hi!");
        pathTrie0.clear();
        String str5 = pathTrie0.findMaxPrefix("hi!");
        org.junit.Assert.assertEquals("'" + str5 + "' != '" + "/" + "'", str5, "/");
    }

    @Test
    public void test055() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test055");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.addPath("hi!");
        pathTrie0.clear();
        pathTrie0.addPath("/");
    }

    @Test
    public void test056() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test056");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        String str4 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        Class<?> wildcardClass8 = pathTrie0.getClass();
        org.junit.Assert.assertEquals("'" + str4 + "' != '" + "/" + "'", str4, "/");
        org.junit.Assert.assertNotNull(wildcardClass8);
    }

    @Test
    public void test057() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test057");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("hi!");
        String str5 = pathTrie0.findMaxPrefix("");
        org.junit.Assert.assertEquals("'" + str5 + "' != '" + "/" + "'", str5, "/");
    }

    @Test
    public void test058() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test058");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        pathTrie0.clear();
        pathTrie0.clear();
        String str7 = pathTrie0.findMaxPrefix("");
        String str9 = pathTrie0.findMaxPrefix("/");
        pathTrie0.clear();
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
        org.junit.Assert.assertEquals("'" + str9 + "' != '" + "/" + "'", str9, "/");
    }

    @Test
    public void test059() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test059");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        boolean boolean2 = pathTrie0.existsNode("/");
        boolean boolean4 = pathTrie0.existsNode("/");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.addPath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean2 + "' != '" + true + "'", boolean2 == true);
        org.junit.Assert.assertTrue("'" + boolean4 + "' != '" + true + "'", boolean4 == true);
    }

    @Test
    public void test060() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test060");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        boolean boolean6 = pathTrie0.existsNode("hi!");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean8 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
        org.junit.Assert.assertTrue("'" + boolean6 + "' != '" + false + "'", boolean6 == false);
    }

    @Test
    public void test061() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test061");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        String str4 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.clear();
        String str7 = pathTrie0.findMaxPrefix("");
        boolean boolean9 = pathTrie0.existsNode("/");
        org.junit.Assert.assertEquals("'" + str4 + "' != '" + "/" + "'", str4, "/");
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
        org.junit.Assert.assertTrue("'" + boolean9 + "' != '" + true + "'", boolean9 == true);
    }

    @Test
    public void test062() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test062");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        String str4 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.clear();
        pathTrie0.addPath("/");
        org.junit.Assert.assertEquals("'" + str4 + "' != '" + "/" + "'", str4, "/");
    }

    @Test
    public void test063() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test063");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.addPath("/");
        pathTrie0.addPath("/");
        pathTrie0.clear();
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
    }

    @Test
    public void test064() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test064");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        String str4 = pathTrie0.findMaxPrefix("hi!");
        Class<?> wildcardClass5 = pathTrie0.getClass();
        org.junit.Assert.assertEquals("'" + str4 + "' != '" + "/" + "'", str4, "/");
        org.junit.Assert.assertNotNull(wildcardClass5);
    }

    @Test
    public void test065() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test065");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        String str7 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.clear();
        Class<?> wildcardClass9 = pathTrie0.getClass();
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
        org.junit.Assert.assertNotNull(wildcardClass9);
    }

    @Test
    public void test066() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test066");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        boolean boolean2 = pathTrie0.existsNode("/");
        boolean boolean4 = pathTrie0.existsNode("/");
        boolean boolean6 = pathTrie0.existsNode("hi!");
        pathTrie0.clear();
        org.junit.Assert.assertTrue("'" + boolean2 + "' != '" + true + "'", boolean2 == true);
        org.junit.Assert.assertTrue("'" + boolean4 + "' != '" + true + "'", boolean4 == true);
        org.junit.Assert.assertTrue("'" + boolean6 + "' != '" + false + "'", boolean6 == false);
    }

    @Test
    public void test067() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test067");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        pathTrie0.clear();
        pathTrie0.clear();
        String str7 = pathTrie0.findMaxPrefix("");
        String str9 = pathTrie0.findMaxPrefix("/");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
        org.junit.Assert.assertEquals("'" + str9 + "' != '" + "/" + "'", str9, "/");
    }

    @Test
    public void test068() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test068");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.addPath("hi!");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test069() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test069");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        String str6 = pathTrie0.findMaxPrefix("/");
        pathTrie0.clear();
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
        org.junit.Assert.assertEquals("'" + str6 + "' != '" + "/" + "'", str6, "/");
    }

    @Test
    public void test070() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test070");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        String str3 = pathTrie0.findMaxPrefix("");
        String str5 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.addPath("hi!");
        String str9 = pathTrie0.findMaxPrefix("");
        org.junit.Assert.assertEquals("'" + str3 + "' != '" + "/" + "'", str3, "/");
        org.junit.Assert.assertEquals("'" + str5 + "' != '" + "/" + "'", str5, "/");
        org.junit.Assert.assertEquals("'" + str9 + "' != '" + "/" + "'", str9, "/");
    }

    @Test
    public void test071() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test071");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("hi!");
        boolean boolean5 = pathTrie0.existsNode("/");
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + true + "'", boolean5 == true);
    }

    @Test
    public void test072() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test072");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean6 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
    }

    @Test
    public void test073() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test073");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        boolean boolean3 = pathTrie0.existsNode("/");
        boolean boolean5 = pathTrie0.existsNode("hi!");
        boolean boolean7 = pathTrie0.existsNode("hi!");
        String str9 = pathTrie0.findMaxPrefix("");
        org.junit.Assert.assertTrue("'" + boolean3 + "' != '" + true + "'", boolean3 == true);
        org.junit.Assert.assertTrue("'" + boolean5 + "' != '" + false + "'", boolean5 == false);
        org.junit.Assert.assertTrue("'" + boolean7 + "' != '" + false + "'", boolean7 == false);
        org.junit.Assert.assertEquals("'" + str9 + "' != '" + "/" + "'", str9, "/");
    }

    @Test
    public void test074() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test074");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
    }

    @Test
    public void test075() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test075");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        boolean boolean3 = pathTrie0.existsNode("/");
        pathTrie0.addPath("hi!");
        String str7 = pathTrie0.findMaxPrefix("/");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean3 + "' != '" + true + "'", boolean3 == true);
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
    }

    @Test
    public void test076() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test076");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        String str4 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.clear();
        String str7 = pathTrie0.findMaxPrefix("");
        String str9 = pathTrie0.findMaxPrefix("");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean11 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str4 + "' != '" + "/" + "'", str4, "/");
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
        org.junit.Assert.assertEquals("'" + str9 + "' != '" + "/" + "'", str9, "/");
    }

    @Test
    public void test077() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test077");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        String str5 = pathTrie0.findMaxPrefix("");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.addPath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertEquals("'" + str5 + "' != '" + "/" + "'", str5, "/");
    }

    @Test
    public void test078() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test078");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        String str6 = pathTrie0.findMaxPrefix("");
        pathTrie0.addPath("/");
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
        org.junit.Assert.assertEquals("'" + str6 + "' != '" + "/" + "'", str6, "/");
    }

    @Test
    public void test079() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test079");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        pathTrie0.clear();
        pathTrie0.clear();
        boolean boolean7 = pathTrie0.existsNode("/");
        String str9 = pathTrie0.findMaxPrefix("/");
        boolean boolean11 = pathTrie0.existsNode("/");
        String str13 = pathTrie0.findMaxPrefix("/");
        org.junit.Assert.assertTrue("'" + boolean7 + "' != '" + true + "'", boolean7 == true);
        org.junit.Assert.assertEquals("'" + str9 + "' != '" + "/" + "'", str9, "/");
        org.junit.Assert.assertTrue("'" + boolean11 + "' != '" + true + "'", boolean11 == true);
        org.junit.Assert.assertEquals("'" + str13 + "' != '" + "/" + "'", str13, "/");
    }

    @Test
    public void test080() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test080");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.clear();
        Class<?> wildcardClass3 = pathTrie0.getClass();
        org.junit.Assert.assertNotNull(wildcardClass3);
    }

    @Test
    public void test081() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test081");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        boolean boolean2 = pathTrie0.existsNode("hi!");
        String str4 = pathTrie0.findMaxPrefix("");
        String str6 = pathTrie0.findMaxPrefix("/");
        org.junit.Assert.assertTrue("'" + boolean2 + "' != '" + false + "'", boolean2 == false);
        org.junit.Assert.assertEquals("'" + str4 + "' != '" + "/" + "'", str4, "/");
        org.junit.Assert.assertEquals("'" + str6 + "' != '" + "/" + "'", str6, "/");
    }

    @Test
    public void test082() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test082");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        org.junit.Assert.assertEquals("'" + str2 + "' != '" + "/" + "'", str2, "/");
    }

    @Test
    public void test083() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test083");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        boolean boolean8 = pathTrie0.existsNode("hi!");
        String str10 = pathTrie0.findMaxPrefix("hi!");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean12 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean8 + "' != '" + false + "'", boolean8 == false);
        org.junit.Assert.assertEquals("'" + str10 + "' != '" + "/" + "'", str10, "/");
    }

    @Test
    public void test084() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test084");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        boolean boolean2 = pathTrie0.existsNode("/");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.addPath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean2 + "' != '" + true + "'", boolean2 == true);
    }

    @Test
    public void test085() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test085");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        boolean boolean4 = pathTrie0.existsNode("hi!");
        pathTrie0.clear();
        pathTrie0.clear();
        String str8 = pathTrie0.findMaxPrefix("hi!");
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.addPath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean4 + "' != '" + false + "'", boolean4 == false);
        org.junit.Assert.assertEquals("'" + str8 + "' != '" + "/" + "'", str8, "/");
    }

    @Test
    public void test086() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test086");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.clear();
        Class<?> wildcardClass6 = pathTrie0.getClass();
        org.junit.Assert.assertNotNull(wildcardClass6);
    }

    @Test
    public void test087() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test087");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        String str3 = pathTrie0.findMaxPrefix("");
        String str5 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        String str10 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.addPath("hi!");
        org.junit.Assert.assertEquals("'" + str3 + "' != '" + "/" + "'", str3, "/");
        org.junit.Assert.assertEquals("'" + str5 + "' != '" + "/" + "'", str5, "/");
        org.junit.Assert.assertEquals("'" + str10 + "' != '" + "/" + "'", str10, "/");
    }

    @Test
    public void test088() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test088");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.clear();
        pathTrie0.clear();
    }

    @Test
    public void test089() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test089");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        pathTrie0.clear();
        pathTrie0.clear();
        boolean boolean7 = pathTrie0.existsNode("/");
        String str9 = pathTrie0.findMaxPrefix("/");
        // The following exception was thrown during execution in test generation
        try {
            boolean boolean11 = pathTrie0.existsNode("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
        org.junit.Assert.assertTrue("'" + boolean7 + "' != '" + true + "'", boolean7 == true);
        org.junit.Assert.assertEquals("'" + str9 + "' != '" + "/" + "'", str9, "/");
    }

    @Test
    public void test090() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test090");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        pathTrie0.clear();
        pathTrie0.clear();
        String str7 = pathTrie0.findMaxPrefix("");
        String str9 = pathTrie0.findMaxPrefix("/");
        String str11 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
        org.junit.Assert.assertEquals("'" + str9 + "' != '" + "/" + "'", str9, "/");
        org.junit.Assert.assertEquals("'" + str11 + "' != '" + "/" + "'", str11, "/");
    }

    @Test
    public void test091() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test091");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        pathTrie0.deletePath("hi!");
    }

    @Test
    public void test092() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test092");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        boolean boolean2 = pathTrie0.existsNode("hi!");
        String str4 = pathTrie0.findMaxPrefix("hi!");
        String str6 = pathTrie0.findMaxPrefix("/");
        org.junit.Assert.assertTrue("'" + boolean2 + "' != '" + false + "'", boolean2 == false);
        org.junit.Assert.assertEquals("'" + str4 + "' != '" + "/" + "'", str4, "/");
        org.junit.Assert.assertEquals("'" + str6 + "' != '" + "/" + "'", str6, "/");
    }

    @Test
    public void test093() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test093");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        boolean boolean3 = pathTrie0.existsNode("/");
        pathTrie0.addPath("hi!");
        String str7 = pathTrie0.findMaxPrefix("");
        org.junit.Assert.assertTrue("'" + boolean3 + "' != '" + true + "'", boolean3 == true);
        org.junit.Assert.assertEquals("'" + str7 + "' != '" + "/" + "'", str7, "/");
    }

    @Test
    public void test094() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test094");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        String str4 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.addPath("/");
        org.junit.Assert.assertEquals("'" + str4 + "' != '" + "/" + "'", str4, "/");
    }

    @Test
    public void test095() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test095");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        String str8 = pathTrie0.findMaxPrefix("/");
        String str10 = pathTrie0.findMaxPrefix("");
        Class<?> wildcardClass11 = pathTrie0.getClass();
        org.junit.Assert.assertEquals("'" + str8 + "' != '" + "/" + "'", str8, "/");
        org.junit.Assert.assertEquals("'" + str10 + "' != '" + "/" + "'", str10, "/");
        org.junit.Assert.assertNotNull(wildcardClass11);
    }

    @Test
    public void test096() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test096");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        boolean boolean8 = pathTrie0.existsNode("hi!");
        String str10 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.addPath("hi!");
        org.junit.Assert.assertTrue("'" + boolean8 + "' != '" + false + "'", boolean8 == false);
        org.junit.Assert.assertEquals("'" + str10 + "' != '" + "/" + "'", str10, "/");
    }

    @Test
    public void test097() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test097");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.addPath("hi!");
        Class<?> wildcardClass6 = pathTrie0.getClass();
        org.junit.Assert.assertNotNull(wildcardClass6);
    }

    @Test
    public void test098() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test098");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        boolean boolean2 = pathTrie0.existsNode("/");
        boolean boolean4 = pathTrie0.existsNode("/");
        boolean boolean6 = pathTrie0.existsNode("/");
        org.junit.Assert.assertTrue("'" + boolean2 + "' != '" + true + "'", boolean2 == true);
        org.junit.Assert.assertTrue("'" + boolean4 + "' != '" + true + "'", boolean4 == true);
        org.junit.Assert.assertTrue("'" + boolean6 + "' != '" + true + "'", boolean6 == true);
    }

    @Test
    public void test099() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test099");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.clear();
        pathTrie0.clear();
    }

    @Test
    public void test100() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test100");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        pathTrie0.clear();
        pathTrie0.clear();
        boolean boolean7 = pathTrie0.existsNode("/");
        String str9 = pathTrie0.findMaxPrefix("/");
        boolean boolean11 = pathTrie0.existsNode("/");
        pathTrie0.clear();
        org.junit.Assert.assertTrue("'" + boolean7 + "' != '" + true + "'", boolean7 == true);
        org.junit.Assert.assertEquals("'" + str9 + "' != '" + "/" + "'", str9, "/");
        org.junit.Assert.assertTrue("'" + boolean11 + "' != '" + true + "'", boolean11 == true);
    }

    @Test
    public void test101() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test101");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.addPath("hi!");
        Class<?> wildcardClass3 = pathTrie0.getClass();
        org.junit.Assert.assertNotNull(wildcardClass3);
    }

    @Test
    public void test102() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test102");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        String str8 = pathTrie0.findMaxPrefix("");
        boolean boolean10 = pathTrie0.existsNode("hi!");
        org.junit.Assert.assertEquals("'" + str8 + "' != '" + "/" + "'", str8, "/");
        org.junit.Assert.assertTrue("'" + boolean10 + "' != '" + false + "'", boolean10 == false);
    }

    @Test
    public void test103() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test103");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("hi!");
        pathTrie0.clear();
        // The following exception was thrown during execution in test generation
        try {
            pathTrie0.deletePath("");
            org.junit.Assert.fail("Expected exception of type java.lang.IllegalArgumentException; message: Invalid path: ");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    @Test
    public void test104() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test104");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        boolean boolean4 = pathTrie0.existsNode("hi!");
        boolean boolean6 = pathTrie0.existsNode("hi!");
        org.junit.Assert.assertTrue("'" + boolean4 + "' != '" + false + "'", boolean4 == false);
        org.junit.Assert.assertTrue("'" + boolean6 + "' != '" + false + "'", boolean6 == false);
    }

    @Test
    public void test105() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test105");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        boolean boolean3 = pathTrie0.existsNode("/");
        pathTrie0.addPath("hi!");
        pathTrie0.addPath("/");
        pathTrie0.addPath("/");
        org.junit.Assert.assertTrue("'" + boolean3 + "' != '" + true + "'", boolean3 == true);
    }

    @Test
    public void test106() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "RegressionTest0.test106");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("hi!");
        pathTrie0.clear();
        String str6 = pathTrie0.findMaxPrefix("hi!");
        org.junit.Assert.assertEquals("'" + str6 + "' != '" + "/" + "'", str6, "/");
    }
}

