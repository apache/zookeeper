package PathTrieTest.randoop.C1;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ErrorTest0 {

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
    public void test01() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test01");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        String str3 = pathTrie0.findMaxPrefix("");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test02() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test02");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test03() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test03");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        String str6 = pathTrie0.findMaxPrefix("hi!");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test04() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test04");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        String str3 = pathTrie0.findMaxPrefix("");
        String str5 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.addPath("hi!");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test05() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test05");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        boolean boolean4 = pathTrie0.existsNode("hi!");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test06() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test06");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test07() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test07");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        boolean boolean5 = pathTrie0.existsNode("/");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test08() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test08");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        String str4 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.clear();
        String str7 = pathTrie0.findMaxPrefix("");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test09() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test09");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        String str3 = pathTrie0.findMaxPrefix("");
        pathTrie0.addPath("/");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test10() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test10");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        boolean boolean2 = pathTrie0.existsNode("/");
        boolean boolean4 = pathTrie0.existsNode("/");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test11() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test11");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        pathTrie0.deletePath("hi!");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test12() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test12");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        boolean boolean2 = pathTrie0.existsNode("/");
        boolean boolean4 = pathTrie0.existsNode("/");
        boolean boolean6 = pathTrie0.existsNode("hi!");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test13() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test13");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        String str2 = pathTrie0.findMaxPrefix("hi!");
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.addPath("/");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test14() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test14");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        String str4 = pathTrie0.findMaxPrefix("hi!");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test15() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test15");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        boolean boolean3 = pathTrie0.existsNode("/");
        pathTrie0.addPath("hi!");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test16() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test16");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        boolean boolean4 = pathTrie0.existsNode("hi!");
        pathTrie0.clear();
        pathTrie0.clear();
        String str8 = pathTrie0.findMaxPrefix("hi!");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test17() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test17");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.clear();
        pathTrie0.addPath("/");
        pathTrie0.clear();
        pathTrie0.deletePath("hi!");
        boolean boolean8 = pathTrie0.existsNode("hi!");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }
}

