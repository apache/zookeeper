// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.zookeeper.recipes.interop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.ProtectedUtils;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.Participant;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.recipes.locks.LockInternals;
import org.apache.curator.framework.recipes.locks.StandardLockInternalsDriver;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Cross-language interoperability tests between Apache Curator and
 * {@code apache-zookeeper-recipes}.
 *
 * <p>Exercises Curator {@code InterProcessMutex} and {@code LeaderLatch}
 * instances concurrently with Rust {@link RustPeer} subprocesses on shared
 * ZooKeeper paths against the server or ensemble specified by
 * {@code zk.connect}. When {@code zk.connect} or the peer binary is absent,
 * tests are skipped via JUnit assumptions.
 *
 * <p>Participant znode creation is sequenced deterministically by awaiting each
 * new child before starting the next contender, and expected ordering is
 * computed using Curator's {@link LockInternals#getSortedChildren}. Because
 * ensemble participants may connect to different quorum peers, child and state
 * assertions poll with a bounded timeout.
 *
 * <p>Wire level and znode layout assertions validate Rust protected node names
 * directly against Curator 5.7.1's {@link ProtectedUtils} and compare captured
 * {@code CreateRequest} frames via {@link CutProxy}.
 */
class CuratorInteropTest {

    /** Maximum timeout for asynchronous test assertions. */
    private static final Duration WAIT = Duration.ofSeconds(20);

    /** Observation window during which blocked contenders must remain pending. */
    private static final Duration STILL = Duration.ofMillis(500);

    /** Node name marker for {@code InterProcessMutex} znodes. */
    private static final String LOCK_NAME = "lock-";

    /** Node name marker for {@code LeaderLatch} znodes. */
    private static final String LATCH_NAME = "latch-";

    /**
     * Synthetic sibling name that sorts ahead of all sequential nodes only when
     * the sort key is extracted after the final occurrence of {@link #LOCK_NAME}
     * (yielding an empty sort key rather than {@code "9-lock-"}).
     */
    private static final String FIRST_LOCK = "lock-9-lock-";

    /** Latch counterpart to {@link #FIRST_LOCK}. */
    private static final String FIRST_LATCH = "latch-9-latch-";

    /** Synthetic sibling name lacking any marker, sorting after all sequential nodes. */
    private static final String LAST = "foreign";


    private String connect;
    private CuratorFramework curator;
    private String root;
    private final List<AutoCloseable> resources = new ArrayList<>();

    @BeforeEach
    void setUp(TestInfo info) throws Exception {
        connect = System.getProperty("zk.connect", "");
        assumeTrue(!connect.isEmpty(), "no zk.connect");
        assumeTrue(RustPeer.binary() != null, "no Rust peer binary");
        curator = CuratorFrameworkFactory.newClient(connect, new RetryOneTime(100));
        curator.start();
        assertTrue(curator.blockUntilConnected((int) WAIT.getSeconds(), TimeUnit.SECONDS),
            "Curator did not connect to " + connect);
        String test = info.getTestMethod().map(m -> m.getName()).orElse("test");
        root = "/curator-interop/" + test + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception e) {
                // Continue closing remaining tracked resources.
            }
        }
        resources.clear();
        if (curator != null) {
            try {
                curator.delete().quietly().deletingChildrenIfNeeded().forPath(root);
            } catch (KeeperException.NoNodeException e) {
                // Container hierarchy already removed.
            }
            curator.close();
        }
    }

    /**
     * Verifies mutual exclusion and FIFO fairness across two Curator
     * {@code InterProcessMutex} instances and two Rust peers contending on a
     * shared lock path.
     */
    @Test
    void lockIsExclusiveAndFairAcrossLanguages() throws Exception {
        String path = root + "/lock";
        Holder c1 = track(new Holder(curator, path, "c1"));
        Holder c2 = track(new Holder(curator, path, "c2"));
        RustPeer r1 = track(RustPeer.start(connect));
        RustPeer r2 = track(RustPeer.start(connect));
        long r1Session = r1.session().id;
        long r2Session = r2.session().id;

        // Enqueue contenders sequentially: C1, R1, C2, R2.
        List<String> created = new ArrayList<>();
        assertTrue(c1.acquire(WAIT).get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        created.add(awaitNewChild(path, created));
        Future<String> r1Lock = r1.lock(path, WAIT.toMillis(), "rust-1");
        created.add(awaitNewChild(path, created));
        Future<Boolean> c2Lock = c2.acquire(WAIT);
        created.add(awaitNewChild(path, created));
        Future<String> r2Lock = r2.lock(path, WAIT.toMillis(), "rust-2");
        created.add(awaitNewChild(path, created));

        // Verify that Curator sorts znodes from both languages in creation order.
        assertEquals(created, sorted(path, LOCK_NAME));
        assertEquals(paths(path, created), new ArrayList<>(c1.mutex.getParticipantNodes()));

        // Verify Rust znode naming, protected UUID uniqueness, payloads, and ephemeral owners.
        assertCuratorLayout(created.get(1), created.get(0), LOCK_NAME);
        assertCuratorLayout(created.get(3), created.get(2), LOCK_NAME);
        assertDistinctIds(created);
        assertArrayEquals(utf8("rust-1"), data(path + "/" + created.get(1)));
        assertArrayEquals(utf8("rust-2"), data(path + "/" + created.get(3)));
        assertOwner(path + "/" + created.get(0), curatorSession());
        assertOwner(path + "/" + created.get(1), r1Session);
        assertOwner(path + "/" + created.get(2), curatorSession());
        assertOwner(path + "/" + created.get(3), r2Session);

        // Release each lock holder in turn and verify strict FIFO handoff.
        assertStillPending(r1Lock, c2Lock, r2Lock);
        c1.release();
        assertEquals("OK " + created.get(1), get(r1Lock));
        assertFalse(c1.mutex.isAcquiredInThisProcess());
        assertStillPending(c2Lock, r2Lock);
        r1.unlock();
        assertTrue(get(c2Lock));
        assertStillPending(r2Lock);
        c2.release();
        assertEquals("OK " + created.get(3), get(r2Lock));

        // nonblocking acquisition attempts from either language fail while R2 holds the lock.
        assertFalse(c1.acquire(Duration.ZERO).get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        assertNull(r1.tryLock(path));
        awaitChildren(path, c -> c.equals(List.of(created.get(3))));
        r2.unlock();

        // Once unlocked, nonblocking tryLock succeeds immediately.
        String r1Node = r1.tryLock(path);
        assertNotNull(r1Node);
        assertProtected(r1Node, LOCK_NAME);
        assertFalse(c1.acquire(Duration.ZERO).get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        r1.unlock();
        awaitChildren(path, List::isEmpty);
    }

    /**
     * Verifies that leadership transitions in creation order across Curator and
     * Rust {@code LeaderLatch} instances and that participant IDs are readable
     * across both languages.
     */
    @Test
    void latchOrderAndParticipantsAcrossLanguages() throws Exception {
        String path = root + "/latch";
        RustPeer r1 = track(RustPeer.start(connect));
        RustPeer r2 = track(RustPeer.start(connect));
        long r1Session = r1.session().id;
        long r2Session = r2.session().id;

        // Start participants sequentially: C1, R1, C2, R2.
        List<String> created = new ArrayList<>();
        LeaderLatch c1 = track(new LeaderLatch(curator, path, "curator-1"));
        c1.start();
        assertTrue(c1.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        created.add(awaitNewChild(path, created));
        String r1Node = r1.latch(path, "rust-1");
        assertEquals(r1Node, awaitNewChild(path, created));
        created.add(r1Node);
        LeaderLatch c2 = track(new LeaderLatch(curator, path, "curator-2"));
        c2.start();
        created.add(awaitNewChild(path, created));
        String r2Node = r2.latch(path, "rust-2");
        assertEquals(r2Node, awaitNewChild(path, created));
        created.add(r2Node);

        assertEquals(created, sorted(path, LATCH_NAME));
        assertEquals(c1.getOurPath(), path + "/" + created.get(0));
        awaitOurPath(c2, path + "/" + created.get(2));
        assertCuratorLayout(r1Node, created.get(0), LATCH_NAME);
        assertCuratorLayout(r2Node, created.get(2), LATCH_NAME);
        assertDistinctIds(created);

        // Verify that participant IDs are stored identically as raw UTF-8 znode data.
        assertArrayEquals(utf8("curator-1"), data(path + "/" + created.get(0)));
        assertArrayEquals(utf8("rust-1"), data(path + "/" + r1Node));
        assertArrayEquals(utf8("curator-2"), data(path + "/" + created.get(2)));
        assertArrayEquals(utf8("rust-2"), data(path + "/" + r2Node));
        assertOwner(path + "/" + r1Node, r1Session);
        assertOwner(path + "/" + r2Node, r2Session);
        assertOwner(path + "/" + created.get(0), curatorSession());

        // Verify that both implementations report the same ordered participant list.
        List<String> all = List.of("curator-1:1", "rust-1:0", "curator-2:0", "rust-2:0");
        assertEquals(all, curatorParticipants(c2));
        awaitParticipants(r1, all);
        awaitParticipants(r2, all);

        // Close each leader in turn and verify leadership handoff across languages.
        assertFalse(r1.awaitLeader(STILL.toMillis()));
        assertFalse(r1.isLeader());
        c1.close();
        assertTrue(r1.awaitLeader(WAIT.toMillis()));
        assertTrue(r1.isLeader());
        assertFalse(c2.hasLeadership());
        assertEquals("rust-1", c2.getLeader().getId());
        assertFalse(r2.awaitLeader(STILL.toMillis()));

        r1.closeLatch();
        assertTrue(c2.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        awaitParticipants(r2, List.of("curator-2:1", "rust-2:0"));
        assertFalse(r2.awaitLeader(STILL.toMillis()));

        c2.close();
        assertTrue(r2.awaitLeader(WAIT.toMillis()));
        awaitParticipants(r2, List.of("rust-2:1"));
        r2.closeLatch();
        awaitChildren(path, List::isEmpty);
    }

    /**
     * Verifies protected-node recovery when a connection drop interrupts a Rust
     * participant's {@code create} request.
     *
     * <p>In {@link CutProxy.Mode#APPLIED}, the server creates the znode before
     * the proxy drops the reply; the peer must adopt the existing znode on
     * reconnect. In {@link CutProxy.Mode#LOST}, the proxy drops the request
     * before forwarding; the peer must retry creation using the same protected
     * UUID prefix without leaving orphan znodes.
     */
    @Test
    void rustCreateRecoveryLeavesNoOrphan() throws Exception {
        CutProxy proxy = track(CutProxy.start(firstServer(connect), proxyPort()));
        RustPeer peer = track(RustPeer.start(proxy.connectString()));
        long session = peer.session().id;

        // DistributedLock: connection dropped after the server applies the create.
        String applied = root + "/lock-applied";
        curator.create().creatingParentsIfNeeded().forPath(applied);
        CompletableFuture<String> cut = proxy.arm(CutProxy.Mode.APPLIED, applied + "/_c_");
        Future<String> lock = peer.lock(applied, WAIT.toMillis());
        String made = name(get(cut));
        assertEquals("OK " + made, get(lock));
        assertProtected(made, LOCK_NAME);
        assertOwner(applied + "/" + made, session);
        assertEquals(1, proxy.cuts());
        awaitChildren(applied, c -> c.equals(List.of(made)));
        peer.unlock();
        awaitChildren(applied, List::isEmpty);

        // DistributedLock: connection dropped before the create reaches the server.
        String lost = root + "/lock-lost";
        curator.create().creatingParentsIfNeeded().forPath(lost);
        cut = proxy.arm(CutProxy.Mode.LOST, lost + "/_c_");
        lock = peer.lock(lost, WAIT.toMillis());
        String prefix = name(get(cut));
        String again = RustPeer.ok(get(lock));
        assertTrue(again.startsWith(prefix), again + " does not keep the prefix " + prefix);
        assertEquals(2, proxy.cuts());
        awaitChildren(lost, c -> c.equals(List.of(again)));
        Holder c1 = track(new Holder(curator, lost, "c1"));
        Future<Boolean> c1Lock = c1.acquire(WAIT);
        assertStillPending(c1Lock);
        peer.unlock();
        assertTrue(get(c1Lock));
        c1.release();
        awaitChildren(lost, List::isEmpty);

        // LeaderLatch: connection dropped after the server applies the create.
        String latchPath = root + "/latch-applied";
        LeaderLatch leader = track(new LeaderLatch(curator, latchPath, "curator-1"));
        leader.start();
        assertTrue(leader.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        cut = proxy.arm(CutProxy.Mode.APPLIED, latchPath + "/_c_");
        Future<String> latch = peer.callAsync("LATCH " + latchPath + " rust-1");
        String latchNode = name(get(cut));
        assertEquals("OK " + latchNode, get(latch));
        assertEquals(3, proxy.cuts());
        awaitChildren(latchPath, c -> c.size() == 2 && c.contains(latchNode));
        assertEquals(List.of("curator-1:1", "rust-1:0"), curatorParticipants(leader));
        awaitParticipants(peer, List.of("curator-1:1", "rust-1:0"));
        leader.close();
        assertTrue(peer.awaitLeader(WAIT.toMillis()));
        peer.closeLatch();
        awaitChildren(latchPath, List::isEmpty);

        // LeaderLatch: connection dropped before the create reaches the server.
        String latchLost = root + "/latch-lost";
        LeaderLatch second = track(new LeaderLatch(curator, latchLost, "curator-2"));
        second.start();
        assertTrue(second.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        cut = proxy.arm(CutProxy.Mode.LOST, latchLost + "/_c_");
        latch = peer.callAsync("LATCH " + latchLost + " rust-2");
        String latchPrefix = name(get(cut));
        String latchAgain = RustPeer.ok(get(latch));
        assertTrue(latchAgain.startsWith(latchPrefix),
            latchAgain + " does not keep the prefix " + latchPrefix);
        assertProtected(latchAgain, LATCH_NAME);
        assertEquals(4, proxy.cuts());
        awaitChildren(latchLost, c -> c.size() == 2 && c.get(1).equals(latchAgain));
        assertEquals(List.of("curator-2:1", "rust-2:0"), curatorParticipants(second));
        awaitParticipants(peer, List.of("curator-2:1", "rust-2:0"));
        second.close();
        assertTrue(peer.awaitLeader(WAIT.toMillis()));
        peer.closeLatch();
        awaitChildren(latchLost, List::isEmpty);

        // Confirm no residual ephemeral znodes remain owned by the peer session.
        awaitNoNodeOf(session);
    }

    /**
     * Verifies that the Rust peer transmits the same sequence of wire
     * {@code CreateRequest} frames and produces the same znode structure as
     * Curator 5.7.1.
     *
     * <p>Because a client-facing {@link Stat} does not distinguish container
     * znodes (whose {@code ephemeralOwner} is 0), each implementation connects
     * through its own {@link CutProxy} instance so captured wire opcodes,
     * flags, payloads, and ACLs can be compared directly.
     */
    @Test
    void createsAndNodesMatchCurator() throws Exception {
        CutProxy rustWire = track(CutProxy.start(firstServer(connect), proxyPort(1)));
        CutProxy javaWire = track(CutProxy.start(firstServer(connect), proxyPort(2)));
        // Configure Curator's default node data to match the payload written by the Rust peer.
        String lockData = "holder-1";
        CuratorFramework java = track(CuratorFrameworkFactory.builder()
            .connectString(javaWire.connectString())
            .retryPolicy(new RetryOneTime(100))
            .defaultData(utf8(lockData))
            .build());
        java.start();
        assertTrue(java.blockUntilConnected((int) WAIT.getSeconds(), TimeUnit.SECONDS));
        long javaSession = java.getZookeeperClient().getZooKeeper().getSessionId();
        RustPeer peer = track(RustPeer.start(rustWire.connectString()));
        long rustSession = peer.session().id;

        // Run the Rust peer first so it creates the shared test root container.
        String rust = root + "/rust";
        String jside = root + "/java";
        String rustNode = peer.tryLock(rust + "/a/lock", lockData);
        assertNotNull(rustNode);
        Holder c1 = track(new Holder(java, jside + "/a/lock", "c1"));
        assertTrue(c1.acquire(Duration.ZERO).get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        String rustLatchNode = peer.latch(rust + "/b/latch", "p-1");
        LeaderLatch latch = track(new LeaderLatch(java, jside + "/b/latch", "p-1"));
        latch.start();
        assertTrue(latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        String javaNode = awaitChildren(jside + "/a/lock", c -> c.size() == 1).get(0);
        String javaLatchNode = name(latch.getOurPath());
        awaitChildren(rust + "/a/lock", c -> c.equals(List.of(rustNode)));
        awaitChildren(rust + "/b/latch", c -> c.equals(List.of(rustLatchNode)));

        // Compare protected node names byte-for-byte.
        assertCuratorLayout(rustNode, javaNode, LOCK_NAME);
        assertCuratorLayout(rustLatchNode, javaLatchNode, LATCH_NAME);
        assertDistinctIds(List.of(rustNode, javaNode, rustLatchNode, javaLatchNode));

        // Compare captured CreateRequest wire frames (including protected-ID reuse across NoNode).
        List<CutProxy.Create> rustCreates = rustWire.creates(rust);
        List<CutProxy.Create> javaCreates = javaWire.creates(jside);
        assertEquals(CutProxy.Create.forms(javaCreates, jside),
            CutProxy.Create.forms(rustCreates, rust));
        assertOneId(rustCreates, rustNode, rustLatchNode);
        assertOneId(javaCreates, javaNode, javaLatchNode);
        for (String level : List.of("", "/a", "/a/lock", "/b", "/b/latch")) {
            assertContainerCreate(rustCreates, rust + level);
            assertContainerCreate(javaCreates, jside + level);
        }
        assertContainerCreate(rustWire.creates(root), root);

        // Compare znode payloads, ephemeral owners, and ACLs.
        String rl = rust + "/a/lock/" + rustNode;
        String jl = jside + "/a/lock/" + javaNode;
        String rn = rust + "/b/latch/" + rustLatchNode;
        String jn = jside + "/b/latch/" + javaLatchNode;
        assertArrayEquals(data(jl), data(rl));
        assertArrayEquals(utf8("p-1"), data(rn));
        assertArrayEquals(utf8("p-1"), data(jn));
        assertOwner(rl, rustSession);
        assertOwner(rn, rustSession);
        assertOwner(jl, javaSession);
        assertOwner(jn, javaSession);
        assertSameAcl(rl, jl);
        assertSameAcl(rn, jn);

        peer.unlock();
        c1.release();
        peer.closeLatch();
        latch.close();
        awaitChildren(rust + "/a/lock", List::isEmpty);
        awaitChildren(jside + "/a/lock", List::isEmpty);
        awaitChildren(rust + "/b/latch", List::isEmpty);
        awaitChildren(jside + "/b/latch", List::isEmpty);
        awaitNoNodeOf(rustSession);
    }

    /**
     * Verifies that non-standard sibling znodes participate in lock ordering on
     * both sides identically to Curator's {@code LockInternals} comparator.
     */
    @Test
    void lockOrderCountsForeignChildren() throws Exception {
        String path = root + "/lock";
        curator.create().creatingParentContainersIfNeeded()
            .forPath(path + "/" + LAST, utf8("foreign-last"));
        curator.create().forPath(path + "/" + FIRST_LOCK, utf8("foreign-first"));
        Holder c1 = track(new Holder(curator, path, "c1"));
        Holder c2 = track(new Holder(curator, path, "c2"));
        RustPeer r1 = track(RustPeer.start(connect));
        RustPeer r2 = track(RustPeer.start(connect));

        // Interleave foreign znodes ahead of R1 and R2 so any sorting divergence
        // causes a Rust waiter to acquire the lock prematurely.
        List<String> known = new ArrayList<>(List.of(LAST, FIRST_LOCK));
        Future<String> r1Lock = r1.lock(path, WAIT.toMillis(), "rust-1");
        String r1Node = awaitNewChild(path, known);
        known.add(r1Node);
        Future<Boolean> c1Lock = c1.acquire(WAIT);
        String c1Node = awaitNewChild(path, known);
        known.add(c1Node);
        String mid = name(curator.create().withMode(CreateMode.PERSISTENT_SEQUENTIAL)
            .forPath(path + "/other-lock-", utf8("foreign-mid")));
        known.add(mid);
        Future<String> r2Lock = r2.lock(path, WAIT.toMillis(), "rust-2");
        String r2Node = awaitNewChild(path, known);
        known.add(r2Node);
        Future<Boolean> c2Lock = c2.acquire(WAIT);
        String c2Node = awaitNewChild(path, known);

        List<String> order = List.of(FIRST_LOCK, r1Node, c1Node, mid, r2Node, c2Node, LAST);
        assertEquals(order, sorted(path, LOCK_NAME));
        assertEquals(paths(path, order), new ArrayList<>(c1.mutex.getParticipantNodes()));

        // Deleting FIRST_LOCK unblocks R1, followed by C1.
        assertStillPending(r1Lock, c1Lock, r2Lock, c2Lock);
        curator.delete().forPath(path + "/" + FIRST_LOCK);
        assertEquals("OK " + r1Node, get(r1Lock));
        assertStillPending(c1Lock, r2Lock, c2Lock);
        r1.unlock();
        assertTrue(get(c1Lock));
        assertStillPending(r2Lock, c2Lock);

        // After C1 releases, the foreign sequential node `mid` blocks R2 until deleted.
        c1.release();
        assertStillPending(r2Lock, c2Lock);
        curator.delete().forPath(path + "/" + mid);
        assertEquals("OK " + r2Node, get(r2Lock));
        assertStillPending(c2Lock);
        r2.unlock();
        assertTrue(get(c2Lock));
        c2.release();
        awaitChildren(path, c -> c.equals(List.of(LAST)));
    }

    /**
     * Verifies that non-standard sibling znodes participate in leader latch
     * ordering and participant listing on both sides.
     */
    @Test
    void latchOrderCountsForeignChildren() throws Exception {
        String path = root + "/latch";
        curator.create().creatingParentContainersIfNeeded()
            .forPath(path + "/" + LAST, utf8("foreign-last"));
        curator.create().forPath(path + "/" + FIRST_LATCH, utf8("foreign-first"));
        RustPeer r1 = track(RustPeer.start(connect));
        RustPeer r2 = track(RustPeer.start(connect));

        // Interleave C1, R1, a foreign sequential node, C2, and R2.
        List<String> known = new ArrayList<>(List.of(LAST, FIRST_LATCH));
        LeaderLatch c1 = track(new LeaderLatch(curator, path, "curator-1"));
        c1.start();
        String c1Node = awaitNewChild(path, known);
        known.add(c1Node);
        String r1Node = r1.latch(path, "rust-1");
        assertEquals(r1Node, awaitNewChild(path, known));
        known.add(r1Node);
        String mid = name(curator.create().withMode(CreateMode.PERSISTENT_SEQUENTIAL)
            .forPath(path + "/other-latch-", utf8("foreign-mid")));
        known.add(mid);
        LeaderLatch c2 = track(new LeaderLatch(curator, path, "curator-2"));
        c2.start();
        String c2Node = awaitNewChild(path, known);
        known.add(c2Node);
        String r2Node = r2.latch(path, "rust-2");
        assertEquals(r2Node, awaitNewChild(path, known));

        List<String> order = List.of(FIRST_LATCH, c1Node, r1Node, mid, c2Node, r2Node, LAST);
        assertEquals(order, sorted(path, LATCH_NAME));
        awaitOurPath(c1, path + "/" + c1Node);
        awaitOurPath(c2, path + "/" + c2Node);
        List<String> all = List.of("foreign-first:1", "curator-1:0", "rust-1:0",
            "foreign-mid:0", "curator-2:0", "rust-2:0", "foreign-last:0");
        assertEquals(all, curatorParticipants(c1));
        awaitParticipants(r1, all);
        awaitParticipants(r2, all);

        // Neither language assumes leadership while FIRST_LATCH remains present.
        assertFalse(c1.await(STILL.toMillis(), TimeUnit.MILLISECONDS));
        assertFalse(r1.awaitLeader(STILL.toMillis()));
        curator.delete().forPath(path + "/" + FIRST_LATCH);
        assertTrue(c1.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        assertFalse(r1.awaitLeader(STILL.toMillis()));
        c1.close();
        assertTrue(r1.awaitLeader(WAIT.toMillis()));
        assertFalse(c2.hasLeadership());

        // After R1 closes, `mid` holds first position and blocks C2 until deleted.
        r1.closeLatch();
        List<String> rest = List.of("foreign-mid:1", "curator-2:0", "rust-2:0", "foreign-last:0");
        awaitParticipants(r2, rest);
        assertEquals(rest, curatorParticipants(c2));
        assertFalse(c2.await(STILL.toMillis(), TimeUnit.MILLISECONDS));
        curator.delete().forPath(path + "/" + mid);
        assertTrue(c2.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        assertFalse(r2.awaitLeader(STILL.toMillis()));
        c2.close();
        assertTrue(r2.awaitLeader(WAIT.toMillis()));
        awaitParticipants(r2, List.of("rust-2:1", "foreign-last:0"));
        r2.closeLatch();
        awaitChildren(path, c -> c.equals(List.of(LAST)));
    }

    /**
     * Verifies that cancelling a waiting Rust lock acquisition deletes its
     * participant znode and allows the next Curator contender to acquire the lock.
     */
    @Test
    void abandonedRustAcquireLeavesNoNode() throws Exception {
        String path = root + "/lock";
        Holder c1 = track(new Holder(curator, path, "c1"));
        Holder c2 = track(new Holder(curator, path, "c2"));
        RustPeer peer = track(RustPeer.start(connect));

        List<String> created = new ArrayList<>();
        assertTrue(c1.acquire(WAIT).get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        created.add(awaitNewChild(path, created));

        // Timed-out LOCK acquisition cleans up its ephemeral-sequential node.
        assertEquals("TIMEOUT", peer.call("LOCK " + path + " " + STILL.toMillis()));
        awaitChildren(path, c -> c.equals(created));

        // Enqueue an abandoned Rust waiter between C1 and C2.
        Future<String> abandon = peer.abandonLock(path, 3000);
        String rustNode = awaitNewChild(path, created);
        assertProtected(rustNode, LOCK_NAME);
        Future<Boolean> c2Lock = c2.acquire(WAIT);
        List<String> withRust = new ArrayList<>(created);
        withRust.add(rustNode);
        String c2Node = awaitNewChild(path, withRust);
        assertEquals("OK", get(abandon));
        created.add(c2Node);
        awaitChildren(path, c -> c.equals(created));

        // Releasing C1 grants the lock directly to C2.
        assertStillPending(c2Lock);
        c1.release();
        assertTrue(get(c2Lock));
        c2.release();
        awaitChildren(path, List::isEmpty);
    }

    /**
     * Verifies that forcibly killing a Rust lock holder retains its ephemeral
     * lock znode until the session expires, at which point the waiting Curator
     * contender acquires the lock.
     */
    @Test
    void killedRustHolderFreesTheLockAtExpiry() throws Exception {
        String path = root + "/lock";
        Holder c1 = track(new Holder(curator, path, "c1"));
        RustPeer peer = track(RustPeer.start(connect));

        String rustNode = peer.tryLock(path);
        assertNotNull(rustNode);
        // Without an explicit payload, a Rust lock znode carries empty data.
        assertArrayEquals(new byte[0], data(path + "/" + rustNode));
        RustPeer.Session session = peer.session();
        peer.kill();

        Future<Boolean> c1Lock = c1.acquire(WAIT);
        awaitChildren(path, c -> c.size() == 2 && c.get(0).equals(rustNode));
        assertStillPending(c1Lock);
        expire(session);
        assertTrue(get(c1Lock));
        c1.release();
        awaitChildren(path, List::isEmpty);
    }

    /**
     * Verifies that when a connection drop interrupts the {@code delete}
     * issued by {@code DROP_LOCK}, the Rust client's background
     * guaranteed-delete mechanism retries the deletion upon reconnect and
     * unblocks the waiting Curator contender.
     */
    @Test
    void lostRustDeleteIsRetriedInTheBackground() throws Exception {
        String path = root + "/lock";
        CutProxy proxy = track(CutProxy.start(firstServer(connect), proxyPort()));
        RustPeer peer = track(RustPeer.start(proxy.connectString()));
        long session = peer.session().id;
        Holder c1 = track(new Holder(curator, path, "c1"));

        String rustNode = peer.tryLock(path);
        assertNotNull(rustNode);
        Future<Boolean> c1Lock = c1.acquire(WAIT);
        awaitChildren(path, c -> c.size() == 2 && c.get(0).equals(rustNode));
        assertStillPending(c1Lock);

        CompletableFuture<String> cut = proxy.arm(CutProxy.Mode.DELETE, path + "/" + rustNode);
        peer.dropLock();
        assertEquals(path + "/" + rustNode, get(cut));
        assertEquals(1, proxy.cuts());
        assertTrue(get(c1Lock));
        awaitChildren(path, c -> c.size() == 1 && !c.contains(rustNode));
        assertEquals(session, peer.session().id);
        c1.release();
        awaitChildren(path, List::isEmpty);
        awaitNoNodeOf(session);
    }

    // ---- Helpers -------------------------------------------------------

    private <T extends AutoCloseable> T track(T resource) {
        resources.add(resource);
        return resource;
    }

    /** Lists children of {@code path}, returning an empty list if {@code path} does not exist. */
    private List<String> children(String path) throws Exception {
        try {
            return curator.getChildren().forPath(path);
        } catch (KeeperException.NoNodeException e) {
            return new ArrayList<>();
        }
    }

    /** Returns children of {@code path} sorted via Curator's {@link LockInternals}. */
    private List<String> sorted(String path, String marker) throws Exception {
        return LockInternals.getSortedChildren(marker, new StandardLockInternalsDriver(),
            children(path));
    }

    /** Polls until the Curator-sorted children of {@code path} satisfy {@code check}. */
    private List<String> awaitChildren(String path, Predicate<List<String>> check)
        throws Exception {
        long end = System.nanoTime() + WAIT.toNanos();
        List<String> now = sorted(path, marker(path));
        while (!check.test(now)) {
            if (System.nanoTime() > end) {
                fail("the children of " + path + " stay " + now);
            }
            Thread.sleep(50);
            now = sorted(path, marker(path));
        }
        return now;
    }

    /** Infers the participant node marker ({@code latch-} or {@code lock-}) from {@code path}. */
    private static String marker(String path) {
        return name(path).startsWith("latch") ? LATCH_NAME : LOCK_NAME;
    }

    /**
     * Polls until {@code path} contains exactly one child not present in
     * {@code known} alongside all elements of {@code known}, and returns it.
     */
    private String awaitNewChild(String path, Collection<String> known) throws Exception {
        List<String> now = awaitChildren(path, c -> c.size() == known.size() + 1);
        Set<String> fresh = new HashSet<>(now);
        fresh.removeAll(known);
        assertEquals(1, fresh.size(), "children " + now + ", known " + known);
        return fresh.iterator().next();
    }

    /** Asserts that {@code name} matches the protected format {@code _c_<uuid>-<marker><seq>}. */
    private static void assertProtected(String name, String marker) {
        String re = "_c_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}-"
            + marker + "[0-9]{10}";
        assertTrue(name.matches(re), name + " is not a protected " + marker + " node");
    }

    /**
     * Verifies a Rust protected node name against a Curator protected node
     * name using Curator's {@link ProtectedUtils} and {@link ZKPaths}.
     */
    private static void assertCuratorLayout(String rustName, String curatorName, String marker) {
        assertProtected(rustName, marker);
        assertProtected(curatorName, marker);
        for (String n : List.of(rustName, curatorName)) {
            assertTrue(ProtectedUtils.isProtectedZNode(n), n + " is not protected for Curator");
            String id = ProtectedUtils.extractProtectedId(n).orElse("");
            UUID uuid = UUID.fromString(id);
            assertEquals(id, uuid.toString(), n + " has no canonical UUID");
            assertEquals(4, uuid.version(), n + " has no version-4 UUID");
            assertEquals(2, uuid.variant(), n + " has no RFC 4122 UUID");
            String seq = ZKPaths.extractSequentialSuffix(n);
            assertEquals(10, seq.length(), n + " has no 10-digit suffix");
            assertArrayEquals(utf8(ProtectedUtils.toProtectedZNode(marker, id) + seq), utf8(n),
                n + " differs from the Curator name");
            assertEquals(marker + seq, ProtectedUtils.normalize(n));
        }
        assertArrayEquals(skeleton(curatorName), skeleton(rustName),
            rustName + " differs from " + curatorName);
    }

    /**
     * Normalizes a protected znode name by replacing UUID hex digits with
     * {@code 'x'} and trailing sequence digits with {@code 'n'}.
     */
    private static byte[] skeleton(String name) {
        byte[] b = utf8(name);
        int idEnd = "_c_".length() + 36;
        for (int i = "_c_".length(); i < idEnd && i < b.length; i++) {
            if (b[i] != '-') {
                b[i] = 'x';
            }
        }
        for (int i = Math.max(0, b.length - 10); i < b.length; i++) {
            b[i] = 'n';
        }
        return b;
    }

    /** Asserts that all protected znode names in {@code names} carry distinct UUIDs. */
    private static void assertDistinctIds(Collection<String> names) {
        Set<String> ids = names.stream()
            .map(n -> ProtectedUtils.extractProtectedId(n).orElse(n))
            .collect(Collectors.toSet());
        assertEquals(names.size(), ids.size(), "the names " + names + " share an id");
    }

    /** Encodes {@code s} as UTF-8 bytes. */
    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Joins {@code parent} with each child name in {@code names}. */
    private static List<String> paths(String parent, List<String> names) {
        return names.stream().map(n -> ZKPaths.makePath(parent, n)).collect(Collectors.toList());
    }

    /** Returns the active session ID of the Curator client. */
    private long curatorSession() throws Exception {
        return curator.getZookeeperClient().getZooKeeper().getSessionId();
    }

    /**
     * Polls until {@code path} exists on the Curator client's connected server
     * and returns its {@link Stat}.
     */
    private Stat stat(String path) throws Exception {
        long end = System.nanoTime() + WAIT.toNanos();
        Stat s = curator.checkExists().forPath(path);
        while (s == null) {
            if (System.nanoTime() > end) {
                fail(path + " does not exist");
            }
            Thread.sleep(50);
            s = curator.checkExists().forPath(path);
        }
        return s;
    }

    /** Reads the payload bytes of {@code path} after awaiting its existence. */
    private byte[] data(String path) throws Exception {
        stat(path);
        return curator.getData().forPath(path);
    }

    /** Asserts that {@code path} is an ephemeral znode owned by {@code session}. */
    private void assertOwner(String path, long session) throws Exception {
        assertEquals(Long.toHexString(session), Long.toHexString(stat(path).getEphemeralOwner()),
            "the owner of " + path);
    }

    /** Asserts that {@code rustPath} and {@code curatorPath} have identical ACL lists. */
    private void assertSameAcl(String rustPath, String curatorPath) throws Exception {
        List<ACL> rust = curator.getACL().forPath(rustPath);
        assertEquals(curator.getACL().forPath(curatorPath), rust, "the ACL of " + rustPath);
    }

    /**
     * Asserts that {@code creates} contains a single empty container creation for {@code path}.
     */
    private static void assertContainerCreate(List<CutProxy.Create> creates, String path) {
        List<CutProxy.Create> made = creates.stream()
            .filter(c -> c.path.equals(path))
            .collect(Collectors.toList());
        assertEquals(1, made.size(), "the creates of " + path + ": " + made);
        CutProxy.Create c = made.get(0);
        assertEquals(CutProxy.OP_CREATE_CONTAINER, c.op, path + " is not a container create");
        assertEquals(CreateMode.CONTAINER.toFlag(), c.flags, path + " has no container flag");
        assertArrayEquals(new byte[0], c.data, path + " has data");
    }

    /**
     * Asserts that each created participant node in {@code nodes} was attempted
     * twice (before and after parent container creation) using a single
     * consistent protected UUID and {@code EPHEMERAL_SEQUENTIAL} flags.
     */
    private static void assertOneId(List<CutProxy.Create> creates, String... nodes) {
        for (String node : nodes) {
            String id = ProtectedUtils.extractProtectedId(node).orElse("");
            List<CutProxy.Create> mine = creates.stream()
                .filter(c -> name(c.path).startsWith("_c_" + id + "-"))
                .collect(Collectors.toList());
            assertEquals(2, mine.size(), "the creates of " + node + ": " + creates);
            for (CutProxy.Create c : mine) {
                assertEquals(CreateMode.EPHEMERAL_SEQUENTIAL.toFlag(), c.flags, c.toString());
            }
        }
    }

    /**
     * Polls until no znode under {@link #root} is owned by {@code session}.
     */
    private void awaitNoNodeOf(long session) throws Exception {
        long end = System.nanoTime() + WAIT.toNanos();
        List<String> owned = nodesOf(session, root);
        while (!owned.isEmpty()) {
            if (System.nanoTime() > end) {
                fail("the session " + Long.toHexString(session) + " still owns " + owned);
            }
            Thread.sleep(50);
            owned = nodesOf(session, root);
        }
    }

    /** Recursively collects all znodes under {@code path} owned by {@code session}. */
    private List<String> nodesOf(long session, String path) throws Exception {
        List<String> out = new ArrayList<>();
        Stat s = curator.checkExists().forPath(path);
        if (s == null) {
            return out;
        }
        if (s.getEphemeralOwner() == session) {
            out.add(path);
        }
        for (String child : children(path)) {
            out.addAll(nodesOf(session, ZKPaths.makePath(path, child)));
        }
        return out;
    }

    /**
     * Sleeps for {@link #STILL} and verifies that all futures in {@code waiters} remain pending.
     */
    private static void assertStillPending(Future<?>... waiters) throws InterruptedException {
        Thread.sleep(STILL.toMillis());
        for (Future<?> w : waiters) {
            assertFalse(w.isDone(), "a waiter proceeded");
        }
    }

    private static <T> T get(Future<T> f) throws Exception {
        return f.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Formats participants of a Curator {@link LeaderLatch} as {@code <id>:<0 or 1>} strings. */
    private static List<String> curatorParticipants(LeaderLatch latch) throws Exception {
        return latch.getParticipants().stream()
            .map(CuratorInteropTest::entry)
            .collect(Collectors.toList());
    }

    private static String entry(Participant p) {
        return p.getId() + ":" + (p.isLeader() ? 1 : 0);
    }

    /**
     * Polls until the Rust peer's {@code LeaderLatch} reports {@code expected}
     * as its participant list.
     */
    private static void awaitParticipants(RustPeer peer, List<String> expected)
        throws Exception {
        long end = System.nanoTime() + WAIT.toNanos();
        List<String> now = peer.participants();
        while (!expected.equals(now)) {
            if (System.nanoTime() > end) {
                fail("the Rust participants stay " + now);
            }
            Thread.sleep(50);
            now = peer.participants();
        }
    }

    /** Polls until Curator's asynchronous latch znode creation records {@code expected}. */
    private static void awaitOurPath(LeaderLatch latch, String expected) throws Exception {
        long end = System.nanoTime() + WAIT.toNanos();
        while (!expected.equals(latch.getOurPath())) {
            if (System.nanoTime() > end) {
                fail("the latch path stays " + latch.getOurPath());
            }
            Thread.sleep(50);
        }
    }

    /** Extracts the final path component after {@code '/'}. */
    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Forces server side expiration of {@code session} by attaching a secondary
     * {@link ZooKeeper} client to the same session ID and password and closing it.
     */
    private void expire(RustPeer.Session session) throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        Watcher watcher = e -> {
            Watcher.Event.KeeperState state = e.getState();
            if (state == Watcher.Event.KeeperState.SyncConnected
                || state == Watcher.Event.KeeperState.Expired) {
                connected.countDown();
            }
        };
        ZooKeeper zk = new ZooKeeper(connect, 10000, watcher, session.id, session.password);
        try {
            assertTrue(connected.await(WAIT.toMillis(), TimeUnit.MILLISECONDS),
                "no connection to the session " + Long.toHexString(session.id));
        } finally {
            zk.close();
        }
    }

    /** Parses the first {@code host:port} endpoint from a ZooKeeper connect string. */
    private static InetSocketAddress firstServer(String connect) {
        String hosts = connect.contains("/")
            ? connect.substring(0, connect.indexOf('/'))
            : connect;
        String first = hosts.split(",")[0].trim();
        int colon = first.lastIndexOf(':');
        return new InetSocketAddress(first.substring(0, colon),
            Integer.parseInt(first.substring(colon + 1)));
    }

    private static int proxyPort() {
        return proxyPort(0);
    }

    /**
     * Computes the listener port for proxy instance {@code n} from
     * {@code interop.proxy.port} (returning 0 for an ephemeral port when unset).
     */
    private static int proxyPort(int n) {
        int base = Integer.getInteger("interop.proxy.port", 0);
        return base == 0 ? 0 : base + n;
    }

    /**
     * Binds a Curator {@link InterProcessMutex} to a dedicated single-thread
     * executor so {@code acquire} and {@code release} execute on the same thread.
     */
    private static final class Holder implements AutoCloseable {
        final InterProcessMutex mutex;
        private final ExecutorService thread;

        Holder(CuratorFramework curator, String path, String name) {
            mutex = new InterProcessMutex(curator, path);
            thread = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "curator-holder-" + name);
                t.setDaemon(true);
                return t;
            });
        }

        /** Submits a lock acquisition attempt bounded by {@code wait}. */
        Future<Boolean> acquire(Duration wait) {
            return thread.submit(() -> mutex.acquire(wait.toMillis(), TimeUnit.MILLISECONDS));
        }

        /** Releases the mutex on its owning thread. */
        void release() throws Exception {
            thread.submit(() -> {
                mutex.release();
                return null;
            }).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() throws Exception {
            try {
                thread.submit(() -> {
                    while (mutex.isOwnedByCurrentThread()) {
                        mutex.release();
                    }
                    return null;
                }).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } finally {
                thread.shutdownNow();
            }
        }
    }

    /**
     * Loopback TCP wire-frame proxy that records {@code CreateRequest} frames
     * and optionally severs the connection at a targeted {@code create} or
     * {@code delete} operation.
     *
     * <p>Parses length-prefixed ZooKeeper wire frames in both directions after
     * the initial connect handshake. Subsequent client frames begin with
     * {@code (xid, op)}, and server reply frames begin with
     * {@code (xid, zxid, err)}.
     *
     * <p>When armed via {@link #arm(Mode, String)}:
     * <ul>
     *   <li>{@link Mode#APPLIED}: forwards a matching {@code create} request
     *       to the server and severs the connection instead of delivering the
     *       successful {@code CreateResponse}, completing the future with the
     *       created path.</li>
     *   <li>{@link Mode#LOST}: severs the connection before forwarding a
     *       matching {@code create} request, completing the future with the
     *       requested path.</li>
     *   <li>{@link Mode#DELETE}: severs the connection before forwarding a
     *       matching {@code delete} (opcode 2) request.</li>
     * </ul>
     * After a cut triggers, the proxy disarms automatically and forwards
     * subsequent reconnections unmodified.
     */
    static final class CutProxy implements AutoCloseable {

        /** Fault-injection trigger mode for an armed cut. */
        enum Mode {
            /** Sever the connection after the server applies the create, dropping the reply. */
            APPLIED,
            /** Sever the connection before forwarding the create request. */
            LOST,
            /** Sever the connection before forwarding the delete request. */
            DELETE
        }

        /** ZooKeeper wire opcode for {@code delete}. */
        private static final int OP_DELETE = 2;

        /** ZooKeeper wire opcode for {@code createContainer}. */
        static final int OP_CREATE_CONTAINER = 19;

        /** ZooKeeper wire opcode for {@code createTTL} (includes a trailing 64-bit TTL field). */
        private static final int OP_CREATE_TTL = 21;

        /** Parsed representation of a forwarded client {@code CreateRequest}. */
        static final class Create {
            final int op;
            final String path;
            final byte[] data;
            final List<String> acl;
            final int flags;

            Create(int op, String path, byte[] data, List<String> acl, int flags) {
                this.op = op;
                this.path = path;
                this.data = data;
                this.acl = acl;
                this.flags = flags;
            }

            /**
             * Formats this request for cross-language comparison by stripping
             * {@code side} and normalizing protected UUIDs to {@code <id>}.
             */
            String form(String side) {
                String rel = path.startsWith(side) ? path.substring(side.length()) : path;
                rel = rel.replaceAll("_c_[0-9a-f-]{36}-", "_c_<id>-");
                return "op=" + op + " path=" + rel + " flags=" + flags
                    + " data=" + (data == null ? "null" : new String(data, StandardCharsets.UTF_8))
                    + " acl=" + acl;
            }

            /** Maps captured creates to their normalized {@link #form(String)} representations. */
            static List<String> forms(List<Create> creates, String side) {
                return creates.stream().map(c -> c.form(side)).collect(Collectors.toList());
            }

            @Override
            public String toString() {
                return form("");
            }

            /** Decodes a {@code CreateRequest} payload following the opcode field. */
            static Create read(int op, ByteBuffer b) throws IOException {
                String path = string(b);
                int len = b.getInt();
                byte[] data = null;
                if (len >= 0) {
                    data = new byte[len];
                    b.get(data);
                }
                List<String> acl = new ArrayList<>();
                int n = b.getInt();
                for (int i = 0; i < n; i++) {
                    int perms = b.getInt();
                    String scheme = string(b);
                    String id = string(b);
                    acl.add(perms + ":" + scheme + ":" + id);
                }
                int flags = b.getInt();
                if (op == OP_CREATE_TTL) {
                    b.getLong();
                }
                return new Create(op, path, data, acl, flags);
            }
        }

        /** Maximum wire frame size accepted by the proxy (64 MiB). */
        private static final int MAX_FRAME = 64 << 20;

        private final ServerSocket listener;
        private final InetSocketAddress target;
        private final ExecutorService pumps = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "cut-proxy");
            t.setDaemon(true);
            return t;
        });
        private final List<Socket> sockets = new ArrayList<>();
        private final List<Create> creates = new ArrayList<>();
        private Mode mode;
        private String prefix;
        private CompletableFuture<String> cut;
        private int cuts;

        private CutProxy(ServerSocket listener, InetSocketAddress target) {
            this.listener = listener;
            this.target = target;
        }

        /** Binds a proxy listener on {@code 127.0.0.1:port} forwarding to {@code target}. */
        static CutProxy start(InetSocketAddress target, int port) throws IOException {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            CutProxy proxy = new CutProxy(s, target);
            proxy.pumps.execute(proxy::acceptLoop);
            return proxy;
        }

        /** Returns the {@code 127.0.0.1:port} connect string of this proxy. */
        String connectString() {
            return "127.0.0.1:" + listener.getLocalPort();
        }

        /**
         * Arms a single connection cut triggered by the next matching
         * {@code create} (or {@code delete} under {@link Mode#DELETE}) whose
         * path starts with {@code pathPrefix}.
         */
        synchronized CompletableFuture<String> arm(Mode m, String pathPrefix) {
            mode = m;
            prefix = pathPrefix;
            cut = new CompletableFuture<>();
            return cut;
        }

        /** Returns all recorded {@code CreateRequest}s at or under {@code root} in wire order. */
        synchronized List<Create> creates(String root) {
            return creates.stream()
                .filter(c -> c.path.equals(root) || c.path.startsWith(root + "/"))
                .collect(Collectors.toList());
        }

        /** Decodes and appends a create request to the history log. */
        private synchronized void record(int op, ByteBuffer body) {
            try {
                creates.add(Create.read(op, body));
            } catch (IOException | RuntimeException e) {
                creates.add(new Create(op, "", null, List.of(), -1));
            }
        }

        /** Returns the total number of connection cuts triggered so far. */
        synchronized int cuts() {
            return cuts;
        }

        /**
         * Evaluates whether an incoming {@code create} or {@code delete}
         * request matches the armed trigger, disarming immediately for
         * {@link Mode#LOST} and {@link Mode#DELETE}.
         */
        private synchronized Mode match(int op, String path) {
            if (mode == null || !path.startsWith(prefix)
                || (mode == Mode.DELETE) != (op == OP_DELETE)) {
                return null;
            }
            Mode m = mode;
            if (m != Mode.APPLIED) {
                disarm(path);
            }
            return m;
        }

        /** Completes the active cut future with {@code path} and disarms the trigger. */
        private synchronized void disarm(String path) {
            mode = null;
            cuts++;
            cut.complete(path);
        }

        private void acceptLoop() {
            while (!listener.isClosed()) {
                try {
                    Socket client = listener.accept();
                    Socket server = new Socket();
                    try {
                        server.connect(target, 5000);
                    } catch (IOException e) {
                        client.close();
                        continue;
                    }
                    synchronized (this) {
                        sockets.add(client);
                        sockets.add(server);
                    }
                    Connection c = new Connection(client, server);
                    pumps.execute(c::requests);
                    pumps.execute(c::replies);
                } catch (IOException e) {
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
            synchronized (this) {
                for (Socket s : sockets) {
                    s.close();
                }
            }
            pumps.shutdownNow();
        }

        /** Bidirectional frame relay for a single proxied TCP connection. */
        private final class Connection {
            private final Socket client;
            private final Socket server;
            /** XID of an in-flight create awaiting its reply under {@link Mode#APPLIED}. */
            private volatile Integer appliedXid;

            Connection(Socket client, Socket server) {
                this.client = client;
                this.server = server;
            }

            /** Closes both client and server sockets to simulate an abrupt connection drop. */
            private void cutBoth() {
                try {
                    client.close();
                } catch (IOException e) {
                    // Server socket is closed below.
                }
                try {
                    server.close();
                } catch (IOException e) {
                    // Ignore close errors.
                }
            }

            /** Relays client-to-server request frames, intercepting armed create/delete ops. */
            void requests() {
                try (DataInputStream in = new DataInputStream(client.getInputStream());
                     DataOutputStream out = new DataOutputStream(server.getOutputStream())) {
                    forward(out, frame(in));
                    while (true) {
                        byte[] f = frame(in);
                        ByteBuffer b = ByteBuffer.wrap(f);
                        if (f.length >= 12) {
                            int xid = b.getInt();
                            int op = b.getInt();
                            if (isCreate(op) || op == OP_DELETE) {
                                if (isCreate(op)) {
                                    record(op, b.duplicate());
                                }
                                Mode m = match(op, string(b));
                                if (m == Mode.LOST || m == Mode.DELETE) {
                                    cutBoth();
                                    return;
                                }
                                if (m == Mode.APPLIED) {
                                    appliedXid = xid;
                                }
                            }
                        }
                        forward(out, f);
                    }
                } catch (IOException e) {
                    cutBoth();
                }
            }

            /** Relays server-to-client reply frames, intercepting armed create responses. */
            void replies() {
                try (DataInputStream in = new DataInputStream(server.getInputStream());
                     DataOutputStream out = new DataOutputStream(client.getOutputStream())) {
                    forward(out, frame(in));
                    while (true) {
                        byte[] f = frame(in);
                        Integer wanted = appliedXid;
                        if (wanted != null && f.length >= 16) {
                            ByteBuffer b = ByteBuffer.wrap(f);
                            int xid = b.getInt();
                            b.getLong();
                            int err = b.getInt();
                            if (xid == wanted) {
                                appliedXid = null;
                                if (err == 0) {
                                    disarm(string(b));
                                    cutBoth();
                                    return;
                                }
                            }
                        }
                        forward(out, f);
                    }
                } catch (IOException e) {
                    cutBoth();
                }
            }
        }

        private static boolean isCreate(int op) {
            return op == 1 || op == 15 || op == 19 || op == 21;
        }

        private static byte[] frame(DataInputStream in) throws IOException {
            int len = in.readInt();
            if (len < 0 || len > MAX_FRAME) {
                throw new IOException("bad frame length " + len);
            }
            byte[] f = new byte[len];
            in.readFully(f);
            return f;
        }

        private static void forward(DataOutputStream out, byte[] f) throws IOException {
            out.writeInt(f.length);
            out.write(f);
            out.flush();
        }

        /** Decodes a length-prefixed UTF-8 Jute string from {@code b}. */
        private static String string(ByteBuffer b) throws IOException {
            if (b.remaining() < 4) {
                throw new EOFException("no string");
            }
            int len = b.getInt();
            if (len < 0 || len > b.remaining()) {
                return "";
            }
            byte[] s = new byte[len];
            b.get(s);
            return new String(s, StandardCharsets.UTF_8);
        }
    }
}
