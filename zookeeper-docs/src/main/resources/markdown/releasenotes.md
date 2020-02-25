<!--
Copyright 2002-2020 The Apache Software Foundation

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



# Release Notes - ZooKeeper - Version 3.6.0

## New Feature

* [ZOOKEEPER-27](https://issues.apache.org/jira/browse/ZOOKEEPER-27) - Unique DB identifiers for servers and clients
* [ZOOKEEPER-1260](https://issues.apache.org/jira/browse/ZOOKEEPER-1260) - Audit logging in ZooKeeper servers.
* [ZOOKEEPER-1634](https://issues.apache.org/jira/browse/ZOOKEEPER-1634) - A new feature proposal to ZooKeeper: authentication enforcement
* [ZOOKEEPER-1703](https://issues.apache.org/jira/browse/ZOOKEEPER-1703) - Please add instructions for running the tutorial
* [ZOOKEEPER-1962](https://issues.apache.org/jira/browse/ZOOKEEPER-1962) - Add a CLI command to recursively list a znode and children
* [ZOOKEEPER-2875](https://issues.apache.org/jira/browse/ZOOKEEPER-2875) - Add ant task for running OWASP dependency report
* [ZOOKEEPER-2933](https://issues.apache.org/jira/browse/ZOOKEEPER-2933) - Ability to monitor the jute.maxBuffer usage in real-time
* [ZOOKEEPER-2994](https://issues.apache.org/jira/browse/ZOOKEEPER-2994) - Tool required to recover log and snapshot entries with CRC errors
* [ZOOKEEPER-3066](https://issues.apache.org/jira/browse/ZOOKEEPER-3066) - Expose on JMX of Followers the id of the current leader
* [ZOOKEEPER-3091](https://issues.apache.org/jira/browse/ZOOKEEPER-3091) - Prometheus.io integration
* [ZOOKEEPER-3092](https://issues.apache.org/jira/browse/ZOOKEEPER-3092) - Pluggable metrics system for ZooKeeper
* [ZOOKEEPER-3114](https://issues.apache.org/jira/browse/ZOOKEEPER-3114) - Built-in data consistency check inside ZooKeeper
* [ZOOKEEPER-3137](https://issues.apache.org/jira/browse/ZOOKEEPER-3137) - add a utility to truncate logs to a zxid
* [ZOOKEEPER-3140](https://issues.apache.org/jira/browse/ZOOKEEPER-3140) - Allow Followers to host Observers
* [ZOOKEEPER-3160](https://issues.apache.org/jira/browse/ZOOKEEPER-3160) - Custom User SSLContext
* [ZOOKEEPER-3167](https://issues.apache.org/jira/browse/ZOOKEEPER-3167) - add an API and the corresponding CLI to get total count of recursive sub nodes under a specific path
* [ZOOKEEPER-3209](https://issues.apache.org/jira/browse/ZOOKEEPER-3209) - New `getEphemerals` api to get all the ephemeral nodes created by the session
* [ZOOKEEPER-3244](https://issues.apache.org/jira/browse/ZOOKEEPER-3244) - Add option to snapshot based on log size
* [ZOOKEEPER-3269](https://issues.apache.org/jira/browse/ZOOKEEPER-3269) - Testable facade would benefit from a queueEvent() method
* [ZOOKEEPER-3311](https://issues.apache.org/jira/browse/ZOOKEEPER-3311) - Allow a delay to the transaction log flush 
* [ZOOKEEPER-3331](https://issues.apache.org/jira/browse/ZOOKEEPER-3331) - Automatically add IP authorization for Netty connections
* [ZOOKEEPER-3343](https://issues.apache.org/jira/browse/ZOOKEEPER-3343) - Add a new doc: zookeeperTools.md
* [ZOOKEEPER-3344](https://issues.apache.org/jira/browse/ZOOKEEPER-3344) - write a new script:zkSnapShotToolkit.sh to encapsulate SnapshotFormatter and doc the usage
* [ZOOKEEPER-3371](https://issues.apache.org/jira/browse/ZOOKEEPER-3371) - Port unification for admin server
* [ZOOKEEPER-3447](https://issues.apache.org/jira/browse/ZOOKEEPER-3447) - add a doc: zookeeperMonitor.md
    
## Improvement

* [ZOOKEEPER-3703](https://issues.apache.org/jira/browse/ZOOKEEPER-3703) - publish a test JAR
* [ZOOKEEPER-3482](https://issues.apache.org/jira/browse/ZOOKEEPER-3482) - SASL (Kerberos) Authentication with SSL for clients and Quorum
* [ZOOKEEPER-3567](https://issues.apache.org/jira/browse/ZOOKEEPER-3567) - add SSL support for zkpython
* [ZOOKEEPER-261](https://issues.apache.org/jira/browse/ZOOKEEPER-261) - Reinitialized servers should not participate in leader election
* [ZOOKEEPER-761](https://issues.apache.org/jira/browse/ZOOKEEPER-761) - Remove *synchronous* calls from the *single-threaded* C clieant API, since they are documented not to work
* [ZOOKEEPER-974](https://issues.apache.org/jira/browse/ZOOKEEPER-974) - Configurable listen socket backlog for the client port
* [ZOOKEEPER-1177](https://issues.apache.org/jira/browse/ZOOKEEPER-1177) - Enabling a large number of watches for a large number of clients
* [ZOOKEEPER-1416](https://issues.apache.org/jira/browse/ZOOKEEPER-1416) - Persistent Recursive Watch
* [ZOOKEEPER-1423](https://issues.apache.org/jira/browse/ZOOKEEPER-1423) - 4lw and jmx should expose the size of the datadir/datalogdir
* [ZOOKEEPER-1425](https://issues.apache.org/jira/browse/ZOOKEEPER-1425) - add version command to the zookeeper client shell
* [ZOOKEEPER-1426](https://issues.apache.org/jira/browse/ZOOKEEPER-1426) - add version command to the zookeeper server
* [ZOOKEEPER-1467](https://issues.apache.org/jira/browse/ZOOKEEPER-1467) - Make server principal configurable at client side.
* [ZOOKEEPER-1504](https://issues.apache.org/jira/browse/ZOOKEEPER-1504) - Multi-thread NIOServerCnxn
* [ZOOKEEPER-1506](https://issues.apache.org/jira/browse/ZOOKEEPER-1506) - Re-try DNS hostname -&gt; IP resolution if node connection fails
* [ZOOKEEPER-1525](https://issues.apache.org/jira/browse/ZOOKEEPER-1525) - Plumb ZooKeeperServer object into auth plugins
* [ZOOKEEPER-1651](https://issues.apache.org/jira/browse/ZOOKEEPER-1651) - Add support for compressed snapshot
* [ZOOKEEPER-1748](https://issues.apache.org/jira/browse/ZOOKEEPER-1748) - TCP keepalive for leader election connections
* [ZOOKEEPER-1907](https://issues.apache.org/jira/browse/ZOOKEEPER-1907) - Improve Thread handling
* [ZOOKEEPER-1908](https://issues.apache.org/jira/browse/ZOOKEEPER-1908) - setAcl should be have a recursive function
* [ZOOKEEPER-1948](https://issues.apache.org/jira/browse/ZOOKEEPER-1948) - Enable JMX remote monitoring
* [ZOOKEEPER-1963](https://issues.apache.org/jira/browse/ZOOKEEPER-1963) - Make JDK 7 the minimum requirement for Zookeeper
* [ZOOKEEPER-1994](https://issues.apache.org/jira/browse/ZOOKEEPER-1994) - Backup config files.
* [ZOOKEEPER-2024](https://issues.apache.org/jira/browse/ZOOKEEPER-2024) - Major throughput improvement with mixed workloads
* [ZOOKEEPER-2040](https://issues.apache.org/jira/browse/ZOOKEEPER-2040) - Server to log underlying cause of SASL connection problems
* [ZOOKEEPER-2079](https://issues.apache.org/jira/browse/ZOOKEEPER-2079) - Stop daemon with &quot;kill&quot; rather than &quot;kill -9&quot;
* [ZOOKEEPER-2083](https://issues.apache.org/jira/browse/ZOOKEEPER-2083) - Remove deprecated LE implementations
* [ZOOKEEPER-2084](https://issues.apache.org/jira/browse/ZOOKEEPER-2084) - Document local session parameters
* [ZOOKEEPER-2087](https://issues.apache.org/jira/browse/ZOOKEEPER-2087) - Few UX improvements in ZooInspector
* [ZOOKEEPER-2098](https://issues.apache.org/jira/browse/ZOOKEEPER-2098) - QuorumCnxManager: use BufferedOutputStream for initial msg
* [ZOOKEEPER-2107](https://issues.apache.org/jira/browse/ZOOKEEPER-2107) - zookeeper client should support custom HostProviders
* [ZOOKEEPER-2110](https://issues.apache.org/jira/browse/ZOOKEEPER-2110) - Typo fixes in the ZK documentation
* [ZOOKEEPER-2126](https://issues.apache.org/jira/browse/ZOOKEEPER-2126) - Improve exit log messsage of EventThread and SendThread by adding SessionId
* [ZOOKEEPER-2139](https://issues.apache.org/jira/browse/ZOOKEEPER-2139) - Support multiple ZooKeeper client, with different configurations, in a single JVM
* [ZOOKEEPER-2140](https://issues.apache.org/jira/browse/ZOOKEEPER-2140) - NettyServerCnxn and NIOServerCnxn code should be improved
* [ZOOKEEPER-2149](https://issues.apache.org/jira/browse/ZOOKEEPER-2149) - Logging of client address when socket connection established
* [ZOOKEEPER-2176](https://issues.apache.org/jira/browse/ZOOKEEPER-2176) - Unclear error message should be info not error
* [ZOOKEEPER-2179](https://issues.apache.org/jira/browse/ZOOKEEPER-2179) - Typo in Watcher.java
* [ZOOKEEPER-2183](https://issues.apache.org/jira/browse/ZOOKEEPER-2183) - Concurrent Testing Processes and Port Assignments
* [ZOOKEEPER-2185](https://issues.apache.org/jira/browse/ZOOKEEPER-2185) - Run server with -XX:+HeapDumpOnOutOfMemoryError and -XX:OnOutOfMemoryError=&#39;kill %p&#39;.
* [ZOOKEEPER-2191](https://issues.apache.org/jira/browse/ZOOKEEPER-2191) - Continue supporting prior Ant versions that don&#39;t implement the threads attribute for the JUnit task.
* [ZOOKEEPER-2194](https://issues.apache.org/jira/browse/ZOOKEEPER-2194) - Let DataNode.getChildren() return an unmodifiable view of its children set
* [ZOOKEEPER-2205](https://issues.apache.org/jira/browse/ZOOKEEPER-2205) - Log type of unexpected quorum packet in learner handler loop
* [ZOOKEEPER-2206](https://issues.apache.org/jira/browse/ZOOKEEPER-2206) - Add missing packet types to LearnerHandler.packetToString()
* [ZOOKEEPER-2207](https://issues.apache.org/jira/browse/ZOOKEEPER-2207) - Enhance error logs with LearnerHandler.packetToString()
* [ZOOKEEPER-2208](https://issues.apache.org/jira/browse/ZOOKEEPER-2208) - Log type of unexpected quorum packet in observer loop
* [ZOOKEEPER-2214](https://issues.apache.org/jira/browse/ZOOKEEPER-2214) - Findbugs warning: LearnerHandler.packetToString Dead store to local variable
* [ZOOKEEPER-2223](https://issues.apache.org/jira/browse/ZOOKEEPER-2223) - support method-level JUnit testcase
* [ZOOKEEPER-2238](https://issues.apache.org/jira/browse/ZOOKEEPER-2238) - Support limiting the maximum number of connections/clients to a zookeeper server.
* [ZOOKEEPER-2240](https://issues.apache.org/jira/browse/ZOOKEEPER-2240) - Make the three-node minimum more explicit in documentation and on website
* [ZOOKEEPER-2270](https://issues.apache.org/jira/browse/ZOOKEEPER-2270) - Allow MBeanRegistry to be overridden for better unit tests
* [ZOOKEEPER-2306](https://issues.apache.org/jira/browse/ZOOKEEPER-2306) - Remove file delete duplicate  code from test code
* [ZOOKEEPER-2315](https://issues.apache.org/jira/browse/ZOOKEEPER-2315) - Change client connect zk service timeout log level from Info to Warn level
* [ZOOKEEPER-2326](https://issues.apache.org/jira/browse/ZOOKEEPER-2326) - Include connected server address:port in log
* [ZOOKEEPER-2359](https://issues.apache.org/jira/browse/ZOOKEEPER-2359) - ZooKeeper client has unnecessary logs for watcher removal errors
* [ZOOKEEPER-2368](https://issues.apache.org/jira/browse/ZOOKEEPER-2368) - Client watches are not disconnected on close
* [ZOOKEEPER-2373](https://issues.apache.org/jira/browse/ZOOKEEPER-2373) - Licenses section missing from pom file
* [ZOOKEEPER-2378](https://issues.apache.org/jira/browse/ZOOKEEPER-2378) - upgrade ivy to recent version
* [ZOOKEEPER-2392](https://issues.apache.org/jira/browse/ZOOKEEPER-2392) - Update netty to 3.7.1.Final
* [ZOOKEEPER-2402](https://issues.apache.org/jira/browse/ZOOKEEPER-2402) - Document client side properties
* [ZOOKEEPER-2410](https://issues.apache.org/jira/browse/ZOOKEEPER-2410) - add time unit to &#39;ELECTION TOOK&#39; log.info message
* [ZOOKEEPER-2433](https://issues.apache.org/jira/browse/ZOOKEEPER-2433) - ZooKeeperSaslServer: allow user principals in subject
* [ZOOKEEPER-2479](https://issues.apache.org/jira/browse/ZOOKEEPER-2479) - Add &#39;electionTimeTaken&#39; value in LeaderMXBean and FollowerMXBean
* [ZOOKEEPER-2489](https://issues.apache.org/jira/browse/ZOOKEEPER-2489) - Upgrade Jetty dependency to a recent stable release version.
* [ZOOKEEPER-2505](https://issues.apache.org/jira/browse/ZOOKEEPER-2505) - Use shared library instead of static library in C client unit test
* [ZOOKEEPER-2507](https://issues.apache.org/jira/browse/ZOOKEEPER-2507) - C unit test improvement: line break between &#39;ZooKeeper server started&#39; and &#39;Running&#39;
* [ZOOKEEPER-2511](https://issues.apache.org/jira/browse/ZOOKEEPER-2511) - Implement AutoCloseable in ZooKeeper.java
* [ZOOKEEPER-2557](https://issues.apache.org/jira/browse/ZOOKEEPER-2557) - Update gitignore to account for other file extensions
* [ZOOKEEPER-2594](https://issues.apache.org/jira/browse/ZOOKEEPER-2594) - Use TLS for downloading artifacts during build
* [ZOOKEEPER-2620](https://issues.apache.org/jira/browse/ZOOKEEPER-2620) - Add comments to testReadOnlySnapshotDir and testReadOnlyTxnLogDir indicating that the tests will fail when run as root
* [ZOOKEEPER-2630](https://issues.apache.org/jira/browse/ZOOKEEPER-2630) - Use interface type instead of implementation type when appropriate.
* [ZOOKEEPER-2632](https://issues.apache.org/jira/browse/ZOOKEEPER-2632) - Add option to inform JIRA_PASSWORD at CLI prompt 
* [ZOOKEEPER-2638](https://issues.apache.org/jira/browse/ZOOKEEPER-2638) - ZooKeeper should log which serverCnxnFactory is used during startup
* [ZOOKEEPER-2641](https://issues.apache.org/jira/browse/ZOOKEEPER-2641) - AvgRequestLatency metric improves to be more accurate
* [ZOOKEEPER-2655](https://issues.apache.org/jira/browse/ZOOKEEPER-2655) - Improve NIOServerCnxn#isZKServerRunning to reflect the semantics correctly
* [ZOOKEEPER-2662](https://issues.apache.org/jira/browse/ZOOKEEPER-2662) - Export a metric for txn log sync times
* [ZOOKEEPER-2672](https://issues.apache.org/jira/browse/ZOOKEEPER-2672) - Remove CHANGE.txt
* [ZOOKEEPER-2682](https://issues.apache.org/jira/browse/ZOOKEEPER-2682) - Make it optional to fail build on test failure
* [ZOOKEEPER-2697](https://issues.apache.org/jira/browse/ZOOKEEPER-2697) - Handle graceful stop of ZookKeeper client
* [ZOOKEEPER-2744](https://issues.apache.org/jira/browse/ZOOKEEPER-2744) - Typos in the comments of ZooKeeper class
* [ZOOKEEPER-2767](https://issues.apache.org/jira/browse/ZOOKEEPER-2767) - Correct the exception messages in X509Util if truststore location or password is not configured
* [ZOOKEEPER-2788](https://issues.apache.org/jira/browse/ZOOKEEPER-2788) - The define of MAX_CONNECTION_ATTEMPTS in QuorumCnxManager.java seems useless, should it be removed?
* [ZOOKEEPER-2815](https://issues.apache.org/jira/browse/ZOOKEEPER-2815) - 1. Using try clause to close resource; 2. Others code refactoring for PERSISTENCE module
* [ZOOKEEPER-2816](https://issues.apache.org/jira/browse/ZOOKEEPER-2816) - Code refactoring for `ZK_SERVER` module
* [ZOOKEEPER-2824](https://issues.apache.org/jira/browse/ZOOKEEPER-2824) - `FileChannel#size` info should be added to `FileTxnLog#commit` to solve the confuse that reason is too large log or too busy disk I/O
* [ZOOKEEPER-2825](https://issues.apache.org/jira/browse/ZOOKEEPER-2825) - 1. Remove unnecessary import; 2. `contains` instead of `indexOf &gt; -1` for more readable; 3. Standardize `StringBuilder#append` usage for CLIENT module
* [ZOOKEEPER-2826](https://issues.apache.org/jira/browse/ZOOKEEPER-2826) - Code refactoring for `CLI` module
* [ZOOKEEPER-2829](https://issues.apache.org/jira/browse/ZOOKEEPER-2829) - Interface usability / compatibility improvements through Java annotation.
* [ZOOKEEPER-2856](https://issues.apache.org/jira/browse/ZOOKEEPER-2856) - ZooKeeperSaslClient#respondToServer should log exception message of SaslException
* [ZOOKEEPER-2864](https://issues.apache.org/jira/browse/ZOOKEEPER-2864) - Add script to run a java api compatibility tool
* [ZOOKEEPER-2865](https://issues.apache.org/jira/browse/ZOOKEEPER-2865) - Reconfig Causes Inconsistent Configuration file among the nodes
* [ZOOKEEPER-2870](https://issues.apache.org/jira/browse/ZOOKEEPER-2870) - Improve the efficiency of AtomicFileOutputStream
* [ZOOKEEPER-2880](https://issues.apache.org/jira/browse/ZOOKEEPER-2880) - Rename README.txt to README.md
* [ZOOKEEPER-2887](https://issues.apache.org/jira/browse/ZOOKEEPER-2887) - define dependency versions in build.xml to be easily overridden in build.properties
* [ZOOKEEPER-2892](https://issues.apache.org/jira/browse/ZOOKEEPER-2892) - Improve lazy initialize and close stream for `PrepRequestProcessor`
* [ZOOKEEPER-2896](https://issues.apache.org/jira/browse/ZOOKEEPER-2896) - Remove unused imports from org.apache.zookeeper.test.CreateTest.java
* [ZOOKEEPER-2904](https://issues.apache.org/jira/browse/ZOOKEEPER-2904) - Remove unused imports from org.apache.zookeeper.server.quorum.WatchLeakTest
* [ZOOKEEPER-2915](https://issues.apache.org/jira/browse/ZOOKEEPER-2915) - Use &quot;strict&quot; conflict management in ivy
* [ZOOKEEPER-2950](https://issues.apache.org/jira/browse/ZOOKEEPER-2950) - Add keys for the Zxid from the stat command to check_zookeeper.py
* [ZOOKEEPER-2952](https://issues.apache.org/jira/browse/ZOOKEEPER-2952) - Upgrade third party libraries to address vulnerabilities
* [ZOOKEEPER-2967](https://issues.apache.org/jira/browse/ZOOKEEPER-2967) - Add check to validate dataDir and dataLogDir parameters at startup
* [ZOOKEEPER-2999](https://issues.apache.org/jira/browse/ZOOKEEPER-2999) - CMake build should use target-level commands
* [ZOOKEEPER-3012](https://issues.apache.org/jira/browse/ZOOKEEPER-3012) - Fix unit test: testDataDirAndDataLogDir should not use hardcode test folders
* [ZOOKEEPER-3019](https://issues.apache.org/jira/browse/ZOOKEEPER-3019) - Add a metric to track number of slow fsyncs
* [ZOOKEEPER-3020](https://issues.apache.org/jira/browse/ZOOKEEPER-3020) - Review of SyncRequestProcessor
* [ZOOKEEPER-3037](https://issues.apache.org/jira/browse/ZOOKEEPER-3037) - Add JvmPauseMonitor to ZooKeeper
* [ZOOKEEPER-3043](https://issues.apache.org/jira/browse/ZOOKEEPER-3043) - QuorumKerberosHostBasedAuthTest fails on Linux box: Unable to parse:includedir /etc/krb5.conf.d/
* [ZOOKEEPER-3044](https://issues.apache.org/jira/browse/ZOOKEEPER-3044) - OutOfMemoryError exceptions in Jenkins when running tests
* [ZOOKEEPER-3063](https://issues.apache.org/jira/browse/ZOOKEEPER-3063) - Track outstanding changes with ArrayDeque
* [ZOOKEEPER-3068](https://issues.apache.org/jira/browse/ZOOKEEPER-3068) - Improve C client logging of IPv6 hosts
* [ZOOKEEPER-3071](https://issues.apache.org/jira/browse/ZOOKEEPER-3071) - Add a config parameter to control transaction log size
* [ZOOKEEPER-3077](https://issues.apache.org/jira/browse/ZOOKEEPER-3077) - Build native C library outside of source directory
* [ZOOKEEPER-3078](https://issues.apache.org/jira/browse/ZOOKEEPER-3078) - Remove unused print_completion_queue function
* [ZOOKEEPER-3083](https://issues.apache.org/jira/browse/ZOOKEEPER-3083) - Remove some redundant and noisy log lines
* [ZOOKEEPER-3084](https://issues.apache.org/jira/browse/ZOOKEEPER-3084) - Exit when ZooKeeper cannot bind to the leader election port
* [ZOOKEEPER-3085](https://issues.apache.org/jira/browse/ZOOKEEPER-3085) - Define constant exit code and add documents
* [ZOOKEEPER-3094](https://issues.apache.org/jira/browse/ZOOKEEPER-3094) - Make BufferSizeTest reliable
* [ZOOKEEPER-3095](https://issues.apache.org/jira/browse/ZOOKEEPER-3095) - Connect string fix for non-existent hosts
* [ZOOKEEPER-3097](https://issues.apache.org/jira/browse/ZOOKEEPER-3097) - Use Runnable instead of Thread for working items in WorkerService to improve the throughput of CommitProcessor
* [ZOOKEEPER-3098](https://issues.apache.org/jira/browse/ZOOKEEPER-3098) - Add additional server metrics
* [ZOOKEEPER-3109](https://issues.apache.org/jira/browse/ZOOKEEPER-3109) - Avoid long unavailable time due to voter changed mind when activating the leader during election
* [ZOOKEEPER-3110](https://issues.apache.org/jira/browse/ZOOKEEPER-3110) - Improve the closeSession throughput in PrepRequestProcessor
* [ZOOKEEPER-3116](https://issues.apache.org/jira/browse/ZOOKEEPER-3116) - Make the DataTree.approximateDataSize more efficient
* [ZOOKEEPER-3124](https://issues.apache.org/jira/browse/ZOOKEEPER-3124) - Add the correct comment to show why we need the special logic to handle cversion and pzxid
* [ZOOKEEPER-3142](https://issues.apache.org/jira/browse/ZOOKEEPER-3142) - Extend SnapshotFormatter to dump data in json format
* [ZOOKEEPER-3146](https://issues.apache.org/jira/browse/ZOOKEEPER-3146) - Limit the maximum client connections per IP in NettyServerCnxnFactory
* [ZOOKEEPER-3152](https://issues.apache.org/jira/browse/ZOOKEEPER-3152) - Port ZK netty stack to netty 4
* [ZOOKEEPER-3159](https://issues.apache.org/jira/browse/ZOOKEEPER-3159) - Flaky: ClientRequestTimeoutTest.testClientRequestTimeout
* [ZOOKEEPER-3161](https://issues.apache.org/jira/browse/ZOOKEEPER-3161) - Refactor QuorumPeerMainTest.java: move commonly used functions to base class
* [ZOOKEEPER-3163](https://issues.apache.org/jira/browse/ZOOKEEPER-3163) - Use session map to improve the performance when closing session in Netty
* [ZOOKEEPER-3177](https://issues.apache.org/jira/browse/ZOOKEEPER-3177) - Refactor request throttle logic in NIO and Netty to keep the same behavior and make the code easier to maintain
* [ZOOKEEPER-3179](https://issues.apache.org/jira/browse/ZOOKEEPER-3179) - Add snapshot compression to reduce the disk IO
* [ZOOKEEPER-3180](https://issues.apache.org/jira/browse/ZOOKEEPER-3180) - Add response cache to improve the throughput of read heavy traffic 
* [ZOOKEEPER-3183](https://issues.apache.org/jira/browse/ZOOKEEPER-3183) - Interrupting or  notifying the WatcherCleaner  thread during shutdown if it is waiting for dead watchers get certain number(watcherCleanThreshold) and also stop adding incoming  deadWatcher to deadWatchersList when shutdown is initiated.
* [ZOOKEEPER-3188](https://issues.apache.org/jira/browse/ZOOKEEPER-3188) - Improve resilience to network
* [ZOOKEEPER-3190](https://issues.apache.org/jira/browse/ZOOKEEPER-3190) - Spell check on the Zookeeper server files
* [ZOOKEEPER-3195](https://issues.apache.org/jira/browse/ZOOKEEPER-3195) - TLS - disable client-initiated renegotiation
* [ZOOKEEPER-3203](https://issues.apache.org/jira/browse/ZOOKEEPER-3203) - Tracking and exposing the non voting followers in ZK
* [ZOOKEEPER-3208](https://issues.apache.org/jira/browse/ZOOKEEPER-3208) - Remove the SSLTest.java.orig introduced in ZOOKEEPER-3032
* [ZOOKEEPER-3216](https://issues.apache.org/jira/browse/ZOOKEEPER-3216) - Make init/sync limit tunable via JMX
* [ZOOKEEPER-3219](https://issues.apache.org/jira/browse/ZOOKEEPER-3219) - Fix flaky FileChangeWatcherTest
* [ZOOKEEPER-3228](https://issues.apache.org/jira/browse/ZOOKEEPER-3228) - [TLS] Fix key usage extension in test certs
* [ZOOKEEPER-3232](https://issues.apache.org/jira/browse/ZOOKEEPER-3232) - make the log of notification about LE more readable
* [ZOOKEEPER-3234](https://issues.apache.org/jira/browse/ZOOKEEPER-3234) - Add Travis-CI configuration file
* [ZOOKEEPER-3235](https://issues.apache.org/jira/browse/ZOOKEEPER-3235) - Enable secure processing and disallow DTDs in the SAXParserFactory
* [ZOOKEEPER-3236](https://issues.apache.org/jira/browse/ZOOKEEPER-3236) - Upgrade BouncyCastle
* [ZOOKEEPER-3237](https://issues.apache.org/jira/browse/ZOOKEEPER-3237) - Allow IPv6 wildcard address in peer config
* [ZOOKEEPER-3238](https://issues.apache.org/jira/browse/ZOOKEEPER-3238) - Add rel=&quot;noopener noreferrer&quot; to target blank link in zookeeper-contrib-huebrowser
* [ZOOKEEPER-3239](https://issues.apache.org/jira/browse/ZOOKEEPER-3239) - Adding EnsembleAuthProvider to verify the ensemble name
* [ZOOKEEPER-3240](https://issues.apache.org/jira/browse/ZOOKEEPER-3240) - Close socket on Learner shutdown to avoid dangling socket
* [ZOOKEEPER-3242](https://issues.apache.org/jira/browse/ZOOKEEPER-3242) - Add server side connecting throttling
* [ZOOKEEPER-3243](https://issues.apache.org/jira/browse/ZOOKEEPER-3243) - Add server side request throttling
* [ZOOKEEPER-3245](https://issues.apache.org/jira/browse/ZOOKEEPER-3245) - Add useful metrics for ZK pipeline and request/server states
* [ZOOKEEPER-3249](https://issues.apache.org/jira/browse/ZOOKEEPER-3249) - Avoid reverting the cversion and pzxid during replaying txns with fuzzy snapshot
* [ZOOKEEPER-3250](https://issues.apache.org/jira/browse/ZOOKEEPER-3250) - typo in doc - zookeeperInternals
* [ZOOKEEPER-3255](https://issues.apache.org/jira/browse/ZOOKEEPER-3255) - add a banner to make the startup of zk server more cool
* [ZOOKEEPER-3257](https://issues.apache.org/jira/browse/ZOOKEEPER-3257) - Merge count and byte update of Stat
* [ZOOKEEPER-3262](https://issues.apache.org/jira/browse/ZOOKEEPER-3262) - Update dependencies flagged by OWASP report
* [ZOOKEEPER-3263](https://issues.apache.org/jira/browse/ZOOKEEPER-3263) - Illegal reflective access in zookeer&#39;s kerberosUtil
* [ZOOKEEPER-3272](https://issues.apache.org/jira/browse/ZOOKEEPER-3272) - Clean up netty4 code per Norman Maurer&#39;s review comments
* [ZOOKEEPER-3273](https://issues.apache.org/jira/browse/ZOOKEEPER-3273) - Sync BouncyCastle version in Maven build and Ant  build
* [ZOOKEEPER-3274](https://issues.apache.org/jira/browse/ZOOKEEPER-3274) - Use CompositeByteBuf to queue data in NettyServerCnxn
* [ZOOKEEPER-3276](https://issues.apache.org/jira/browse/ZOOKEEPER-3276) - Make X509UtilTest.testCreateSSLServerSocketWithPort less flaky
* [ZOOKEEPER-3277](https://issues.apache.org/jira/browse/ZOOKEEPER-3277) - Add trace listener in NettyServerCnxnFactory only if trace logging is enabled
* [ZOOKEEPER-3291](https://issues.apache.org/jira/browse/ZOOKEEPER-3291) - improve error message when JAVA_HOME is set to the wrong value
* [ZOOKEEPER-3312](https://issues.apache.org/jira/browse/ZOOKEEPER-3312) - Upgrade Jetty to 9.4.15.v20190215
* [ZOOKEEPER-3314](https://issues.apache.org/jira/browse/ZOOKEEPER-3314) - Document the possibility of MultiCallback receiving a null pointer
* [ZOOKEEPER-3332](https://issues.apache.org/jira/browse/ZOOKEEPER-3332) - TxnLogToolkit should print multi transactions readably
* [ZOOKEEPER-3335](https://issues.apache.org/jira/browse/ZOOKEEPER-3335) - Improve the usage of Collections
* [ZOOKEEPER-3339](https://issues.apache.org/jira/browse/ZOOKEEPER-3339) - Improve Debug and Trace Log Statements
* [ZOOKEEPER-3340](https://issues.apache.org/jira/browse/ZOOKEEPER-3340) - Introduce CircularBlockingQueue in QuorumCnxManager.java
* [ZOOKEEPER-3341](https://issues.apache.org/jira/browse/ZOOKEEPER-3341) - Remove Superfluous ByteBuffer Duplicate
* [ZOOKEEPER-3347](https://issues.apache.org/jira/browse/ZOOKEEPER-3347) - Improve PathTrie Consistency 
* [ZOOKEEPER-3348](https://issues.apache.org/jira/browse/ZOOKEEPER-3348) - Make TxnLog and TxnLog Iterator Closable
* [ZOOKEEPER-3350](https://issues.apache.org/jira/browse/ZOOKEEPER-3350) - Get rid of CommonNames
* [ZOOKEEPER-3351](https://issues.apache.org/jira/browse/ZOOKEEPER-3351) - Migrate qa-test-pullrequest ant task to maven
* [ZOOKEEPER-3353](https://issues.apache.org/jira/browse/ZOOKEEPER-3353) - Admin commands for showing initial settings
* [ZOOKEEPER-3354](https://issues.apache.org/jira/browse/ZOOKEEPER-3354) - Improve efficiency of DeleteAllCommand
* [ZOOKEEPER-3359](https://issues.apache.org/jira/browse/ZOOKEEPER-3359) - Batch commits in the CommitProcessor
* [ZOOKEEPER-3360](https://issues.apache.org/jira/browse/ZOOKEEPER-3360) - Misprint in WriteLock javadoc
* [ZOOKEEPER-3364](https://issues.apache.org/jira/browse/ZOOKEEPER-3364) - Compile with strict options in order to check code quality
* [ZOOKEEPER-3365](https://issues.apache.org/jira/browse/ZOOKEEPER-3365) - Use Concurrent HashMap in NettyServerCnxnFactory 
* [ZOOKEEPER-3369](https://issues.apache.org/jira/browse/ZOOKEEPER-3369) - Maven release artifacts cleanup
* [ZOOKEEPER-3370](https://issues.apache.org/jira/browse/ZOOKEEPER-3370) - Remove SVN specific revision generation
* [ZOOKEEPER-3372](https://issues.apache.org/jira/browse/ZOOKEEPER-3372) - Cleanup pom.xml in order to let Maven clients import as few dependencies as possible
* [ZOOKEEPER-3378](https://issues.apache.org/jira/browse/ZOOKEEPER-3378) - Set the quorum cnxn timeout independently from syncLimit
* [ZOOKEEPER-3382](https://issues.apache.org/jira/browse/ZOOKEEPER-3382) - Update Documentation: If you only have one storage device
* [ZOOKEEPER-3385](https://issues.apache.org/jira/browse/ZOOKEEPER-3385) - Add admin command to display leader
* [ZOOKEEPER-3386](https://issues.apache.org/jira/browse/ZOOKEEPER-3386) - Add admin command to display voting view
* [ZOOKEEPER-3388](https://issues.apache.org/jira/browse/ZOOKEEPER-3388) - Allow client port to support plaintext and encrypted connections simultaneously
* [ZOOKEEPER-3391](https://issues.apache.org/jira/browse/ZOOKEEPER-3391) - Drop unused CSVInputArchive and XMLInputArchive
* [ZOOKEEPER-3392](https://issues.apache.org/jira/browse/ZOOKEEPER-3392) - Add admin command to display last snapshot information
* [ZOOKEEPER-3394](https://issues.apache.org/jira/browse/ZOOKEEPER-3394) - Delay observer reconnect when all learner masters have been tried
* [ZOOKEEPER-3395](https://issues.apache.org/jira/browse/ZOOKEEPER-3395) - Document individual admin commands in markdown
* [ZOOKEEPER-3396](https://issues.apache.org/jira/browse/ZOOKEEPER-3396) - Flaky test in RestoreCommittedLogTest
* [ZOOKEEPER-3398](https://issues.apache.org/jira/browse/ZOOKEEPER-3398) - Learner.connectToLeader() may take too long to time-out 
* [ZOOKEEPER-3400](https://issues.apache.org/jira/browse/ZOOKEEPER-3400) - Add documentation on local sessions
* [ZOOKEEPER-3402](https://issues.apache.org/jira/browse/ZOOKEEPER-3402) - Add a multiRead operation
* [ZOOKEEPER-3411](https://issues.apache.org/jira/browse/ZOOKEEPER-3411) - remove the deprecated CLI: ls2 and rmr
* [ZOOKEEPER-3416](https://issues.apache.org/jira/browse/ZOOKEEPER-3416) - Remove redundant ServerCnxnFactoryAccessor
* [ZOOKEEPER-3418](https://issues.apache.org/jira/browse/ZOOKEEPER-3418) - Improve quorum throughput through eager ACL checks of requests on local servers
* [ZOOKEEPER-3423](https://issues.apache.org/jira/browse/ZOOKEEPER-3423) - use the maven-like way to ignore the generated version java files and doc the cmd:&#39;./zkServer.sh version&#39;
* [ZOOKEEPER-3430](https://issues.apache.org/jira/browse/ZOOKEEPER-3430) - Observability improvement: provide top N read / write path queries
* [ZOOKEEPER-3436](https://issues.apache.org/jira/browse/ZOOKEEPER-3436) - Enhance Mavenized Make C client 
* [ZOOKEEPER-3437](https://issues.apache.org/jira/browse/ZOOKEEPER-3437) - Improve sync throttling on a learner master
* [ZOOKEEPER-3439](https://issues.apache.org/jira/browse/ZOOKEEPER-3439) - Observability improvements on client / server connection close
* [ZOOKEEPER-3448](https://issues.apache.org/jira/browse/ZOOKEEPER-3448) - Introduce MessageTracker to assist debug leader and leaner connectivity issues
* [ZOOKEEPER-3453](https://issues.apache.org/jira/browse/ZOOKEEPER-3453) - missing &#39;SET&#39; in zkCli on windows
* [ZOOKEEPER-3457](https://issues.apache.org/jira/browse/ZOOKEEPER-3457) - Code optimization in QuorumCnxManager
* [ZOOKEEPER-3459](https://issues.apache.org/jira/browse/ZOOKEEPER-3459) - Add admin command to display synced state of peer
* [ZOOKEEPER-3472](https://issues.apache.org/jira/browse/ZOOKEEPER-3472) - Treat check request as a write request which needs to wait for the check txn commit from leader
* [ZOOKEEPER-3473](https://issues.apache.org/jira/browse/ZOOKEEPER-3473) - Improving successful TLS handshake throughput with concurrent control
* [ZOOKEEPER-3484](https://issues.apache.org/jira/browse/ZOOKEEPER-3484) - Improve the throughput by optimizing the synchronization around outstandingChanges
* [ZOOKEEPER-3491](https://issues.apache.org/jira/browse/ZOOKEEPER-3491) - Specify commitLogCount value using a system property
* [ZOOKEEPER-3492](https://issues.apache.org/jira/browse/ZOOKEEPER-3492) - Add weights to server side connection throttling
* [ZOOKEEPER-3494](https://issues.apache.org/jira/browse/ZOOKEEPER-3494) - No need to depend on netty-all (SSL)
* [ZOOKEEPER-3501](https://issues.apache.org/jira/browse/ZOOKEEPER-3501) - unify the method:op2String()
* [ZOOKEEPER-3502](https://issues.apache.org/jira/browse/ZOOKEEPER-3502) - improve the server command: zabstate to have a better observation on the process of leader election
* [ZOOKEEPER-3503](https://issues.apache.org/jira/browse/ZOOKEEPER-3503) - Add server side large request throttling
* [ZOOKEEPER-3506](https://issues.apache.org/jira/browse/ZOOKEEPER-3506) - correct the SessionTrackerImpl#initializeNextSession&#39;s javaDoc about how to generate the sessionId
* [ZOOKEEPER-3509](https://issues.apache.org/jira/browse/ZOOKEEPER-3509) - Revisit log format
* [ZOOKEEPER-3519](https://issues.apache.org/jira/browse/ZOOKEEPER-3519) - upgrade dependency-check to 5.2.1
* [ZOOKEEPER-3522](https://issues.apache.org/jira/browse/ZOOKEEPER-3522) - Consistency guarantees discussion.
* [ZOOKEEPER-3523](https://issues.apache.org/jira/browse/ZOOKEEPER-3523) - Replace dummy watcher with a unified singleton
* [ZOOKEEPER-3525](https://issues.apache.org/jira/browse/ZOOKEEPER-3525) - Add project status badges to README
* [ZOOKEEPER-3530](https://issues.apache.org/jira/browse/ZOOKEEPER-3530) - Include compiled C-client in the binary tarball
* [ZOOKEEPER-3532](https://issues.apache.org/jira/browse/ZOOKEEPER-3532) - Provide a docker-based environment to work on a known OS
* [ZOOKEEPER-3537](https://issues.apache.org/jira/browse/ZOOKEEPER-3537) - Leader election - Use of out of election messages
* [ZOOKEEPER-3548](https://issues.apache.org/jira/browse/ZOOKEEPER-3548) - Redundant zxid check in SnapStream.isValidSnapshot
* [ZOOKEEPER-3560](https://issues.apache.org/jira/browse/ZOOKEEPER-3560) - Add response cache to serve get children (2) requests.
* [ZOOKEEPER-3570](https://issues.apache.org/jira/browse/ZOOKEEPER-3570) - make the special client xid constant
* [ZOOKEEPER-3571](https://issues.apache.org/jira/browse/ZOOKEEPER-3571) - Create test base directory on test started
* [ZOOKEEPER-3593](https://issues.apache.org/jira/browse/ZOOKEEPER-3593) - fix the default value of jute.maxbuffer in client side and an optimization for the documentation
* [ZOOKEEPER-3595](https://issues.apache.org/jira/browse/ZOOKEEPER-3595) - Fsync parameter for serialize method is ingnored
* [ZOOKEEPER-3599](https://issues.apache.org/jira/browse/ZOOKEEPER-3599) - cli.c: Resuscitate &quot;old-style&quot; argument parsing
* [ZOOKEEPER-3606](https://issues.apache.org/jira/browse/ZOOKEEPER-3606) - add JMXHOSTNAME to zkServer.sh to enable user to change the exposed hostname of jmx service
* [ZOOKEEPER-3620](https://issues.apache.org/jira/browse/ZOOKEEPER-3620) - Allow to override calls to System.exit in server side code
* [ZOOKEEPER-3630](https://issues.apache.org/jira/browse/ZOOKEEPER-3630) - Autodetection of SSL library during Zookeeper C client build
* [ZOOKEEPER-3636](https://issues.apache.org/jira/browse/ZOOKEEPER-3636) - find back the missing configuration property in the zookeeperAdmin page when moving from xml to markdown
* [ZOOKEEPER-3638](https://issues.apache.org/jira/browse/ZOOKEEPER-3638) - Update Jetty to 9.4.24.v20191120
* [ZOOKEEPER-3640](https://issues.apache.org/jira/browse/ZOOKEEPER-3640) - Implement &quot;batch mode&quot; in cli_mt
* [ZOOKEEPER-3648](https://issues.apache.org/jira/browse/ZOOKEEPER-3648) - remove Hadoop logo in the ZooKeeper documentation
* [ZOOKEEPER-3649](https://issues.apache.org/jira/browse/ZOOKEEPER-3649) - ls -s CLI need a line break
    
        
## Bug

* [ZOOKEEPER-3231](https://issues.apache.org/jira/browse/ZOOKEEPER-3231) - Purge task may lost data when the recent snapshots are all invalid
* [ZOOKEEPER-3720](https://issues.apache.org/jira/browse/ZOOKEEPER-3720) - Fix rolling upgrade failure (invalid protocol version)
* [ZOOKEEPER-3677](https://issues.apache.org/jira/browse/ZOOKEEPER-3677) - Setting jute.maxbuffer value in hexadecimal throws Exception
* [ZOOKEEPER-3695](https://issues.apache.org/jira/browse/ZOOKEEPER-3695) - Source release tarball does not match repository in 3.6.0
* [ZOOKEEPER-3667](https://issues.apache.org/jira/browse/ZOOKEEPER-3667) - owasp checker failing for - CVE-2019-17571 Apache Log4j 1.2 deserialization of untrusted data in SocketServer
* [ZOOKEEPER-3613](https://issues.apache.org/jira/browse/ZOOKEEPER-3613) - ZKConfig fails to return proper value on getBoolean()when user accidentally includes spaces at the end of the value
* [ZOOKEEPER-3699](https://issues.apache.org/jira/browse/ZOOKEEPER-3699) - upgrade jackson-databind to address CVE-2019-20330
* [ZOOKEEPER-3698](https://issues.apache.org/jira/browse/ZOOKEEPER-3698) - fixing NoRouteToHostException when starting large cluster locally
* [ZOOKEEPER-1936](https://issues.apache.org/jira/browse/ZOOKEEPER-1936) - Server exits when unable to create data directory due to race
* [ZOOKEEPER-3701](https://issues.apache.org/jira/browse/ZOOKEEPER-3701) - Split brain on log disk full
* [ZOOKEEPER-1105](https://issues.apache.org/jira/browse/ZOOKEEPER-1105) - wait for server response in C client zookeeper_close
* [ZOOKEEPER-706](https://issues.apache.org/jira/browse/ZOOKEEPER-706) - large numbers of watches can cause session re-establishment to fail
* [ZOOKEEPER-1029](https://issues.apache.org/jira/browse/ZOOKEEPER-1029) - C client bug in zookeeper_init (if bad hostname is given)
* [ZOOKEEPER-1077](https://issues.apache.org/jira/browse/ZOOKEEPER-1077) - C client lib doesn&#39;t build on Solaris
* [ZOOKEEPER-1256](https://issues.apache.org/jira/browse/ZOOKEEPER-1256) - ClientPortBindTest is failing on Mac OS X
* [ZOOKEEPER-1366](https://issues.apache.org/jira/browse/ZOOKEEPER-1366) - Zookeeper should be tolerant of clock adjustments
* [ZOOKEEPER-1371](https://issues.apache.org/jira/browse/ZOOKEEPER-1371) - Remove dependency on log4j in the source code.
* [ZOOKEEPER-1392](https://issues.apache.org/jira/browse/ZOOKEEPER-1392) - Should not allow to read ACL when not authorized to read node
* [ZOOKEEPER-1460](https://issues.apache.org/jira/browse/ZOOKEEPER-1460) - IPv6 literal address not supported for quorum members
* [ZOOKEEPER-1580](https://issues.apache.org/jira/browse/ZOOKEEPER-1580) - QuorumPeer.setRunning is not used
* [ZOOKEEPER-1636](https://issues.apache.org/jira/browse/ZOOKEEPER-1636) - c-client crash when zoo_amulti failed 
* [ZOOKEEPER-1782](https://issues.apache.org/jira/browse/ZOOKEEPER-1782) - zookeeper.superUser is not as super as superDigest
* [ZOOKEEPER-1803](https://issues.apache.org/jira/browse/ZOOKEEPER-1803) - Add description for pzxid in programmer&#39;s guide.
* [ZOOKEEPER-1807](https://issues.apache.org/jira/browse/ZOOKEEPER-1807) - Observers spam each other creating connections to the election addr
* [ZOOKEEPER-1818](https://issues.apache.org/jira/browse/ZOOKEEPER-1818) - Fix don&#39;t care for trunk
* [ZOOKEEPER-1823](https://issues.apache.org/jira/browse/ZOOKEEPER-1823) - zkTxnLogToolkit -dump should support printing transaction data as a string
* [ZOOKEEPER-1853](https://issues.apache.org/jira/browse/ZOOKEEPER-1853) - zkCli.sh can&#39;t issue a CREATE command containing spaces in the data
* [ZOOKEEPER-1893](https://issues.apache.org/jira/browse/ZOOKEEPER-1893) - automake: use serial-tests option
* [ZOOKEEPER-1898](https://issues.apache.org/jira/browse/ZOOKEEPER-1898) - ZooKeeper Java cli shell always returns &quot;0&quot; as exit code
* [ZOOKEEPER-1917](https://issues.apache.org/jira/browse/ZOOKEEPER-1917) - Apache Zookeeper logs cleartext admin passwords
* [ZOOKEEPER-1919](https://issues.apache.org/jira/browse/ZOOKEEPER-1919) - Update the C implementation of removeWatches to have it match ZOOKEEPER-1910
* [ZOOKEEPER-1927](https://issues.apache.org/jira/browse/ZOOKEEPER-1927) - zkServer.sh fails to read dataDir (and others) from zoo.cfg on Solaris 10 (grep issue, manifests as FAILED TO WRITE PID).  
* [ZOOKEEPER-1932](https://issues.apache.org/jira/browse/ZOOKEEPER-1932) - Remove deprecated LeaderElection class
* [ZOOKEEPER-1949](https://issues.apache.org/jira/browse/ZOOKEEPER-1949) - recipes jar not included in the distribution package
* [ZOOKEEPER-1952](https://issues.apache.org/jira/browse/ZOOKEEPER-1952) - Default log directory and file name can be changed
* [ZOOKEEPER-1990](https://issues.apache.org/jira/browse/ZOOKEEPER-1990) - suspicious instantiation of java Random instances
* [ZOOKEEPER-1991](https://issues.apache.org/jira/browse/ZOOKEEPER-1991) - zkServer.sh returns with a zero exit status when a ZooKeeper process is already running
* [ZOOKEEPER-2006](https://issues.apache.org/jira/browse/ZOOKEEPER-2006) - Standalone mode won&#39;t take client port from dynamic config
* [ZOOKEEPER-2008](https://issues.apache.org/jira/browse/ZOOKEEPER-2008) - System test fails due to missing leader election port
* [ZOOKEEPER-2013](https://issues.apache.org/jira/browse/ZOOKEEPER-2013) - typos in zookeeperProgrammers
* [ZOOKEEPER-2014](https://issues.apache.org/jira/browse/ZOOKEEPER-2014) - Only admin should be allowed to reconfig a cluster
* [ZOOKEEPER-2026](https://issues.apache.org/jira/browse/ZOOKEEPER-2026) - Startup order in ServerCnxnFactory-ies is wrong
* [ZOOKEEPER-2029](https://issues.apache.org/jira/browse/ZOOKEEPER-2029) - Leader.LearnerCnxAcceptor should handle exceptions in run()
* [ZOOKEEPER-2030](https://issues.apache.org/jira/browse/ZOOKEEPER-2030) - dynamicConfigFile should have an absolute path, not a relative path, to the dynamic configuration file
* [ZOOKEEPER-2049](https://issues.apache.org/jira/browse/ZOOKEEPER-2049) - Yosemite build failure: htonll conflict
* [ZOOKEEPER-2052](https://issues.apache.org/jira/browse/ZOOKEEPER-2052) - Unable to delete a node when the node has no children
* [ZOOKEEPER-2054](https://issues.apache.org/jira/browse/ZOOKEEPER-2054) - test-patch.sh: don&#39;t set ulimit -n
* [ZOOKEEPER-2056](https://issues.apache.org/jira/browse/ZOOKEEPER-2056) - Zookeeper 3.4.x and 3.5.0-alpha is not OSGi compliant
* [ZOOKEEPER-2058](https://issues.apache.org/jira/browse/ZOOKEEPER-2058) - rat: exclude *.cer files
* [ZOOKEEPER-2060](https://issues.apache.org/jira/browse/ZOOKEEPER-2060) - Trace bug in NettyServerCnxnFactory
* [ZOOKEEPER-2062](https://issues.apache.org/jira/browse/ZOOKEEPER-2062) - RemoveWatchesTest takes forever to run
* [ZOOKEEPER-2064](https://issues.apache.org/jira/browse/ZOOKEEPER-2064) - Prevent resource leak in various classes
* [ZOOKEEPER-2072](https://issues.apache.org/jira/browse/ZOOKEEPER-2072) - Netty Server Should Configure Child Channel Pipeline By Specifying ChannelPipelineFactory
* [ZOOKEEPER-2073](https://issues.apache.org/jira/browse/ZOOKEEPER-2073) - Memory leak on zookeeper_close
* [ZOOKEEPER-2074](https://issues.apache.org/jira/browse/ZOOKEEPER-2074) - Incorrect exit codes for &quot;./zkCli.sh cmd arg&quot;
* [ZOOKEEPER-2096](https://issues.apache.org/jira/browse/ZOOKEEPER-2096) - C client builds with incorrect error codes in VisualStudio 2010+
* [ZOOKEEPER-2109](https://issues.apache.org/jira/browse/ZOOKEEPER-2109) - Typo in src/c/src/load_gen.c
* [ZOOKEEPER-2111](https://issues.apache.org/jira/browse/ZOOKEEPER-2111) - Not isAlive states should be synchronized in ClientCnxn
* [ZOOKEEPER-2114](https://issues.apache.org/jira/browse/ZOOKEEPER-2114) - jute generated allocate_* functions are not externally visible
* [ZOOKEEPER-2116](https://issues.apache.org/jira/browse/ZOOKEEPER-2116) - zkCli.sh doesn&#39;t honor host:port parameter
* [ZOOKEEPER-2124](https://issues.apache.org/jira/browse/ZOOKEEPER-2124) - Allow Zookeeper version string to have underscore &#39;_&#39;
* [ZOOKEEPER-2133](https://issues.apache.org/jira/browse/ZOOKEEPER-2133) - zkperl: Segmentation fault if getting a node with null value
* [ZOOKEEPER-2142](https://issues.apache.org/jira/browse/ZOOKEEPER-2142) - JMX ObjectName is incorrect for observers
* [ZOOKEEPER-2146](https://issues.apache.org/jira/browse/ZOOKEEPER-2146) - BinaryInputArchive readString should check length before allocating memory
* [ZOOKEEPER-2156](https://issues.apache.org/jira/browse/ZOOKEEPER-2156) - If JAVA_HOME is not set zk startup and fetching status command execution result misleads user.
* [ZOOKEEPER-2157](https://issues.apache.org/jira/browse/ZOOKEEPER-2157) - Upgrade option should be removed from zkServer.sh usage
* [ZOOKEEPER-2171](https://issues.apache.org/jira/browse/ZOOKEEPER-2171) - avoid reverse lookups in QuorumCnxManager
* [ZOOKEEPER-2172](https://issues.apache.org/jira/browse/ZOOKEEPER-2172) - Cluster crashes when reconfig a new node as a participant
* [ZOOKEEPER-2173](https://issues.apache.org/jira/browse/ZOOKEEPER-2173) - ZK startup failure should be handled with proper error message
* [ZOOKEEPER-2174](https://issues.apache.org/jira/browse/ZOOKEEPER-2174) - JUnit4ZKTestRunner logs test failure for all exceptions even if the test method is annotated with an expected exception.
* [ZOOKEEPER-2178](https://issues.apache.org/jira/browse/ZOOKEEPER-2178) - Native client fails compilation on Windows.
* [ZOOKEEPER-2182](https://issues.apache.org/jira/browse/ZOOKEEPER-2182) - Several test suites are not running during pre-commit, because their names do not end with &quot;Test&quot;.
* [ZOOKEEPER-2184](https://issues.apache.org/jira/browse/ZOOKEEPER-2184) - Zookeeper Client should re-resolve hosts when connection attempts fail
* [ZOOKEEPER-2186](https://issues.apache.org/jira/browse/ZOOKEEPER-2186) - QuorumCnxManager#receiveConnection may crash with random input
* [ZOOKEEPER-2187](https://issues.apache.org/jira/browse/ZOOKEEPER-2187) - remove duplicated code between CreateRequest{,2}
* [ZOOKEEPER-2190](https://issues.apache.org/jira/browse/ZOOKEEPER-2190) - In StandaloneDisabledTest, testReconfig() shouldn&#39;t take leaving servers as joining servers
* [ZOOKEEPER-2193](https://issues.apache.org/jira/browse/ZOOKEEPER-2193) - reconfig command completes even if parameter is wrong obviously
* [ZOOKEEPER-2195](https://issues.apache.org/jira/browse/ZOOKEEPER-2195) - fsync.warningthresholdms in zoo.cfg not working
* [ZOOKEEPER-2197](https://issues.apache.org/jira/browse/ZOOKEEPER-2197) - non-ascii character in FinalRequestProcessor.java
* [ZOOKEEPER-2198](https://issues.apache.org/jira/browse/ZOOKEEPER-2198) - Set default test.junit.threads to 1.
* [ZOOKEEPER-2201](https://issues.apache.org/jira/browse/ZOOKEEPER-2201) - Network issues can cause cluster to hang due to near-deadlock
* [ZOOKEEPER-2210](https://issues.apache.org/jira/browse/ZOOKEEPER-2210) - clock_gettime is not available in os x
* [ZOOKEEPER-2211](https://issues.apache.org/jira/browse/ZOOKEEPER-2211) - PurgeTxnLog does not correctly purge when snapshots and logs are at different locations
* [ZOOKEEPER-2212](https://issues.apache.org/jira/browse/ZOOKEEPER-2212) - distributed race condition related to QV version
* [ZOOKEEPER-2213](https://issues.apache.org/jira/browse/ZOOKEEPER-2213) - Empty path in Set crashes server and prevents restart
* [ZOOKEEPER-2221](https://issues.apache.org/jira/browse/ZOOKEEPER-2221) - Zookeeper JettyAdminServer server should start on configured IP.
* [ZOOKEEPER-2224](https://issues.apache.org/jira/browse/ZOOKEEPER-2224) - Four letter command hangs when network is slow
* [ZOOKEEPER-2227](https://issues.apache.org/jira/browse/ZOOKEEPER-2227) - stmk four-letter word fails execution at server while reading trace mask argument.
* [ZOOKEEPER-2229](https://issues.apache.org/jira/browse/ZOOKEEPER-2229) - Several four-letter words are undocumented.
* [ZOOKEEPER-2235](https://issues.apache.org/jira/browse/ZOOKEEPER-2235) - License update
* [ZOOKEEPER-2239](https://issues.apache.org/jira/browse/ZOOKEEPER-2239) - JMX State from LocalPeerBean incorrect
* [ZOOKEEPER-2243](https://issues.apache.org/jira/browse/ZOOKEEPER-2243) - Supported platforms is completely out of date
* [ZOOKEEPER-2244](https://issues.apache.org/jira/browse/ZOOKEEPER-2244) - On Windows zookeeper fails to restart
* [ZOOKEEPER-2245](https://issues.apache.org/jira/browse/ZOOKEEPER-2245) - SimpleSysTest test cases fails
* [ZOOKEEPER-2247](https://issues.apache.org/jira/browse/ZOOKEEPER-2247) - Zookeeper service becomes unavailable when leader fails to write transaction log
* [ZOOKEEPER-2249](https://issues.apache.org/jira/browse/ZOOKEEPER-2249) - CRC check failed when preAllocSize smaller than node data
* [ZOOKEEPER-2251](https://issues.apache.org/jira/browse/ZOOKEEPER-2251) - Add Client side packet response timeout to avoid infinite wait.
* [ZOOKEEPER-2252](https://issues.apache.org/jira/browse/ZOOKEEPER-2252) - Random test case failure in org.apache.zookeeper.test.StaticHostProviderTest
* [ZOOKEEPER-2256](https://issues.apache.org/jira/browse/ZOOKEEPER-2256) - Zookeeper is not using specified JMX port in zkEnv.sh
* [ZOOKEEPER-2261](https://issues.apache.org/jira/browse/ZOOKEEPER-2261) - When only secureClientPort is configured connections, configuration, connection_stat_reset, and stats admin commands throw NullPointerException
* [ZOOKEEPER-2264](https://issues.apache.org/jira/browse/ZOOKEEPER-2264) - Wrong error message when secureClientPortAddress is configured but secureClientPort is not configured 
* [ZOOKEEPER-2269](https://issues.apache.org/jira/browse/ZOOKEEPER-2269) - NullPointerException  in RemotePeerBean
* [ZOOKEEPER-2279](https://issues.apache.org/jira/browse/ZOOKEEPER-2279) - QuorumPeer  loadDataBase() error message is incorrect
* [ZOOKEEPER-2281](https://issues.apache.org/jira/browse/ZOOKEEPER-2281) - ZK Server startup fails if there are spaces in the JAVA_HOME path
* [ZOOKEEPER-2282](https://issues.apache.org/jira/browse/ZOOKEEPER-2282) - chroot not stripped from path in asynchronous callbacks
* [ZOOKEEPER-2283](https://issues.apache.org/jira/browse/ZOOKEEPER-2283) - traceFile property is not used in the ZooKeeper,  it should be removed from documentation
* [ZOOKEEPER-2284](https://issues.apache.org/jira/browse/ZOOKEEPER-2284) - LogFormatter and SnapshotFormatter does not handle FileNotFoundException gracefully
* [ZOOKEEPER-2294](https://issues.apache.org/jira/browse/ZOOKEEPER-2294) - Ant target generate-clover-reports is broken
* [ZOOKEEPER-2295](https://issues.apache.org/jira/browse/ZOOKEEPER-2295) - TGT refresh time logic is wrong
* [ZOOKEEPER-2297](https://issues.apache.org/jira/browse/ZOOKEEPER-2297) - NPE is thrown while creating &quot;key manager&quot; and &quot;trust manager&quot; 
* [ZOOKEEPER-2299](https://issues.apache.org/jira/browse/ZOOKEEPER-2299) - NullPointerException in LocalPeerBean for ClientAddress
* [ZOOKEEPER-2302](https://issues.apache.org/jira/browse/ZOOKEEPER-2302) - Some test cases are not running because wrongly named
* [ZOOKEEPER-2307](https://issues.apache.org/jira/browse/ZOOKEEPER-2307) - ZooKeeper not starting because acceptedEpoch is less than the currentEpoch
* [ZOOKEEPER-2311](https://issues.apache.org/jira/browse/ZOOKEEPER-2311) - assert in setup_random
* [ZOOKEEPER-2316](https://issues.apache.org/jira/browse/ZOOKEEPER-2316) - comment does not match code logic
* [ZOOKEEPER-2317](https://issues.apache.org/jira/browse/ZOOKEEPER-2317) - Non-OSGi compatible version
* [ZOOKEEPER-2319](https://issues.apache.org/jira/browse/ZOOKEEPER-2319) - UnresolvedAddressException cause the QuorumCnxManager.Listener exit
* [ZOOKEEPER-2325](https://issues.apache.org/jira/browse/ZOOKEEPER-2325) - Data inconsistency if all snapshots empty or missing
* [ZOOKEEPER-2330](https://issues.apache.org/jira/browse/ZOOKEEPER-2330) - ZooKeeper close API does not close Login thread.
* [ZOOKEEPER-2335](https://issues.apache.org/jira/browse/ZOOKEEPER-2335) - Java Compilation Error in ClientCnxn.java
* [ZOOKEEPER-2338](https://issues.apache.org/jira/browse/ZOOKEEPER-2338) - c bindings should create socket&#39;s with SOCK_CLOEXEC to avoid fd leaks on fork/exec
* [ZOOKEEPER-2340](https://issues.apache.org/jira/browse/ZOOKEEPER-2340) - JMX is disabled even if JMXDISABLE is false
* [ZOOKEEPER-2349](https://issues.apache.org/jira/browse/ZOOKEEPER-2349) - Update documentation for snapCount
* [ZOOKEEPER-2355](https://issues.apache.org/jira/browse/ZOOKEEPER-2355) - Ephemeral node is never deleted if follower fails while reading the proposal packet
* [ZOOKEEPER-2364](https://issues.apache.org/jira/browse/ZOOKEEPER-2364) - &quot;ant docs&quot; fails on branch-3.5 due to missing releasenotes.xml.
* [ZOOKEEPER-2366](https://issues.apache.org/jira/browse/ZOOKEEPER-2366) - Reconfiguration of client port causes a socket leak
* [ZOOKEEPER-2375](https://issues.apache.org/jira/browse/ZOOKEEPER-2375) - Prevent multiple initialization of login object in each ZooKeeperSaslClient instance
* [ZOOKEEPER-2379](https://issues.apache.org/jira/browse/ZOOKEEPER-2379) - recent commit broke findbugs qabot check
* [ZOOKEEPER-2380](https://issues.apache.org/jira/browse/ZOOKEEPER-2380) - Deadlock between leader shutdown and forwarding ACK to the leader
* [ZOOKEEPER-2383](https://issues.apache.org/jira/browse/ZOOKEEPER-2383) - Startup race in ZooKeeperServer
* [ZOOKEEPER-2385](https://issues.apache.org/jira/browse/ZOOKEEPER-2385) - Zookeeper trunk build is failing on windows
* [ZOOKEEPER-2388](https://issues.apache.org/jira/browse/ZOOKEEPER-2388) - Unit tests failing on Solaris
* [ZOOKEEPER-2393](https://issues.apache.org/jira/browse/ZOOKEEPER-2393) - Revert run-time dependency on log4j and slf4j-log4j12
* [ZOOKEEPER-2405](https://issues.apache.org/jira/browse/ZOOKEEPER-2405) - getTGT() in Login.java mishandles confidential information
* [ZOOKEEPER-2413](https://issues.apache.org/jira/browse/ZOOKEEPER-2413) - ContainerManager doesn&#39;t close the Timer it creates when stop() is called
* [ZOOKEEPER-2418](https://issues.apache.org/jira/browse/ZOOKEEPER-2418) - txnlog diff sync can skip sending some transactions to followers
* [ZOOKEEPER-2442](https://issues.apache.org/jira/browse/ZOOKEEPER-2442) - Socket leak in QuorumCnxManager connectOne
* [ZOOKEEPER-2450](https://issues.apache.org/jira/browse/ZOOKEEPER-2450) - Upgrade Netty version due to security vulnerability (CVE-2014-3488)
* [ZOOKEEPER-2458](https://issues.apache.org/jira/browse/ZOOKEEPER-2458) - Remove license file for servlet-api dependency
* [ZOOKEEPER-2459](https://issues.apache.org/jira/browse/ZOOKEEPER-2459) - Update NOTICE file with Netty notice
* [ZOOKEEPER-2460](https://issues.apache.org/jira/browse/ZOOKEEPER-2460) - Remove javacc dependency from public Maven pom
* [ZOOKEEPER-2464](https://issues.apache.org/jira/browse/ZOOKEEPER-2464) - NullPointerException on ContainerManager
* [ZOOKEEPER-2465](https://issues.apache.org/jira/browse/ZOOKEEPER-2465) - Documentation copyright notice is out of date.
* [ZOOKEEPER-2467](https://issues.apache.org/jira/browse/ZOOKEEPER-2467) - NullPointerException when redo Command is passed negative value
* [ZOOKEEPER-2470](https://issues.apache.org/jira/browse/ZOOKEEPER-2470) - ServerConfig#parse(String[])  ignores tickTime
* [ZOOKEEPER-2474](https://issues.apache.org/jira/browse/ZOOKEEPER-2474) - add a way for client to reattach to a session when using ZKClientConfig
* [ZOOKEEPER-2477](https://issues.apache.org/jira/browse/ZOOKEEPER-2477) - documentation should refer to Java cli shell and not C cli shell
* [ZOOKEEPER-2500](https://issues.apache.org/jira/browse/ZOOKEEPER-2500) - Fix compilation warnings for CliException classes
* [ZOOKEEPER-2517](https://issues.apache.org/jira/browse/ZOOKEEPER-2517) - jute.maxbuffer is ignored
* [ZOOKEEPER-2536](https://issues.apache.org/jira/browse/ZOOKEEPER-2536) - When provide path for &quot;dataDir&quot; with trailing space, it is taking correct path (by trucating space) for snapshot but creating temporary file with some junk folder name for zookeeper_server.pid
* [ZOOKEEPER-2539](https://issues.apache.org/jira/browse/ZOOKEEPER-2539) - Throwing nullpointerException when run the command &quot;config -c&quot; when client port is mentioned as separate and not like new style
* [ZOOKEEPER-2548](https://issues.apache.org/jira/browse/ZOOKEEPER-2548) - zooInspector does not start on Windows
* [ZOOKEEPER-2558](https://issues.apache.org/jira/browse/ZOOKEEPER-2558) - Potential memory leak in recordio.c
* [ZOOKEEPER-2563](https://issues.apache.org/jira/browse/ZOOKEEPER-2563) - A revisit to setquota
* [ZOOKEEPER-2573](https://issues.apache.org/jira/browse/ZOOKEEPER-2573) - Modify Info.REVISION to adapt git repo
* [ZOOKEEPER-2574](https://issues.apache.org/jira/browse/ZOOKEEPER-2574) - PurgeTxnLog can inadvertently delete required txn log files
* [ZOOKEEPER-2576](https://issues.apache.org/jira/browse/ZOOKEEPER-2576) - After svn to git migration ZooKeeper Precommit jenkins job is failing.
* [ZOOKEEPER-2579](https://issues.apache.org/jira/browse/ZOOKEEPER-2579) - ZooKeeper server should verify that dataDir and snapDir are writeable before starting
* [ZOOKEEPER-2581](https://issues.apache.org/jira/browse/ZOOKEEPER-2581) - Not handled NullPointerException while creating key manager and trustManager
* [ZOOKEEPER-2606](https://issues.apache.org/jira/browse/ZOOKEEPER-2606) - SaslServerCallbackHandler#handleAuthorizeCallback() should log the exception
* [ZOOKEEPER-2611](https://issues.apache.org/jira/browse/ZOOKEEPER-2611) - zoo_remove_watchers - can remove the wrong watch 
* [ZOOKEEPER-2617](https://issues.apache.org/jira/browse/ZOOKEEPER-2617) - correct a few spelling typos
* [ZOOKEEPER-2621](https://issues.apache.org/jira/browse/ZOOKEEPER-2621) - ZooKeeper doesn&#39;t start on MINGW32 (Windows)
* [ZOOKEEPER-2622](https://issues.apache.org/jira/browse/ZOOKEEPER-2622) - ZooTrace.logQuorumPacket does nothing
* [ZOOKEEPER-2628](https://issues.apache.org/jira/browse/ZOOKEEPER-2628) - Investigate and fix findbug warnings
* [ZOOKEEPER-2633](https://issues.apache.org/jira/browse/ZOOKEEPER-2633) - Build failure in contrib/zkfuse with gcc 6.x
* [ZOOKEEPER-2635](https://issues.apache.org/jira/browse/ZOOKEEPER-2635) - Regenerate documentation
* [ZOOKEEPER-2636](https://issues.apache.org/jira/browse/ZOOKEEPER-2636) - Fix C build break.
* [ZOOKEEPER-2642](https://issues.apache.org/jira/browse/ZOOKEEPER-2642) - ZooKeeper reconfig API backward compatibility fix
* [ZOOKEEPER-2647](https://issues.apache.org/jira/browse/ZOOKEEPER-2647) - Fix TestReconfigServer.cc
* [ZOOKEEPER-2651](https://issues.apache.org/jira/browse/ZOOKEEPER-2651) - Missing src/pom.template in release
* [ZOOKEEPER-2678](https://issues.apache.org/jira/browse/ZOOKEEPER-2678) - Large databases take a long time to regain a quorum
* [ZOOKEEPER-2680](https://issues.apache.org/jira/browse/ZOOKEEPER-2680) - Correct DataNode.getChildren() inconsistent behaviour.
* [ZOOKEEPER-2683](https://issues.apache.org/jira/browse/ZOOKEEPER-2683) - RaceConditionTest is flaky
* [ZOOKEEPER-2684](https://issues.apache.org/jira/browse/ZOOKEEPER-2684) - Fix a crashing bug in the mixed workloads commit processor
* [ZOOKEEPER-2687](https://issues.apache.org/jira/browse/ZOOKEEPER-2687) - Deadlock while shutting down the Leader server.
* [ZOOKEEPER-2690](https://issues.apache.org/jira/browse/ZOOKEEPER-2690) - Update documentation source for ZOOKEEPER-2574
* [ZOOKEEPER-2693](https://issues.apache.org/jira/browse/ZOOKEEPER-2693) - DOS attack on wchp/wchc four letter words (4lw)
* [ZOOKEEPER-2694](https://issues.apache.org/jira/browse/ZOOKEEPER-2694) - sync CLI command does not wait for result from server
* [ZOOKEEPER-2722](https://issues.apache.org/jira/browse/ZOOKEEPER-2722) - Flaky Test: org.apache.zookeeper.test.ReadOnlyModeTest.testSessionEstablishment
* [ZOOKEEPER-2725](https://issues.apache.org/jira/browse/ZOOKEEPER-2725) - Upgrading to a global session fails with a multiop
* [ZOOKEEPER-2726](https://issues.apache.org/jira/browse/ZOOKEEPER-2726) - Patch for ZOOKEEPER-2693 introduces potential race condition
* [ZOOKEEPER-2735](https://issues.apache.org/jira/browse/ZOOKEEPER-2735) - Typo fixes in some scripts
* [ZOOKEEPER-2737](https://issues.apache.org/jira/browse/ZOOKEEPER-2737) - NettyServerCnxFactory leaks connection if exception happens while writing to a channel.
* [ZOOKEEPER-2743](https://issues.apache.org/jira/browse/ZOOKEEPER-2743) - Netty connection leaks JMX connection bean upon connection close in certain race conditions.
* [ZOOKEEPER-2747](https://issues.apache.org/jira/browse/ZOOKEEPER-2747) - Fix ZooKeeperAdmin Compilation Warning
* [ZOOKEEPER-2757](https://issues.apache.org/jira/browse/ZOOKEEPER-2757) - Incorrect path crashes zkCli
* [ZOOKEEPER-2758](https://issues.apache.org/jira/browse/ZOOKEEPER-2758) - Typo: transasction --&gt; transaction
* [ZOOKEEPER-2775](https://issues.apache.org/jira/browse/ZOOKEEPER-2775) - ZK Client not able to connect with Xid out of order error 
* [ZOOKEEPER-2777](https://issues.apache.org/jira/browse/ZOOKEEPER-2777) - There is a typo in zk.py which prevents from using/compiling it.
* [ZOOKEEPER-2778](https://issues.apache.org/jira/browse/ZOOKEEPER-2778) - Potential server deadlock between follower sync with leader and follower receiving external connection requests.
* [ZOOKEEPER-2785](https://issues.apache.org/jira/browse/ZOOKEEPER-2785) - Server inappropriately throttles connections under load before SASL completes
* [ZOOKEEPER-2786](https://issues.apache.org/jira/browse/ZOOKEEPER-2786) - Flaky test: org.apache.zookeeper.test.ClientTest.testNonExistingOpCode
* [ZOOKEEPER-2797](https://issues.apache.org/jira/browse/ZOOKEEPER-2797) - Invalid TTL from misbehaving client nukes zookeeper
* [ZOOKEEPER-2798](https://issues.apache.org/jira/browse/ZOOKEEPER-2798) - Fix flaky test: org.apache.zookeeper.test.ReadOnlyModeTest.testConnectionEvents
* [ZOOKEEPER-2804](https://issues.apache.org/jira/browse/ZOOKEEPER-2804) - Node creation fails with NPE if ACLs are null
* [ZOOKEEPER-2806](https://issues.apache.org/jira/browse/ZOOKEEPER-2806) - Flaky test: org.apache.zookeeper.server.quorum.FLEBackwardElectionRoundTest.testBackwardElectionRound
* [ZOOKEEPER-2808](https://issues.apache.org/jira/browse/ZOOKEEPER-2808) - ACL with index 1 might be removed if it&#39;s only being used once
* [ZOOKEEPER-2818](https://issues.apache.org/jira/browse/ZOOKEEPER-2818) - Improve the ZooKeeper#setACL  java doc
* [ZOOKEEPER-2819](https://issues.apache.org/jira/browse/ZOOKEEPER-2819) - Changing membership configuration via rolling restart does not work on 3.5.x.
* [ZOOKEEPER-2822](https://issues.apache.org/jira/browse/ZOOKEEPER-2822) - Wrong `ObjectName` about `MBeanServer` in JMX module
* [ZOOKEEPER-2841](https://issues.apache.org/jira/browse/ZOOKEEPER-2841) - ZooKeeper public include files leak porting changes
* [ZOOKEEPER-2845](https://issues.apache.org/jira/browse/ZOOKEEPER-2845) - Data inconsistency issue due to retain database in leader election
* [ZOOKEEPER-2847](https://issues.apache.org/jira/browse/ZOOKEEPER-2847) - Cannot bind to client port when reconfig based on old static config
* [ZOOKEEPER-2852](https://issues.apache.org/jira/browse/ZOOKEEPER-2852) - Snapshot size factor is not read from system property
* [ZOOKEEPER-2853](https://issues.apache.org/jira/browse/ZOOKEEPER-2853) - The lastZxidSeen in FileTxnLog.java is never being assigned
* [ZOOKEEPER-2859](https://issues.apache.org/jira/browse/ZOOKEEPER-2859) - CMake build doesn&#39;t support OS X
* [ZOOKEEPER-2861](https://issues.apache.org/jira/browse/ZOOKEEPER-2861) - Main-Class JAR manifest attribute is incorrect
* [ZOOKEEPER-2862](https://issues.apache.org/jira/browse/ZOOKEEPER-2862) - Incorrect javadoc syntax for web links in StaticHostProvider.java
* [ZOOKEEPER-2874](https://issues.apache.org/jira/browse/ZOOKEEPER-2874) - Windows Debug builds don&#39;t link with `/MTd`
* [ZOOKEEPER-2886](https://issues.apache.org/jira/browse/ZOOKEEPER-2886) - Permanent session moved error in multi-op only connections
* [ZOOKEEPER-2890](https://issues.apache.org/jira/browse/ZOOKEEPER-2890) - Local automatic variable is left uninitialized and then freed.
* [ZOOKEEPER-2891](https://issues.apache.org/jira/browse/ZOOKEEPER-2891) - Invalid processing of zookeeper_close for mutli-request
* [ZOOKEEPER-2893](https://issues.apache.org/jira/browse/ZOOKEEPER-2893) - very poor choice of logging if client fails to connect to server
* [ZOOKEEPER-2894](https://issues.apache.org/jira/browse/ZOOKEEPER-2894) - Memory and completions leak on zookeeper_close
* [ZOOKEEPER-2901](https://issues.apache.org/jira/browse/ZOOKEEPER-2901) - Session ID that is negative causes mis-calculation of Ephemeral Type
* [ZOOKEEPER-2905](https://issues.apache.org/jira/browse/ZOOKEEPER-2905) - Don&#39;t include `config.h` in `zookeeper.h`
* [ZOOKEEPER-2906](https://issues.apache.org/jira/browse/ZOOKEEPER-2906) - The OWASP dependency check jar should not be included in the default classpath
* [ZOOKEEPER-2909](https://issues.apache.org/jira/browse/ZOOKEEPER-2909) - Create ant task to generate ivy dependency reports
* [ZOOKEEPER-2913](https://issues.apache.org/jira/browse/ZOOKEEPER-2913) - testEphemeralNodeDeletion is flaky
* [ZOOKEEPER-2914](https://issues.apache.org/jira/browse/ZOOKEEPER-2914) - compiler warning using java 9
* [ZOOKEEPER-2920](https://issues.apache.org/jira/browse/ZOOKEEPER-2920) - Upgrade OWASP Dependency Check to 3.2.1
* [ZOOKEEPER-2923](https://issues.apache.org/jira/browse/ZOOKEEPER-2923) - The comment of the variable matchSyncs in class CommitProcessor has a mistake.
* [ZOOKEEPER-2924](https://issues.apache.org/jira/browse/ZOOKEEPER-2924) - Flaky Test: org.apache.zookeeper.test.LoadFromLogTest.testRestoreWithTransactionErrors
* [ZOOKEEPER-2926](https://issues.apache.org/jira/browse/ZOOKEEPER-2926) - Data inconsistency issue due to the flaw in the session management
* [ZOOKEEPER-2931](https://issues.apache.org/jira/browse/ZOOKEEPER-2931) - WriteLock recipe: incorrect znode ordering when the sessionId is part of the znode name
* [ZOOKEEPER-2934](https://issues.apache.org/jira/browse/ZOOKEEPER-2934) - c versions of election and queue recipes do not compile
* [ZOOKEEPER-2936](https://issues.apache.org/jira/browse/ZOOKEEPER-2936) - Duplicate Keys in log4j.properties config files
* [ZOOKEEPER-2944](https://issues.apache.org/jira/browse/ZOOKEEPER-2944) - Specify correct overflow value
* [ZOOKEEPER-2948](https://issues.apache.org/jira/browse/ZOOKEEPER-2948) - Failing c unit tests on apache jenkins
* [ZOOKEEPER-2949](https://issues.apache.org/jira/browse/ZOOKEEPER-2949) - SSL ServerName not set when using hostname, some proxies may failed to proxy the request.
* [ZOOKEEPER-2951](https://issues.apache.org/jira/browse/ZOOKEEPER-2951) - zkServer.cmd does not start when JAVA_HOME ends with a \
* [ZOOKEEPER-2953](https://issues.apache.org/jira/browse/ZOOKEEPER-2953) - Flaky Test: testNoLogBeforeLeaderEstablishment
* [ZOOKEEPER-2959](https://issues.apache.org/jira/browse/ZOOKEEPER-2959) - ignore accepted epoch and LEADERINFO ack from observers when a newly elected leader computes new epoch
* [ZOOKEEPER-2961](https://issues.apache.org/jira/browse/ZOOKEEPER-2961) - Fix testElectionFraud Flakyness
* [ZOOKEEPER-2964](https://issues.apache.org/jira/browse/ZOOKEEPER-2964) - &quot;Conf&quot; command returns dataDir and dataLogDir opposingly
* [ZOOKEEPER-2978](https://issues.apache.org/jira/browse/ZOOKEEPER-2978) - fix potential null pointer exception when deleting node
* [ZOOKEEPER-2982](https://issues.apache.org/jira/browse/ZOOKEEPER-2982) - Re-try DNS hostname -&gt; IP resolution
* [ZOOKEEPER-2988](https://issues.apache.org/jira/browse/ZOOKEEPER-2988) - NPE triggered if server receives a vote for a server id not in their voting view
* [ZOOKEEPER-2992](https://issues.apache.org/jira/browse/ZOOKEEPER-2992) - The eclipse build target fails due to protocol redirection: http-&gt;https
* [ZOOKEEPER-2993](https://issues.apache.org/jira/browse/ZOOKEEPER-2993) - .ignore file prevents adding src/java/main/org/apache/jute/compiler/generated dir to git repo
* [ZOOKEEPER-2997](https://issues.apache.org/jira/browse/ZOOKEEPER-2997) - CMake should not force static CRT linking
* [ZOOKEEPER-2998](https://issues.apache.org/jira/browse/ZOOKEEPER-2998) - CMake declares incorrect ZooKeeper version
* [ZOOKEEPER-3001](https://issues.apache.org/jira/browse/ZOOKEEPER-3001) - Incorrect log message when try to delete container node
* [ZOOKEEPER-3006](https://issues.apache.org/jira/browse/ZOOKEEPER-3006) - Potential NPE in ZKDatabase#calculateTxnLogSizeLimit
* [ZOOKEEPER-3007](https://issues.apache.org/jira/browse/ZOOKEEPER-3007) - Potential NPE in ReferenceCountedACLCache#deserialize 
* [ZOOKEEPER-3009](https://issues.apache.org/jira/browse/ZOOKEEPER-3009) - Potential NPE in NIOServerCnxnFactory
* [ZOOKEEPER-3025](https://issues.apache.org/jira/browse/ZOOKEEPER-3025) - cmake windows build is broken on jenkins
* [ZOOKEEPER-3027](https://issues.apache.org/jira/browse/ZOOKEEPER-3027) - Accidently removed public API of FileTxnLog.setPreallocSize()
* [ZOOKEEPER-3034](https://issues.apache.org/jira/browse/ZOOKEEPER-3034) - Facing issues while building from source
* [ZOOKEEPER-3038](https://issues.apache.org/jira/browse/ZOOKEEPER-3038) - Cleanup some nitpicks in TTL implementation
* [ZOOKEEPER-3039](https://issues.apache.org/jira/browse/ZOOKEEPER-3039) - TxnLogToolkit uses Scanner badly
* [ZOOKEEPER-3041](https://issues.apache.org/jira/browse/ZOOKEEPER-3041) - Typo in error message, affects log analysis
* [ZOOKEEPER-3050](https://issues.apache.org/jira/browse/ZOOKEEPER-3050) - owasp ant target is highlighting jetty version needs to be updated
* [ZOOKEEPER-3051](https://issues.apache.org/jira/browse/ZOOKEEPER-3051) - owasp complaining about jackson version used
* [ZOOKEEPER-3056](https://issues.apache.org/jira/browse/ZOOKEEPER-3056) - Fails to load database with missing snapshot file but valid transaction log file
* [ZOOKEEPER-3057](https://issues.apache.org/jira/browse/ZOOKEEPER-3057) - Fix IPv6 literal usage
* [ZOOKEEPER-3059](https://issues.apache.org/jira/browse/ZOOKEEPER-3059) - EventThread leak in case of Sasl AuthFailed
* [ZOOKEEPER-3072](https://issues.apache.org/jira/browse/ZOOKEEPER-3072) - Race condition in throttling
* [ZOOKEEPER-3079](https://issues.apache.org/jira/browse/ZOOKEEPER-3079) - Fix unsafe use of sprintf(3) for creating IP address strings
* [ZOOKEEPER-3082](https://issues.apache.org/jira/browse/ZOOKEEPER-3082) - Fix server snapshot behavior when out of disk space
* [ZOOKEEPER-3093](https://issues.apache.org/jira/browse/ZOOKEEPER-3093) - sync zerror(int rc) with newest error definitions
* [ZOOKEEPER-3104](https://issues.apache.org/jira/browse/ZOOKEEPER-3104) - Potential data inconsistency due to NEWLEADER packet being sent too early during SNAP sync
* [ZOOKEEPER-3105](https://issues.apache.org/jira/browse/ZOOKEEPER-3105) - Character coding problem occur when create a node using python3
* [ZOOKEEPER-3113](https://issues.apache.org/jira/browse/ZOOKEEPER-3113) - EphemeralType.get() fails to verify ephemeralOwner when currentElapsedTime() is small enough
* [ZOOKEEPER-3117](https://issues.apache.org/jira/browse/ZOOKEEPER-3117) - Correct the LeaderBean.followerInfo to only return the followers list
* [ZOOKEEPER-3125](https://issues.apache.org/jira/browse/ZOOKEEPER-3125) - Pzxid inconsistent issue when replaying a txn for a deleted node
* [ZOOKEEPER-3127](https://issues.apache.org/jira/browse/ZOOKEEPER-3127) - Fixing potential data inconsistency due to update last processed zxid with partial multi-op txn
* [ZOOKEEPER-3131](https://issues.apache.org/jira/browse/ZOOKEEPER-3131) - org.apache.zookeeper.server.WatchManager resource leak
* [ZOOKEEPER-3144](https://issues.apache.org/jira/browse/ZOOKEEPER-3144) - Potential ephemeral nodes inconsistent due to global session inconsistent with fuzzy snapshot
* [ZOOKEEPER-3145](https://issues.apache.org/jira/browse/ZOOKEEPER-3145) - Potential watch missing issue due to stale pzxid when replaying CloseSession txn with fuzzy snapshot
* [ZOOKEEPER-3156](https://issues.apache.org/jira/browse/ZOOKEEPER-3156) - ZOOKEEPER-2184 causes kerberos principal to not have resolved host name
* [ZOOKEEPER-3162](https://issues.apache.org/jira/browse/ZOOKEEPER-3162) - Broken lock semantics in C client lock-recipe
* [ZOOKEEPER-3210](https://issues.apache.org/jira/browse/ZOOKEEPER-3210) - Typo in zookeeperInternals doc
* [ZOOKEEPER-3212](https://issues.apache.org/jira/browse/ZOOKEEPER-3212) - Fix website with adding doap.rdf back
* [ZOOKEEPER-3217](https://issues.apache.org/jira/browse/ZOOKEEPER-3217) - owasp job flagging slf4j on trunk
* [ZOOKEEPER-3218](https://issues.apache.org/jira/browse/ZOOKEEPER-3218) - zk server reopened，the interval for observer connect to the new leader is too long，then session expired
* [ZOOKEEPER-3253](https://issues.apache.org/jira/browse/ZOOKEEPER-3253) - client should not send requests with cxid=-4, -2, or -1
* [ZOOKEEPER-3265](https://issues.apache.org/jira/browse/ZOOKEEPER-3265) - Build failure on branch-3.4
* [ZOOKEEPER-3296](https://issues.apache.org/jira/browse/ZOOKEEPER-3296) - Cannot join quorum due to Quorum SSLSocket connection not closed explicitly when there is handshake issue
* [ZOOKEEPER-3306](https://issues.apache.org/jira/browse/ZOOKEEPER-3306) - Node may not accessible due the the inconsistent ACL reference map after SNAP sync 
* [ZOOKEEPER-3320](https://issues.apache.org/jira/browse/ZOOKEEPER-3320) - Leader election port stop listen when hostname unresolvable for some time 
* [ZOOKEEPER-3356](https://issues.apache.org/jira/browse/ZOOKEEPER-3356) - Request throttling in Netty is not working as expected and could cause direct buffer OOM issue 
* [ZOOKEEPER-3373](https://issues.apache.org/jira/browse/ZOOKEEPER-3373) - need change description for &quot;Single System Image&quot; guarantee in document
* [ZOOKEEPER-3399](https://issues.apache.org/jira/browse/ZOOKEEPER-3399) - Remove logging in getGlobalOutstandingLimit for optimal performance.
* [ZOOKEEPER-3404](https://issues.apache.org/jira/browse/ZOOKEEPER-3404) - BouncyCastle upgrade to 1.61 might cause flaky test issues
* [ZOOKEEPER-3405](https://issues.apache.org/jira/browse/ZOOKEEPER-3405) - owasp flagging jackson-databind
* [ZOOKEEPER-3433](https://issues.apache.org/jira/browse/ZOOKEEPER-3433) - zkpython build broken after maven migration
* [ZOOKEEPER-3440](https://issues.apache.org/jira/browse/ZOOKEEPER-3440) - Fix Apache RAT check by excluding binary files (images)
* [ZOOKEEPER-3471](https://issues.apache.org/jira/browse/ZOOKEEPER-3471) - Potential lock unavailable due to dangling ephemeral nodes left during local session upgrading
* [ZOOKEEPER-3479](https://issues.apache.org/jira/browse/ZOOKEEPER-3479) - Logging false leader election times
* [ZOOKEEPER-3496](https://issues.apache.org/jira/browse/ZOOKEEPER-3496) - Transaction larger than jute.maxbuffer makes ZooKeeper unavailable
* [ZOOKEEPER-3498](https://issues.apache.org/jira/browse/ZOOKEEPER-3498) - In zookeeper-jute project generated source should not be in target\classes folder
* [ZOOKEEPER-3510](https://issues.apache.org/jira/browse/ZOOKEEPER-3510) - Frequent &#39;zkServer.sh stop&#39; failures when running C test suite
* [ZOOKEEPER-3518](https://issues.apache.org/jira/browse/ZOOKEEPER-3518) - owasp check flagging jackson-databind 2.9.9.1
* [ZOOKEEPER-3531](https://issues.apache.org/jira/browse/ZOOKEEPER-3531) - Synchronization on ACLCache cause cluster to hang when network/disk issues happen during datatree serialization
* [ZOOKEEPER-3540](https://issues.apache.org/jira/browse/ZOOKEEPER-3540) - Client port unavailable after binding the same client port during reconfig
* [ZOOKEEPER-3546](https://issues.apache.org/jira/browse/ZOOKEEPER-3546) - Containers that never have children stay forever
* [ZOOKEEPER-3559](https://issues.apache.org/jira/browse/ZOOKEEPER-3559) - Update Jackson to 2.9.10
* [ZOOKEEPER-3563](https://issues.apache.org/jira/browse/ZOOKEEPER-3563) - dependency check failing on 3.4 and 3.5 branches - CVE-2019-16869 on Netty
* [ZOOKEEPER-3590](https://issues.apache.org/jira/browse/ZOOKEEPER-3590) - Zookeeper is unable to set the zookeeper.sasl.client.canonicalize.hostname using system variable
* [ZOOKEEPER-3605](https://issues.apache.org/jira/browse/ZOOKEEPER-3605) - ZOOKEEPER-3242 add a connection throttle. Default constructor needs to set it
* [ZOOKEEPER-3633](https://issues.apache.org/jira/browse/ZOOKEEPER-3633) - AdminServer commands throw NPE when only secure client port is used
* [ZOOKEEPER-3641](https://issues.apache.org/jira/browse/ZOOKEEPER-3641) - New ZOO_VERSION define breaks Perl &amp; Python contribs
* [ZOOKEEPER-3644](https://issues.apache.org/jira/browse/ZOOKEEPER-3644) - Data loss after upgrading standalone ZK server 3.4.14 to 3.5.6 with snapshot.trust.empty=true
* [ZOOKEEPER-3651](https://issues.apache.org/jira/browse/ZOOKEEPER-3651) - NettyServerCnxnFactoryTest is flaky
* [ZOOKEEPER-3653](https://issues.apache.org/jira/browse/ZOOKEEPER-3653) - Audit Log feature fails in a stand alone zookeeper setup
    
## Test

* [ZOOKEEPER-1441](https://issues.apache.org/jira/browse/ZOOKEEPER-1441) - Some test cases are failing because Port bind issue.
* [ZOOKEEPER-2017](https://issues.apache.org/jira/browse/ZOOKEEPER-2017) - New tests for reconfig failure cases
* [ZOOKEEPER-2204](https://issues.apache.org/jira/browse/ZOOKEEPER-2204) - LearnerSnapshotThrottlerTest.testHighContentionWithTimeout fails occasionally
* [ZOOKEEPER-2415](https://issues.apache.org/jira/browse/ZOOKEEPER-2415) - SessionTest is using Thread deprecated API.
* [ZOOKEEPER-2484](https://issues.apache.org/jira/browse/ZOOKEEPER-2484) - Flaky Test: org.apache.zookeeper.test.LoadFromLogTest.testLoadFailure
* [ZOOKEEPER-2508](https://issues.apache.org/jira/browse/ZOOKEEPER-2508) - Many ZooKeeper tests are flaky because they proceed with zk operation without connecting to ZooKeeper server.
* [ZOOKEEPER-2577](https://issues.apache.org/jira/browse/ZOOKEEPER-2577) - Flaky Test: org.apache.zookeeper.server.quorum.ReconfigDuringLeaderSyncTest.testDuringLeaderSync
* [ZOOKEEPER-2656](https://issues.apache.org/jira/browse/ZOOKEEPER-2656) - Fix ServerConfigTest#testValidArguments test case failures
* [ZOOKEEPER-2664](https://issues.apache.org/jira/browse/ZOOKEEPER-2664) - ClientPortBindTest#testBindByAddress may fail due to &quot;No such device&quot; exception
* [ZOOKEEPER-2686](https://issues.apache.org/jira/browse/ZOOKEEPER-2686) - Flaky Test: org.apache.zookeeper.test.WatcherTest.
* [ZOOKEEPER-2716](https://issues.apache.org/jira/browse/ZOOKEEPER-2716) - Flaky Test: org.apache.zookeeper.server.SessionTrackerTest.testAddSessionAfterSessionExpiry
* [ZOOKEEPER-2718](https://issues.apache.org/jira/browse/ZOOKEEPER-2718) - org.apache.zookeeper.server.quorum.StandaloneDisabledTest fails intermittently
* [ZOOKEEPER-2742](https://issues.apache.org/jira/browse/ZOOKEEPER-2742) - Few test cases of org.apache.zookeeper.ZooKeeperTest fails in Windows
* [ZOOKEEPER-2746](https://issues.apache.org/jira/browse/ZOOKEEPER-2746) - Leader hand-off during dynamic reconfig is best effort, while test always expects it
* [ZOOKEEPER-2796](https://issues.apache.org/jira/browse/ZOOKEEPER-2796) - Test org.apache.zookeeper.ZooKeeperTest.testCreateNodeWithoutData is broken by ZOOKEEPER-2757
* [ZOOKEEPER-2955](https://issues.apache.org/jira/browse/ZOOKEEPER-2955) - Enable Clover code coverage report
* [ZOOKEEPER-2968](https://issues.apache.org/jira/browse/ZOOKEEPER-2968) - Add C client code coverage tests
* [ZOOKEEPER-3074](https://issues.apache.org/jira/browse/ZOOKEEPER-3074) - Flaky test:org.apache.zookeeper.server.ServerStatsTest.testLatencyMetrics
* [ZOOKEEPER-3157](https://issues.apache.org/jira/browse/ZOOKEEPER-3157) - Improve FuzzySnapshotRelatedTest to avoid flaky due to issues like connection loss
* [ZOOKEEPER-3205](https://issues.apache.org/jira/browse/ZOOKEEPER-3205) - Jute - o.a.jute.BinaryInputArchive Test cases
* [ZOOKEEPER-3455](https://issues.apache.org/jira/browse/ZOOKEEPER-3455) - Java 13 build failure on trunk: UnifiedServerSocketTest.testConnectWithoutSSLToStrictServer
* [ZOOKEEPER-3480](https://issues.apache.org/jira/browse/ZOOKEEPER-3480) - Flaky test CommitProcessorMetricsTest.testConcurrentRequestProcessingInCommitProcessor
* [ZOOKEEPER-3495](https://issues.apache.org/jira/browse/ZOOKEEPER-3495) - Broken test in JDK12+: SnapshotDigestTest.testDifferentDigestVersion
* [ZOOKEEPER-3542](https://issues.apache.org/jira/browse/ZOOKEEPER-3542) - X509UtilTest#testClientRenegotiationFails is flaky on JDK8 + linux on machines with 2 cores
</ul>
    
## Wish

* [ZOOKEEPER-2795](https://issues.apache.org/jira/browse/ZOOKEEPER-2795) - Change log level for &quot;ZKShutdownHandler is not registered&quot; error message
* [ZOOKEEPER-3073](https://issues.apache.org/jira/browse/ZOOKEEPER-3073) - fix couple of typos
* [ZOOKEEPER-3302](https://issues.apache.org/jira/browse/ZOOKEEPER-3302) - ZooKeeper C client does not compile on Fedora 29 
* [ZOOKEEPER-3421](https://issues.apache.org/jira/browse/ZOOKEEPER-3421) - Better insight into Observer connections
    
## Task

* [ZOOKEEPER-3733](https://issues.apache.org/jira/browse/ZOOKEEPER-3733) - Fix issues reported in 3.6.0rc3
* [ZOOKEEPER-3734](https://issues.apache.org/jira/browse/ZOOKEEPER-3734) - upgrade jackson-databind to address CVE-2020-8840
* [ZOOKEEPER-3715](https://issues.apache.org/jira/browse/ZOOKEEPER-3715) - fix Kerberos test failures for new JDK versions
* [ZOOKEEPER-3716](https://issues.apache.org/jira/browse/ZOOKEEPER-3716) - upgrade netty 4.1.42 to address CVE-2019-20444 CVE-2019-20445
* [ZOOKEEPER-3708](https://issues.apache.org/jira/browse/ZOOKEEPER-3708) - Move Logging Code into Logging Guard in Learner
* [ZOOKEEPER-3669](https://issues.apache.org/jira/browse/ZOOKEEPER-3669) - Use switch Statement in ClientCnxn SendThread
* [ZOOKEEPER-3704](https://issues.apache.org/jira/browse/ZOOKEEPER-3704) - upgrade maven dependency-check to 5.3.0
* [ZOOKEEPER-925](https://issues.apache.org/jira/browse/ZOOKEEPER-925) - Consider maven site generation to replace our forrest site and documentation generation
* [ZOOKEEPER-1604](https://issues.apache.org/jira/browse/ZOOKEEPER-1604) - remove rpm/deb/... packaging
* [ZOOKEEPER-2658](https://issues.apache.org/jira/browse/ZOOKEEPER-2658) - Trunk / branch-3.5 build broken.
* [ZOOKEEPER-2709](https://issues.apache.org/jira/browse/ZOOKEEPER-2709) - Clarify documentation around &quot;auth&quot; ACL scheme
* [ZOOKEEPER-3002](https://issues.apache.org/jira/browse/ZOOKEEPER-3002) - Upgrade branches 3.5 and trunk to Java 1.8
* [ZOOKEEPER-3017](https://issues.apache.org/jira/browse/ZOOKEEPER-3017) - Link libm in CMake on FreeBSD
* [ZOOKEEPER-3061](https://issues.apache.org/jira/browse/ZOOKEEPER-3061) - add more details to &#39;Unhandled scenario for peer&#39; log.warn message
* [ZOOKEEPER-3062](https://issues.apache.org/jira/browse/ZOOKEEPER-3062) - introduce fsync.warningthresholdms constant for FileTxnLog LOG.warn message
* [ZOOKEEPER-3067](https://issues.apache.org/jira/browse/ZOOKEEPER-3067) - Optionally suppress client environment logging.
* [ZOOKEEPER-3087](https://issues.apache.org/jira/browse/ZOOKEEPER-3087) - Fix findbug warning introduced by ZOOKEEPER-3084.
* [ZOOKEEPER-3120](https://issues.apache.org/jira/browse/ZOOKEEPER-3120) - add NetBeans nbproject directory to .gitignore
* [ZOOKEEPER-3136](https://issues.apache.org/jira/browse/ZOOKEEPER-3136) - Reduce log in ClientBase in case of ConnectException
* [ZOOKEEPER-3197](https://issues.apache.org/jira/browse/ZOOKEEPER-3197) - Improve documentation in ZooKeeperServer.superSecret
* [ZOOKEEPER-3230](https://issues.apache.org/jira/browse/ZOOKEEPER-3230) - Add Apache NetBeans Maven project files to .gitignore
* [ZOOKEEPER-3362](https://issues.apache.org/jira/browse/ZOOKEEPER-3362) - Create a simple checkstyle file
* [ZOOKEEPER-3406](https://issues.apache.org/jira/browse/ZOOKEEPER-3406) - Update website for new mailing lists
* [ZOOKEEPER-3407](https://issues.apache.org/jira/browse/ZOOKEEPER-3407) - Update POM file with new information
* [ZOOKEEPER-3431](https://issues.apache.org/jira/browse/ZOOKEEPER-3431) - Enable BookKeeper checkstyle configuration
* [ZOOKEEPER-3441](https://issues.apache.org/jira/browse/ZOOKEEPER-3441) - OWASP is flagging jackson-databind-2.9.9.jar for CVE-2019-12814
* [ZOOKEEPER-3545](https://issues.apache.org/jira/browse/ZOOKEEPER-3545) - Fix LICENSE files for netty dependency
* [ZOOKEEPER-3668](https://issues.apache.org/jira/browse/ZOOKEEPER-3668) - Clean up release package for 3.6.0

## Sub-task

* [ZOOKEEPER-169](https://issues.apache.org/jira/browse/ZOOKEEPER-169) - Content needed: &quot;Connecting to ZooKeeper&quot;
* [ZOOKEEPER-236](https://issues.apache.org/jira/browse/ZOOKEEPER-236) - SSL Support for Atomic Broadcast protocol
* [ZOOKEEPER-1626](https://issues.apache.org/jira/browse/ZOOKEEPER-1626) - Zookeeper C client should be tolerant of clock adjustments 
* [ZOOKEEPER-1660](https://issues.apache.org/jira/browse/ZOOKEEPER-1660) - Add documentation for dynamic reconfiguration
* [ZOOKEEPER-1872](https://issues.apache.org/jira/browse/ZOOKEEPER-1872) - QuorumPeer is not shutdown in few cases
* [ZOOKEEPER-2047](https://issues.apache.org/jira/browse/ZOOKEEPER-2047) - testTruncationNullLog fails on windows
* [ZOOKEEPER-2069](https://issues.apache.org/jira/browse/ZOOKEEPER-2069) - Netty Support for ClientCnxnSocket
* [ZOOKEEPER-2080](https://issues.apache.org/jira/browse/ZOOKEEPER-2080) - Fix deadlock in dynamic reconfiguration
* [ZOOKEEPER-2119](https://issues.apache.org/jira/browse/ZOOKEEPER-2119) - Netty client docs
* [ZOOKEEPER-2122](https://issues.apache.org/jira/browse/ZOOKEEPER-2122) - Impplement SSL support in the Zookeeper C client library
* [ZOOKEEPER-2123](https://issues.apache.org/jira/browse/ZOOKEEPER-2123) - Provide implementation of X509 AuthenticationProvider
* [ZOOKEEPER-2125](https://issues.apache.org/jira/browse/ZOOKEEPER-2125) - SSL on Netty client-server communication
* [ZOOKEEPER-2134](https://issues.apache.org/jira/browse/ZOOKEEPER-2134) - AsyncHammerTest.testHammer fails intermittently
* [ZOOKEEPER-2137](https://issues.apache.org/jira/browse/ZOOKEEPER-2137) - Make testPortChange() less flaky
* [ZOOKEEPER-2152](https://issues.apache.org/jira/browse/ZOOKEEPER-2152) - Intermittent failure in TestReconfig.cc
* [ZOOKEEPER-2153](https://issues.apache.org/jira/browse/ZOOKEEPER-2153) - X509 Authentication Documentation
* [ZOOKEEPER-2168](https://issues.apache.org/jira/browse/ZOOKEEPER-2168) - Add C APIs for new createContainer Methods
* [ZOOKEEPER-2396](https://issues.apache.org/jira/browse/ZOOKEEPER-2396) - Login object in ZooKeeperSaslClient is static
* [ZOOKEEPER-2608](https://issues.apache.org/jira/browse/ZOOKEEPER-2608) - Create CLI option for TTL ephemerals
* [ZOOKEEPER-2609](https://issues.apache.org/jira/browse/ZOOKEEPER-2609) - Add TTL Node APIs to C client
* [ZOOKEEPER-2692](https://issues.apache.org/jira/browse/ZOOKEEPER-2692) - Fix race condition in testWatchAutoResetWithPending
* [ZOOKEEPER-2707](https://issues.apache.org/jira/browse/ZOOKEEPER-2707) - Fix &quot;Unexpected bean exists!&quot; issue in WatcherTests
* [ZOOKEEPER-2750](https://issues.apache.org/jira/browse/ZOOKEEPER-2750) - Document SSL Support for Atomic Broadcast protocol
* [ZOOKEEPER-2753](https://issues.apache.org/jira/browse/ZOOKEEPER-2753) - Introduce a python script for generating flaky test report
* [ZOOKEEPER-2754](https://issues.apache.org/jira/browse/ZOOKEEPER-2754) - Set up Apache Jenkins job that runs the flaky test analyzer script.
* [ZOOKEEPER-2935](https://issues.apache.org/jira/browse/ZOOKEEPER-2935) - [QP MutualAuth]: Port ZOOKEEPER-1045 implementation from branch-3.5 to trunk
* [ZOOKEEPER-2939](https://issues.apache.org/jira/browse/ZOOKEEPER-2939) - Deal with maxbuffer as it relates to proposals
* [ZOOKEEPER-2940](https://issues.apache.org/jira/browse/ZOOKEEPER-2940) - Deal with maxbuffer as it relates to large requests from clients
* [ZOOKEEPER-3022](https://issues.apache.org/jira/browse/ZOOKEEPER-3022) - Step 1.1 - Create docs and it maven structure
* [ZOOKEEPER-3028](https://issues.apache.org/jira/browse/ZOOKEEPER-3028) - Create assembly in pom.xml
* [ZOOKEEPER-3029](https://issues.apache.org/jira/browse/ZOOKEEPER-3029) - Create pom files for jute, server and client
* [ZOOKEEPER-3030](https://issues.apache.org/jira/browse/ZOOKEEPER-3030) - Step 1.3 - Create zk-contrib maven structure
* [ZOOKEEPER-3031](https://issues.apache.org/jira/browse/ZOOKEEPER-3031) - Step 1.4 - Create zk-client maven structure
* [ZOOKEEPER-3032](https://issues.apache.org/jira/browse/ZOOKEEPER-3032) - Step 1.6 - Create zk-server maven structure
* [ZOOKEEPER-3033](https://issues.apache.org/jira/browse/ZOOKEEPER-3033) - Step 1.2 - Create zk-recipes maven structure
* [ZOOKEEPER-3046](https://issues.apache.org/jira/browse/ZOOKEEPER-3046) - testManyChildWatchersAutoReset is flaky
* [ZOOKEEPER-3080](https://issues.apache.org/jira/browse/ZOOKEEPER-3080) - Step 1.5 - Separate jute structure
* [ZOOKEEPER-3103](https://issues.apache.org/jira/browse/ZOOKEEPER-3103) - Pluggable metrics system for ZooKeeper - MetricsProvider API definition
* [ZOOKEEPER-3123](https://issues.apache.org/jira/browse/ZOOKEEPER-3123) - MetricsProvider Lifecycle in ZooKeeper Server
* [ZOOKEEPER-3143](https://issues.apache.org/jira/browse/ZOOKEEPER-3143) - Pluggable metrics system for ZooKeeper - Data Collection on Server
* [ZOOKEEPER-3150](https://issues.apache.org/jira/browse/ZOOKEEPER-3150) - Data integrity check when loading snapshot/txns from disk
* [ZOOKEEPER-3153](https://issues.apache.org/jira/browse/ZOOKEEPER-3153) - Create MarkDown files and build process for them
* [ZOOKEEPER-3154](https://issues.apache.org/jira/browse/ZOOKEEPER-3154) - Update release process to use the MarkDown solution
* [ZOOKEEPER-3155](https://issues.apache.org/jira/browse/ZOOKEEPER-3155) - Remove Forrest XMLs and their build process from the project
* [ZOOKEEPER-3171](https://issues.apache.org/jira/browse/ZOOKEEPER-3171) - Create pom.xml for recipes and contrib
* [ZOOKEEPER-3172](https://issues.apache.org/jira/browse/ZOOKEEPER-3172) - Quorum TLS - fix port unification to allow rolling upgrades
* [ZOOKEEPER-3173](https://issues.apache.org/jira/browse/ZOOKEEPER-3173) - Quorum TLS - support PEM trust/key stores
* [ZOOKEEPER-3174](https://issues.apache.org/jira/browse/ZOOKEEPER-3174) - Quorum TLS - support reloading trust/key store
* [ZOOKEEPER-3175](https://issues.apache.org/jira/browse/ZOOKEEPER-3175) - Quorum TLS - test improvements
* [ZOOKEEPER-3176](https://issues.apache.org/jira/browse/ZOOKEEPER-3176) - Quorum TLS - add SSL config options
* [ZOOKEEPER-3193](https://issues.apache.org/jira/browse/ZOOKEEPER-3193) - Flaky: org.apache.zookeeper.test.SaslAuthFailNotifyTest
* [ZOOKEEPER-3194](https://issues.apache.org/jira/browse/ZOOKEEPER-3194) - Quorum TLS - fix copy/paste bug in ZKTrustManager
* [ZOOKEEPER-3202](https://issues.apache.org/jira/browse/ZOOKEEPER-3202) - Flaky test: org.apache.zookeeper.test.ClientSSLTest.testClientServerSSL
* [ZOOKEEPER-3222](https://issues.apache.org/jira/browse/ZOOKEEPER-3222) - Flaky: multiple intermittent segfaults in C++ tests
* [ZOOKEEPER-3223](https://issues.apache.org/jira/browse/ZOOKEEPER-3223) - Configure Spotbugs
* [ZOOKEEPER-3224](https://issues.apache.org/jira/browse/ZOOKEEPER-3224) - CI integration with maven
* [ZOOKEEPER-3225](https://issues.apache.org/jira/browse/ZOOKEEPER-3225) - Create code coverage analysis with maven build
* [ZOOKEEPER-3226](https://issues.apache.org/jira/browse/ZOOKEEPER-3226) - Activate C Client with a profile, disabled by default
* [ZOOKEEPER-3229](https://issues.apache.org/jira/browse/ZOOKEEPER-3229) - [TLS] add AES-256 ciphers to default cipher list
* [ZOOKEEPER-3233](https://issues.apache.org/jira/browse/ZOOKEEPER-3233) - Run github pre-commit hook tests on 4 threads
* [ZOOKEEPER-3251](https://issues.apache.org/jira/browse/ZOOKEEPER-3251) - Add new server metric types: percentile counter and counter set
* [ZOOKEEPER-3256](https://issues.apache.org/jira/browse/ZOOKEEPER-3256) - Enable OWASP checks  to Maven build
* [ZOOKEEPER-3267](https://issues.apache.org/jira/browse/ZOOKEEPER-3267) - Add watcher metrics
* [ZOOKEEPER-3268](https://issues.apache.org/jira/browse/ZOOKEEPER-3268) - Add commit processor metrics
* [ZOOKEEPER-3275](https://issues.apache.org/jira/browse/ZOOKEEPER-3275) - Fix release targets: package, tar, mvn-deploy
* [ZOOKEEPER-3285](https://issues.apache.org/jira/browse/ZOOKEEPER-3285) - Move assembly into its own sub-module
* [ZOOKEEPER-3288](https://issues.apache.org/jira/browse/ZOOKEEPER-3288) - add a new doc:ZookeeperCLI.md
* [ZOOKEEPER-3305](https://issues.apache.org/jira/browse/ZOOKEEPER-3305) - Add Quorum Packet metrics
* [ZOOKEEPER-3309](https://issues.apache.org/jira/browse/ZOOKEEPER-3309) - Add sync processor metrics
* [ZOOKEEPER-3310](https://issues.apache.org/jira/browse/ZOOKEEPER-3310) - Add metrics for prep processor
* [ZOOKEEPER-3319](https://issues.apache.org/jira/browse/ZOOKEEPER-3319) - Add metrics for follower and observer
* [ZOOKEEPER-3321](https://issues.apache.org/jira/browse/ZOOKEEPER-3321) - Add metrics for Leader
* [ZOOKEEPER-3323](https://issues.apache.org/jira/browse/ZOOKEEPER-3323) - Add TxnSnapLog metrics
* [ZOOKEEPER-3324](https://issues.apache.org/jira/browse/ZOOKEEPER-3324) - Add read/write metrics for top level znodes
* [ZOOKEEPER-3326](https://issues.apache.org/jira/browse/ZOOKEEPER-3326) - Add session/connection related metrics
* [ZOOKEEPER-3327](https://issues.apache.org/jira/browse/ZOOKEEPER-3327) - Add unrecoverable error count
* [ZOOKEEPER-3366](https://issues.apache.org/jira/browse/ZOOKEEPER-3366) - Pluggable metrics system for ZooKeeper - move remaining metrics to MetricsProvider
* [ZOOKEEPER-3379](https://issues.apache.org/jira/browse/ZOOKEEPER-3379) - De-flaky test in Quorum Packet Metrics
* [ZOOKEEPER-3383](https://issues.apache.org/jira/browse/ZOOKEEPER-3383) - Improve prep processor metric accuracy and de-flaky unit test
* [ZOOKEEPER-3401](https://issues.apache.org/jira/browse/ZOOKEEPER-3401) - Fix metric PROPOSAL_ACK_CREATION_LATENCY
* [ZOOKEEPER-3409](https://issues.apache.org/jira/browse/ZOOKEEPER-3409) - Compile Java code with -Xdoclint
* [ZOOKEEPER-3443](https://issues.apache.org/jira/browse/ZOOKEEPER-3443) - Add support for PKCS12 trust/key stores
* [ZOOKEEPER-3465](https://issues.apache.org/jira/browse/ZOOKEEPER-3465) - Introduce BookKeeper checkstyle configuration
* [ZOOKEEPER-3470](https://issues.apache.org/jira/browse/ZOOKEEPER-3470) - Flaky test: LearnerMetricsTest.testLearnerMetricsTest()
* [ZOOKEEPER-3474](https://issues.apache.org/jira/browse/ZOOKEEPER-3474) - Enable BookKeeper checkstyle configuration on zookeeper-promethus
* [ZOOKEEPER-3475](https://issues.apache.org/jira/browse/ZOOKEEPER-3475) - Enable BookKeeper checkstyle configuration on zookeeper-server
* [ZOOKEEPER-3512](https://issues.apache.org/jira/browse/ZOOKEEPER-3512) - Real time data integrity check during broadcast time
* [ZOOKEEPER-3517](https://issues.apache.org/jira/browse/ZOOKEEPER-3517) - Turn on BookKeeper checkstyle configuration at project level
* [ZOOKEEPER-3528](https://issues.apache.org/jira/browse/ZOOKEEPER-3528) - Revisit AsyncCallback javadoc
* [ZOOKEEPER-3529](https://issues.apache.org/jira/browse/ZOOKEEPER-3529) - add a new doc: zookeeperUseCases.md

                                                                                                                                            












