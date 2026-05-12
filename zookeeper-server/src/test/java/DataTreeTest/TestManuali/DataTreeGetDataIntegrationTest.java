package DataTreeTest.TestManuali;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class DataTreeGetDataIntegrationTest {

    private DataTree dataTree;

    private static final byte[] OLD_DATA = "old-data".getBytes();
    private static final byte[] NEW_DATA = "new-data".getBytes();

    private static final List<ACL> VALID_ACL = ZooDefs.Ids.OPEN_ACL_UNSAFE;

    private static final long CREATE_ZXID = 1L;
    private static final long SET_ZXID = 2L;

    private static final long CREATE_TIME = 1000L;
    private static final long SET_TIME = 2000L;

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
    }

    @Test
    public void getDataShouldReadNodeDataCopyStatAndRegisterWatcher() throws Exception {

        /*
         * 1. DataTree.createNode(...)
         * Prepara uno znode reale dentro il DataTree.
         */
        dataTree.createNode(
                "/a",
                OLD_DATA,
                VALID_ACL,
                0L,
                0,
                CREATE_ZXID,
                CREATE_TIME
        );

        /*
         * 2. Watcher.process(WatchedEvent event)
         * Il watcher salva l'evento ricevuto quando viene notificato.
         */
        AtomicInteger receivedEvents = new AtomicInteger(0);
        AtomicReference<WatchedEvent> receivedEvent = new AtomicReference<>();

        Watcher watcher = event -> {
            receivedEvents.incrementAndGet();
            receivedEvent.set(event);
        };

        /*
         * 3. DataTree.getData(...)
         * Verifica:
         * - ricerca del nodo tramite la struttura interna nodes;
         * - recupero dei dati dal DataNode;
         * - copia dei metadati nello Stat;
         * - registrazione del Watcher.
         */
        Stat stat = new Stat();

        byte[] result = dataTree.getData(
                "/a",
                stat,
                watcher
        );

        /*
         * 4. Verifica della lettura dei dati.
         * getData(...) deve restituire i dati contenuti nello znode "/a".
         */
        assertArrayEquals(OLD_DATA, result);

        /*
         * 5. Verifica indiretta di DataNode.copyStat(stat).
         * Lo Stat passato a getData(...) deve essere stato valorizzato.
         */
        assertEquals(CREATE_ZXID, stat.getCzxid());
        assertEquals(CREATE_ZXID, stat.getMzxid());
        assertEquals(CREATE_TIME, stat.getCtime());
        assertEquals(CREATE_TIME, stat.getMtime());
        assertEquals(OLD_DATA.length, stat.getDataLength());

        /*
         * 6. getData(...) registra la watch, ma non la attiva subito.
         */
        assertEquals(0, receivedEvents.get());
        assertNull(receivedEvent.get());

        /*
         * 7. DataTree.setData(...)
         * Modifica il nodo precedentemente letto.
         * Questa modifica deve attivare il watcher registrato da getData(...).
         */
        Stat updatedStat = dataTree.setData(
                "/a",
                NEW_DATA,
                1,
                SET_ZXID,
                SET_TIME
        );

        /*
         * 8. Verifica che setData(...) abbia aggiornato lo stato del nodo.
         */
        assertNotNull(updatedStat);
        assertEquals(1, updatedStat.getVersion());
        assertEquals(SET_ZXID, updatedStat.getMzxid());
        assertEquals(SET_TIME, updatedStat.getMtime());

        /*
         * 9. Verifica del WatchedEvent ricevuto dal Watcher.
         */
        assertEquals(1, receivedEvents.get());
        assertNotNull(receivedEvent.get());

        assertEquals(
                Watcher.Event.EventType.NodeDataChanged,
                receivedEvent.get().getType()
        );

        assertEquals(
                "/a",
                receivedEvent.get().getPath()
        );
    }
}