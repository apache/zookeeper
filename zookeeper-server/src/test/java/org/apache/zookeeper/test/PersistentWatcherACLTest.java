/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;

import static org.apache.zookeeper.AddWatchMode.PERSISTENT;
import static org.apache.zookeeper.AddWatchMode.PERSISTENT_RECURSIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.AddWatchMode;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class encodes a set of tests corresponding to a "truth table"
 * of interactions between persistent watchers and znode ACLs:
 *
 * <a href="https://docs.google.com/spreadsheets/d/1eMH2aimrrMc_b6McU8CHm2yCj2X-w30Fy4fCBOHn7NA/edit#gid=0">https://docs.google.com/spreadsheets/d/1eMH2aimrrMc_b6McU8CHm2yCj2X-w30Fy4fCBOHn7NA/edit#gid=0</a>
 */
public class PersistentWatcherACLTest extends ClientBase {
    private static final Logger LOG = LoggerFactory.getLogger(PersistentWatcherACLTest.class);
    /** An ACL denying READ. */
    private static final List<ACL> ACL_NO_READ = Collections.singletonList(new ACL(ZooDefs.Perms.ALL & ~ZooDefs.Perms.READ, ZooDefs.Ids.ANYONE_ID_UNSAFE));
    private BlockingQueue<WatchedEvent> events;
    private Watcher persistentWatcher;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();

        events = new LinkedBlockingQueue<>();
        persistentWatcher = event -> {
            events.add(event);
            LOG.info("Added event: {}; total: {}", event, events.size());
        };
    }

    /**
     * This Step class, with the Round class below, is used to encode
     * the contents of the truth table.
     *
     * (These should become Records once we target JDK 14+.)
     */
    private static class Step {
        Step(int opCode, String target) {
            this(opCode, target, null, null);
        }
        Step(int opCode, String target, EventType eventType, String eventPath) {
            this.opCode = opCode;
            this.target = target;
            this.eventType = eventType;
            this.eventPath = eventPath;
        }
        /** Action: create, setData or delete */
        final int opCode;
        /** Target path */
        final String target;
        /** Expected event type, {@code null} if no event is expected */
        final EventType eventType;
        /** Expected event path, {@code null} if no event is expected */
        final String eventPath;
    }

    /**
     * This Round class, with the Step class above, is used to encode
     * the contents of the truth table.
     *
     * (These should become Records once we target JDK 14+.)
     */
    private static class Round {
        Round(String summary, Boolean allowA, Boolean allowB, Boolean allowC, String watchTarget, AddWatchMode watchMode, Step[] steps) {
            this.summary = summary;
            this.allowA = allowA;
            this.allowB = allowB;
            this.allowC = allowC;
            this.watchTarget = watchTarget;
            this.watchMode = watchMode;
            this.steps = steps;
        }
        /** Notes/summary */
        final String summary;
        /** Should /a's ACL leave it readable? */
        final Boolean allowA;
        /** Should /a/b's ACL leave it readable? */
        final Boolean allowB;
        /** Should /a/b/c's ACL leave it readable? */
        final Boolean allowC;
        /** Watch path */
        final String watchTarget;
        /** Watch mode */
        final AddWatchMode watchMode;
        /** Actions and expected events */
        final Step[] steps;
    }

    /**
     * A "round" of tests from the table encoded as Java objects.
     *
     * Note that the set of rounds is collected in a {@code ROUNDS}
     * array below, and that this test class includes a {@code main}
     * method which produces a "CSV" rendition of the table, for ease
     * of comparison with the original.
     *
     * @see #ROUNDS
     */
    private static final Round roundNothingAsAIsWatchedButDeniedBIsNotWatched =
        new Round(
            "Nothing as a is watched but denied. b is not watched",
            false, true, null, "/a", PERSISTENT, new Step[] {
                new Step(ZooDefs.OpCode.setData, "/a"),
                new Step(ZooDefs.OpCode.create, "/a/b"),
                new Step(ZooDefs.OpCode.setData, "/a/b"),
                new Step(ZooDefs.OpCode.delete, "/a/b"),
                new Step(ZooDefs.OpCode.delete, "/a"),
            }
        );

    /**
     * @see #roundNothingAsAIsWatchedButDeniedBIsNotWatched
     */
    private static final Round roundNothingAsBothAAndBDenied =
        new Round(
            "Nothing as both a and b denied",
            false, false, null, "/a", PERSISTENT, new Step[] {
                new Step(ZooDefs.OpCode.setData, "/a"),
                new Step(ZooDefs.OpCode.create, "/a/b"),
                new Step(ZooDefs.OpCode.delete, "/a/b"),
                new Step(ZooDefs.OpCode.delete, "/a"),
            }
        );

    /**
     * @see #roundNothingAsAIsWatchedButDeniedBIsNotWatched
     */
    private static final Round roundAChangesInclChildrenAreSeen =
        new Round(
            "a changes, incl children, are seen",
            true, false, null, "/a", PERSISTENT, new Step[] {
                new Step(ZooDefs.OpCode.create, "/a", EventType.NodeCreated, "/a"),
                new Step(ZooDefs.OpCode.setData, "/a", EventType.NodeDataChanged, "/a"),
                new Step(ZooDefs.OpCode.create, "/a/b", EventType.NodeChildrenChanged, "/a"),
                new Step(ZooDefs.OpCode.setData, "/a/b"),
                new Step(ZooDefs.OpCode.delete, "/a/b", EventType.NodeChildrenChanged, "/a"),
                new Step(ZooDefs.OpCode.delete, "/a", EventType.NodeDeleted, "/a"),
            }
        );

    /**
     * @see #roundNothingAsAIsWatchedButDeniedBIsNotWatched
     */
    private static final Round roundNothingForAAsItSDeniedBChangesSeen =
        new Round(
            "Nothing for a as it's denied, b changes allowed/seen",
            false, true, null, "/a", PERSISTENT_RECURSIVE, new Step[] {
                new Step(ZooDefs.OpCode.setData, "/a"),
                new Step(ZooDefs.OpCode.create, "/a/b", EventType.NodeCreated, "/a/b"),
                new Step(ZooDefs.OpCode.setData, "/a/b", EventType.NodeDataChanged, "/a/b"),
                new Step(ZooDefs.OpCode.delete, "/a/b", EventType.NodeDeleted, "/a/b"),
                new Step(ZooDefs.OpCode.delete, "/a"),
            }
        );

    /**
     * @see #roundNothingAsAIsWatchedButDeniedBIsNotWatched
     */
    private static final Round roundNothingBothDenied =
        new Round(
            "Nothing - both denied",
            false, false, null, "/a", PERSISTENT_RECURSIVE, new Step[] {
                new Step(ZooDefs.OpCode.setData, "/a"),
                new Step(ZooDefs.OpCode.create, "/a/b"),
                new Step(ZooDefs.OpCode.setData, "/a/b"),
                new Step(ZooDefs.OpCode.delete, "/a/b"),
                new Step(ZooDefs.OpCode.delete, "/a"),
            }
        );

    /**
     * @see #roundNothingAsAIsWatchedButDeniedBIsNotWatched
     */
    private static final Round roundNothingAllDenied =
        new Round(
            "Nothing - all denied",
            false, false, false, "/a", PERSISTENT_RECURSIVE, new Step[] {
                new Step(ZooDefs.OpCode.create, "/a/b"),
                new Step(ZooDefs.OpCode.setData, "/a/b"),
                new Step(ZooDefs.OpCode.create, "/a/b/c"),
                new Step(ZooDefs.OpCode.setData, "/a/b/c"),
                new Step(ZooDefs.OpCode.delete, "/a/b/c"),
                new Step(ZooDefs.OpCode.delete, "/a/b"),
            }
        );

    /**
     * @see #roundNothingAsAIsWatchedButDeniedBIsNotWatched
     */
    private static final Round roundADeniesSeeAllChangesForBAndCIncludingBChildren =
        new Round(
            "a denies, see all changes for b and c, including b's children",
            false, true, true, "/a", PERSISTENT_RECURSIVE, new Step[] {
                new Step(ZooDefs.OpCode.create, "/a/b", EventType.NodeCreated, "/a/b"),
                new Step(ZooDefs.OpCode.setData, "/a/b", EventType.NodeDataChanged, "/a/b"),
                new Step(ZooDefs.OpCode.create, "/a/b/c", EventType.NodeCreated, "/a/b/c"),
                new Step(ZooDefs.OpCode.setData, "/a/b/c", EventType.NodeDataChanged, "/a/b/c"),
                new Step(ZooDefs.OpCode.delete, "/a/b/c", EventType.NodeDeleted, "/a/b/c"),
                new Step(ZooDefs.OpCode.delete, "/a/b", EventType.NodeDeleted, "/a/b"),
            }
        );

    /**
     * @see #roundNothingAsAIsWatchedButDeniedBIsNotWatched
     */
    private static final Round roundADeniesSeeAllBChangesAndBChildrenNothingForC =
        new Round(
            "a denies, see all b changes and b's children, nothing for c",
            false, true, false, "/a", PERSISTENT_RECURSIVE, new Step[] {
                new Step(ZooDefs.OpCode.create, "/a/b", EventType.NodeCreated, "/a/b"),
                new Step(ZooDefs.OpCode.setData, "/a/b", EventType.NodeDataChanged, "/a/b"),
                new Step(ZooDefs.OpCode.create, "/a/b/c"),
                new Step(ZooDefs.OpCode.setData, "/a/b/c"),
                new Step(ZooDefs.OpCode.delete, "/a/b/c"),
                new Step(ZooDefs.OpCode.delete, "/a/b", EventType.NodeDeleted, "/a/b"),
            }
        );

    /**
     * @see #roundNothingAsAIsWatchedButDeniedBIsNotWatched
     */
    private static final Round roundNothingTheWatchIsOnC =
        new Round(
            "Nothing - the watch is on c",
            false, true, false, "/a/b/c", PERSISTENT_RECURSIVE, new Step[] {
                new Step(ZooDefs.OpCode.create, "/a/b"),
                new Step(ZooDefs.OpCode.setData, "/a/b"),
                new Step(ZooDefs.OpCode.create, "/a/b/c"),
                new Step(ZooDefs.OpCode.setData, "/a/b/c"),
                new Step(ZooDefs.OpCode.delete, "/a/b/c"),
                new Step(ZooDefs.OpCode.delete, "/a/b"),
            }
        );

    /**
     * @see #roundNothingAsAIsWatchedButDeniedBIsNotWatched
     */
    private static final Round roundTheWatchIsOnlyOnCBAndCAllowed =
        new Round(
            "The watch is only on c (b and c allowed)",
            false, true, true, "/a/b/c", PERSISTENT_RECURSIVE, new Step[] {
                new Step(ZooDefs.OpCode.create, "/a/b"),
                new Step(ZooDefs.OpCode.setData, "/a/b"),
                new Step(ZooDefs.OpCode.create, "/a/b/c", EventType.NodeCreated, "/a/b/c"),
                new Step(ZooDefs.OpCode.setData, "/a/b/c", EventType.NodeDataChanged, "/a/b/c"),
                new Step(ZooDefs.OpCode.delete, "/a/b/c", EventType.NodeDeleted, "/a/b/c"),
                new Step(ZooDefs.OpCode.delete, "/a/b"),
            }
        );

    /**
     * Transform the "tristate" {@code allow} property to a concrete
     * ACL which can be passed to the ZooKeeper API.
     *
     * @param allow "tristate" value: {@code null}/don't care, {@code
     * true}, {@code false}
     * @return the ACL
     */
    private static List<ACL> selectAcl(Boolean allow) {
        if (allow == null) {
            return null;
        } else if (!allow) {
            return ACL_NO_READ;
        } else {
            return ZooDefs.Ids.OPEN_ACL_UNSAFE;
        }
    }

    /**
     * Executes one "round" of tests from the Java object encoding of
     * the table.
     *
     * @param round the "round"
     *
     * @see #roundNothingAsAIsWatchedButDeniedBIsNotWatched
     * @see PersistentWatcherACLTest.Round
     * @see PersistentWatcherACLTest.Step
     */
    private void execRound(Round round)
        throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient(new CountdownWatcher(), hostPort)) {
            List<ACL> aclForA = selectAcl(round.allowA);
            List<ACL> aclForB = selectAcl(round.allowB);
            List<ACL> aclForC = selectAcl(round.allowC);

            boolean firstStepCreatesA = round.steps.length > 0
                && round.steps[0].opCode == ZooDefs.OpCode.create
                && round.steps[0].target.equals("/a");

            // Assume /a always exists (except if it's about to be created)
            if (!firstStepCreatesA) {
                zk.create("/a", new byte[0], aclForA, CreateMode.PERSISTENT);
            }

            zk.addWatch(round.watchTarget, persistentWatcher, round.watchMode);

            for (int i = 0; i < round.steps.length; i++) {
                Step step = round.steps[i];

                switch (step.opCode) {
                case ZooDefs.OpCode.create:
                    List<ACL> acl = step.target.endsWith("/c")
                        ? aclForC
                        : step.target.endsWith("/b")
                        ? aclForB
                        : aclForA;
                    zk.create(step.target, new byte[0], acl, CreateMode.PERSISTENT);
                    break;
                case ZooDefs.OpCode.delete:
                    zk.delete(step.target, -1);
                    break;
                case ZooDefs.OpCode.setData:
                    zk.setData(step.target, new byte[0], -1);
                    break;
                default:
                    fail("Unexpected opCode " + step.opCode + " in step " + i);
                    break;
                }

                WatchedEvent actualEvent = events.poll(500, TimeUnit.MILLISECONDS);
                if (step.eventType == null) {
                    assertNull(actualEvent, "Unexpected event " + actualEvent + " at step " + i);
                } else {
                    String m = "In event " + actualEvent + " at step " + i;
                    assertNotNull(actualEvent, m);
                    assertEquals(step.eventType,  actualEvent.getType(), m);
                    assertEquals(step.eventPath, actualEvent.getPath(), m);
                }
            }
        }
    }

    /**
     * A test method, wrapping the definition of a "round."  This
     * should really use JUnit 5's runtime test case generation
     * facilities, but that would prevent backporting this suite to
     * JUnit 4.
     *
     * @see #roundNothingAsAIsWatchedButDeniedBIsNotWatched
     * @see <a href="https://junit.org/junit5/docs/5.0.2/api/org/junit/jupiter/api/DynamicTest.html">JUnit 5 runtime test case generation</a>
     */
    @Test
    public void testNothingAsAIsWatchedButDeniedBIsNotWatched()
        throws IOException, InterruptedException, KeeperException {
        execRound(roundNothingAsAIsWatchedButDeniedBIsNotWatched);
    }

    /**
     * @see #testNothingAsAIsWatchedButDeniedBIsNotWatched
     * @see #roundNothingAsBothAAndBDenied
     */
    @Test
    public void testNothingAsBothAAndBDenied()
        throws IOException, InterruptedException, KeeperException {
        execRound(roundNothingAsBothAAndBDenied);
    }

    /**
     * @see #testNothingAsAIsWatchedButDeniedBIsNotWatched
     * @see #roundAChangesInclChildrenAreSeen
     */
    @Test
    public void testAChangesInclChildrenAreSeen()
        throws IOException, InterruptedException, KeeperException {
        execRound(roundAChangesInclChildrenAreSeen);
    }

    /**
     * @see #testNothingAsAIsWatchedButDeniedBIsNotWatched
     * @see #roundNothingForAAsItSDeniedBChangesSeen
     */
    @Test
    public void testNothingForAAsItSDeniedBChangesSeen()
        throws IOException, InterruptedException, KeeperException {
        execRound(roundNothingForAAsItSDeniedBChangesSeen);
    }

    /**
     * @see #testNothingAsAIsWatchedButDeniedBIsNotWatched
     * @see #roundNothingBothDenied
     */
    @Test
    public void testNothingBothDenied()
        throws IOException, InterruptedException, KeeperException {
        execRound(roundNothingBothDenied);
    }

    /**
     * @see #testNothingAsAIsWatchedButDeniedBIsNotWatched
     * @see #roundNothingAllDenied
     */
    @Test
    public void testNothingAllDenied()
        throws IOException, InterruptedException, KeeperException {
        execRound(roundNothingAllDenied);
    }

    /**
     * @see #testNothingAsAIsWatchedButDeniedBIsNotWatched
     * @see #roundADeniesSeeAllChangesForBAndCIncludingBChildren
     */
    @Test
    public void testADeniesSeeAllChangesForBAndCIncludingBChildren()
        throws IOException, InterruptedException, KeeperException {
        execRound(roundADeniesSeeAllChangesForBAndCIncludingBChildren);
    }

    /**
     * @see #testNothingAsAIsWatchedButDeniedBIsNotWatched
     * @see #roundADeniesSeeAllBChangesAndBChildrenNothingForC
     */
    @Test
    public void testADeniesSeeAllBChangesAndBChildrenNothingForC()
        throws IOException, InterruptedException, KeeperException {
        execRound(roundADeniesSeeAllBChangesAndBChildrenNothingForC);
    }

    /**
     * @see #testNothingAsAIsWatchedButDeniedBIsNotWatched
     * @see #roundNothingTheWatchIsOnC
     */
    @Test
    public void testNothingTheWatchIsOnC()
        throws IOException, InterruptedException, KeeperException {
        execRound(roundNothingTheWatchIsOnC);
    }

    /**
     * @see #testNothingAsAIsWatchedButDeniedBIsNotWatched
     * @see #roundTheWatchIsOnlyOnCBAndCAllowed
     */
    @Test
    public void testTheWatchIsOnlyOnCBAndCAllowed()
        throws IOException, InterruptedException, KeeperException {
        execRound(roundTheWatchIsOnlyOnCBAndCAllowed);
    }

    // The rest of this class is the world's lamest "CSV" encoder.

    /**
     * The set of rounds.  This array includes one entry for each
     * {@code private static final Round round*} member variable
     * defined above.
     *
     * @see #roundNothingAsAIsWatchedButDeniedBIsNotWatched
     */
    private static final Round[] ROUNDS = new Round[] {
        roundNothingAsAIsWatchedButDeniedBIsNotWatched,
        roundNothingAsBothAAndBDenied,
        roundAChangesInclChildrenAreSeen,
        roundNothingForAAsItSDeniedBChangesSeen,
        roundNothingBothDenied,
        roundNothingAllDenied,
        roundADeniesSeeAllChangesForBAndCIncludingBChildren,
        roundADeniesSeeAllBChangesAndBChildrenNothingForC,
        roundNothingTheWatchIsOnC,
        roundTheWatchIsOnlyOnCBAndCAllowed,
    };

    private static String allowString(String prefix, Boolean allow) {
        if (allow == null) {
            return "";
        } else {
            return prefix + (allow ? "allow" : "deny");
        }
    }

    private static String watchModeString(AddWatchMode watchMode) {
        switch (watchMode) {
        case PERSISTENT:
            return "PERSISTENT";
        case PERSISTENT_RECURSIVE:
            return "PRECURSIVE";
        default:
            return "?";
        }
    }

    private static String actionString(int opCode) {
        switch (opCode) {
        case ZooDefs.OpCode.create:
            return "create";
        case ZooDefs.OpCode.delete:
            return "delete";
        case ZooDefs.OpCode.setData:
            return "modify";
        default:
            return "?";
        }
    }

    private static String eventPathString(String eventPath) {
        if (eventPath == null) {
            return "?";
        } else if (eventPath.length() <= 1) {
            return eventPath;
        } else {
            return eventPath.substring(eventPath.lastIndexOf('/') + 1);
        }
    }

    /**
     * Generates a "CSV" rendition of the table in sb.
     *
     * @param sb the target string builder
     */
    private static void genCsv(StringBuilder sb) {
        sb.append("Initial State,")
            .append("Action,")
            .append("NodeCreated,")
            .append("NodeDeleted,")
            .append("NodeDataChanged,")
            .append("NodeChildrenChanged,")
            .append("Notes/summary\n");
        sb.append("Assume /a always exists\n\n");

        for (Round round : ROUNDS) {
            sb.append("\"ACL")
                .append(allowString(": a ", round.allowA))
                .append(allowString(", b ", round.allowB))
                .append(allowString(", c ", round.allowC))
                .append("\"")
                .append(",,,,,,\"")
                .append(round.summary)
                .append("\"\n");
            for (int i = 0; i < round.steps.length; i++) {
                Step step = round.steps[i];

                if (i == 0) {
                    sb.append("\"addWatch(")
                        .append(round.watchTarget)
                        .append(", ")
                        .append(watchModeString(round.watchMode))
                        .append(")\"");
                }

                sb.append(",")
                    .append(actionString(step.opCode))
                    .append(" ")
                    .append(step.target)
                    .append(",");

                if (step.eventType == EventType.NodeCreated) {
                    sb.append("y - ")
                        .append(eventPathString(step.eventPath));
                }

                sb.append(",");

                if (step.eventType == EventType.NodeDeleted) {
                    sb.append("y - ")
                        .append(eventPathString(step.eventPath));
                }

                sb.append(",");

                if (step.eventType == EventType.NodeDataChanged) {
                    sb.append("y - ")
                        .append(eventPathString(step.eventPath));
                }

                sb.append(",");

                if (round.watchMode == PERSISTENT_RECURSIVE) {
                    sb.append("n");
                } else if (step.eventType == EventType.NodeChildrenChanged) {
                    sb.append("y - ")
                        .append(eventPathString(step.eventPath));
                }

                sb.append("\n");
            }

            sb.append("\n");
        }
    }

    /**
     * Generates a "CSV" rendition of the table to standard output.
     *
     * @see #ROUNDS
     */
    public static void main(String[] args) {
        StringBuilder sb = new StringBuilder();
        genCsv(sb);
        System.out.println(sb);
    }
}
