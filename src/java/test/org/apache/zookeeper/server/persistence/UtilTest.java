package org.apache.zookeeper.server.persistence;

import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class UtilTest {
    @Test
    public void testSortDataDirAscending() {
        File[] files = new File[5];

        files[0] = new File("foo.10027c6de");
        files[1] = new File("foo.10027c6df");
        files[2] = new File("bar.10027c6dd");
        files[3] = new File("foo.10027c6dc");
        files[4] = new File("foo.20027c6dc");

        File[] orig = files.clone();

        List<File> filelist = Util.sortDataDir(files, "foo", true);

        Assert.assertEquals(orig[2], filelist.get(0));
        Assert.assertEquals(orig[3], filelist.get(1));
        Assert.assertEquals(orig[0], filelist.get(2));
        Assert.assertEquals(orig[1], filelist.get(3));
        Assert.assertEquals(orig[4], filelist.get(4));
    }

    @Test
    public void testSortDataDirDescending() {
        File[] files = new File[5];

        files[0] = new File("foo.10027c6de");
        files[1] = new File("foo.10027c6df");
        files[2] = new File("bar.10027c6dd");
        files[3] = new File("foo.10027c6dc");
        files[4] = new File("foo.20027c6dc");

        File[] orig = files.clone();

        List<File> filelist = Util.sortDataDir(files, "foo", false);

        Assert.assertEquals(orig[4], filelist.get(0));
        Assert.assertEquals(orig[1], filelist.get(1));
        Assert.assertEquals(orig[0], filelist.get(2));
        Assert.assertEquals(orig[3], filelist.get(3));
        Assert.assertEquals(orig[2], filelist.get(4));
    }

    @Test
    public void testInvalidSnapshot() {
        File f = null;
        File tmpFileDir = null;
        try {
            tmpFileDir = ClientBase.createTmpDir();
            f = new File(tmpFileDir, "snapshot.0");
            if (!f.exists()) {
                f.createNewFile();
            }
            Assert.assertFalse("Snapshot file size is greater than 9 bytes", Util.isValidSnapshot(f));
            Assert.assertTrue("Can't delete file", f.delete());
        } catch (IOException e) {
        } finally {
            if (null != tmpFileDir) {
                ClientBase.recursiveDelete(tmpFileDir);
            }
        }
    }
}
