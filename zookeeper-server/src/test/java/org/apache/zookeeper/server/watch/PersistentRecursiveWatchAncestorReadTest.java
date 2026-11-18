/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.AddWatchMode;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ZooKeeperServerMainTest;
import org.apache.zookeeper.server.auth.DigestAuthenticationProvider;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code OpCode.addWatch} ({@code DataTree.addWatch()}) registers a watch -- including
 * PERSISTENT_RECURSIVE -- with no READ-ACL check at all, unlike {@code exists}/{@code getData}/
 * {@code getChildren}. Event delivery re-checks READ, but by default only against the CHANGED
 * node's own ACL, never against the ancestor path a recursive watch was registered on. Since
 * ZooKeeper ACLs are per-node and not inherited, a child can have a strictly more permissive ACL
 * than its parent, so a client with zero READ on a restricted subtree can register a recursive
 * watch on it and learn new descendants' names as soon as a more permissively-ACL'd descendant
 * changes -- bypassing the parent-READ requirement that {@code getChildren()} enforces for that
 * same discovery.
 *
 * <p>This is long-standing, specified ZooKeeper behavior -- see {@code PersistentWatcherACLTest}'s
 * own "truth table" -- so {@link DataTree#REQUIRE_RECURSIVE_WATCH_ANCESTOR_READ} is opt-in, OFF
 * by default. These tests enable it ({@link #enableAncestorReadGate}) and cover the hardening it
 * turns on: {@link WatchManager#triggerWatch} additionally requires READ on EVERY node from a
 * PERSISTENT_RECURSIVE watch's own registration root down to the event's parent, inclusive,
 * resolved live at delivery time via {@link IWatchManager#setPathAclLookup}, before notifying a
 * watcher about one of that root's descendants -- not just READ on the registration root alone,
 * since an unreadable node anywhere in between would otherwise still have its existence disclosed
 * (see {@link #recursiveWatchOnRootStillBlockedByUnreadableIntermediateNode} and
 * {@link #intermediateSegmentNameDoesNotLeakThroughAReadableDescendant}).
 */
public class PersistentRecursiveWatchAncestorReadTest extends ZKTestCase {

    private ZooKeeperServerMainTest.MainThread main;
    private String connString;

    @BeforeAll
    public static void enableAncestorReadGate() {
        // Must happen before DataTree is first loaded in this test JVM (surefire forks one JVM
        // per test class), since the flag is read once into a static final field.
        System.setProperty(DataTree.REQUIRE_RECURSIVE_WATCH_ANCESTOR_READ, "true");
    }

    @AfterEach
    public void cleanup() throws Exception {
        if (main != null) {
            main.shutdown();
        }
    }

    private void startServer() throws Exception {
        final int clientPort = org.apache.zookeeper.PortAssignment.unique();
        main = new ZooKeeperServerMainTest.MainThread(clientPort, false, null);
        main.start();
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPort, ClientBase.CONNECTION_TIMEOUT),
            "waiting for server to come up");
        connString = "127.0.0.1:" + clientPort;
    }

    private static List<ACL> ownerOnly(String idPassword) throws java.security.NoSuchAlgorithmException {
        return Collections.singletonList(
            new ACL(ZooDefs.Perms.ALL, new Id("digest", DigestAuthenticationProvider.generateDigest(idPassword))));
    }

    @Test
    public void recursiveWatchNoLongerLeaksChildNameUnderDenyAllParent() throws Exception {
        startServer();

        ZooKeeper admin = new ZooKeeper(connString, ClientBase.CONNECTION_TIMEOUT, event -> { });
        try {
            admin.addAuthInfo("digest", "admin:adminpw".getBytes());
            ClientBase.waitForServerUp(connString, ClientBase.CONNECTION_TIMEOUT);

            // Deny-all parent: only the admin's own authenticated digest identity has any
            // permission on it at all.
            admin.create("/restricted", new byte[0], ownerOnly("admin:adminpw"), CreateMode.PERSISTENT);

            ZooKeeper attacker = new ZooKeeper(connString, ClientBase.CONNECTION_TIMEOUT, event -> { });
            try {
                assertThrows(KeeperException.NoAuthException.class, () -> attacker.getChildren("/restricted", false),
                    "expected the unauthenticated client to have no READ on /restricted via the normal API");

                // The gap: addWatch requires no ACL at all. Register a persistent recursive
                // watch on /restricted with zero credentials.
                final CountDownLatch gotEvent = new CountDownLatch(1);
                final List<WatchedEvent> captured = new ArrayList<>();
                Watcher recursiveWatcher = event -> {
                    captured.add(event);
                    gotEvent.countDown();
                };
                attacker.addWatch("/restricted", recursiveWatcher, AddWatchMode.PERSISTENT_RECURSIVE);

                // Admin creates a child under the restricted parent, but gives the CHILD itself
                // a world-readable ACL (a valid, if unusual, real-world layout -- ACLs are
                // per-node, not inherited).
                admin.create("/restricted/leaked-child-name", "secret-ish".getBytes(),
                    ZooDefs.Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT);

                boolean delivered = gotEvent.await(5, TimeUnit.SECONDS);

                assertFalse(delivered,
                    "the recursive watch's registration root (/restricted) denies this client READ, "
                    + "so the child-creation event under it must not be delivered even though the "
                    + "child's own ACL happens to be world-readable; got: " + captured);

                // Still can't discover it through the normal API either -- unchanged.
                assertThrows(KeeperException.NoAuthException.class, () -> attacker.getChildren("/restricted", false));
            } finally {
                attacker.close();
            }
        } finally {
            admin.close();
        }
    }

    @Test
    public void recursiveWatchStillDeliversWhenWatcherCanReadRegistrationRoot() throws Exception {
        startServer();

        ZooKeeper admin = new ZooKeeper(connString, ClientBase.CONNECTION_TIMEOUT, event -> { });
        try {
            admin.addAuthInfo("digest", "admin:adminpw".getBytes());
            ClientBase.waitForServerUp(connString, ClientBase.CONNECTION_TIMEOUT);

            // This time, the parent grants READ to a second identity ("legit") as well as ALL to
            // admin -- a legitimate recursive-watch user.
            List<ACL> adminAllPlusLegitRead = Arrays.asList(
                new ACL(ZooDefs.Perms.ALL, new Id("digest", DigestAuthenticationProvider.generateDigest("admin:adminpw"))),
                new ACL(ZooDefs.Perms.READ, new Id("digest", DigestAuthenticationProvider.generateDigest("legit:legitpw"))));
            admin.create("/allowed", new byte[0], adminAllPlusLegitRead, CreateMode.PERSISTENT);

            ZooKeeper legit = new ZooKeeper(connString, ClientBase.CONNECTION_TIMEOUT, event -> { });
            try {
                legit.addAuthInfo("digest", "legit:legitpw".getBytes());
                ClientBase.waitForServerUp(connString, ClientBase.CONNECTION_TIMEOUT);
                // confirm legit genuinely has READ via the normal API before relying on it below.
                legit.getChildren("/allowed", false);

                final CountDownLatch gotEvent = new CountDownLatch(1);
                final List<WatchedEvent> captured = new ArrayList<>();
                Watcher recursiveWatcher = event -> {
                    captured.add(event);
                    gotEvent.countDown();
                };
                legit.addWatch("/allowed", recursiveWatcher, AddWatchMode.PERSISTENT_RECURSIVE);

                admin.create("/allowed/child", "data".getBytes(), ZooDefs.Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT);

                assertTrue(gotEvent.await(10, TimeUnit.SECONDS),
                    "legit has READ on the watch's own registration root (/allowed), so delivery "
                    + "must not be suppressed just because an unrelated client elsewhere might not");
                assertEquals(1, captured.size());
                assertTrue(captured.get(0).getPath().contains("/allowed/child"));
            } finally {
                legit.close();
            }
        } finally {
            admin.close();
        }
    }

    @Test
    public void exactPathMatchStillDeliversEvenWithoutReadOnAnUnrelatedRecursiveRoot() throws Exception {
        startServer();

        ZooKeeper admin = new ZooKeeper(connString, ClientBase.CONNECTION_TIMEOUT, event -> { });
        try {
            admin.addAuthInfo("digest", "admin:adminpw".getBytes());
            ClientBase.waitForServerUp(connString, ClientBase.CONNECTION_TIMEOUT);

            admin.create("/restricted2", new byte[0], ownerOnly("admin:adminpw"), CreateMode.PERSISTENT);

            ZooKeeper attacker = new ZooKeeper(connString, ClientBase.CONNECTION_TIMEOUT, event -> { });
            try {
                final CountDownLatch gotEvent = new CountDownLatch(1);
                final List<WatchedEvent> captured = new ArrayList<>();
                Watcher watcher = event -> {
                    captured.add(event);
                    gotEvent.countDown();
                };

                // Same connection registers two watches: a PERSISTENT_RECURSIVE one on the
                // deny-all parent (the ancestor registration root that must now be denied), AND
                // a separate PERSISTENT watch directly on the exact child path that's about to
                // be created. The second registration's own path is going to end up
                // world-readable, so its exact-path match must still deliver on its own merits
                // -- it must not be suppressed just because the SAME connection also happens to
                // hold an unrelated, ACL-denied recursive watch on an ancestor.
                attacker.addWatch("/restricted2", watcher, AddWatchMode.PERSISTENT_RECURSIVE);
                attacker.addWatch("/restricted2/child", watcher, AddWatchMode.PERSISTENT);

                admin.create("/restricted2/child", "data".getBytes(), ZooDefs.Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT);

                assertTrue(gotEvent.await(10, TimeUnit.SECONDS),
                    "the exact-path PERSISTENT watch on /restricted2/child is independently valid "
                    + "(that node's own ACL is world-readable) and must still deliver, even though "
                    + "the same connection's unrelated recursive watch on /restricted2 is denied");
                assertEquals(1, captured.size());
                assertTrue(captured.get(0).getPath().contains("/restricted2/child"));
            } finally {
                attacker.close();
            }
        } finally {
            admin.close();
        }
    }

    @Test
    public void recursiveWatchOnRootStillBlockedByUnreadableIntermediateNode() throws Exception {
        startServer();

        ZooKeeper admin = new ZooKeeper(connString, ClientBase.CONNECTION_TIMEOUT, event -> { });
        try {
            admin.addAuthInfo("digest", "admin:adminpw".getBytes());
            ClientBase.waitForServerUp(connString, ClientBase.CONNECTION_TIMEOUT);

            // A deny-all intermediate node directly under the world-readable root ("/" always has
            // the default world:anyone:ALL ACL), with a world-readable child underneath it.
            admin.create("/rootWatchDenyAllParent", new byte[0], ownerOnly("admin:adminpw"), CreateMode.PERSISTENT);

            ZooKeeper attacker = new ZooKeeper(connString, ClientBase.CONNECTION_TIMEOUT, event -> { });
            try {
                assertThrows(KeeperException.NoAuthException.class,
                    () -> attacker.getChildren("/rootWatchDenyAllParent", false),
                    "expected the unauthenticated client to have no READ on the intermediate node");

                // Registering directly on "/" -- the farthest possible ancestor, always readable
                // by anyone -- must not bypass a deny-all node further down the same chain.
                final CountDownLatch gotEvent = new CountDownLatch(1);
                final List<WatchedEvent> captured = new ArrayList<>();
                Watcher recursiveWatcher = event -> {
                    captured.add(event);
                    gotEvent.countDown();
                };
                attacker.addWatch("/", recursiveWatcher, AddWatchMode.PERSISTENT_RECURSIVE);

                admin.create("/rootWatchDenyAllParent/leaked-child-name", "secret-ish".getBytes(),
                    ZooDefs.Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT);

                boolean delivered = gotEvent.await(5, TimeUnit.SECONDS);

                assertFalse(delivered,
                    "the registration root (\"/\") is readable by anyone, but the intermediate "
                    + "node /rootWatchDenyAllParent denies this client READ, so a root-only check "
                    + "must not be enough to deliver the child-creation event under it; got: "
                    + captured);
            } finally {
                attacker.close();
            }
        } finally {
            admin.close();
        }
    }

    @Test
    public void recursiveWatchOnReadableGrandparentStillBlockedByUnreadableParent() throws Exception {
        startServer();

        ZooKeeper admin = new ZooKeeper(connString, ClientBase.CONNECTION_TIMEOUT, event -> { });
        try {
            admin.addAuthInfo("digest", "admin:adminpw".getBytes());
            ClientBase.waitForServerUp(connString, ClientBase.CONNECTION_TIMEOUT);

            // grandparent: readable by "legit". parent (one level down): deny-all. child (two
            // levels down from the registration root): world-readable. The registration root
            // itself passes, but the node between it and the event does not.
            List<ACL> adminAllPlusLegitRead = Arrays.asList(
                new ACL(ZooDefs.Perms.ALL, new Id("digest", DigestAuthenticationProvider.generateDigest("admin:adminpw"))),
                new ACL(ZooDefs.Perms.READ, new Id("digest", DigestAuthenticationProvider.generateDigest("legit:legitpw"))));
            admin.create("/readableGrandparent", new byte[0], adminAllPlusLegitRead, CreateMode.PERSISTENT);
            admin.create("/readableGrandparent/denyAllParent", new byte[0], ownerOnly("admin:adminpw"), CreateMode.PERSISTENT);

            ZooKeeper legit = new ZooKeeper(connString, ClientBase.CONNECTION_TIMEOUT, event -> { });
            try {
                legit.addAuthInfo("digest", "legit:legitpw".getBytes());
                ClientBase.waitForServerUp(connString, ClientBase.CONNECTION_TIMEOUT);
                legit.getChildren("/readableGrandparent", false);
                assertThrows(KeeperException.NoAuthException.class,
                    () -> legit.getChildren("/readableGrandparent/denyAllParent", false),
                    "expected legit to have READ on the grandparent but not on the intermediate parent");

                final CountDownLatch gotEvent = new CountDownLatch(1);
                final List<WatchedEvent> captured = new ArrayList<>();
                Watcher recursiveWatcher = event -> {
                    captured.add(event);
                    gotEvent.countDown();
                };
                legit.addWatch("/readableGrandparent", recursiveWatcher, AddWatchMode.PERSISTENT_RECURSIVE);

                admin.create("/readableGrandparent/denyAllParent/leaked-child-name", "secret-ish".getBytes(),
                    ZooDefs.Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT);

                boolean delivered = gotEvent.await(5, TimeUnit.SECONDS);

                assertFalse(delivered,
                    "legit has READ on the registration root /readableGrandparent, but NOT on "
                    + "/readableGrandparent/denyAllParent (one level further down the same chain "
                    + "towards the event), so delivery must still be suppressed; got: " + captured);
            } finally {
                legit.close();
            }
        } finally {
            admin.close();
        }
    }

    @Test
    public void recursiveWatchDeliversWhenEveryNodeInTheChainIsReadable() throws Exception {
        startServer();

        ZooKeeper admin = new ZooKeeper(connString, ClientBase.CONNECTION_TIMEOUT, event -> { });
        try {
            admin.addAuthInfo("digest", "admin:adminpw".getBytes());
            ClientBase.waitForServerUp(connString, ClientBase.CONNECTION_TIMEOUT);

            // Positive control for the two tests above: grandparent AND intermediate parent are
            // both readable by "legit" this time, so the full chain down to the event's own
            // parent passes and delivery must proceed, exactly as a multi-level getChildren()
            // walk would also succeed for this same client.
            List<ACL> adminAllPlusLegitRead = Arrays.asList(
                new ACL(ZooDefs.Perms.ALL, new Id("digest", DigestAuthenticationProvider.generateDigest("admin:adminpw"))),
                new ACL(ZooDefs.Perms.READ, new Id("digest", DigestAuthenticationProvider.generateDigest("legit:legitpw"))));
            admin.create("/fullyReadableGrandparent", new byte[0], adminAllPlusLegitRead, CreateMode.PERSISTENT);
            admin.create("/fullyReadableGrandparent/readableParent", new byte[0], adminAllPlusLegitRead, CreateMode.PERSISTENT);

            ZooKeeper legit = new ZooKeeper(connString, ClientBase.CONNECTION_TIMEOUT, event -> { });
            try {
                legit.addAuthInfo("digest", "legit:legitpw".getBytes());
                ClientBase.waitForServerUp(connString, ClientBase.CONNECTION_TIMEOUT);
                legit.getChildren("/fullyReadableGrandparent", false);
                legit.getChildren("/fullyReadableGrandparent/readableParent", false);

                final CountDownLatch gotEvent = new CountDownLatch(1);
                final List<WatchedEvent> captured = new ArrayList<>();
                Watcher recursiveWatcher = event -> {
                    captured.add(event);
                    gotEvent.countDown();
                };
                legit.addWatch("/fullyReadableGrandparent", recursiveWatcher, AddWatchMode.PERSISTENT_RECURSIVE);

                admin.create("/fullyReadableGrandparent/readableParent/child", "data".getBytes(),
                    ZooDefs.Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT);

                assertTrue(gotEvent.await(10, TimeUnit.SECONDS),
                    "legit has READ on every node from the registration root down to the event's "
                    + "own parent, so delivery must proceed; got: " + captured);
                assertEquals(1, captured.size());
                assertTrue(captured.get(0).getPath().contains("/fullyReadableGrandparent/readableParent/child"));
            } finally {
                legit.close();
            }
        } finally {
            admin.close();
        }
    }

    @Test
    public void intermediateSegmentNameDoesNotLeakThroughAReadableDescendant() throws Exception {
        startServer();

        ZooKeeper admin = new ZooKeeper(connString, ClientBase.CONNECTION_TIMEOUT, event -> { });
        try {
            admin.addAuthInfo("digest", "admin:adminpw".getBytes());
            ClientBase.waitForServerUp(connString, ClientBase.CONNECTION_TIMEOUT);

            // /tenants: readable by "legit" (the registration root). /tenants/secret: deny-all
            // (its very NAME is what must not leak). /tenants/secret/x: world-readable. Without
            // requiring READ on the intermediate /tenants/secret node specifically, a recursive
            // watch on /tenants would still reveal that a child path segment named "secret"
            // exists, purely because something readable happens to live two levels further down
            // -- exactly the kind of disclosure requireAncestorRead is meant to prevent.
            List<ACL> adminAllPlusLegitRead = Arrays.asList(
                new ACL(ZooDefs.Perms.ALL, new Id("digest", DigestAuthenticationProvider.generateDigest("admin:adminpw"))),
                new ACL(ZooDefs.Perms.READ, new Id("digest", DigestAuthenticationProvider.generateDigest("legit:legitpw"))));
            admin.create("/tenants", new byte[0], adminAllPlusLegitRead, CreateMode.PERSISTENT);
            admin.create("/tenants/secret", new byte[0], ownerOnly("admin:adminpw"), CreateMode.PERSISTENT);

            ZooKeeper legit = new ZooKeeper(connString, ClientBase.CONNECTION_TIMEOUT, event -> { });
            try {
                legit.addAuthInfo("digest", "legit:legitpw".getBytes());
                ClientBase.waitForServerUp(connString, ClientBase.CONNECTION_TIMEOUT);
                legit.getChildren("/tenants", false);
                assertThrows(KeeperException.NoAuthException.class, () -> legit.getChildren("/tenants/secret", false),
                    "expected legit to have no READ on /tenants/secret via the normal API either");

                final CountDownLatch gotEvent = new CountDownLatch(1);
                final List<WatchedEvent> captured = new ArrayList<>();
                Watcher recursiveWatcher = event -> {
                    captured.add(event);
                    gotEvent.countDown();
                };
                legit.addWatch("/tenants", recursiveWatcher, AddWatchMode.PERSISTENT_RECURSIVE);

                admin.create("/tenants/secret/x", "data".getBytes(), ZooDefs.Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT);

                boolean delivered = gotEvent.await(5, TimeUnit.SECONDS);

                assertFalse(delivered,
                    "legit can read /tenants (the registration root) and /tenants/secret/x (the "
                    + "event's own node) but NOT /tenants/secret (the intermediate segment "
                    + "between them), so delivery must be suppressed -- otherwise the mere fact "
                    + "that a child named \"secret\" exists under /tenants would leak; got: "
                    + captured);
            } finally {
                legit.close();
            }
        } finally {
            admin.close();
        }
    }
}
