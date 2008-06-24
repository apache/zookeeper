package org.apache.zookeeper.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import junit.framework.TestCase;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.KeeperException;

public class DeserializationPerfTest extends TestCase {
    private static void deserializeTree(int depth, int width, int len)
            throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        BinaryInputArchive ia;
        int count;
        {
            DataTree tree = new DataTree();
            SerializationPerfTest.createNodes(tree, "/", depth, width, new byte[len]);
            count = tree.getNodeCount();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive oa = BinaryOutputArchive.getArchive(baos);
            tree.serialize(oa, "test");
            baos.flush();
    
            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            ia = BinaryInputArchive.getArchive(bais);
        }

        DataTree dserTree = new DataTree();

        System.gc();
        long start = System.nanoTime();
        dserTree.deserialize(ia, "test");
        long end = System.nanoTime();
        long durationms = (end - start) / 1000000L;
        long pernodeus = ((end - start) / 1000L) / count;
        
        assertEquals(count, dserTree.getNodeCount());
        
        System.out.println("Deserialized " + count + " nodes in " + durationms
                + " ms (" + pernodeus + "us/node), depth=" + depth + " width="
                + width + " datalen=" + len);
    }

    public void testSingleDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(1, 0, 20);
    }

    public void testWideDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(2, 50000, 20);
    }

    public void testDeepDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(1000, 1, 20);
    }

    public void test10Wide5DeepDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(5, 10, 20);
    }

    public void test15Wide5DeepDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(5, 15, 20);
    }

    public void test25Wide4DeepDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(4, 25, 20);
    }

    public void test40Wide4DeepDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(4, 40, 20);
    }

    public void test300Wide3DeepDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(3, 300, 20);
    }

}
