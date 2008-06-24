package org.apache.zookeeper.server;

import java.io.File;
import java.util.List;

import junit.framework.TestCase;

public class ZooKeeperServerTest extends TestCase {
    public void testSortDataDirAscending() {
        File[] files = new File[5];
        
        files[0] = new File("foo.10027c6de");
        files[1] = new File("foo.10027c6df");
        files[2] = new File("bar.10027c6dd");
        files[3] = new File("foo.10027c6dc");
        files[4] = new File("foo.20027c6dc");
        
        File[] orig = files.clone();

        List<File> filelist = ZooKeeperServer.sortDataDir(files, "foo", true);
        
        assertEquals(orig[2], filelist.get(0));
        assertEquals(orig[3], filelist.get(1));
        assertEquals(orig[0], filelist.get(2));
        assertEquals(orig[1], filelist.get(3));
        assertEquals(orig[4], filelist.get(4));
    }

    public void testSortDataDirDescending() {
        File[] files = new File[5];
        
        files[0] = new File("foo.10027c6de");
        files[1] = new File("foo.10027c6df");
        files[2] = new File("bar.10027c6dd");
        files[3] = new File("foo.10027c6dc");
        files[4] = new File("foo.20027c6dc");

        File[] orig = files.clone();

        List<File> filelist = ZooKeeperServer.sortDataDir(files, "foo", false);
        
        assertEquals(orig[4], filelist.get(0));
        assertEquals(orig[1], filelist.get(1));
        assertEquals(orig[0], filelist.get(2));
        assertEquals(orig[3], filelist.get(3));
        assertEquals(orig[2], filelist.get(4));
    }
    
    public void testGetLogFiles() {
        File[] files = new File[5];
        
        files[0] = new File("log.10027c6de");
        files[1] = new File("log.10027c6df");
        files[2] = new File("snapshot.10027c6dd");
        files[3] = new File("log.10027c6dc");
        files[4] = new File("log.20027c6dc");

        File[] orig = files.clone();

        File[] filelist = 
            ZooKeeperServer.getLogFiles(files,
                Long.parseLong("10027c6de", 16));
        
        assertEquals(3, filelist.length);
        assertEquals(orig[0], filelist[0]);
        assertEquals(orig[1], filelist[1]);
        assertEquals(orig[4], filelist[2]);
    }

}
