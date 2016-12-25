package org.apache.zookeeper.test;

import org.apache.zookeeper.server.PathIterator;
import org.junit.Assert;
import org.junit.Test;

public class PathIteratorTest {
    @Test
    public void testRoot() {
        PathIterator pathIterator = new PathIterator("/");
        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertFalse(pathIterator.atParentPath());
        Assert.assertEquals(pathIterator.next(), "/");
        Assert.assertFalse(pathIterator.hasNext());
    }

    @Test
    public void test1Level() {
        PathIterator pathIterator = new PathIterator("/a");
        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertFalse(pathIterator.atParentPath());
        Assert.assertEquals(pathIterator.next(), "/a");

        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertEquals(pathIterator.next(), "/");
        Assert.assertTrue(pathIterator.atParentPath());

        Assert.assertFalse(pathIterator.hasNext());
    }

    @Test
    public void testLong() {
        PathIterator pathIterator = new PathIterator("/a/b/c/d");

        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertEquals(pathIterator.next(), "/a/b/c/d");
        Assert.assertFalse(pathIterator.atParentPath());

        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertEquals(pathIterator.next(), "/a/b/c");
        Assert.assertTrue(pathIterator.atParentPath());

        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertEquals(pathIterator.next(), "/a/b");
        Assert.assertTrue(pathIterator.atParentPath());

        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertEquals(pathIterator.next(), "/a");
        Assert.assertTrue(pathIterator.atParentPath());

        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertEquals(pathIterator.next(), "/");
        Assert.assertTrue(pathIterator.atParentPath());

        Assert.assertFalse(pathIterator.hasNext());
    }
}
