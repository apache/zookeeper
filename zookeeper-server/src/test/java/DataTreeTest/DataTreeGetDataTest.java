package DataTreeTest;


import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class DataTreeGetDataTest {

    private DataTree dataTree;

    private static final byte[] VALID_DATA = "data".getBytes();
    private static final byte[] CHILD_DATA = "child-data".getBytes();
    private static final byte[] NEW_DATA = "new-data".getBytes();

    private static final List<ACL> VALID_ACL = ZooDefs.Ids.OPEN_ACL_UNSAFE;
    private static final long VALID_TIME = System.currentTimeMillis();

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
    }

    private void createValidNode(String path) throws Exception {
        dataTree.createNode(
                path,
                VALID_DATA,
                VALID_ACL,
                -1L,
                0,
                1L,
                VALID_TIME
        );
    }

    private void createValidNode(String path, byte[] data) throws Exception {
        dataTree.createNode(
                path,
                data,
                VALID_ACL,
                -1L,
                0,
                1L,
                VALID_TIME
        );
    }

    private void assertNodeExists(String path) {
        assertNotNull(dataTree.getNode(path), "Il nodo " + path + " dovrebbe esistere");
    }

    private void assertNodeDoesNotExist(String path) {
        assertNull(dataTree.getNode(path), "Il nodo " + path + " non dovrebbe esistere");
    }

    private static class RecordingWatcher implements Watcher {

        private int notificationCount = 0;
        private WatchedEvent lastEvent;

        @Override
        public void process(WatchedEvent event) {
            notificationCount++;
            lastEvent = event;
        }

        public int getNotificationCount() {
            return notificationCount;
        }

        public WatchedEvent getLastEvent() {
            return lastEvent;
        }
    }

    @Test
    public void getDataShouldReturnDataForExistingSimplePath() throws Exception {
        // T1 - path valido semplice, nodo richiesto già presente, stat valido, watcher null

        createValidNode("/a", VALID_DATA);

        Stat stat = new Stat();
        byte[] result = dataTree.getData("/a", stat, null);

        assertArrayEquals(VALID_DATA, result);
        assertEquals(0, stat.getVersion());
        assertNodeExists("/a");
    }

    @Test
    public void getDataShouldReturnDataForExistingMultilevelPath() throws Exception {
        // T2 - path valido multilivello, nodo richiesto presente in un path multilivello

        createValidNode("/a");
        createValidNode("/a/b", CHILD_DATA);

        Stat stat = new Stat();
        byte[] result = dataTree.getData("/a/b", stat, null);

        assertArrayEquals(CHILD_DATA, result);
        assertEquals(0, stat.getVersion());
        assertNodeExists("/a");
        assertNodeExists("/a/b");
    }

    @Test
    public void getDataShouldPopulateValidStatObject() throws Exception {
        // T3 - stat = new Stat()

        createValidNode("/a", VALID_DATA);

        Stat stat = new Stat();
        byte[] result = dataTree.getData("/a", stat, null);

        assertArrayEquals(VALID_DATA, result);
        assertEquals(0, stat.getVersion());
        assertEquals(1L, stat.getCzxid());
        assertEquals(1L, stat.getMzxid());
    }

    @Test
    public void getDataShouldReturnDataWhenStatIsNull() throws Exception {
        // T4 - stat = null

        createValidNode("/a", VALID_DATA);

        byte[] result = dataTree.getData("/a", null, null);

        assertArrayEquals(VALID_DATA, result);
        assertNodeExists("/a");
    }

    @Test
    public void getDataShouldOverwritePreviouslyValuedStat() throws Exception {
        // T5 - stat già valorizzato prima della chiamata

        createValidNode("/a", VALID_DATA);

        Stat stat = new Stat();
        stat.setVersion(999);
        stat.setCzxid(999L);
        stat.setMzxid(999L);

        byte[] result = dataTree.getData("/a", stat, null);

        assertArrayEquals(VALID_DATA, result);
        assertEquals(0, stat.getVersion());
        assertEquals(1L, stat.getCzxid());
        assertEquals(1L, stat.getMzxid());
    }

    @Test
    public void getDataShouldRegisterValidWatcher() throws Exception {
        // T6 - watcher valido

        createValidNode("/a", VALID_DATA);

        RecordingWatcher watcher = new RecordingWatcher();

        byte[] result = dataTree.getData("/a", new Stat(), watcher);

        assertArrayEquals(VALID_DATA, result);
        assertEquals(0, watcher.getNotificationCount());

        dataTree.setData(
                "/a",
                NEW_DATA,
                -1,
                2L,
                System.currentTimeMillis()
        );

        assertTrue(
                watcher.getNotificationCount() > 0,
                "Il watcher dovrebbe essere notificato dopo la modifica del nodo"
        );
        assertNotNull(watcher.getLastEvent());
    }

    @Test
    public void getDataWithNullWatcherShouldNotRegisterWatcher() throws Exception {
        // T7 - watcher = null

        createValidNode("/a", VALID_DATA);

        byte[] result = dataTree.getData("/a", new Stat(), null);

        assertArrayEquals(VALID_DATA, result);

        assertDoesNotThrow(() -> dataTree.setData(
                "/a",
                NEW_DATA,
                -1,
                2L,
                System.currentTimeMillis()
        ));

        byte[] updatedData = dataTree.getData("/a", new Stat(), null);
        assertArrayEquals(NEW_DATA, updatedData);
    }

    @Test
    public void getDataWithFaultyWatcherShouldStillReturnData() throws Exception {
        // T8 - watcher non valido/anomalo mockato

        createValidNode("/a", VALID_DATA);

        Watcher faultyWatcher = mock(Watcher.class);
        doThrow(new RuntimeException("Watcher failure"))
                .when(faultyWatcher)
                .process(any(WatchedEvent.class));

        byte[] result = dataTree.getData("/a", new Stat(), faultyWatcher);

        assertArrayEquals(VALID_DATA, result);

        verify(faultyWatcher, never()).process(any(WatchedEvent.class));
        assertNodeExists("/a");
    }

    @Test
    public void getDataShouldThrowNoNodeExceptionForMissingSimplePath() {
        // T9 - path valido semplice, nodo richiesto assente

        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.getData("/a", new Stat(), null)
        );

        assertNodeDoesNotExist("/a");
        assertNodeExists("/");
    }

    @Test
    public void getDataShouldThrowNoNodeExceptionForMissingMultilevelPath() {
        // T10 - path valido multilivello, nodo richiesto assente

        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.getData("/x/y", new Stat(), null)
        );

        assertNodeDoesNotExist("/x");
        assertNodeDoesNotExist("/x/y");
        assertNodeExists("/");
    }

    @Test
    public void getDataOnIndependentBranchShouldNotAlterOtherBranches() throws Exception {
        // T11 - DataTree con più nodi e rami indipendenti

        createValidNode("/a", VALID_DATA);
        createValidNode("/x", VALID_DATA);
        createValidNode("/x/y", CHILD_DATA);

        byte[] result = dataTree.getData("/x/y", new Stat(), null);

        assertArrayEquals(CHILD_DATA, result);

        assertNodeExists("/a");
        assertNodeExists("/x");
        assertNodeExists("/x/y");

        byte[] dataOnA = dataTree.getData("/a", new Stat(), null);
        assertArrayEquals(VALID_DATA, dataOnA);
    }

    @Test
    public void getDataOnRootInInitialTreeShouldBeHandledCorrectly() {
        // T12 - path radice, DataTree nello stato iniziale

        assertDoesNotThrow(() -> {
            Stat stat = new Stat();
            dataTree.getData("/", stat, null);
        });

        assertNodeExists("/");
    }

    @Test
    public void getDataOnRootShouldNotAlterExistingApplicationNodes() throws Exception {
        // T13 - path radice, DataTree con più nodi e rami indipendenti

        createValidNode("/a", VALID_DATA);
        createValidNode("/x", VALID_DATA);

        assertDoesNotThrow(() -> {
            Stat stat = new Stat();
            dataTree.getData("/", stat, null);
        });

        assertNodeExists("/");
        assertNodeExists("/a");
        assertNodeExists("/x");

        byte[] dataA = dataTree.getData("/a", new Stat(), null);
        byte[] dataX = dataTree.getData("/x", new Stat(), null);

        assertArrayEquals(VALID_DATA, dataA);
        assertArrayEquals(VALID_DATA, dataX);
    }

    @Test
    public void getDataWithNullPathShouldNotCorruptTree() {
        // T14 - path nullo

        assertThrows(
                Exception.class,
                () -> dataTree.getData(null, new Stat(), null)
        );

        assertNodeExists("/");
    }

    @Test
    public void getDataWithEmptyPathShouldNotCorruptTree() {
        // T15 - path vuoto o malformato: ""

        assertThrows(
                Exception.class,
                () -> dataTree.getData("", new Stat(), null)
        );

        assertNodeExists("/");
    }

    @Test
    public void getDataWithPathWithoutInitialSlashShouldNotCorruptTree() {
        // T16 - path vuoto o malformato: "a/b"

        assertThrows(
                Exception.class,
                () -> dataTree.getData("a/b", new Stat(), null)
        );

        assertNodeExists("/");
    }

    @Test
    public void getDataWithDoubleSlashPathShouldNotCorruptTree() {
        // T17 - path vuoto o malformato: "/a//b"

        assertThrows(
                Exception.class,
                () -> dataTree.getData("/a//b", new Stat(), null)
        );

        assertNodeExists("/");
    }

    @Test
    public void getDataShouldThrowNoNodeExceptionWhenOnlyRootExists() {
        // T18 - DataTree con solo nodo radice presente

        assertNodeExists("/");
        assertNodeDoesNotExist("/a");

        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.getData("/a", new Stat(), null)
        );

        assertNodeExists("/");
        assertNodeDoesNotExist("/a");
    }
}