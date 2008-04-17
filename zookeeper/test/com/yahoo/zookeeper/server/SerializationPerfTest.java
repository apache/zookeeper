package com.yahoo.zookeeper.server;

import java.io.IOException;
import java.io.OutputStream;

import junit.framework.TestCase;

import com.yahoo.jute.BinaryOutputArchive;
import com.yahoo.zookeeper.KeeperException;

public class SerializationPerfTest extends TestCase {
    private static class NullOutputStream extends OutputStream {
        public void write(int b) {
        }
    }

    static int createNodes(DataTree tree, String path, int depth,
            int childcount, byte[] data)
        throws KeeperException 
    {
        path += "node" + depth;
        tree.createNode(path, data, null, -1, 1, 1);

        if (--depth == 0) {
            return 1;
        }
        
        path += "/";
        
        int count = 1;
        for (int i = 0; i < childcount; i++) {
            count += createNodes(tree, path + i, depth, childcount, data);
        }
        
        return count;
    }

    private static void serializeTree(int depth, int width, int len) 
        throws KeeperException, InterruptedException, IOException
    {
        DataTree tree = new DataTree();
        createNodes(tree, "/", depth, width, new byte[len]);
        int count = tree.getNodeCount();
        
        BinaryOutputArchive oa = 
            BinaryOutputArchive.getArchive(new NullOutputStream());
        System.gc();
        long start = System.nanoTime();
        tree.serialize(oa, "test");
        long end = System.nanoTime();
        long durationms = (end - start)/1000000L;
        long pernodeus = ((end - start)/1000L)/count;
        System.out.println("Serialized " + count + " nodes in "
                + durationms + " ms (" + pernodeus + "us/node), depth=" 
                + depth + " width=" + width + " datalen=" + len);
    }

    public void testSingleSerialize() 
        throws KeeperException, InterruptedException, IOException
    {
        serializeTree(1, 0, 20);
    }

    public void testWideSerialize() 
        throws KeeperException, InterruptedException, IOException
    {
        serializeTree(2, 50000, 20);
    }

    public void testDeepSerialize() 
        throws KeeperException, InterruptedException, IOException
    {
        serializeTree(1000, 1, 20);
    }
    
    public void test10Wide5DeepSerialize() 
        throws KeeperException, InterruptedException, IOException
    {
        serializeTree(5, 10, 20);
    }

    public void test15Wide5DeepSerialize() 
        throws KeeperException, InterruptedException, IOException
    {
        serializeTree(5, 15, 20);
    }

    public void test25Wide4DeepSerialize() 
        throws KeeperException, InterruptedException, IOException
    {
        serializeTree(4, 25, 20);
    }

    public void test40Wide4DeepSerialize() 
        throws KeeperException, InterruptedException, IOException
    {
        serializeTree(4, 40, 20);
    }

    public void test300Wide3DeepSerialize() 
        throws KeeperException, InterruptedException, IOException
    {
        serializeTree(3, 300, 20);
    }
}
