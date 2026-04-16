package org.apache.zookeeper.randoop.generated2;

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
    public void test1() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test1");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.clear();
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test2() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test2");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test3() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test3");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.deletePath("log");
        pathTrie0.addPath("/");
        boolean boolean8 = pathTrie0.existsNode("/");
        String str10 = pathTrie0.findMaxPrefix("");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }

    @Test
    public void test4() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test4");
        org.apache.zookeeper.common.PathTrie pathTrie0 = new org.apache.zookeeper.common.PathTrie();
        pathTrie0.deletePath("hi!");
        pathTrie0.clear();
        pathTrie0.clear();
        String str6 = pathTrie0.findMaxPrefix("/");
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        pathTrie0.deletePath("/");
    }
}

