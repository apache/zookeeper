package DataTreeTest.randoop.generated2;

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
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        dataTree0.compareSnapshotDigests((long) (-1));
    }

    @Test
    public void test2() throws Throwable {
        if (debug)
            System.out.format("%n%s%n", "ErrorTest0.test2");
        org.apache.zookeeper.server.DataTree dataTree0 = new org.apache.zookeeper.server.DataTree();
        dataTree0.setCversionPzxid("", (int) (byte) 100, (long) (-1));
        // during test generation this statement threw an exception of type java.lang.NullPointerException in error
        dataTree0.compareSnapshotDigests((long) (byte) 1);
    }
}

