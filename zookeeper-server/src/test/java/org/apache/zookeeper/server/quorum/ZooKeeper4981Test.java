package org.apache.zookeeper.server.quorum;



import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.QuorumUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compatible version of EphemeralCrashTest for 3.6.x+ ZooKeeper branches.
 * Automatically adapts to QuorumUtil method names (killServer/shutdown/createClient/getLeader etc.)
 */
public class ZooKeeper4981Test {

    @Test
    public void reproduceEphemeralAfterFollowerCrash() throws Exception {
        System.out.println("🧪 Starting EphemeralCrashTest...");

        QuorumUtil qu = new QuorumUtil(5);
        boolean started = false;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                System.out.println("🟢 Attempting to start quorum, try " + attempt);
                invokeIfExists(qu, "startAll");
                started = true;
                break;
            } catch (Exception e) {
                System.out.println("⚠️  Quorum start failed: " + e.getMessage());
                Thread.sleep(3000);
            }
        }
        assertTrue(started, "Quorum should start successfully");

        invokeIfExists(qu, "startAll");
        TimeUnit.SECONDS.sleep(2);

        // Find leader (try both getLeaderIndex and getLeader)
        int leader = -1;
        try {
            leader = (int) invokeIfExists(qu, "getLeaderIndex");
        } catch (Exception ignored) {
            try {
                leader = (int) invokeIfExists(qu, "getLeader");
            } catch (Exception ignored2) {}
        }

        System.out.println("✅ Leader found: " + leader);
        assertTrue(leader >= 0, "Leader should be present");

        // Pick a follower (not leader)
        int client1Server = (leader == 4 ? 3 : 4);

        // Connect client1
        ZooKeeper cli1 = connectClientCompat(qu, client1Server);
        assertNotNull(cli1, "Client1 connected");

        // Step 1: Create znodes
        createOrReplace(cli1, "/bug", "hello");
        createOrReplace(cli1, "/delete", "hello");

        // Step 2: Crash follower zk5
        crashCompat(qu, 4);

        // Step 3: Reconnect client1
        cli1.close();
        cli1 = connectClientCompat(qu, client1Server);
        assertNotNull(cli1, "Client1 reconnected");

        // Update /bug to "nice"
        Stat st = cli1.exists("/bug", false);
        cli1.setData("/bug", "nice".getBytes(), st.getVersion());
        byte[] data = cli1.getData("/bug", false, null);
        assertEquals("nice", new String(data), "/bug should be updated to 'nice'");

        // Delete /delete
        if (cli1.exists("/delete", false) != null)
            cli1.delete("/delete", -1);

        // Create ephemeral /eph
        cli1.create("/eph", "ephem".getBytes(),
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        assertNotNull(cli1.exists("/eph", false), "Ephemeral /eph created");

        // Step 4: Crash the client1’s server (zk4)
        crashCompat(qu, client1Server);

        // Step 5: Connect client2 to another node
        int client2Server = (client1Server == 0) ? 1 : 0;
        ZooKeeper cli2 = connectClientCompat(qu, client2Server);

        // Wait for ephemeral cleanup
        TimeUnit.SECONDS.sleep(2);

        // Check if /eph still exists
        Stat eph = cli2.exists("/eph", false);
        if (eph != null) {
            System.out.println("🐞 BUG REPRODUCED: /eph still visible after client1 crash");
        } else {
            System.out.println("✅ /eph cleaned correctly after crash");
        }

        assertNull(eph, "/eph should not exist after client1 disconnects");

        cli1.close();
        cli2.close();
        invokeIfExists(qu, "shutdownAll");
    }

    // --- Helpers ---

    private static Object invokeIfExists(Object obj, String methodName, Object... args) {
        for (Method m : obj.getClass().getMethods()) {
            if (m.getName().equals(methodName)) {
                try {
                    return m.invoke(obj, args);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return null;
    }

    private static ZooKeeper connectClientCompat(QuorumUtil qu, int idx) throws Exception {
        // Try all known method names
        for (String m : new String[]{"connectClient", "createClient", "connectToServer"}) {
            for (Method method : qu.getClass().getMethods()) {
                if (method.getName().equals(m)) {
                    Object res = method.invoke(qu, idx);
                    if (res instanceof ZooKeeper) return (ZooKeeper) res;
                    if (res != null && res.getClass().getSimpleName().contains("ZooKeeper"))
                        return (ZooKeeper) res;
                }
            }
        }
        throw new IllegalStateException("No suitable connectClient method found in QuorumUtil");
    }

    private static void crashCompat(QuorumUtil qu, int idx) {
        for (String m : new String[]{"shutdown", "shutdownServer", "kill", "killServer"}) {
            for (Method method : qu.getClass().getMethods()) {
                if (method.getName().equals(m)) {
                    try {
                        method.invoke(qu, idx);
                        System.out.println("💥 Crashed server " + idx);
                        return;
                    } catch (Exception e) {
                        // try next
                    }
                }
            }
        }
        throw new IllegalStateException("No method to crash server found in QuorumUtil");
    }

    private static void createOrReplace(ZooKeeper zk, String path, String val) throws Exception {
        if (zk.exists(path, false) != null) {
            zk.delete(path, -1);
        }
        zk.create(path, val.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }
}
