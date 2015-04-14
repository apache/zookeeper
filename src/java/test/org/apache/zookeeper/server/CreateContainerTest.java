package org.apache.zookeeper.server;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

public class CreateContainerTest extends ClientBase {
    private ZooKeeper zk;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        zk = createClient();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        zk.close();
    }

    @Test
    public void testCreate()
            throws IOException, KeeperException, InterruptedException {
        createNoStatVerifyResult("/foo");
        createNoStatVerifyResult("/foo/child");
    }

    @Test
    public void testCreateWithStat()
            throws IOException, KeeperException, InterruptedException {
        Stat stat = createWithStatVerifyResult("/foo");
        Stat childStat = createWithStatVerifyResult("/foo/child");
        // Don't expect to get the same stats for different creates.
        Assert.assertFalse(stat.equals(childStat));
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    public void testCreateWithNullStat()
            throws IOException, KeeperException, InterruptedException {
        final String name = "/foo";
        Assert.assertNull(zk.exists(name, false));

        Stat stat = null;
        // If a null Stat object is passed the create should still
        // succeed, but no Stat info will be returned.
        zk.createContainer(name, name.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, stat);
        Assert.assertNull(stat);
        Assert.assertNotNull(zk.exists(name, false));
    }

    @Test
    public void testSimpleDeletion()
            throws IOException, KeeperException, InterruptedException {
        zk.createContainer("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE);
        zk.create("/foo/bar", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.delete("/foo/bar", -1);  // should cause "/foo" to get deleted when checkContainers() is called

        ContainerManager containerManager = new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(), serverFactory.getZooKeeperServer().firstProcessor, 1, 1);
        containerManager.checkContainers();

        Thread.sleep(1000);

        Assert.assertNull("Container should have been deleted", zk.exists("/foo", false));
    }

    @Test
    public void testCascadingDeletion()
            throws IOException, KeeperException, InterruptedException {
        zk.createContainer("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE);
        zk.createContainer("/foo/bar", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE);
        zk.create("/foo/bar/one", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.delete("/foo/bar/one", -1);  // should cause "/foo/bar" and "/foo" to get deleted when checkContainers() is called

        ContainerManager containerManager = new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(), serverFactory.getZooKeeperServer().firstProcessor, 1, 1);
        containerManager.checkContainers();
        Thread.sleep(1000);
        containerManager
                .checkContainers();
        Thread.sleep(1000);

        Assert.assertNull("Container should have been deleted", zk.exists("/foo/bar", false));
        Assert.assertNull("Container should have been deleted", zk.exists("/foo", false));
    }

    @Test
    public void testFalseEmpty()
            throws IOException, KeeperException, InterruptedException {
        zk.createContainer("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE);
        zk.create("/foo/bar", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        ContainerManager containerManager = new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(), serverFactory.getZooKeeperServer().firstProcessor, 1, 1) {
            @Override
            protected Collection<String> getCandidates() {
                return Collections.singletonList("/foo");
            }
        };
        containerManager.checkContainers();
        Thread.sleep(1000);

        Assert.assertNotNull("Container should have not been deleted", zk.exists("/foo", false));
    }

    private void createNoStatVerifyResult(String newName)
            throws KeeperException, InterruptedException {
        Assert.assertNull("Node existed before created", zk.exists(newName, false));
        zk.createContainer(newName, newName.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE);
        Assert.assertNotNull("Node was not created as expected",
                zk.exists(newName, false));
    }
    private Stat createWithStatVerifyResult(String newName)
            throws KeeperException, InterruptedException {
        Assert.assertNull("Node existed before created", zk.exists(newName, false));
        Stat stat = new Stat();
        zk.createContainer(newName, newName.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, stat);
        validateCreateStat(stat, newName);

        Stat referenceStat = zk.exists(newName, false);
        Assert.assertNotNull("Node was not created as expected", referenceStat);
        Assert.assertEquals(referenceStat, stat);

        return stat;
    }

    private void validateCreateStat(Stat stat, String name) {
        Assert.assertEquals(stat.getCzxid(), stat.getMzxid());
        Assert.assertEquals(stat.getCzxid(), stat.getPzxid());
        Assert.assertEquals(stat.getCtime(), stat.getMtime());
        Assert.assertEquals(0, stat.getCversion());
        Assert.assertEquals(0, stat.getVersion());
        Assert.assertEquals(0, stat.getAversion());
        Assert.assertEquals(DataTree.CONTAINER_EPHEMERAL_OWNER, stat.getEphemeralOwner());
        Assert.assertEquals(name.length(), stat.getDataLength());
        Assert.assertEquals(0, stat.getNumChildren());
    }
}
