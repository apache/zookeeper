<!--
Copyright 2002-2004 The Apache Software Foundation

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
//-->

# Release Notes - ZooKeeper - Version 3.4.14

## Task
* [ZOOKEEPER-3062](https://issues.apache.org/jira/browse/ZOOKEEPER-3062) - introduce fsync.warningthresholdms constant for FileTxnLog LOG.warn message
* [ZOOKEEPER-3120](https://issues.apache.org/jira/browse/ZOOKEEPER-3120) - add NetBeans nbproject directory to .gitignore
* [ZOOKEEPER-925](https://issues.apache.org/jira/browse/ZOOKEEPER-925) - Consider maven site generation to replace our forrest site and documentation generation
* [ZOOKEEPER-3230](https://issues.apache.org/jira/browse/ZOOKEEPER-3230) - Add Apache NetBeans Maven project files to .gitignore

## Sub-task
* [ZOOKEEPER-3155](https://issues.apache.org/jira/browse/ZOOKEEPER-3155) - ZOOKEEPER-925 Remove Forrest XMLs and their build process from the project
* [ZOOKEEPER-3154](https://issues.apache.org/jira/browse/ZOOKEEPER-3154) - ZOOKEEPER-925 Update release process to use the MarkDown solution
* [ZOOKEEPER-3153](https://issues.apache.org/jira/browse/ZOOKEEPER-3153) - ZOOKEEPER-925 Create MarkDown files and build process for them
* [ZOOKEEPER-3022](https://issues.apache.org/jira/browse/ZOOKEEPER-3022) - ZOOKEEPER-3021 Step 1.1 - Create docs and it maven structure
* [ZOOKEEPER-3033](https://issues.apache.org/jira/browse/ZOOKEEPER-3033) - ZOOKEEPER-3021 Step 1.2 - Create zk-recipes maven structure
* [ZOOKEEPER-3030](https://issues.apache.org/jira/browse/ZOOKEEPER-3030) - ZOOKEEPER-3021 Step 1.3 - Create zk-contrib maven structure
* [ZOOKEEPER-3031](https://issues.apache.org/jira/browse/ZOOKEEPER-3031) - ZOOKEEPER-3021 Step 1.4 - Create zk-client maven structure
* [ZOOKEEPER-3080](https://issues.apache.org/jira/browse/ZOOKEEPER-3080) - ZOOKEEPER-3021 Step 1.5 - Separate jute structure
* [ZOOKEEPER-3032](https://issues.apache.org/jira/browse/ZOOKEEPER-3032) - ZOOKEEPER-3021 Step 1.6 - Create zk-server maven structure
* [ZOOKEEPER-3223](https://issues.apache.org/jira/browse/ZOOKEEPER-3223) - ZOOKEEPER-3021 Configure Spotbugs
* [ZOOKEEPER-3256](https://issues.apache.org/jira/browse/ZOOKEEPER-3256) - ZOOKEEPER-3021 Enable OWASP checks to Maven build
* [ZOOKEEPER-3029](https://issues.apache.org/jira/browse/ZOOKEEPER-3029) - ZOOKEEPER-3021 Create pom files for jute, server and client
* [ZOOKEEPER-3225](https://issues.apache.org/jira/browse/ZOOKEEPER-3225) - ZOOKEEPER-3021 Create code coverage analysis with maven build
* [ZOOKEEPER-3226](https://issues.apache.org/jira/browse/ZOOKEEPER-3226) - ZOOKEEPER-3021 Activate C Client with a profile, disabled by default
* [ZOOKEEPER-3171](https://issues.apache.org/jira/browse/ZOOKEEPER-3171) - ZOOKEEPER-3021 Create pom.xml for recipes and contrib
* [ZOOKEEPER-3122](https://issues.apache.org/jira/browse/ZOOKEEPER-3122) - ZOOKEEPER-3021 Verify build after maven migration and the end artifact

## Improvement
* [ZOOKEEPER-3262](https://issues.apache.org/jira/browse/ZOOKEEPER-3262) - Update dependencies flagged by OWASP report
* [ZOOKEEPER-3021](https://issues.apache.org/jira/browse/ZOOKEEPER-3021) - Umbrella: Migrate project structure to Maven build
* [ZOOKEEPER-3094](https://issues.apache.org/jira/browse/ZOOKEEPER-3094) - Make BufferSizeTest reliable
* [ZOOKEEPER-3077](https://issues.apache.org/jira/browse/ZOOKEEPER-3077) - Build native C library outside of source directory

## Bug
* [ZOOKEEPER-3217](https://issues.apache.org/jira/browse/ZOOKEEPER-3217) - owasp job flagging slf4j on trunk
* [ZOOKEEPER-3156](https://issues.apache.org/jira/browse/ZOOKEEPER-3156) - ZOOKEEPER-2184 causes kerberos principal to not have resolved host name
* [ZOOKEEPER-3210](https://issues.apache.org/jira/browse/ZOOKEEPER-3210) - Typo in zookeeperInternals doc
* [ZOOKEEPER-1392](https://issues.apache.org/jira/browse/ZOOKEEPER-1392) - Should not allow to read ACL when not authorized to read node
* [ZOOKEEPER-3009](https://issues.apache.org/jira/browse/ZOOKEEPER-3009) - Potential NPE in NIOServerCnxnFactory
* [ZOOKEEPER-3148](https://issues.apache.org/jira/browse/ZOOKEEPER-3148) - Fix Kerberos tests on branch 3.4 and JDK11
* [ZOOKEEPER-3265](https://issues.apache.org/jira/browse/ZOOKEEPER-3265) - Build failure on branch-3.4
* [ZOOKEEPER-3162](https://issues.apache.org/jira/browse/ZOOKEEPER-3162) - Broken lock semantics in C client lock-recipe

# Release Notes - ZooKeeper - Version 3.4.13

## Sub-task
* [ZOOKEEPER-2980](https://issues.apache.org/jira/browse/ZOOKEEPER-2980) - Backport ZOOKEEPER-2939 Deal with maxbuffer as it relates to proposals

## Bug
* [ZOOKEEPER-2184](https://issues.apache.org/jira/browse/ZOOKEEPER-2184) - Zookeeper Client should re-resolve hosts when connection attempts fail
* [ZOOKEEPER-2920](https://issues.apache.org/jira/browse/ZOOKEEPER-2920) - Upgrade OWASP Dependency Check to 3.2.1
* [ZOOKEEPER-2959](https://issues.apache.org/jira/browse/ZOOKEEPER-2959) - ignore accepted epoch and LEADERINFO ack from observers when a newly elected leader computes new epoch
* [ZOOKEEPER-2988](https://issues.apache.org/jira/browse/ZOOKEEPER-2988) - NPE triggered if server receives a vote for a server id not in their voting view
* [ZOOKEEPER-2993](https://issues.apache.org/jira/browse/ZOOKEEPER-2993) - .ignore file prevents adding src/java/main/org/apache/jute/compiler/generated dir to git repo
* [ZOOKEEPER-3007](https://issues.apache.org/jira/browse/ZOOKEEPER-3007) - Potential NPE in ReferenceCountedACLCache#deserialize 
* [ZOOKEEPER-3027](https://issues.apache.org/jira/browse/ZOOKEEPER-3027) - Accidently removed public API of FileTxnLog.setPreallocSize()
* [ZOOKEEPER-3039](https://issues.apache.org/jira/browse/ZOOKEEPER-3039) - TxnLogToolkit uses Scanner badly
* [ZOOKEEPER-3041](https://issues.apache.org/jira/browse/ZOOKEEPER-3041) - Typo in error message, affects log analysis

## New Feature
* [ZOOKEEPER-2994](https://issues.apache.org/jira/browse/ZOOKEEPER-2994) - Tool required to recover log and snapshot entries with CRC errors

## Improvement
* [ZOOKEEPER-3012](https://issues.apache.org/jira/browse/ZOOKEEPER-3012) - Fix unit test: testDataDirAndDataLogDir should not use hardcode test folders
* [ZOOKEEPER-3019](https://issues.apache.org/jira/browse/ZOOKEEPER-3019) - Add a metric to track number of slow fsyncs
* [ZOOKEEPER-3043](https://issues.apache.org/jira/browse/ZOOKEEPER-3043) - QuorumKerberosHostBasedAuthTest fails on Linux box: Unable to parse:includedir /etc/krb5.conf.d/

## Test
* [ZOOKEEPER-2415](https://issues.apache.org/jira/browse/ZOOKEEPER-2415) - SessionTest is using Thread deprecated API.
* [ZOOKEEPER-2955](https://issues.apache.org/jira/browse/ZOOKEEPER-2955) - Enable Clover code coverage report
* [ZOOKEEPER-2968](https://issues.apache.org/jira/browse/ZOOKEEPER-2968) - Add C client code coverage tests


# Release Notes - ZooKeeper - Version 3.4.12

## Bug
* [ZOOKEEPER-2249](https://issues.apache.org/jira/browse/ZOOKEEPER-2249) - CRC check failed when preAllocSize smaller than node data
* [ZOOKEEPER-2690](https://issues.apache.org/jira/browse/ZOOKEEPER-2690) - Update documentation source for ZOOKEEPER-2574
* [ZOOKEEPER-2806](https://issues.apache.org/jira/browse/ZOOKEEPER-2806) - Flaky test: org.apache.zookeeper.server.quorum.FLEBackwardElectionRoundTest.testBackwardElectionRound
* [ZOOKEEPER-2845](https://issues.apache.org/jira/browse/ZOOKEEPER-2845) - Data inconsistency issue due to retain database in leader election
* [ZOOKEEPER-2893](https://issues.apache.org/jira/browse/ZOOKEEPER-2893) - very poor choice of logging if client fails to connect to server
* [ZOOKEEPER-2923](https://issues.apache.org/jira/browse/ZOOKEEPER-2923) - The comment of the variable matchSyncs in class CommitProcessor has a mistake.
* [ZOOKEEPER-2924](https://issues.apache.org/jira/browse/ZOOKEEPER-2924) - Flaky Test: org.apache.zookeeper.test.LoadFromLogTest.testRestoreWithTransactionErrors
* [ZOOKEEPER-2931](https://issues.apache.org/jira/browse/ZOOKEEPER-2931) - WriteLock recipe: incorrect znode ordering when the sessionId is part of the znode name
* [ZOOKEEPER-2936](https://issues.apache.org/jira/browse/ZOOKEEPER-2936) - Duplicate Keys in log4j.properties config files
* [ZOOKEEPER-2944](https://issues.apache.org/jira/browse/ZOOKEEPER-2944) - Specify correct overflow value
* [ZOOKEEPER-2948](https://issues.apache.org/jira/browse/ZOOKEEPER-2948) - Failing c unit tests on apache jenkins
* [ZOOKEEPER-2951](https://issues.apache.org/jira/browse/ZOOKEEPER-2951) - zkServer.cmd does not start when JAVA_HOME ends with a \
* [ZOOKEEPER-2953](https://issues.apache.org/jira/browse/ZOOKEEPER-2953) - Flaky Test: testNoLogBeforeLeaderEstablishment
* [ZOOKEEPER-2960](https://issues.apache.org/jira/browse/ZOOKEEPER-2960) - The dataDir and dataLogDir are used opposingly
* [ZOOKEEPER-2961](https://issues.apache.org/jira/browse/ZOOKEEPER-2961) - Fix testElectionFraud Flakyness
* [ZOOKEEPER-2978](https://issues.apache.org/jira/browse/ZOOKEEPER-2978) - fix potential null pointer exception when deleting node
* [ZOOKEEPER-2992](https://issues.apache.org/jira/browse/ZOOKEEPER-2992) - The eclipse build target fails due to protocol redirection: http-&gt;https

## Improvement
* [ZOOKEEPER-2950](https://issues.apache.org/jira/browse/ZOOKEEPER-2950) - Add keys for the Zxid from the stat command to check_zookeeper.py
* [ZOOKEEPER-2952](https://issues.apache.org/jira/browse/ZOOKEEPER-2952) - Upgrade third party libraries to address vulnerabilities
* [ZOOKEEPER-2962](https://issues.apache.org/jira/browse/ZOOKEEPER-2962) - The function queueEmpty() in FastLeaderElection.Messenger is not used, should be removed.
* [ZOOKEEPER-2967](https://issues.apache.org/jira/browse/ZOOKEEPER-2967) - Add check to validate dataDir and dataLogDir parameters at startup

## Wish
* [ZOOKEEPER-2795](https://issues.apache.org/jira/browse/ZOOKEEPER-2795) - Change log level for &quot;ZKShutdownHandler is not registered&quot; error message


# Release Notes - ZooKeeper - Version 3.4.11

## Sub-task
* [ZOOKEEPER-2707](https://issues.apache.org/jira/browse/ZOOKEEPER-2707) - Fix &quot;Unexpected bean exists!&quot; issue in WatcherTests
* [ZOOKEEPER-2729](https://issues.apache.org/jira/browse/ZOOKEEPER-2729) - Cleanup findbug warnings in branch-3.4: Correctness Warnings
* [ZOOKEEPER-2730](https://issues.apache.org/jira/browse/ZOOKEEPER-2730) - Cleanup findbug warnings in branch-3.4: Disable Internationalization Warnings
* [ZOOKEEPER-2731](https://issues.apache.org/jira/browse/ZOOKEEPER-2731) - Cleanup findbug warnings in branch-3.4: Malicious code vulnerability Warnings
* [ZOOKEEPER-2732](https://issues.apache.org/jira/browse/ZOOKEEPER-2732) - Cleanup findbug warnings in branch-3.4: Performance Warnings
* [ZOOKEEPER-2733](https://issues.apache.org/jira/browse/ZOOKEEPER-2733) - Cleanup findbug warnings in branch-3.4: Dodgy code Warnings
* [ZOOKEEPER-2749](https://issues.apache.org/jira/browse/ZOOKEEPER-2749) - Cleanup findbug warnings in branch-3.4: Experimental Warnings
* [ZOOKEEPER-2754](https://issues.apache.org/jira/browse/ZOOKEEPER-2754) - Set up Apache Jenkins job that runs the flaky test analyzer script.
* [ZOOKEEPER-2762](https://issues.apache.org/jira/browse/ZOOKEEPER-2762) - Multithreaded correctness Warnings
* [ZOOKEEPER-2834](https://issues.apache.org/jira/browse/ZOOKEEPER-2834) - ZOOKEEPER-2355 fix for branch-3.4

## Bug
* [ZOOKEEPER-1643](https://issues.apache.org/jira/browse/ZOOKEEPER-1643) - Windows: fetch_and_add not 64bit-compatible, may not be correct
* [ZOOKEEPER-2349](https://issues.apache.org/jira/browse/ZOOKEEPER-2349) - Update documentation for snapCount
* [ZOOKEEPER-2355](https://issues.apache.org/jira/browse/ZOOKEEPER-2355) - Ephemeral node is never deleted if follower fails while reading the proposal packet
* [ZOOKEEPER-2614](https://issues.apache.org/jira/browse/ZOOKEEPER-2614) - Port ZOOKEEPER-1576 to branch3.4
* [ZOOKEEPER-2691](https://issues.apache.org/jira/browse/ZOOKEEPER-2691) - recreateSocketAddresses may recreate the unreachable IP address
* [ZOOKEEPER-2722](https://issues.apache.org/jira/browse/ZOOKEEPER-2722) - Flaky Test: org.apache.zookeeper.test.ReadOnlyModeTest.testSessionEstablishment
* [ZOOKEEPER-2728](https://issues.apache.org/jira/browse/ZOOKEEPER-2728) - Clean up findbug warnings in branch-3.4
* [ZOOKEEPER-2740](https://issues.apache.org/jira/browse/ZOOKEEPER-2740) - Port ZOOKEEPER-2737 to branch-3.4
* [ZOOKEEPER-2743](https://issues.apache.org/jira/browse/ZOOKEEPER-2743) - Netty connection leaks JMX connection bean upon connection close in certain race conditions.
* [ZOOKEEPER-2758](https://issues.apache.org/jira/browse/ZOOKEEPER-2758) - Typo: transasction --&gt; transaction
* [ZOOKEEPER-2759](https://issues.apache.org/jira/browse/ZOOKEEPER-2759) - Flaky test: org.apache.zookeeper.server.quorum.QuorumCnxManagerTest.testNoAuthLearnerConnectToAuthRequiredServerWithHigherSid
* [ZOOKEEPER-2774](https://issues.apache.org/jira/browse/ZOOKEEPER-2774) - Ephemeral znode will not be removed when sesstion timeout, if the system time of ZooKeeper node changes unexpectedly.
* [ZOOKEEPER-2775](https://issues.apache.org/jira/browse/ZOOKEEPER-2775) - ZK Client not able to connect with Xid out of order error 
* [ZOOKEEPER-2777](https://issues.apache.org/jira/browse/ZOOKEEPER-2777) - There is a typo in zk.py which prevents from using/compiling it.
* [ZOOKEEPER-2783](https://issues.apache.org/jira/browse/ZOOKEEPER-2783) - follower disconnects and cannot reconnect
* [ZOOKEEPER-2785](https://issues.apache.org/jira/browse/ZOOKEEPER-2785) - Server inappropriately throttles connections under load before SASL completes
* [ZOOKEEPER-2786](https://issues.apache.org/jira/browse/ZOOKEEPER-2786) - Flaky test: org.apache.zookeeper.test.ClientTest.testNonExistingOpCode
* [ZOOKEEPER-2798](https://issues.apache.org/jira/browse/ZOOKEEPER-2798) - Fix flaky test: org.apache.zookeeper.test.ReadOnlyModeTest.testConnectionEvents
* [ZOOKEEPER-2809](https://issues.apache.org/jira/browse/ZOOKEEPER-2809) - Unnecessary stack-trace in server when the client disconnect unexpectedly
* [ZOOKEEPER-2811](https://issues.apache.org/jira/browse/ZOOKEEPER-2811) - PurgeTxnLog#validateAndGetFile: return tag has no arguments.
* [ZOOKEEPER-2818](https://issues.apache.org/jira/browse/ZOOKEEPER-2818) - Improve the ZooKeeper#setACL  java doc
* [ZOOKEEPER-2841](https://issues.apache.org/jira/browse/ZOOKEEPER-2841) - ZooKeeper public include files leak porting changes
* [ZOOKEEPER-2859](https://issues.apache.org/jira/browse/ZOOKEEPER-2859) - CMake build doesn&#39;t support OS X
* [ZOOKEEPER-2861](https://issues.apache.org/jira/browse/ZOOKEEPER-2861) - Main-Class JAR manifest attribute is incorrect
* [ZOOKEEPER-2874](https://issues.apache.org/jira/browse/ZOOKEEPER-2874) - Windows Debug builds don&#39;t link with `/MTd`
* [ZOOKEEPER-2890](https://issues.apache.org/jira/browse/ZOOKEEPER-2890) - Local automatic variable is left uninitialized and then freed.
* [ZOOKEEPER-2905](https://issues.apache.org/jira/browse/ZOOKEEPER-2905) - Don&#39;t include `config.h` in `zookeeper.h`
* [ZOOKEEPER-2906](https://issues.apache.org/jira/browse/ZOOKEEPER-2906) - The OWASP dependency check jar should not be included in the default classpath
* [ZOOKEEPER-2908](https://issues.apache.org/jira/browse/ZOOKEEPER-2908) - quorum.auth.MiniKdcTest.testKerberosLogin failing with NPE on java 9
* [ZOOKEEPER-2909](https://issues.apache.org/jira/browse/ZOOKEEPER-2909) - Create ant task to generate ivy dependency reports
* [ZOOKEEPER-2914](https://issues.apache.org/jira/browse/ZOOKEEPER-2914) - compiler warning using java 9

## Improvement
* [ZOOKEEPER-1669](https://issues.apache.org/jira/browse/ZOOKEEPER-1669) - Operations to server will be timed-out while thousands of sessions expired same time
* [ZOOKEEPER-1748](https://issues.apache.org/jira/browse/ZOOKEEPER-1748) - TCP keepalive for leader election connections
* [ZOOKEEPER-2788](https://issues.apache.org/jira/browse/ZOOKEEPER-2788) - The define of MAX_CONNECTION_ATTEMPTS in QuorumCnxManager.java seems useless, should it be removed?
* [ZOOKEEPER-2856](https://issues.apache.org/jira/browse/ZOOKEEPER-2856) - ZooKeeperSaslClient#respondToServer should log exception message of SaslException
* [ZOOKEEPER-2864](https://issues.apache.org/jira/browse/ZOOKEEPER-2864) - Add script to run a java api compatibility tool
* [ZOOKEEPER-2870](https://issues.apache.org/jira/browse/ZOOKEEPER-2870) - Improve the efficiency of AtomicFileOutputStream
* [ZOOKEEPER-2880](https://issues.apache.org/jira/browse/ZOOKEEPER-2880) - Rename README.txt to README.md
* [ZOOKEEPER-2887](https://issues.apache.org/jira/browse/ZOOKEEPER-2887) - define dependency versions in build.xml to be easily overridden in build.properties

## New Feature
* [ZOOKEEPER-1703](https://issues.apache.org/jira/browse/ZOOKEEPER-1703) - Please add instructions for running the tutorial
* [ZOOKEEPER-2875](https://issues.apache.org/jira/browse/ZOOKEEPER-2875) - Add ant task for running OWASP dependency report

## Test
* [ZOOKEEPER-2686](https://issues.apache.org/jira/browse/ZOOKEEPER-2686) - Flaky Test: org.apache.zookeeper.test.WatcherTest.


# Release Notes - ZooKeeper - Version 3.4.10

## Sub-task
* [ZOOKEEPER-2692](https://issues.apache.org/jira/browse/ZOOKEEPER-2692) - Fix race condition in testWatchAutoResetWithPending

## Bug
* [ZOOKEEPER-2044](https://issues.apache.org/jira/browse/ZOOKEEPER-2044) - CancelledKeyException in zookeeper branch-3.4
* [ZOOKEEPER-2383](https://issues.apache.org/jira/browse/ZOOKEEPER-2383) - Startup race in ZooKeeperServer
* [ZOOKEEPER-2465](https://issues.apache.org/jira/browse/ZOOKEEPER-2465) - Documentation copyright notice is out of date.
* [ZOOKEEPER-2467](https://issues.apache.org/jira/browse/ZOOKEEPER-2467) - NullPointerException when redo Command is passed negative value
* [ZOOKEEPER-2470](https://issues.apache.org/jira/browse/ZOOKEEPER-2470) - ServerConfig#parse(String[])  ignores tickTime
* [ZOOKEEPER-2542](https://issues.apache.org/jira/browse/ZOOKEEPER-2542) - Update NOTICE file with Netty notice in 3.4
* [ZOOKEEPER-2552](https://issues.apache.org/jira/browse/ZOOKEEPER-2552) - Revisit release note doc and remove the items which are not related to the released version
* [ZOOKEEPER-2558](https://issues.apache.org/jira/browse/ZOOKEEPER-2558) - Potential memory leak in recordio.c
* [ZOOKEEPER-2573](https://issues.apache.org/jira/browse/ZOOKEEPER-2573) - Modify Info.REVISION to adapt git repo
* [ZOOKEEPER-2574](https://issues.apache.org/jira/browse/ZOOKEEPER-2574) - PurgeTxnLog can inadvertently delete required txn log files
* [ZOOKEEPER-2579](https://issues.apache.org/jira/browse/ZOOKEEPER-2579) - ZooKeeper server should verify that dataDir and snapDir are writeable before starting
* [ZOOKEEPER-2606](https://issues.apache.org/jira/browse/ZOOKEEPER-2606) - SaslServerCallbackHandler#handleAuthorizeCallback() should log the exception
* [ZOOKEEPER-2617](https://issues.apache.org/jira/browse/ZOOKEEPER-2617) - correct a few spelling typos
* [ZOOKEEPER-2622](https://issues.apache.org/jira/browse/ZOOKEEPER-2622) - ZooTrace.logQuorumPacket does nothing
* [ZOOKEEPER-2633](https://issues.apache.org/jira/browse/ZOOKEEPER-2633) - Build failure in contrib/zkfuse with gcc 6.x
* [ZOOKEEPER-2646](https://issues.apache.org/jira/browse/ZOOKEEPER-2646) - Java target in branch 3.4 doesn&#39;t match documentation 
* [ZOOKEEPER-2651](https://issues.apache.org/jira/browse/ZOOKEEPER-2651) - Missing src/pom.template in release
* [ZOOKEEPER-2652](https://issues.apache.org/jira/browse/ZOOKEEPER-2652) - Fix HierarchicalQuorumTest.java
* [ZOOKEEPER-2671](https://issues.apache.org/jira/browse/ZOOKEEPER-2671) - Fix compilation error in branch-3.4
* [ZOOKEEPER-2678](https://issues.apache.org/jira/browse/ZOOKEEPER-2678) - Large databases take a long time to regain a quorum
* [ZOOKEEPER-2680](https://issues.apache.org/jira/browse/ZOOKEEPER-2680) - Correct DataNode.getChildren() inconsistent behaviour.
* [ZOOKEEPER-2689](https://issues.apache.org/jira/browse/ZOOKEEPER-2689) - Fix Kerberos Authentication related test cases
* [ZOOKEEPER-2693](https://issues.apache.org/jira/browse/ZOOKEEPER-2693) - DOS attack on wchp/wchc four letter words (4lw)
* [ZOOKEEPER-2696](https://issues.apache.org/jira/browse/ZOOKEEPER-2696) - Eclipse ant task no longer determines correct classpath for tests after ZOOKEEPER-2689
* [ZOOKEEPER-2706](https://issues.apache.org/jira/browse/ZOOKEEPER-2706) - checkstyle broken on branch-3.4
* [ZOOKEEPER-2710](https://issues.apache.org/jira/browse/ZOOKEEPER-2710) - Regenerate documentation for branch-3.4 release
* [ZOOKEEPER-2712](https://issues.apache.org/jira/browse/ZOOKEEPER-2712) - MiniKdc test case intermittently failing due to principal not found in Kerberos database
* [ZOOKEEPER-2726](https://issues.apache.org/jira/browse/ZOOKEEPER-2726) - Patch for ZOOKEEPER-2693 introduces potential race condition

## Improvement
* [ZOOKEEPER-2479](https://issues.apache.org/jira/browse/ZOOKEEPER-2479) - Add &#39;electionTimeTaken&#39; value in LeaderMXBean and FollowerMXBean
* [ZOOKEEPER-2507](https://issues.apache.org/jira/browse/ZOOKEEPER-2507) - C unit test improvement: line break between &#39;ZooKeeper server started&#39; and &#39;Running&#39;
* [ZOOKEEPER-2557](https://issues.apache.org/jira/browse/ZOOKEEPER-2557) - Update gitignore to account for other file extensions
* [ZOOKEEPER-2594](https://issues.apache.org/jira/browse/ZOOKEEPER-2594) - Use TLS for downloading artifacts during build
* [ZOOKEEPER-2620](https://issues.apache.org/jira/browse/ZOOKEEPER-2620) - Add comments to testReadOnlySnapshotDir and testReadOnlyTxnLogDir indicating that the tests will fail when run as root
* [ZOOKEEPER-2672](https://issues.apache.org/jira/browse/ZOOKEEPER-2672) - Remove CHANGE.txt
* [ZOOKEEPER-2682](https://issues.apache.org/jira/browse/ZOOKEEPER-2682) - Make it optional to fail build on test failure

## New Feature
* [ZOOKEEPER-1045](https://issues.apache.org/jira/browse/ZOOKEEPER-1045) - Support Quorum Peer mutual authentication via SASL

## Test
* [ZOOKEEPER-2502](https://issues.apache.org/jira/browse/ZOOKEEPER-2502) - Flaky Test: org.apache.zookeeper.server.quorum.CnxManagerTest.testCnxFromFutureVersion
* [ZOOKEEPER-2650](https://issues.apache.org/jira/browse/ZOOKEEPER-2650) - Test Improvement by adding more QuorumPeer Auth related test cases
* [ZOOKEEPER-2656](https://issues.apache.org/jira/browse/ZOOKEEPER-2656) - Fix ServerConfigTest#testValidArguments test case failures
* [ZOOKEEPER-2664](https://issues.apache.org/jira/browse/ZOOKEEPER-2664) - ClientPortBindTest#testBindByAddress may fail due to &quot;No such device&quot; exception
* [ZOOKEEPER-2665](https://issues.apache.org/jira/browse/ZOOKEEPER-2665) - Port QA github pull request build to branch 3.4 and 3.5
* [ZOOKEEPER-2716](https://issues.apache.org/jira/browse/ZOOKEEPER-2716) - Flaky Test: org.apache.zookeeper.server.SessionTrackerTest.testAddSessionAfterSessionExpiry


# Release Notes - ZooKeeper - Version 3.4.9

## Sub-task
* [ZOOKEEPER-2396](https://issues.apache.org/jira/browse/ZOOKEEPER-2396) - Login object in ZooKeeperSaslClient is static

## Bug
* [ZOOKEEPER-1676](https://issues.apache.org/jira/browse/ZOOKEEPER-1676) - C client zookeeper_interest returning ZOK on Connection Loss
* [ZOOKEEPER-2133](https://issues.apache.org/jira/browse/ZOOKEEPER-2133) - zkperl: Segmentation fault if getting a node with null value
* [ZOOKEEPER-2141](https://issues.apache.org/jira/browse/ZOOKEEPER-2141) - ACL cache in DataTree never removes entries
* [ZOOKEEPER-2195](https://issues.apache.org/jira/browse/ZOOKEEPER-2195) - fsync.warningthresholdms in zoo.cfg not working
* [ZOOKEEPER-2243](https://issues.apache.org/jira/browse/ZOOKEEPER-2243) - Supported platforms is completely out of date
* [ZOOKEEPER-2247](https://issues.apache.org/jira/browse/ZOOKEEPER-2247) - Zookeeper service becomes unavailable when leader fails to write transaction log
* [ZOOKEEPER-2283](https://issues.apache.org/jira/browse/ZOOKEEPER-2283) - traceFile property is not used in the ZooKeeper,  it should be removed from documentation
* [ZOOKEEPER-2294](https://issues.apache.org/jira/browse/ZOOKEEPER-2294) - Ant target generate-clover-reports is broken
* [ZOOKEEPER-2375](https://issues.apache.org/jira/browse/ZOOKEEPER-2375) - Prevent multiple initialization of login object in each ZooKeeperSaslClient instance
* [ZOOKEEPER-2379](https://issues.apache.org/jira/browse/ZOOKEEPER-2379) - recent commit broke findbugs qabot check
* [ZOOKEEPER-2385](https://issues.apache.org/jira/browse/ZOOKEEPER-2385) - Zookeeper trunk build is failing on windows
* [ZOOKEEPER-2405](https://issues.apache.org/jira/browse/ZOOKEEPER-2405) - getTGT() in Login.java mishandles confidential information
* [ZOOKEEPER-2450](https://issues.apache.org/jira/browse/ZOOKEEPER-2450) - Upgrade Netty version due to security vulnerability (CVE-2014-3488)
* [ZOOKEEPER-2452](https://issues.apache.org/jira/browse/ZOOKEEPER-2452) - Back-port ZOOKEEPER-1460 to 3.4 for IPv6 literal address support.
* [ZOOKEEPER-2477](https://issues.apache.org/jira/browse/ZOOKEEPER-2477) - documentation should refer to Java cli shell and not C cli shell
* [ZOOKEEPER-2498](https://issues.apache.org/jira/browse/ZOOKEEPER-2498) - Potential resource leak in C client when processing unexpected / out of order response

## Improvement
* [ZOOKEEPER-2240](https://issues.apache.org/jira/browse/ZOOKEEPER-2240) - Make the three-node minimum more explicit in documentation and on website
* [ZOOKEEPER-2373](https://issues.apache.org/jira/browse/ZOOKEEPER-2373) - Licenses section missing from pom file
* [ZOOKEEPER-2378](https://issues.apache.org/jira/browse/ZOOKEEPER-2378) - upgrade ivy to recent version
* [ZOOKEEPER-2514](https://issues.apache.org/jira/browse/ZOOKEEPER-2514) - Simplify releasenotes creation for 3.4 branch - consistent with newer branches.


# Release Notes - ZooKeeper - Version 3.4.8

## Bug
* [ZOOKEEPER-1929](https://issues.apache.org/jira/browse/ZOOKEEPER-1929) - std::length_error on update children
* [ZOOKEEPER-2211](https://issues.apache.org/jira/browse/ZOOKEEPER-2211) - PurgeTxnLog does not correctly purge when snapshots and logs are at different locations
* [ZOOKEEPER-2229](https://issues.apache.org/jira/browse/ZOOKEEPER-2229) - Several four-letter words are undocumented.
* [ZOOKEEPER-2281](https://issues.apache.org/jira/browse/ZOOKEEPER-2281) - ZK Server startup fails if there are spaces in the JAVA_HOME path
* [ZOOKEEPER-2295](https://issues.apache.org/jira/browse/ZOOKEEPER-2295) - TGT refresh time logic is wrong
* [ZOOKEEPER-2311](https://issues.apache.org/jira/browse/ZOOKEEPER-2311) - assert in setup_random
* [ZOOKEEPER-2337](https://issues.apache.org/jira/browse/ZOOKEEPER-2337) - Fake &quot;invalid&quot; hostnames used in tests are sometimes valid
* [ZOOKEEPER-2340](https://issues.apache.org/jira/browse/ZOOKEEPER-2340) - JMX is disabled even if JMXDISABLE is false
* [ZOOKEEPER-2347](https://issues.apache.org/jira/browse/ZOOKEEPER-2347) - Deadlock shutting down zookeeper
* [ZOOKEEPER-2360](https://issues.apache.org/jira/browse/ZOOKEEPER-2360) - Update commons collections version used by tests/releaseaudit
* [ZOOKEEPER-2412](https://issues.apache.org/jira/browse/ZOOKEEPER-2412) - leader zk out of memory,  and leader db lastZxid is not update when process set data. 


# Release Notes - ZooKeeper - Version 3.4.7

## Sub-task
* [ZOOKEEPER-1866](https://issues.apache.org/jira/browse/ZOOKEEPER-1866) - ClientBase#createClient is failing frequently
* [ZOOKEEPER-1868](https://issues.apache.org/jira/browse/ZOOKEEPER-1868) - Server not coming back up in QuorumZxidSyncTest
* [ZOOKEEPER-1872](https://issues.apache.org/jira/browse/ZOOKEEPER-1872) - QuorumPeer is not shutdown in few cases
* [ZOOKEEPER-1904](https://issues.apache.org/jira/browse/ZOOKEEPER-1904) - WatcherTest#testWatchAutoResetWithPending is failing
* [ZOOKEEPER-1905](https://issues.apache.org/jira/browse/ZOOKEEPER-1905) - ZKClients are hitting KeeperException$ConnectionLossException due to wrong usage pattern
* [ZOOKEEPER-2047](https://issues.apache.org/jira/browse/ZOOKEEPER-2047) - testTruncationNullLog fails on windows
* [ZOOKEEPER-2237](https://issues.apache.org/jira/browse/ZOOKEEPER-2237) - Port async multi to 3.4 branch

## Bug
* [ZOOKEEPER-602](https://issues.apache.org/jira/browse/ZOOKEEPER-602) - log all exceptions not caught by ZK threads
* [ZOOKEEPER-706](https://issues.apache.org/jira/browse/ZOOKEEPER-706) - large numbers of watches can cause session re-establishment to fail
* [ZOOKEEPER-1002](https://issues.apache.org/jira/browse/ZOOKEEPER-1002) - The Barrier sample code should create a EPHEMERAL znode instead of EPHEMERAL_SEQUENTIAL znode
* [ZOOKEEPER-1029](https://issues.apache.org/jira/browse/ZOOKEEPER-1029) - C client bug in zookeeper_init (if bad hostname is given)
* [ZOOKEEPER-1062](https://issues.apache.org/jira/browse/ZOOKEEPER-1062) - Net-ZooKeeper: Net::ZooKeeper consumes 100% cpu on wait
* [ZOOKEEPER-1077](https://issues.apache.org/jira/browse/ZOOKEEPER-1077) - C client lib doesn&#39;t build on Solaris
* [ZOOKEEPER-1222](https://issues.apache.org/jira/browse/ZOOKEEPER-1222) - getACL should only call DataTree.copyStat when passed in stat is not null
* [ZOOKEEPER-1575](https://issues.apache.org/jira/browse/ZOOKEEPER-1575) - adding .gitattributes to prevent CRLF and LF mismatches for source and text files
* [ZOOKEEPER-1797](https://issues.apache.org/jira/browse/ZOOKEEPER-1797) - PurgeTxnLog may delete data logs during roll
* [ZOOKEEPER-1803](https://issues.apache.org/jira/browse/ZOOKEEPER-1803) - Add description for pzxid in programmer&#39;s guide.
* [ZOOKEEPER-1833](https://issues.apache.org/jira/browse/ZOOKEEPER-1833) - fix windows build
* [ZOOKEEPER-1853](https://issues.apache.org/jira/browse/ZOOKEEPER-1853) - zkCli.sh can&#39;t issue a CREATE command containing spaces in the data
* [ZOOKEEPER-1878](https://issues.apache.org/jira/browse/ZOOKEEPER-1878) - Inconsistent behavior in autocreation of dataDir and dataLogDir
* [ZOOKEEPER-1888](https://issues.apache.org/jira/browse/ZOOKEEPER-1888) - ZkCli.cmd commands fail with &quot;&#39;java&#39; is not recognized as an internal or external command&quot;
* [ZOOKEEPER-1895](https://issues.apache.org/jira/browse/ZOOKEEPER-1895) - update all notice files, copyright, etc... with the new year - 2014
* [ZOOKEEPER-1897](https://issues.apache.org/jira/browse/ZOOKEEPER-1897) - ZK Shell/Cli not processing commands
* [ZOOKEEPER-1900](https://issues.apache.org/jira/browse/ZOOKEEPER-1900) -  NullPointerException in truncate
* [ZOOKEEPER-1901](https://issues.apache.org/jira/browse/ZOOKEEPER-1901) - [JDK8] Sort children for comparison in AsyncOps tests
* [ZOOKEEPER-1906](https://issues.apache.org/jira/browse/ZOOKEEPER-1906) - zkpython: invalid data in GetData for empty node
* [ZOOKEEPER-1911](https://issues.apache.org/jira/browse/ZOOKEEPER-1911) - REST contrib module does not include all required files when packaged
* [ZOOKEEPER-1913](https://issues.apache.org/jira/browse/ZOOKEEPER-1913) - Invalid manifest files due to bogus revision property value
* [ZOOKEEPER-1917](https://issues.apache.org/jira/browse/ZOOKEEPER-1917) - Apache Zookeeper logs cleartext admin passwords
* [ZOOKEEPER-1926](https://issues.apache.org/jira/browse/ZOOKEEPER-1926) - Unit tests should only use build/test/data for data
* [ZOOKEEPER-1927](https://issues.apache.org/jira/browse/ZOOKEEPER-1927) - zkServer.sh fails to read dataDir (and others) from zoo.cfg on Solaris 10 (grep issue, manifests as FAILED TO WRITE PID).  
* [ZOOKEEPER-1939](https://issues.apache.org/jira/browse/ZOOKEEPER-1939) - ReconfigRecoveryTest.testNextConfigUnreachable is failing
* [ZOOKEEPER-1943](https://issues.apache.org/jira/browse/ZOOKEEPER-1943) - &quot;src/contrib/zooinspector/NOTICE.txt&quot; isn&#39;t complying to &quot;.gitattributes&quot; in branch-3.4
* [ZOOKEEPER-1945](https://issues.apache.org/jira/browse/ZOOKEEPER-1945) - deb - zkCli.sh, zkServer.sh and zkEnv.sh regression caused by ZOOKEEPER-1663
* [ZOOKEEPER-1949](https://issues.apache.org/jira/browse/ZOOKEEPER-1949) - recipes jar not included in the distribution package
* [ZOOKEEPER-2026](https://issues.apache.org/jira/browse/ZOOKEEPER-2026) - Startup order in ServerCnxnFactory-ies is wrong
* [ZOOKEEPER-2033](https://issues.apache.org/jira/browse/ZOOKEEPER-2033) - zookeeper follower fails to start after a restart immediately following a new epoch
* [ZOOKEEPER-2039](https://issues.apache.org/jira/browse/ZOOKEEPER-2039) - Jute compareBytes incorrect comparison index
* [ZOOKEEPER-2049](https://issues.apache.org/jira/browse/ZOOKEEPER-2049) - Yosemite build failure: htonll conflict
* [ZOOKEEPER-2052](https://issues.apache.org/jira/browse/ZOOKEEPER-2052) - Unable to delete a node when the node has no children
* [ZOOKEEPER-2056](https://issues.apache.org/jira/browse/ZOOKEEPER-2056) - Zookeeper 3.4.x and 3.5.0-alpha is not OSGi compliant
* [ZOOKEEPER-2060](https://issues.apache.org/jira/browse/ZOOKEEPER-2060) - Trace bug in NettyServerCnxnFactory
* [ZOOKEEPER-2064](https://issues.apache.org/jira/browse/ZOOKEEPER-2064) - Prevent resource leak in various classes
* [ZOOKEEPER-2073](https://issues.apache.org/jira/browse/ZOOKEEPER-2073) - Memory leak on zookeeper_close
* [ZOOKEEPER-2096](https://issues.apache.org/jira/browse/ZOOKEEPER-2096) - C client builds with incorrect error codes in VisualStudio 2010+
* [ZOOKEEPER-2114](https://issues.apache.org/jira/browse/ZOOKEEPER-2114) - jute generated allocate_* functions are not externally visible
* [ZOOKEEPER-2124](https://issues.apache.org/jira/browse/ZOOKEEPER-2124) - Allow Zookeeper version string to have underscore &#39;_&#39;
* [ZOOKEEPER-2142](https://issues.apache.org/jira/browse/ZOOKEEPER-2142) - JMX ObjectName is incorrect for observers
* [ZOOKEEPER-2146](https://issues.apache.org/jira/browse/ZOOKEEPER-2146) - BinaryInputArchive readString should check length before allocating memory
* [ZOOKEEPER-2174](https://issues.apache.org/jira/browse/ZOOKEEPER-2174) - JUnit4ZKTestRunner logs test failure for all exceptions even if the test method is annotated with an expected exception.
* [ZOOKEEPER-2186](https://issues.apache.org/jira/browse/ZOOKEEPER-2186) - QuorumCnxManager#receiveConnection may crash with random input
* [ZOOKEEPER-2201](https://issues.apache.org/jira/browse/ZOOKEEPER-2201) - Network issues can cause cluster to hang due to near-deadlock
* [ZOOKEEPER-2213](https://issues.apache.org/jira/browse/ZOOKEEPER-2213) - Empty path in Set crashes server and prevents restart
* [ZOOKEEPER-2224](https://issues.apache.org/jira/browse/ZOOKEEPER-2224) - Four letter command hangs when network is slow
* [ZOOKEEPER-2227](https://issues.apache.org/jira/browse/ZOOKEEPER-2227) - stmk four-letter word fails execution at server while reading trace mask argument.
* [ZOOKEEPER-2235](https://issues.apache.org/jira/browse/ZOOKEEPER-2235) - License update
* [ZOOKEEPER-2239](https://issues.apache.org/jira/browse/ZOOKEEPER-2239) - JMX State from LocalPeerBean incorrect
* [ZOOKEEPER-2245](https://issues.apache.org/jira/browse/ZOOKEEPER-2245) - SimpleSysTest test cases fails
* [ZOOKEEPER-2256](https://issues.apache.org/jira/browse/ZOOKEEPER-2256) - Zookeeper is not using specified JMX port in zkEnv.sh
* [ZOOKEEPER-2268](https://issues.apache.org/jira/browse/ZOOKEEPER-2268) - Zookeeper doc creation fails on windows
* [ZOOKEEPER-2279](https://issues.apache.org/jira/browse/ZOOKEEPER-2279) - QuorumPeer  loadDataBase() error message is incorrect
* [ZOOKEEPER-2296](https://issues.apache.org/jira/browse/ZOOKEEPER-2296) - compilation broken for 3.4

## Improvement
* [ZOOKEEPER-657](https://issues.apache.org/jira/browse/ZOOKEEPER-657) - Cut down the running time of ZKDatabase corruption.
* [ZOOKEEPER-1402](https://issues.apache.org/jira/browse/ZOOKEEPER-1402) - Upload Zookeeper package to Maven Central
* [ZOOKEEPER-1506](https://issues.apache.org/jira/browse/ZOOKEEPER-1506) - Re-try DNS hostname -&gt; IP resolution if node connection fails
* [ZOOKEEPER-1574](https://issues.apache.org/jira/browse/ZOOKEEPER-1574) - mismatched CR/LF endings in text files
* [ZOOKEEPER-1746](https://issues.apache.org/jira/browse/ZOOKEEPER-1746) - AsyncCallback.*Callback don&#39;t have any Javadoc
* [ZOOKEEPER-1907](https://issues.apache.org/jira/browse/ZOOKEEPER-1907) - Improve Thread handling
* [ZOOKEEPER-1948](https://issues.apache.org/jira/browse/ZOOKEEPER-1948) - Enable JMX remote monitoring
* [ZOOKEEPER-2040](https://issues.apache.org/jira/browse/ZOOKEEPER-2040) - Server to log underlying cause of SASL connection problems
* [ZOOKEEPER-2126](https://issues.apache.org/jira/browse/ZOOKEEPER-2126) - Improve exit log messsage of EventThread and SendThread by adding SessionId
* [ZOOKEEPER-2179](https://issues.apache.org/jira/browse/ZOOKEEPER-2179) - Typo in Watcher.java
* [ZOOKEEPER-2194](https://issues.apache.org/jira/browse/ZOOKEEPER-2194) - Let DataNode.getChildren() return an unmodifiable view of its children set
* [ZOOKEEPER-2205](https://issues.apache.org/jira/browse/ZOOKEEPER-2205) - Log type of unexpected quorum packet in learner handler loop
* [ZOOKEEPER-2315](https://issues.apache.org/jira/browse/ZOOKEEPER-2315) - Change client connect zk service timeout log level from Info to Warn level


# Release Notes - ZooKeeper - Version 3.4.6

## Sub-task
* [ZOOKEEPER-1414](https://issues.apache.org/jira/browse/ZOOKEEPER-1414) - QuorumPeerMainTest.testQuorum, testBadPackets are failing intermittently
* [ZOOKEEPER-1459](https://issues.apache.org/jira/browse/ZOOKEEPER-1459) - Standalone ZooKeeperServer is not closing the transaction log files on shutdown
* [ZOOKEEPER-1558](https://issues.apache.org/jira/browse/ZOOKEEPER-1558) - Leader should not snapshot uncommitted state
* [ZOOKEEPER-1808](https://issues.apache.org/jira/browse/ZOOKEEPER-1808) - Add version to FLE notifications for 3.4 branch
* [ZOOKEEPER-1817](https://issues.apache.org/jira/browse/ZOOKEEPER-1817) - Fix don&#39;t care for b3.4
* [ZOOKEEPER-1834](https://issues.apache.org/jira/browse/ZOOKEEPER-1834) - Catch IOException in FileTxnLog
* [ZOOKEEPER-1837](https://issues.apache.org/jira/browse/ZOOKEEPER-1837) - Fix JMXEnv checks (potential race conditions)
* [ZOOKEEPER-1838](https://issues.apache.org/jira/browse/ZOOKEEPER-1838) - ZooKeeper shutdown hangs indefinitely at NioServerSocketChannelFactory.releaseExternalResources
* [ZOOKEEPER-1841](https://issues.apache.org/jira/browse/ZOOKEEPER-1841) - problem in QuorumTest
* [ZOOKEEPER-1849](https://issues.apache.org/jira/browse/ZOOKEEPER-1849) - Need to properly tear down tests in various cases
* [ZOOKEEPER-1852](https://issues.apache.org/jira/browse/ZOOKEEPER-1852) - ServerCnxnFactory instance is not properly cleanedup
* [ZOOKEEPER-1854](https://issues.apache.org/jira/browse/ZOOKEEPER-1854) - ClientBase ZooKeeper server clean-up
* [ZOOKEEPER-1857](https://issues.apache.org/jira/browse/ZOOKEEPER-1857) - PrepRequestProcessotTest doesn&#39;t shutdown ZooKeeper server
* [ZOOKEEPER-1858](https://issues.apache.org/jira/browse/ZOOKEEPER-1858) - JMX checks - potential race conditions while stopping and starting server
* [ZOOKEEPER-1867](https://issues.apache.org/jira/browse/ZOOKEEPER-1867) - Bug in ZkDatabaseCorruptionTest
* [ZOOKEEPER-1873](https://issues.apache.org/jira/browse/ZOOKEEPER-1873) - Unnecessarily InstanceNotFoundException is coming when unregister failed jmxbeans

## Bug
* [ZOOKEEPER-87](https://issues.apache.org/jira/browse/ZOOKEEPER-87) - Follower does not shut itself down if its too far behind the leader.
* [ZOOKEEPER-732](https://issues.apache.org/jira/browse/ZOOKEEPER-732) - Improper translation of error into Python exception
* [ZOOKEEPER-753](https://issues.apache.org/jira/browse/ZOOKEEPER-753) - update log4j dependency from 1.2.15 to 1.2.16 in branch 3.4
* [ZOOKEEPER-805](https://issues.apache.org/jira/browse/ZOOKEEPER-805) - four letter words fail with latest ubuntu nc.openbsd
* [ZOOKEEPER-877](https://issues.apache.org/jira/browse/ZOOKEEPER-877) - zkpython does not work with python3.1
* [ZOOKEEPER-978](https://issues.apache.org/jira/browse/ZOOKEEPER-978) - ZookeeperServer does not close zk database on shutdwon
* [ZOOKEEPER-1057](https://issues.apache.org/jira/browse/ZOOKEEPER-1057) - zookeeper c-client, connection to offline server fails to successfully fallback to second zk host
* [ZOOKEEPER-1179](https://issues.apache.org/jira/browse/ZOOKEEPER-1179) - NettyServerCnxn does not properly close socket on 4 letter word requests
* [ZOOKEEPER-1238](https://issues.apache.org/jira/browse/ZOOKEEPER-1238) - when the linger time was changed for NIO the patch missed Netty
* [ZOOKEEPER-1334](https://issues.apache.org/jira/browse/ZOOKEEPER-1334) - Zookeeper 3.4.x is not OSGi compliant - MANIFEST.MF is flawed
* [ZOOKEEPER-1379](https://issues.apache.org/jira/browse/ZOOKEEPER-1379) - &#39;printwatches, redo, history and connect &#39;. client commands always print usage. This is not necessary
* [ZOOKEEPER-1382](https://issues.apache.org/jira/browse/ZOOKEEPER-1382) - Zookeeper server holds onto dead/expired session ids in the watch data structures
* [ZOOKEEPER-1387](https://issues.apache.org/jira/browse/ZOOKEEPER-1387) - Wrong epoch file created
* [ZOOKEEPER-1388](https://issues.apache.org/jira/browse/ZOOKEEPER-1388) - Client side &#39;PathValidation&#39; is missing for the multi-transaction api.
* [ZOOKEEPER-1448](https://issues.apache.org/jira/browse/ZOOKEEPER-1448) - Node+Quota creation in transaction log can crash leader startup
* [ZOOKEEPER-1462](https://issues.apache.org/jira/browse/ZOOKEEPER-1462) - Read-only server does not initialize database properly
* [ZOOKEEPER-1474](https://issues.apache.org/jira/browse/ZOOKEEPER-1474) - Cannot build Zookeeper with IBM Java: use of Sun MXBean classes
* [ZOOKEEPER-1478](https://issues.apache.org/jira/browse/ZOOKEEPER-1478) - Small bug in QuorumTest.testFollowersStartAfterLeader( )
* [ZOOKEEPER-1495](https://issues.apache.org/jira/browse/ZOOKEEPER-1495) - ZK client hangs when using a function not available on the server.
* [ZOOKEEPER-1513](https://issues.apache.org/jira/browse/ZOOKEEPER-1513) - &quot;Unreasonable length&quot; exception while starting a server.
* [ZOOKEEPER-1535](https://issues.apache.org/jira/browse/ZOOKEEPER-1535) - ZK Shell/Cli re-executes last command on exit
* [ZOOKEEPER-1548](https://issues.apache.org/jira/browse/ZOOKEEPER-1548) - Cluster fails election loop in new and interesting way
* [ZOOKEEPER-1551](https://issues.apache.org/jira/browse/ZOOKEEPER-1551) - Observers ignore txns that come after snapshot and UPTODATE 
* [ZOOKEEPER-1553](https://issues.apache.org/jira/browse/ZOOKEEPER-1553) - Findbugs configuration is missing some dependencies
* [ZOOKEEPER-1554](https://issues.apache.org/jira/browse/ZOOKEEPER-1554) - Can&#39;t use zookeeper client without SASL
* [ZOOKEEPER-1557](https://issues.apache.org/jira/browse/ZOOKEEPER-1557) - jenkins jdk7 test failure in testBadSaslAuthNotifiesWatch
* [ZOOKEEPER-1562](https://issues.apache.org/jira/browse/ZOOKEEPER-1562) - Memory leaks in zoo_multi API
* [ZOOKEEPER-1573](https://issues.apache.org/jira/browse/ZOOKEEPER-1573) - Unable to load database due to missing parent node
* [ZOOKEEPER-1578](https://issues.apache.org/jira/browse/ZOOKEEPER-1578) - org.apache.zookeeper.server.quorum.Zab1_0Test failed due to hard code with 33556 port
* [ZOOKEEPER-1581](https://issues.apache.org/jira/browse/ZOOKEEPER-1581) - change copyright in notice to 2012
* [ZOOKEEPER-1596](https://issues.apache.org/jira/browse/ZOOKEEPER-1596) - Zab1_0Test should ensure that the file is closed
* [ZOOKEEPER-1597](https://issues.apache.org/jira/browse/ZOOKEEPER-1597) - Windows build failing
* [ZOOKEEPER-1599](https://issues.apache.org/jira/browse/ZOOKEEPER-1599) - 3.3 server cannot join 3.4 quorum
* [ZOOKEEPER-1603](https://issues.apache.org/jira/browse/ZOOKEEPER-1603) - StaticHostProviderTest testUpdateClientMigrateOrNot hangs
* [ZOOKEEPER-1606](https://issues.apache.org/jira/browse/ZOOKEEPER-1606) - intermittent failures in ZkDatabaseCorruptionTest on jenkins
* [ZOOKEEPER-1610](https://issues.apache.org/jira/browse/ZOOKEEPER-1610) - Some classes are using == or != to compare Long/String objects instead of .equals()
* [ZOOKEEPER-1613](https://issues.apache.org/jira/browse/ZOOKEEPER-1613) - The documentation still points to 2008 in the copyright notice
* [ZOOKEEPER-1622](https://issues.apache.org/jira/browse/ZOOKEEPER-1622) - session ids will be negative in the year 2022
* [ZOOKEEPER-1624](https://issues.apache.org/jira/browse/ZOOKEEPER-1624) - PrepRequestProcessor abort multi-operation incorrectly
* [ZOOKEEPER-1629](https://issues.apache.org/jira/browse/ZOOKEEPER-1629) - testTransactionLogCorruption occasionally fails
* [ZOOKEEPER-1632](https://issues.apache.org/jira/browse/ZOOKEEPER-1632) - fix memory leaks in cli_st 
* [ZOOKEEPER-1633](https://issues.apache.org/jira/browse/ZOOKEEPER-1633) - Introduce a protocol version to connection initiation message
* [ZOOKEEPER-1642](https://issues.apache.org/jira/browse/ZOOKEEPER-1642) - Leader loading database twice
* [ZOOKEEPER-1645](https://issues.apache.org/jira/browse/ZOOKEEPER-1645) - ZooKeeper OSGi package imports not complete
* [ZOOKEEPER-1646](https://issues.apache.org/jira/browse/ZOOKEEPER-1646) - mt c client tests fail on Ubuntu Raring
* [ZOOKEEPER-1647](https://issues.apache.org/jira/browse/ZOOKEEPER-1647) - OSGi package import/export changes not applied to bin-jar
* [ZOOKEEPER-1648](https://issues.apache.org/jira/browse/ZOOKEEPER-1648) - Fix WatcherTest in JDK7
* [ZOOKEEPER-1653](https://issues.apache.org/jira/browse/ZOOKEEPER-1653) - zookeeper fails to start because of inconsistent epoch
* [ZOOKEEPER-1657](https://issues.apache.org/jira/browse/ZOOKEEPER-1657) - Increased CPU usage by unnecessary SASL checks
* [ZOOKEEPER-1663](https://issues.apache.org/jira/browse/ZOOKEEPER-1663) - scripts don&#39;t work when path contains spaces
* [ZOOKEEPER-1667](https://issues.apache.org/jira/browse/ZOOKEEPER-1667) - Watch event isn&#39;t handled correctly when a client reestablish to a server
* [ZOOKEEPER-1696](https://issues.apache.org/jira/browse/ZOOKEEPER-1696) - Fail to run zookeeper client on Weblogic application server
* [ZOOKEEPER-1697](https://issues.apache.org/jira/browse/ZOOKEEPER-1697) - large snapshots can cause continuous quorum failure
* [ZOOKEEPER-1702](https://issues.apache.org/jira/browse/ZOOKEEPER-1702) - ZooKeeper client may write operation packets before receiving successful response to connection request, can cause TCP RST
* [ZOOKEEPER-1706](https://issues.apache.org/jira/browse/ZOOKEEPER-1706) - Typo in Double Barriers example
* [ZOOKEEPER-1711](https://issues.apache.org/jira/browse/ZOOKEEPER-1711) - ZooKeeper server binds to all ip addresses for leader election and broadcast
* [ZOOKEEPER-1713](https://issues.apache.org/jira/browse/ZOOKEEPER-1713) - wrong time calculation in zkfuse.cc
* [ZOOKEEPER-1714](https://issues.apache.org/jira/browse/ZOOKEEPER-1714) - perl client segfaults if ZOO_READ_ACL_UNSAFE constant is used
* [ZOOKEEPER-1719](https://issues.apache.org/jira/browse/ZOOKEEPER-1719) - zkCli.sh, zkServer.sh and zkEnv.sh regression caused by ZOOKEEPER-1663
* [ZOOKEEPER-1731](https://issues.apache.org/jira/browse/ZOOKEEPER-1731) - Unsynchronized access to ServerCnxnFactory.connectionBeans results in deadlock
* [ZOOKEEPER-1732](https://issues.apache.org/jira/browse/ZOOKEEPER-1732) - ZooKeeper server unable to join established ensemble
* [ZOOKEEPER-1733](https://issues.apache.org/jira/browse/ZOOKEEPER-1733) - FLETest#testLE is flaky on windows boxes
* [ZOOKEEPER-1744](https://issues.apache.org/jira/browse/ZOOKEEPER-1744) - clientPortAddress breaks &quot;zkServer.sh status&quot; 
* [ZOOKEEPER-1745](https://issues.apache.org/jira/browse/ZOOKEEPER-1745) - Wrong Import-Package in the META-INF/MANIFEST.MF of zookeeper 3.4.5 bundle
* [ZOOKEEPER-1750](https://issues.apache.org/jira/browse/ZOOKEEPER-1750) - Race condition producing NPE in NIOServerCnxn.toString
* [ZOOKEEPER-1751](https://issues.apache.org/jira/browse/ZOOKEEPER-1751) - ClientCnxn#run could miss the second ping or connection get dropped before a ping
* [ZOOKEEPER-1753](https://issues.apache.org/jira/browse/ZOOKEEPER-1753) - ClientCnxn is not properly releasing the resources, which are used to ping RwServer
* [ZOOKEEPER-1754](https://issues.apache.org/jira/browse/ZOOKEEPER-1754) - Read-only server allows to create znode
* [ZOOKEEPER-1755](https://issues.apache.org/jira/browse/ZOOKEEPER-1755) - Concurrent operations of four letter &#39;dump&#39; ephemeral command and killSession causing NPE
* [ZOOKEEPER-1756](https://issues.apache.org/jira/browse/ZOOKEEPER-1756) - zookeeper_interest() in C client can return a timeval of 0
* [ZOOKEEPER-1764](https://issues.apache.org/jira/browse/ZOOKEEPER-1764) - ZooKeeper attempts at SASL eventhough it shouldn&#39;t
* [ZOOKEEPER-1765](https://issues.apache.org/jira/browse/ZOOKEEPER-1765) - Update code conventions link on &quot;How to contribute&quot; page
* [ZOOKEEPER-1770](https://issues.apache.org/jira/browse/ZOOKEEPER-1770) - NullPointerException in SnapshotFormatter
* [ZOOKEEPER-1774](https://issues.apache.org/jira/browse/ZOOKEEPER-1774) - QuorumPeerMainTest fails consistently with &quot;complains about host&quot; assertion failure
* [ZOOKEEPER-1775](https://issues.apache.org/jira/browse/ZOOKEEPER-1775) - Ephemeral nodes not present in one of the members of the ensemble
* [ZOOKEEPER-1776](https://issues.apache.org/jira/browse/ZOOKEEPER-1776) - Ephemeral nodes not present in one of the members of the ensemble
* [ZOOKEEPER-1781](https://issues.apache.org/jira/browse/ZOOKEEPER-1781) - ZooKeeper Server fails if snapCount is set to 1 
* [ZOOKEEPER-1786](https://issues.apache.org/jira/browse/ZOOKEEPER-1786) - ZooKeeper data model documentation is incorrect
* [ZOOKEEPER-1790](https://issues.apache.org/jira/browse/ZOOKEEPER-1790) - Deal with special ObserverId in QuorumCnxManager.receiveConnection
* [ZOOKEEPER-1798](https://issues.apache.org/jira/browse/ZOOKEEPER-1798) - Fix race condition in testNormalObserverRun
* [ZOOKEEPER-1799](https://issues.apache.org/jira/browse/ZOOKEEPER-1799) - SaslAuthFailDesignatedClientTest.testAuth fails frequently on SUSE
* [ZOOKEEPER-1805](https://issues.apache.org/jira/browse/ZOOKEEPER-1805) - &quot;Don&#39;t care&quot; value in ZooKeeper election breaks rolling upgrades
* [ZOOKEEPER-1811](https://issues.apache.org/jira/browse/ZOOKEEPER-1811) - The ZooKeeperSaslClient service name principal is hardcoded to &quot;zookeeper&quot;
* [ZOOKEEPER-1812](https://issues.apache.org/jira/browse/ZOOKEEPER-1812) - ZooInspector reconnection always fails if first connection fails
* [ZOOKEEPER-1821](https://issues.apache.org/jira/browse/ZOOKEEPER-1821) - very ugly warning when compiling load_gen.c
* [ZOOKEEPER-1839](https://issues.apache.org/jira/browse/ZOOKEEPER-1839) - Deadlock in NettyServerCnxn
* [ZOOKEEPER-1844](https://issues.apache.org/jira/browse/ZOOKEEPER-1844) - TruncateTest fails on windows
* [ZOOKEEPER-1845](https://issues.apache.org/jira/browse/ZOOKEEPER-1845) - FLETest.testLE fails on windows
* [ZOOKEEPER-1850](https://issues.apache.org/jira/browse/ZOOKEEPER-1850) - cppunit test testNonexistingHost in TestZookeeperInit is failing on Unbuntu
* [ZOOKEEPER-2015](https://issues.apache.org/jira/browse/ZOOKEEPER-2015) - I found memory leak in zk client for c++

## Improvement
* [ZOOKEEPER-1019](https://issues.apache.org/jira/browse/ZOOKEEPER-1019) - zkfuse doesn&#39;t list dependency on boost in README
* [ZOOKEEPER-1096](https://issues.apache.org/jira/browse/ZOOKEEPER-1096) - Leader communication should listen on specified IP, not wildcard address
* [ZOOKEEPER-1324](https://issues.apache.org/jira/browse/ZOOKEEPER-1324) - Remove Duplicate NEWLEADER packets from the Leader to the Follower.
* [ZOOKEEPER-1552](https://issues.apache.org/jira/browse/ZOOKEEPER-1552) - Enable sync request processor in Observer
* [ZOOKEEPER-1564](https://issues.apache.org/jira/browse/ZOOKEEPER-1564) - Allow JUnit test build with IBM Java
* [ZOOKEEPER-1583](https://issues.apache.org/jira/browse/ZOOKEEPER-1583) - Document maxClientCnxns in conf/zoo_sample.cfg
* [ZOOKEEPER-1584](https://issues.apache.org/jira/browse/ZOOKEEPER-1584) - Adding mvn-install target for deploying the zookeeper artifacts to .m2 repository.
* [ZOOKEEPER-1598](https://issues.apache.org/jira/browse/ZOOKEEPER-1598) - Ability to support more digits in the version string
* [ZOOKEEPER-1615](https://issues.apache.org/jira/browse/ZOOKEEPER-1615) - minor typos in ZooKeeper Programmer&#39;s Guide web page
* [ZOOKEEPER-1627](https://issues.apache.org/jira/browse/ZOOKEEPER-1627) - Add org.apache.zookeeper.common to exported packages in OSGi MANIFEST headers
* [ZOOKEEPER-1666](https://issues.apache.org/jira/browse/ZOOKEEPER-1666) - Avoid Reverse DNS lookup if the hostname in connection string is literal IP address.
* [ZOOKEEPER-1715](https://issues.apache.org/jira/browse/ZOOKEEPER-1715) - Upgrade netty version
* [ZOOKEEPER-1758](https://issues.apache.org/jira/browse/ZOOKEEPER-1758) - Add documentation for zookeeper.observer.syncEnabled flag
* [ZOOKEEPER-1771](https://issues.apache.org/jira/browse/ZOOKEEPER-1771) - ZooInspector authentication

## Task
* [ZOOKEEPER-1430](https://issues.apache.org/jira/browse/ZOOKEEPER-1430) - add maven deploy support to the build

## Test
* [ZOOKEEPER-1980](https://issues.apache.org/jira/browse/ZOOKEEPER-1980) - how to draw the figure&quot;ZooKeeper Throughput as the Read-Write Ratio Varies&quot; ?


# Release Notes - ZooKeeper - Version 3.4.5

## Bug
* [ZOOKEEPER-1376](https://issues.apache.org/jira/browse/ZOOKEEPER-1376) - zkServer.sh does not correctly check for $SERVER_JVMFLAGS
* [ZOOKEEPER-1550](https://issues.apache.org/jira/browse/ZOOKEEPER-1550) - ZooKeeperSaslClient does not finish anonymous login on OpenJDK
* [ZOOKEEPER-1560](https://issues.apache.org/jira/browse/ZOOKEEPER-1560) - Zookeeper client hangs on creation of large nodes
* [ZOOKEEPER-1686](https://issues.apache.org/jira/browse/ZOOKEEPER-1686) - Publish ZK 3.4.5 test jar

## Improvement
* [ZOOKEEPER-1640](https://issues.apache.org/jira/browse/ZOOKEEPER-1640) - dynamically load command objects in zk


# Release Notes - ZooKeeper - Version 3.4.4

## Bug
* [ZOOKEEPER-1048](https://issues.apache.org/jira/browse/ZOOKEEPER-1048) - addauth command does not work in cli_mt/cli_st
* [ZOOKEEPER-1163](https://issues.apache.org/jira/browse/ZOOKEEPER-1163) - Memory leak in zk_hashtable.c:do_insert_watcher_object()
* [ZOOKEEPER-1210](https://issues.apache.org/jira/browse/ZOOKEEPER-1210) - Can&#39;t build ZooKeeper RPM with RPM &gt;= 4.6.0 (i.e. on RHEL 6 and Fedora &gt;= 10)
* [ZOOKEEPER-1236](https://issues.apache.org/jira/browse/ZOOKEEPER-1236) - Security uses proprietary Sun APIs
* [ZOOKEEPER-1277](https://issues.apache.org/jira/browse/ZOOKEEPER-1277) - servers stop serving when lower 32bits of zxid roll over
* [ZOOKEEPER-1303](https://issues.apache.org/jira/browse/ZOOKEEPER-1303) - Observer LearnerHandlers are not removed from Leader collection.
* [ZOOKEEPER-1307](https://issues.apache.org/jira/browse/ZOOKEEPER-1307) - zkCli.sh is exiting when an Invalid ACL exception is thrown from setACL command through client
* [ZOOKEEPER-1318](https://issues.apache.org/jira/browse/ZOOKEEPER-1318) - In Python binding, get_children (and get and exists, and probably others) with expired session doesn&#39;t raise exception properly
* [ZOOKEEPER-1339](https://issues.apache.org/jira/browse/ZOOKEEPER-1339) - C clien doesn&#39;t build with --enable-debug
* [ZOOKEEPER-1344](https://issues.apache.org/jira/browse/ZOOKEEPER-1344) - ZooKeeper client multi-update command is not considering the Chroot request
* [ZOOKEEPER-1354](https://issues.apache.org/jira/browse/ZOOKEEPER-1354) - AuthTest.testBadAuthThenSendOtherCommands fails intermittently
* [ZOOKEEPER-1361](https://issues.apache.org/jira/browse/ZOOKEEPER-1361) - Leader.lead iterates over &#39;learners&#39; set without proper synchronisation
* [ZOOKEEPER-1380](https://issues.apache.org/jira/browse/ZOOKEEPER-1380) - zkperl: _zk_release_watch doesn&#39;t remove items properly from the watch list
* [ZOOKEEPER-1384](https://issues.apache.org/jira/browse/ZOOKEEPER-1384) - test-cppunit overrides LD_LIBRARY_PATH and fails if gcc is in non-standard location
* [ZOOKEEPER-1386](https://issues.apache.org/jira/browse/ZOOKEEPER-1386) - avoid flaky URL redirection in &quot;ant javadoc&quot; : replace &quot;http://java.sun.com/javase/6/docs/api/&quot; with &quot;http://download.oracle.com/javase/6/docs/api/&quot; 
* [ZOOKEEPER-1395](https://issues.apache.org/jira/browse/ZOOKEEPER-1395) - node-watcher double-free redux
* [ZOOKEEPER-1403](https://issues.apache.org/jira/browse/ZOOKEEPER-1403) - zkCli.sh script quoting issue
* [ZOOKEEPER-1406](https://issues.apache.org/jira/browse/ZOOKEEPER-1406) - dpkg init scripts don&#39;t restart - missing check_priv_sep_dir
* [ZOOKEEPER-1412](https://issues.apache.org/jira/browse/ZOOKEEPER-1412) - java client watches inconsistently triggered on reconnect
* [ZOOKEEPER-1419](https://issues.apache.org/jira/browse/ZOOKEEPER-1419) - Leader election never settles for a 5-node cluster
* [ZOOKEEPER-1427](https://issues.apache.org/jira/browse/ZOOKEEPER-1427) - Writing to local files is done non-atomically
* [ZOOKEEPER-1431](https://issues.apache.org/jira/browse/ZOOKEEPER-1431) - zkpython: async calls leak memory
* [ZOOKEEPER-1437](https://issues.apache.org/jira/browse/ZOOKEEPER-1437) - Client uses session before SASL authentication complete
* [ZOOKEEPER-1463](https://issues.apache.org/jira/browse/ZOOKEEPER-1463) - external inline function is not compatible with C99
* [ZOOKEEPER-1465](https://issues.apache.org/jira/browse/ZOOKEEPER-1465) - Cluster availability following new leader election takes a long time with large datasets - is correlated to dataset size
* [ZOOKEEPER-1466](https://issues.apache.org/jira/browse/ZOOKEEPER-1466) - QuorumCnxManager.shutdown missing synchronization
* [ZOOKEEPER-1471](https://issues.apache.org/jira/browse/ZOOKEEPER-1471) - Jute generates invalid C++ code
* [ZOOKEEPER-1483](https://issues.apache.org/jira/browse/ZOOKEEPER-1483) - Fix leader election recipe documentation
* [ZOOKEEPER-1489](https://issues.apache.org/jira/browse/ZOOKEEPER-1489) - Data loss after truncate on transaction log
* [ZOOKEEPER-1490](https://issues.apache.org/jira/browse/ZOOKEEPER-1490) -  If the configured log directory does not exist zookeeper will not start. Better to create the directory and start
* [ZOOKEEPER-1493](https://issues.apache.org/jira/browse/ZOOKEEPER-1493) - C Client: zookeeper_process doesn&#39;t invoke completion callback if zookeeper_close has been called
* [ZOOKEEPER-1494](https://issues.apache.org/jira/browse/ZOOKEEPER-1494) - C client: socket leak after receive timeout in zookeeper_interest()
* [ZOOKEEPER-1496](https://issues.apache.org/jira/browse/ZOOKEEPER-1496) - Ephemeral node not getting cleared even after client has exited
* [ZOOKEEPER-1501](https://issues.apache.org/jira/browse/ZOOKEEPER-1501) - Nagios plugin always returns OK when it cannot connect to zookeeper
* [ZOOKEEPER-1514](https://issues.apache.org/jira/browse/ZOOKEEPER-1514) - FastLeaderElection - leader ignores the round information when joining a quorum
* [ZOOKEEPER-1521](https://issues.apache.org/jira/browse/ZOOKEEPER-1521) - LearnerHandler initLimit/syncLimit problems specifying follower socket timeout limits
* [ZOOKEEPER-1522](https://issues.apache.org/jira/browse/ZOOKEEPER-1522) - intermittent failures in Zab test due to NPE in recursiveDelete test function
* [ZOOKEEPER-1536](https://issues.apache.org/jira/browse/ZOOKEEPER-1536) - c client : memory leak in winport.c
* [ZOOKEEPER-1686](https://issues.apache.org/jira/browse/ZOOKEEPER-1686) - Publish ZK 3.4.5 test jar

## Improvement
* [ZOOKEEPER-1321](https://issues.apache.org/jira/browse/ZOOKEEPER-1321) - Add number of client connections metric in JMX and srvr
* [ZOOKEEPER-1377](https://issues.apache.org/jira/browse/ZOOKEEPER-1377) - add support for dumping a snapshot file content (similar to LogFormatter)
* [ZOOKEEPER-1389](https://issues.apache.org/jira/browse/ZOOKEEPER-1389) - it would be nice if start-foreground used exec $JAVA in order to get rid of the intermediate shell process
* [ZOOKEEPER-1390](https://issues.apache.org/jira/browse/ZOOKEEPER-1390) - some expensive debug code not protected by a check for debug
* [ZOOKEEPER-1433](https://issues.apache.org/jira/browse/ZOOKEEPER-1433) - improve ZxidRolloverTest (test seems flakey)
* [ZOOKEEPER-1454](https://issues.apache.org/jira/browse/ZOOKEEPER-1454) - Document how to run autoreconf if cppunit is installed in a non-standard directory
* [ZOOKEEPER-1481](https://issues.apache.org/jira/browse/ZOOKEEPER-1481) - allow the C cli to run exists with a watcher
* [ZOOKEEPER-1497](https://issues.apache.org/jira/browse/ZOOKEEPER-1497) - Allow server-side SASL login with JAAS configuration to be programmatically set (rather than only by reading JAAS configuration file)
* [ZOOKEEPER-1503](https://issues.apache.org/jira/browse/ZOOKEEPER-1503) - remove redundant JAAS configuration code in SaslAuthTest and SaslAuthFailTest
* [ZOOKEEPER-1510](https://issues.apache.org/jira/browse/ZOOKEEPER-1510) - Should not log SASL errors for non-secure usage
* [ZOOKEEPER-1565](https://issues.apache.org/jira/browse/ZOOKEEPER-1565) - Allow ClientTest.java build with IBM Java
* [ZOOKEEPER-1570](https://issues.apache.org/jira/browse/ZOOKEEPER-1570) - Allow QuorumBase.java build with IBM Java
* [ZOOKEEPER-1571](https://issues.apache.org/jira/browse/ZOOKEEPER-1571) - Allow QuorumUtil.java build with IBM Java

## Task
* [ZOOKEEPER-1450](https://issues.apache.org/jira/browse/ZOOKEEPER-1450) - Backport ZOOKEEPER-1294 fix to 3.4 and 3.3


# Release Notes - ZooKeeper - Version 3.4.3

## Bug
* [ZOOKEEPER-973](https://issues.apache.org/jira/browse/ZOOKEEPER-973) - bind() could fail on Leader because it does not setReuseAddress on its ServerSocket 
* [ZOOKEEPER-1089](https://issues.apache.org/jira/browse/ZOOKEEPER-1089) - zkServer.sh status does not work due to invalid option of nc
* [ZOOKEEPER-1327](https://issues.apache.org/jira/browse/ZOOKEEPER-1327) - there are still remnants of hadoop urls
* [ZOOKEEPER-1336](https://issues.apache.org/jira/browse/ZOOKEEPER-1336) - javadoc for multi is confusing, references functionality that doesn&#39;t seem to exist 
* [ZOOKEEPER-1338](https://issues.apache.org/jira/browse/ZOOKEEPER-1338) - class cast exceptions may be thrown by multi ErrorResult class (invalid equals)
* [ZOOKEEPER-1340](https://issues.apache.org/jira/browse/ZOOKEEPER-1340) - multi problem - typical user operations are generating ERROR level messages in the server
* [ZOOKEEPER-1343](https://issues.apache.org/jira/browse/ZOOKEEPER-1343) - getEpochToPropose should check if lastAcceptedEpoch is greater or equal than epoch
* [ZOOKEEPER-1348](https://issues.apache.org/jira/browse/ZOOKEEPER-1348) - Zookeeper 3.4.2 C client incorrectly reports string version of 3.4.1
* [ZOOKEEPER-1351](https://issues.apache.org/jira/browse/ZOOKEEPER-1351) - invalid test verification in MultiTransactionTest
* [ZOOKEEPER-1352](https://issues.apache.org/jira/browse/ZOOKEEPER-1352) - server.InvalidSnapshotTest is using connection timeouts that are too short
* [ZOOKEEPER-1353](https://issues.apache.org/jira/browse/ZOOKEEPER-1353) - C client test suite fails consistently
* [ZOOKEEPER-1367](https://issues.apache.org/jira/browse/ZOOKEEPER-1367) - Data inconsistencies and unexpired ephemeral nodes after cluster restart
* [ZOOKEEPER-1370](https://issues.apache.org/jira/browse/ZOOKEEPER-1370) - Add logging changes in Release Notes needed for clients because of ZOOKEEPER-850.
* [ZOOKEEPER-1373](https://issues.apache.org/jira/browse/ZOOKEEPER-1373) - Hardcoded SASL login context name clashes with Hadoop security configuration override
* [ZOOKEEPER-1374](https://issues.apache.org/jira/browse/ZOOKEEPER-1374) - C client multi-threaded test suite fails to compile on ARM architectures.

## Improvement
* [ZOOKEEPER-1322](https://issues.apache.org/jira/browse/ZOOKEEPER-1322) - Cleanup/fix logging in Quorum code.
* [ZOOKEEPER-1345](https://issues.apache.org/jira/browse/ZOOKEEPER-1345) - Add a .gitignore file with general exclusions and Eclipse project files excluded

## Test
* [ZOOKEEPER-1337](https://issues.apache.org/jira/browse/ZOOKEEPER-1337) - multi&#39;s &quot;Transaction&quot; class is missing tests.


# Release Notes - ZooKeeper - Version 3.4.2

## Bug
* [ZOOKEEPER-1323](https://issues.apache.org/jira/browse/ZOOKEEPER-1323) - c client doesn&#39;t compile on freebsd
* [ZOOKEEPER-1333](https://issues.apache.org/jira/browse/ZOOKEEPER-1333) - NPE in FileTxnSnapLog when restarting a cluster


# Release Notes - ZooKeeper - Version 3.4.1

## Bug
* [ZOOKEEPER-1269](https://issues.apache.org/jira/browse/ZOOKEEPER-1269) - Multi deserialization issues
* [ZOOKEEPER-1305](https://issues.apache.org/jira/browse/ZOOKEEPER-1305) - zookeeper.c:prepend_string func can dereference null ptr
* [ZOOKEEPER-1311](https://issues.apache.org/jira/browse/ZOOKEEPER-1311) - ZooKeeper test jar is broken
* [ZOOKEEPER-1315](https://issues.apache.org/jira/browse/ZOOKEEPER-1315) - zookeeper_init always reports sessionPasswd=&lt;hidden&gt;
* [ZOOKEEPER-1316](https://issues.apache.org/jira/browse/ZOOKEEPER-1316) - zookeeper_init leaks memory if chroot is just &#39;/&#39;
* [ZOOKEEPER-1317](https://issues.apache.org/jira/browse/ZOOKEEPER-1317) - Possible segfault in zookeeper_init
* [ZOOKEEPER-1319](https://issues.apache.org/jira/browse/ZOOKEEPER-1319) - Missing data after restarting+expanding a cluster
* [ZOOKEEPER-1332](https://issues.apache.org/jira/browse/ZOOKEEPER-1332) - Zookeeper data is not in sync with quorum in the mentioned scenario


# Release Notes - ZooKeeper - Version 3.4.0

## Sub-task
* [ZOOKEEPER-784](https://issues.apache.org/jira/browse/ZOOKEEPER-784) - server-side functionality for read-only mode
* [ZOOKEEPER-798](https://issues.apache.org/jira/browse/ZOOKEEPER-798) - Fixup loggraph for FLE changes
* [ZOOKEEPER-839](https://issues.apache.org/jira/browse/ZOOKEEPER-839) - deleteRecursive does not belong to the other methods
* [ZOOKEEPER-908](https://issues.apache.org/jira/browse/ZOOKEEPER-908) - Remove code duplication and inconsistent naming in ClientCnxn.Packet creation
* [ZOOKEEPER-909](https://issues.apache.org/jira/browse/ZOOKEEPER-909) - Extract NIO specific code from ClientCnxn
* [ZOOKEEPER-966](https://issues.apache.org/jira/browse/ZOOKEEPER-966) - Client side for multi
* [ZOOKEEPER-967](https://issues.apache.org/jira/browse/ZOOKEEPER-967) - Server side decoding and function dispatch
* [ZOOKEEPER-968](https://issues.apache.org/jira/browse/ZOOKEEPER-968) - Database multi-update
* [ZOOKEEPER-1042](https://issues.apache.org/jira/browse/ZOOKEEPER-1042) - Generate zookeeper test jar for maven installation
* [ZOOKEEPER-1081](https://issues.apache.org/jira/browse/ZOOKEEPER-1081) - modify leader/follower code to correctly deal with new leader
* [ZOOKEEPER-1082](https://issues.apache.org/jira/browse/ZOOKEEPER-1082) - modify leader election to correctly take into account current epoch
* [ZOOKEEPER-1150](https://issues.apache.org/jira/browse/ZOOKEEPER-1150) - fix for this patch to compile on windows...
* [ZOOKEEPER-1160](https://issues.apache.org/jira/browse/ZOOKEEPER-1160) - test timeouts are too small
* [ZOOKEEPER-1201](https://issues.apache.org/jira/browse/ZOOKEEPER-1201) - Clean SaslServerCallbackHandler.java
* [ZOOKEEPER-1246](https://issues.apache.org/jira/browse/ZOOKEEPER-1246) - Dead code in PrepRequestProcessor catch Exception block
* [ZOOKEEPER-1282](https://issues.apache.org/jira/browse/ZOOKEEPER-1282) - Learner.java not following Zab 1.0 protocol - setCurrentEpoch should be done upon receipt of NEWLEADER (before acking it) and not upon receipt of UPTODATE
* [ZOOKEEPER-1291](https://issues.apache.org/jira/browse/ZOOKEEPER-1291) - AcceptedEpoch not updated at leader before it proposes the epoch to followers

## Bug
* [ZOOKEEPER-335](https://issues.apache.org/jira/browse/ZOOKEEPER-335) - zookeeper servers should commit the new leader txn to their logs.
* [ZOOKEEPER-418](https://issues.apache.org/jira/browse/ZOOKEEPER-418) - Need nifty zookeeper browser
* [ZOOKEEPER-603](https://issues.apache.org/jira/browse/ZOOKEEPER-603) - zkpython should do a better job of freeing memory under error conditions
* [ZOOKEEPER-662](https://issues.apache.org/jira/browse/ZOOKEEPER-662) - Too many CLOSE_WAIT socket state on a server
* [ZOOKEEPER-690](https://issues.apache.org/jira/browse/ZOOKEEPER-690) - AsyncTestHammer test fails on hudson.
* [ZOOKEEPER-719](https://issues.apache.org/jira/browse/ZOOKEEPER-719) - Add throttling to BookKeeper client
* [ZOOKEEPER-720](https://issues.apache.org/jira/browse/ZOOKEEPER-720) - Use zookeeper-{version}-sources.jar instead of zookeeper-{version}-src.jar to publish sources in the Maven repository
* [ZOOKEEPER-722](https://issues.apache.org/jira/browse/ZOOKEEPER-722) - zkServer.sh uses sh&#39;s builtin echo on BSD, behaves incorrectly.
* [ZOOKEEPER-731](https://issues.apache.org/jira/browse/ZOOKEEPER-731) - Zookeeper#delete  , #create - async versions miss a verb in the javadoc 
* [ZOOKEEPER-734](https://issues.apache.org/jira/browse/ZOOKEEPER-734) - QuorumPeerTestBase.java and ZooKeeperServerMainTest.java do not handle windows path correctly
* [ZOOKEEPER-735](https://issues.apache.org/jira/browse/ZOOKEEPER-735) - cppunit test testipv6 assumes that the machine is ipv6 enabled.
* [ZOOKEEPER-737](https://issues.apache.org/jira/browse/ZOOKEEPER-737) - some 4 letter words may fail with netcat (nc)
* [ZOOKEEPER-738](https://issues.apache.org/jira/browse/ZOOKEEPER-738) - zookeeper.jute.h fails to compile with -pedantic 
* [ZOOKEEPER-741](https://issues.apache.org/jira/browse/ZOOKEEPER-741) - root level create on REST proxy fails
* [ZOOKEEPER-742](https://issues.apache.org/jira/browse/ZOOKEEPER-742) - Deallocatng None on writes
* [ZOOKEEPER-746](https://issues.apache.org/jira/browse/ZOOKEEPER-746) - learner outputs session id to log in dec (should be hex)
* [ZOOKEEPER-749](https://issues.apache.org/jira/browse/ZOOKEEPER-749) - OSGi metadata not included in binary only jar
* [ZOOKEEPER-750](https://issues.apache.org/jira/browse/ZOOKEEPER-750) - move maven artifacts into &quot;dist-maven&quot; subdir of the release (package target)
* [ZOOKEEPER-758](https://issues.apache.org/jira/browse/ZOOKEEPER-758) - zkpython segfaults on invalid acl with missing key
* [ZOOKEEPER-763](https://issues.apache.org/jira/browse/ZOOKEEPER-763) - Deadlock on close w/ zkpython / c client
* [ZOOKEEPER-764](https://issues.apache.org/jira/browse/ZOOKEEPER-764) - Observer elected leader due to inconsistent voting view
* [ZOOKEEPER-766](https://issues.apache.org/jira/browse/ZOOKEEPER-766) - forrest recipes docs don&#39;t mention the lock/queue recipe implementations available in the release
* [ZOOKEEPER-769](https://issues.apache.org/jira/browse/ZOOKEEPER-769) - Leader can treat observers as quorum members
* [ZOOKEEPER-772](https://issues.apache.org/jira/browse/ZOOKEEPER-772) - zkpython segfaults when watcher from async get children is invoked.
* [ZOOKEEPER-774](https://issues.apache.org/jira/browse/ZOOKEEPER-774) - Recipes tests are slightly outdated: they do not compile against JUnit 4.8
* [ZOOKEEPER-782](https://issues.apache.org/jira/browse/ZOOKEEPER-782) - Incorrect C API documentation for Watches
* [ZOOKEEPER-783](https://issues.apache.org/jira/browse/ZOOKEEPER-783) - committedLog in ZKDatabase is not properly synchronized
* [ZOOKEEPER-785](https://issues.apache.org/jira/browse/ZOOKEEPER-785) -  Zookeeper 3.3.1 shouldn&#39;t infinite loop if someone creates a server.0 line
* [ZOOKEEPER-787](https://issues.apache.org/jira/browse/ZOOKEEPER-787) - groupId in deployed pom is wrong
* [ZOOKEEPER-790](https://issues.apache.org/jira/browse/ZOOKEEPER-790) - Last processed zxid set prematurely while establishing leadership
* [ZOOKEEPER-792](https://issues.apache.org/jira/browse/ZOOKEEPER-792) - zkpython memory leak
* [ZOOKEEPER-794](https://issues.apache.org/jira/browse/ZOOKEEPER-794) - Callbacks are not invoked when the client is closed
* [ZOOKEEPER-795](https://issues.apache.org/jira/browse/ZOOKEEPER-795) - eventThread isn&#39;t shutdown after a connection &quot;session expired&quot; event coming
* [ZOOKEEPER-796](https://issues.apache.org/jira/browse/ZOOKEEPER-796) - zkServer.sh should support an external PIDFILE variable
* [ZOOKEEPER-800](https://issues.apache.org/jira/browse/ZOOKEEPER-800) - zoo_add_auth returns ZOK if zookeeper handle is in ZOO_CLOSED_STATE
* [ZOOKEEPER-804](https://issues.apache.org/jira/browse/ZOOKEEPER-804) - c unit tests failing due to &quot;assertion cptr failed&quot;
* [ZOOKEEPER-813](https://issues.apache.org/jira/browse/ZOOKEEPER-813) - maven install is broken due to incorrect organisation
* [ZOOKEEPER-814](https://issues.apache.org/jira/browse/ZOOKEEPER-814) - monitoring scripts are missing apache license headers
* [ZOOKEEPER-820](https://issues.apache.org/jira/browse/ZOOKEEPER-820) - update c unit tests to ensure &quot;zombie&quot; java server processes don&#39;t cause failure
* [ZOOKEEPER-822](https://issues.apache.org/jira/browse/ZOOKEEPER-822) - Leader election taking a long time  to complete
* [ZOOKEEPER-831](https://issues.apache.org/jira/browse/ZOOKEEPER-831) - BookKeeper: Throttling improved for reads
* [ZOOKEEPER-844](https://issues.apache.org/jira/browse/ZOOKEEPER-844) - handle auth failure in java client
* [ZOOKEEPER-846](https://issues.apache.org/jira/browse/ZOOKEEPER-846) - zookeeper client doesn&#39;t shut down cleanly on the close call
* [ZOOKEEPER-854](https://issues.apache.org/jira/browse/ZOOKEEPER-854) - BookKeeper does not compile due to changes in the ZooKeeper code
* [ZOOKEEPER-855](https://issues.apache.org/jira/browse/ZOOKEEPER-855) - clientPortBindAddress should be clientPortAddress
* [ZOOKEEPER-861](https://issues.apache.org/jira/browse/ZOOKEEPER-861) - Missing the test SSL certificate used for running junit tests.
* [ZOOKEEPER-867](https://issues.apache.org/jira/browse/ZOOKEEPER-867) - ClientTest is failing on hudson - fd cleanup
* [ZOOKEEPER-870](https://issues.apache.org/jira/browse/ZOOKEEPER-870) - Zookeeper trunk build broken.
* [ZOOKEEPER-874](https://issues.apache.org/jira/browse/ZOOKEEPER-874) - FileTxnSnapLog.restore does not call listener
* [ZOOKEEPER-880](https://issues.apache.org/jira/browse/ZOOKEEPER-880) - QuorumCnxManager$SendWorker grows without bounds
* [ZOOKEEPER-881](https://issues.apache.org/jira/browse/ZOOKEEPER-881) - ZooKeeperServer.loadData loads database twice
* [ZOOKEEPER-882](https://issues.apache.org/jira/browse/ZOOKEEPER-882) - Startup loads last transaction from snapshot
* [ZOOKEEPER-884](https://issues.apache.org/jira/browse/ZOOKEEPER-884) - Remove LedgerSequence references from BookKeeper documentation and comments in tests 
* [ZOOKEEPER-888](https://issues.apache.org/jira/browse/ZOOKEEPER-888) - c-client / zkpython: Double free corruption on node watcher
* [ZOOKEEPER-893](https://issues.apache.org/jira/browse/ZOOKEEPER-893) - ZooKeeper high cpu usage when invalid requests
* [ZOOKEEPER-897](https://issues.apache.org/jira/browse/ZOOKEEPER-897) - C Client seg faults during close
* [ZOOKEEPER-898](https://issues.apache.org/jira/browse/ZOOKEEPER-898) - C Client might not cleanup correctly during close
* [ZOOKEEPER-902](https://issues.apache.org/jira/browse/ZOOKEEPER-902) - Fix findbug issue in trunk &quot;Malicious code vulnerability&quot;
* [ZOOKEEPER-904](https://issues.apache.org/jira/browse/ZOOKEEPER-904) - super digest is not actually acting as a full superuser
* [ZOOKEEPER-907](https://issues.apache.org/jira/browse/ZOOKEEPER-907) - Spurious &quot;KeeperErrorCode = Session moved&quot; messages
* [ZOOKEEPER-913](https://issues.apache.org/jira/browse/ZOOKEEPER-913) - Version parser fails to parse &quot;3.3.2-dev&quot; from build.xml.
* [ZOOKEEPER-919](https://issues.apache.org/jira/browse/ZOOKEEPER-919) - Ephemeral nodes remains in one of ensemble after deliberate SIGKILL
* [ZOOKEEPER-921](https://issues.apache.org/jira/browse/ZOOKEEPER-921) - zkPython incorrectly checks for existence of required ACL elements
* [ZOOKEEPER-937](https://issues.apache.org/jira/browse/ZOOKEEPER-937) - test -e not available on solaris /bin/sh
* [ZOOKEEPER-957](https://issues.apache.org/jira/browse/ZOOKEEPER-957) - zkCleanup.sh doesn&#39;t do anything
* [ZOOKEEPER-958](https://issues.apache.org/jira/browse/ZOOKEEPER-958) - Flag to turn off autoconsume in hedwig c++ client
* [ZOOKEEPER-961](https://issues.apache.org/jira/browse/ZOOKEEPER-961) - Watch recovery after disconnection when connection string contains a prefix
* [ZOOKEEPER-962](https://issues.apache.org/jira/browse/ZOOKEEPER-962) - leader/follower coherence issue when follower is receiving a DIFF
* [ZOOKEEPER-963](https://issues.apache.org/jira/browse/ZOOKEEPER-963) - Make Forrest work with JDK6
* [ZOOKEEPER-975](https://issues.apache.org/jira/browse/ZOOKEEPER-975) - new peer goes in LEADING state even if ensemble is online
* [ZOOKEEPER-976](https://issues.apache.org/jira/browse/ZOOKEEPER-976) - ZooKeeper startup script doesn&#39;t use JAVA_HOME
* [ZOOKEEPER-981](https://issues.apache.org/jira/browse/ZOOKEEPER-981) - Hang in zookeeper_close() in the multi-threaded C client
* [ZOOKEEPER-983](https://issues.apache.org/jira/browse/ZOOKEEPER-983) - running zkServer.sh start remotely using ssh hangs
* [ZOOKEEPER-985](https://issues.apache.org/jira/browse/ZOOKEEPER-985) - Test BookieRecoveryTest fails on trunk.
* [ZOOKEEPER-994](https://issues.apache.org/jira/browse/ZOOKEEPER-994) - &quot;eclipse&quot; target in the build script doesnot include libraray required for test classes in the classpath
* [ZOOKEEPER-1006](https://issues.apache.org/jira/browse/ZOOKEEPER-1006) - QuorumPeer &quot;Address already in use&quot; -- regression in 3.3.3
* [ZOOKEEPER-1007](https://issues.apache.org/jira/browse/ZOOKEEPER-1007) - iarchive leak in C client
* [ZOOKEEPER-1013](https://issues.apache.org/jira/browse/ZOOKEEPER-1013) - zkServer.sh usage message should mention all startup options
* [ZOOKEEPER-1027](https://issues.apache.org/jira/browse/ZOOKEEPER-1027) - chroot not transparent in zoo_create()
* [ZOOKEEPER-1028](https://issues.apache.org/jira/browse/ZOOKEEPER-1028) - In python bindings, zookeeper.set2() should return a stat dict but instead returns None
* [ZOOKEEPER-1033](https://issues.apache.org/jira/browse/ZOOKEEPER-1033) - c client should install includes into INCDIR/zookeeper, not INCDIR/c-client-src
* [ZOOKEEPER-1034](https://issues.apache.org/jira/browse/ZOOKEEPER-1034) - perl bindings should automatically find the zookeeper c-client headers
* [ZOOKEEPER-1046](https://issues.apache.org/jira/browse/ZOOKEEPER-1046) - Creating a new sequential node results in a ZNODEEXISTS error
* [ZOOKEEPER-1049](https://issues.apache.org/jira/browse/ZOOKEEPER-1049) - Session expire/close flooding renders heartbeats to delay significantly
* [ZOOKEEPER-1051](https://issues.apache.org/jira/browse/ZOOKEEPER-1051) - SIGPIPE in Zookeeper 0.3.* when send&#39;ing after cluster disconnection
* [ZOOKEEPER-1052](https://issues.apache.org/jira/browse/ZOOKEEPER-1052) - Findbugs warning in QuorumPeer.ResponderThread.run()
* [ZOOKEEPER-1055](https://issues.apache.org/jira/browse/ZOOKEEPER-1055) - check for duplicate ACLs in addACL() and create()
* [ZOOKEEPER-1058](https://issues.apache.org/jira/browse/ZOOKEEPER-1058) - fix typo in opToString for getData
* [ZOOKEEPER-1059](https://issues.apache.org/jira/browse/ZOOKEEPER-1059) - stat command isses on non-existing node causes NPE 
* [ZOOKEEPER-1060](https://issues.apache.org/jira/browse/ZOOKEEPER-1060) - QuorumPeer takes a long time to shutdown
* [ZOOKEEPER-1061](https://issues.apache.org/jira/browse/ZOOKEEPER-1061) - Zookeeper stop fails if start called twice
* [ZOOKEEPER-1063](https://issues.apache.org/jira/browse/ZOOKEEPER-1063) - Dubious synchronization in Zookeeper and ClientCnxnSocketNIO classes
* [ZOOKEEPER-1068](https://issues.apache.org/jira/browse/ZOOKEEPER-1068) - Documentation and default config suggest incorrect location for Zookeeper state
* [ZOOKEEPER-1069](https://issues.apache.org/jira/browse/ZOOKEEPER-1069) - Calling shutdown() on a QuorumPeer too quickly can lead to a corrupt log
* [ZOOKEEPER-1073](https://issues.apache.org/jira/browse/ZOOKEEPER-1073) - address a documentation issue in ZOOKEEPER-1030
* [ZOOKEEPER-1074](https://issues.apache.org/jira/browse/ZOOKEEPER-1074) - zkServer.sh is missing nohup/sleep, which are necessary for remote invocation
* [ZOOKEEPER-1076](https://issues.apache.org/jira/browse/ZOOKEEPER-1076) - some quorum tests are unnecessarily extending QuorumBase
* [ZOOKEEPER-1083](https://issues.apache.org/jira/browse/ZOOKEEPER-1083) - Javadoc for WatchedEvent not being generated
* [ZOOKEEPER-1086](https://issues.apache.org/jira/browse/ZOOKEEPER-1086) - zookeeper test jar has non mavenised dependency.
* [ZOOKEEPER-1087](https://issues.apache.org/jira/browse/ZOOKEEPER-1087) - ForceSync VM arguement not working when set to &quot;no&quot;
* [ZOOKEEPER-1090](https://issues.apache.org/jira/browse/ZOOKEEPER-1090) - Race condition while taking snapshot can lead to not restoring data tree correctly
* [ZOOKEEPER-1091](https://issues.apache.org/jira/browse/ZOOKEEPER-1091) - when the chrootPath of ClientCnxn is not null and the Watches of zooKeeper is not null and the method primeConnection(SelectionKey k) of ClientCnxn Occurred again for some reason ,then the wrong watcher clientPath is sended to server
* [ZOOKEEPER-1097](https://issues.apache.org/jira/browse/ZOOKEEPER-1097) - Quota is not correctly rehydrated on snapshot reload
* [ZOOKEEPER-1101](https://issues.apache.org/jira/browse/ZOOKEEPER-1101) - Upload zookeeper-test maven artifacts to maven repository.
* [ZOOKEEPER-1108](https://issues.apache.org/jira/browse/ZOOKEEPER-1108) - Various bugs in zoo_add_auth in C
* [ZOOKEEPER-1109](https://issues.apache.org/jira/browse/ZOOKEEPER-1109) - Zookeeper service is down when SyncRequestProcessor meets any exception.
* [ZOOKEEPER-1111](https://issues.apache.org/jira/browse/ZOOKEEPER-1111) - JMXEnv uses System.err instead of logging
* [ZOOKEEPER-1117](https://issues.apache.org/jira/browse/ZOOKEEPER-1117) - zookeeper 3.3.3 fails to build with gcc &gt;= 4.6.1 on Debian/Ubuntu
* [ZOOKEEPER-1119](https://issues.apache.org/jira/browse/ZOOKEEPER-1119) - zkServer stop command incorrectly reading comment lines in zoo.cfg
* [ZOOKEEPER-1124](https://issues.apache.org/jira/browse/ZOOKEEPER-1124) - Multiop submitted to non-leader always fails due to timeout
* [ZOOKEEPER-1134](https://issues.apache.org/jira/browse/ZOOKEEPER-1134) - ClientCnxnSocket string comparison using == rather than equals
* [ZOOKEEPER-1136](https://issues.apache.org/jira/browse/ZOOKEEPER-1136) - NEW_LEADER should be queued not sent to match the Zab 1.0 protocol on the twiki
* [ZOOKEEPER-1138](https://issues.apache.org/jira/browse/ZOOKEEPER-1138) - release audit failing for a number of new files
* [ZOOKEEPER-1139](https://issues.apache.org/jira/browse/ZOOKEEPER-1139) - jenkins is reporting two warnings, fix these
* [ZOOKEEPER-1140](https://issues.apache.org/jira/browse/ZOOKEEPER-1140) - server shutdown is not stopping threads
* [ZOOKEEPER-1141](https://issues.apache.org/jira/browse/ZOOKEEPER-1141) - zkpython fails tests under python 2.4
* [ZOOKEEPER-1142](https://issues.apache.org/jira/browse/ZOOKEEPER-1142) - incorrect stat output
* [ZOOKEEPER-1144](https://issues.apache.org/jira/browse/ZOOKEEPER-1144) - ZooKeeperServer not starting on leader due to a race condition
* [ZOOKEEPER-1145](https://issues.apache.org/jira/browse/ZOOKEEPER-1145) - ObserverTest.testObserver fails at particular point after several runs of ant junt.run -Dtestcase=ObserverTest
* [ZOOKEEPER-1146](https://issues.apache.org/jira/browse/ZOOKEEPER-1146) - significant regression in client (c/python) performance
* [ZOOKEEPER-1152](https://issues.apache.org/jira/browse/ZOOKEEPER-1152) - Exceptions thrown from handleAuthentication can cause buffer corruption issues in NIOServer
* [ZOOKEEPER-1154](https://issues.apache.org/jira/browse/ZOOKEEPER-1154) - Data inconsistency when the node(s) with the highest zxid is not present at the time of leader election
* [ZOOKEEPER-1156](https://issues.apache.org/jira/browse/ZOOKEEPER-1156) - Log truncation truncating log too much - can cause data loss
* [ZOOKEEPER-1165](https://issues.apache.org/jira/browse/ZOOKEEPER-1165) - better eclipse support in tests
* [ZOOKEEPER-1168](https://issues.apache.org/jira/browse/ZOOKEEPER-1168) - ZooKeeper fails to run with IKVM
* [ZOOKEEPER-1171](https://issues.apache.org/jira/browse/ZOOKEEPER-1171) - fix build for java 7
* [ZOOKEEPER-1174](https://issues.apache.org/jira/browse/ZOOKEEPER-1174) - FD leak when network unreachable
* [ZOOKEEPER-1181](https://issues.apache.org/jira/browse/ZOOKEEPER-1181) - Fix problems with Kerberos TGT renewal
* [ZOOKEEPER-1185](https://issues.apache.org/jira/browse/ZOOKEEPER-1185) - Send AuthFailed event to client if SASL authentication fails
* [ZOOKEEPER-1189](https://issues.apache.org/jira/browse/ZOOKEEPER-1189) - For an invalid snapshot file(less than 10bytes size) RandomAccessFile stream is leaking.
* [ZOOKEEPER-1190](https://issues.apache.org/jira/browse/ZOOKEEPER-1190) - ant package is not including many of the bin scripts in the package (zkServer.sh for example)
* [ZOOKEEPER-1192](https://issues.apache.org/jira/browse/ZOOKEEPER-1192) - Leader.waitForEpochAck() checks waitingForNewEpoch instead of checking electionFinished
* [ZOOKEEPER-1194](https://issues.apache.org/jira/browse/ZOOKEEPER-1194) - Two possible race conditions during leader establishment
* [ZOOKEEPER-1195](https://issues.apache.org/jira/browse/ZOOKEEPER-1195) - SASL authorizedID being incorrectly set: should use getHostName() rather than getServiceName()
* [ZOOKEEPER-1203](https://issues.apache.org/jira/browse/ZOOKEEPER-1203) - Zookeeper systest is missing Junit Classes 
* [ZOOKEEPER-1206](https://issues.apache.org/jira/browse/ZOOKEEPER-1206) - Sequential node creation does not use always use digits in node name given certain Locales.
* [ZOOKEEPER-1208](https://issues.apache.org/jira/browse/ZOOKEEPER-1208) - Ephemeral node not removed after the client session is long gone
* [ZOOKEEPER-1212](https://issues.apache.org/jira/browse/ZOOKEEPER-1212) - zkServer.sh stop action is not conformat with LSB para 20.2 Init Script Actions
* [ZOOKEEPER-1264](https://issues.apache.org/jira/browse/ZOOKEEPER-1264) - FollowerResyncConcurrencyTest failing intermittently
* [ZOOKEEPER-1268](https://issues.apache.org/jira/browse/ZOOKEEPER-1268) - problems with read only mode, intermittent test failures and ERRORs in the log
* [ZOOKEEPER-1270](https://issues.apache.org/jira/browse/ZOOKEEPER-1270) - testEarlyLeaderAbandonment failing intermittently, quorum formed, no serving.
* [ZOOKEEPER-1271](https://issues.apache.org/jira/browse/ZOOKEEPER-1271) - testEarlyLeaderAbandonment failing on solaris - clients not retrying connection
* [ZOOKEEPER-1299](https://issues.apache.org/jira/browse/ZOOKEEPER-1299) - Add winconfig.h file to ignore in release audit.

## Improvement
* [ZOOKEEPER-494](https://issues.apache.org/jira/browse/ZOOKEEPER-494) - zookeeper should install include headers in /usr/local/include/zookeeper
* [ZOOKEEPER-500](https://issues.apache.org/jira/browse/ZOOKEEPER-500) - Async methods shouldnt throw exceptions
* [ZOOKEEPER-631](https://issues.apache.org/jira/browse/ZOOKEEPER-631) - zkpython&#39;s C code could do with a style clean-up
* [ZOOKEEPER-636](https://issues.apache.org/jira/browse/ZOOKEEPER-636) - configure.ac has instructions which override the contents of CFLAGS and CXXFLAGS.
* [ZOOKEEPER-724](https://issues.apache.org/jira/browse/ZOOKEEPER-724) - Improve junit test integration - log harness information
* [ZOOKEEPER-733](https://issues.apache.org/jira/browse/ZOOKEEPER-733) - use netty to handle client connections
* [ZOOKEEPER-765](https://issues.apache.org/jira/browse/ZOOKEEPER-765) - Add python example script
* [ZOOKEEPER-773](https://issues.apache.org/jira/browse/ZOOKEEPER-773) - Log visualisation
* [ZOOKEEPER-788](https://issues.apache.org/jira/browse/ZOOKEEPER-788) - Add server id to message logs
* [ZOOKEEPER-789](https://issues.apache.org/jira/browse/ZOOKEEPER-789) - Improve FLE log messages
* [ZOOKEEPER-797](https://issues.apache.org/jira/browse/ZOOKEEPER-797) - c client source with AI_ADDRCONFIG cannot be compiled with early glibc
* [ZOOKEEPER-809](https://issues.apache.org/jira/browse/ZOOKEEPER-809) - Improved REST Interface
* [ZOOKEEPER-821](https://issues.apache.org/jira/browse/ZOOKEEPER-821) - Add ZooKeeper version information to zkpython
* [ZOOKEEPER-850](https://issues.apache.org/jira/browse/ZOOKEEPER-850) - Switch from log4j to slf4j
* [ZOOKEEPER-853](https://issues.apache.org/jira/browse/ZOOKEEPER-853) - Make zookeeper.is_unrecoverable return True or False and not an integer
* [ZOOKEEPER-862](https://issues.apache.org/jira/browse/ZOOKEEPER-862) - Hedwig created ledgers with hardcoded Bookkeeper ensemble and quorum size.  Make these a server config parameter instead.
* [ZOOKEEPER-864](https://issues.apache.org/jira/browse/ZOOKEEPER-864) - Hedwig C++ client improvements
* [ZOOKEEPER-891](https://issues.apache.org/jira/browse/ZOOKEEPER-891) - Allow non-numeric version strings
* [ZOOKEEPER-905](https://issues.apache.org/jira/browse/ZOOKEEPER-905) - enhance zkServer.sh for easier zookeeper automation-izing
* [ZOOKEEPER-926](https://issues.apache.org/jira/browse/ZOOKEEPER-926) - Fork Hadoop common&#39;s test-patch.sh and modify for Zookeeper
* [ZOOKEEPER-977](https://issues.apache.org/jira/browse/ZOOKEEPER-977) - passing null for path_buffer in zoo_create
* [ZOOKEEPER-980](https://issues.apache.org/jira/browse/ZOOKEEPER-980) - allow configuration parameters for log4j.properties
* [ZOOKEEPER-993](https://issues.apache.org/jira/browse/ZOOKEEPER-993) - Code improvements
* [ZOOKEEPER-997](https://issues.apache.org/jira/browse/ZOOKEEPER-997) - ZkClient ignores command if there are any space in front of it
* [ZOOKEEPER-1018](https://issues.apache.org/jira/browse/ZOOKEEPER-1018) - The connection permutation in get_addrs uses a weak and inefficient shuffle
* [ZOOKEEPER-1025](https://issues.apache.org/jira/browse/ZOOKEEPER-1025) - zkCli is overly sensitive to to spaces.
* [ZOOKEEPER-1030](https://issues.apache.org/jira/browse/ZOOKEEPER-1030) - Increase default for maxClientCnxns
* [ZOOKEEPER-1094](https://issues.apache.org/jira/browse/ZOOKEEPER-1094) - Small improvements to LeaderElection and Vote classes
* [ZOOKEEPER-1095](https://issues.apache.org/jira/browse/ZOOKEEPER-1095) - Simple leader election recipe
* [ZOOKEEPER-1103](https://issues.apache.org/jira/browse/ZOOKEEPER-1103) - In QuorumTest, use the same &quot;for ( .. try { break } catch { } )&quot; pattern in testFollowersStartAfterLeaders as in testSessionMove.
* [ZOOKEEPER-1104](https://issues.apache.org/jira/browse/ZOOKEEPER-1104) - CLONE - In QuorumTest, use the same &quot;for ( .. try { break } catch { } )&quot; pattern in testFollowersStartAfterLeaders as in testSessionMove.
* [ZOOKEEPER-1143](https://issues.apache.org/jira/browse/ZOOKEEPER-1143) - quorum send &amp; recv workers are missing thread names
* [ZOOKEEPER-1153](https://issues.apache.org/jira/browse/ZOOKEEPER-1153) - Deprecate AuthFLE and LE
* [ZOOKEEPER-1166](https://issues.apache.org/jira/browse/ZOOKEEPER-1166) - Please add a few svn:ignore properties
* [ZOOKEEPER-1169](https://issues.apache.org/jira/browse/ZOOKEEPER-1169) - Fix compiler (eclipse) warnings in (generated) jute code
* [ZOOKEEPER-1239](https://issues.apache.org/jira/browse/ZOOKEEPER-1239) - add logging/stats to identify fsync stalls

## New Feature
* [ZOOKEEPER-464](https://issues.apache.org/jira/browse/ZOOKEEPER-464) - Need procedure to garbage collect ledgers
* [ZOOKEEPER-465](https://issues.apache.org/jira/browse/ZOOKEEPER-465) - Ledger size in bytes
* [ZOOKEEPER-712](https://issues.apache.org/jira/browse/ZOOKEEPER-712) - Bookie recovery
* [ZOOKEEPER-729](https://issues.apache.org/jira/browse/ZOOKEEPER-729) - Recursively delete a znode  - zkCli.sh rmr /node
* [ZOOKEEPER-744](https://issues.apache.org/jira/browse/ZOOKEEPER-744) - Add monitoring four-letter word
* [ZOOKEEPER-747](https://issues.apache.org/jira/browse/ZOOKEEPER-747) - Add C# generation to Jute
* [ZOOKEEPER-775](https://issues.apache.org/jira/browse/ZOOKEEPER-775) - A large scale pub/sub system
* [ZOOKEEPER-799](https://issues.apache.org/jira/browse/ZOOKEEPER-799) - Add tools and recipes for monitoring as a contrib
* [ZOOKEEPER-808](https://issues.apache.org/jira/browse/ZOOKEEPER-808) - Web-based Administrative Interface
* [ZOOKEEPER-859](https://issues.apache.org/jira/browse/ZOOKEEPER-859) - Native Windows version of C client
* [ZOOKEEPER-938](https://issues.apache.org/jira/browse/ZOOKEEPER-938) - Support Kerberos authentication of clients.
* [ZOOKEEPER-965](https://issues.apache.org/jira/browse/ZOOKEEPER-965) - Need a multi-update command to allow multiple znodes to be updated safely
* [ZOOKEEPER-992](https://issues.apache.org/jira/browse/ZOOKEEPER-992) - MT Native Version of Windows C Client 
* [ZOOKEEPER-999](https://issues.apache.org/jira/browse/ZOOKEEPER-999) - Create an package integration project
* [ZOOKEEPER-1012](https://issues.apache.org/jira/browse/ZOOKEEPER-1012) - support distinct JVMFLAGS for zookeeper server in zkServer.sh and zookeeper client in zkCli.sh
* [ZOOKEEPER-1020](https://issues.apache.org/jira/browse/ZOOKEEPER-1020) - Implement function in C client to determine which host you&#39;re currently connected to.
* [ZOOKEEPER-1107](https://issues.apache.org/jira/browse/ZOOKEEPER-1107) - automating log and snapshot cleaning

## Task
* [ZOOKEEPER-754](https://issues.apache.org/jira/browse/ZOOKEEPER-754) - numerous misspellings &quot;succesfully&quot;
* [ZOOKEEPER-1149](https://issues.apache.org/jira/browse/ZOOKEEPER-1149) - users cannot migrate from 3.4-&gt;3.3-&gt;3.4 server code against a single datadir

## Test
* [ZOOKEEPER-239](https://issues.apache.org/jira/browse/ZOOKEEPER-239) - ZooKeeper System Tests
