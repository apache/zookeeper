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

# Release Notes - ZooKeeper - Version 3.5.7

## Bug

* [ZOOKEEPER-1105](https://issues.apache.org/jira/browse/ZOOKEEPER-1105) - c client zookeeper_close not send CLOSE_OP request to server
* [ZOOKEEPER-2282](https://issues.apache.org/jira/browse/ZOOKEEPER-2282) - chroot not stripped from path in asynchronous callbacks
* [ZOOKEEPER-3057](https://issues.apache.org/jira/browse/ZOOKEEPER-3057) - Fix IPv6 literal usage
* [ZOOKEEPER-3496](https://issues.apache.org/jira/browse/ZOOKEEPER-3496) - Transaction larger than jute.maxbuffer makes ZooKeeper unavailable
* [ZOOKEEPER-3590](https://issues.apache.org/jira/browse/ZOOKEEPER-3590) - Zookeeper is unable to set the zookeeper.sasl.client.canonicalize.hostname using system variable
* [ZOOKEEPER-3613](https://issues.apache.org/jira/browse/ZOOKEEPER-3613) - ZKConfig fails to return proper value on getBoolean() when user accidentally includes spaces at the end of the value
* [ZOOKEEPER-3633](https://issues.apache.org/jira/browse/ZOOKEEPER-3633) - AdminServer commands throw NPE when only secure client port is used
* [ZOOKEEPER-3644](https://issues.apache.org/jira/browse/ZOOKEEPER-3644) - Data loss after upgrading standalone ZK server 3.4.14 to 3.5.6 with snapshot.trust.empty=true
* [ZOOKEEPER-3667](https://issues.apache.org/jira/browse/ZOOKEEPER-3667) - set jute.maxbuffer hexadecimal number throw parseInt error
* [ZOOKEEPER-3699](https://issues.apache.org/jira/browse/ZOOKEEPER-3699) - upgrade jackson-databind to address CVE-2019-20330
* [ZOOKEEPER-3716](https://issues.apache.org/jira/browse/ZOOKEEPER-3716) - upgrade netty 4.1.42 to address CVE-2019-20444 CVE-2019-20445
* [ZOOKEEPER-3718](https://issues.apache.org/jira/browse/ZOOKEEPER-3718) - Generated source tarball is missing some files
* [ZOOKEEPER-3719](https://issues.apache.org/jira/browse/ZOOKEEPER-3719) - C Client compilation issues in 3.5.7-rc

## Improvement

* [ZOOKEEPER-1467](https://issues.apache.org/jira/browse/ZOOKEEPER-1467) - Make server principal configurable at client side.
* [ZOOKEEPER-2084](https://issues.apache.org/jira/browse/ZOOKEEPER-2084) - Document local session parameters
* [ZOOKEEPER-3388](https://issues.apache.org/jira/browse/ZOOKEEPER-3388) - Allow client port to support plaintext and encrypted connections simultaneously
* [ZOOKEEPER-3453](https://issues.apache.org/jira/browse/ZOOKEEPER-3453) - missing 'SET' in zkCli on windows
* [ZOOKEEPER-3482](https://issues.apache.org/jira/browse/ZOOKEEPER-3482) - SASL (Kerberos) Authentication with SSL for clients and Quorum
* [ZOOKEEPER-3627](https://issues.apache.org/jira/browse/ZOOKEEPER-3627) - Update Jackson to 2.9.10.1 and the Owasp plugin to 5.2.4
* [ZOOKEEPER-3638](https://issues.apache.org/jira/browse/ZOOKEEPER-3638) - Update Jetty to 9.4.24.v20191120
* [ZOOKEEPER-3703](https://issues.apache.org/jira/browse/ZOOKEEPER-3703) - Publish a Test-Jar from ZooKeeper Server
* [ZOOKEEPER-3708](https://issues.apache.org/jira/browse/ZOOKEEPER-3708) - Move Logging Code into Logging Guard in Learner
* [ZOOKEEPER-3715](https://issues.apache.org/jira/browse/ZOOKEEPER-3715) - Kerberos Authentication related tests fail for new JDK versions

## Task

* [ZOOKEEPER-3677](https://issues.apache.org/jira/browse/ZOOKEEPER-3677) - owasp checker failing for - CVE-2019-17571 Apache Log4j 1.2 deserialization of untrusted data in SocketServer
* [ZOOKEEPER-3704](https://issues.apache.org/jira/browse/ZOOKEEPER-3704) - upgrade maven dependency-check to 5.3.0

# Release Notes - ZooKeeper - Version 3.5.6

## Sub-task

* [ZOOKEEPER-2609](https://issues.apache.org/jira/browse/ZOOKEEPER-2168) - Add TTL Node APIs to C client
* [ZOOKEEPER-3443](https://issues.apache.org/jira/browse/ZOOKEEPER-3443) - Add support for PKCS12 trust/key stores

## Bug

* [ZOOKEEPER-2694](https://issues.apache.org/jira/browse/ZOOKEEPER-2694) - sync CLI command does not wait for result from server
* [ZOOKEEPER-2891](https://issues.apache.org/jira/browse/ZOOKEEPER-2891) - Invalid processing of zookeeper_close for mutli-request
* [ZOOKEEPER-2894](https://issues.apache.org/jira/browse/ZOOKEEPER-2894) - Memory and completions leak on zookeeper_close
* [ZOOKEEPER-3056](https://issues.apache.org/jira/browse/ZOOKEEPER-3056) - Fails to load database with missing snapshot file but valid transaction log file
* [ZOOKEEPER-3105](https://issues.apache.org/jira/browse/ZOOKEEPER-3105) - Character coding problem occur when create a node using python3
* [ZOOKEEPER-3320](https://issues.apache.org/jira/browse/ZOOKEEPER-3320) - Leader election port stop listen when hostname unresolvable for some time
* [ZOOKEEPER-3404](https://issues.apache.org/jira/browse/ZOOKEEPER-3404) - BouncyCastle upgrade to 1.61 might cause flaky test issues
* [ZOOKEEPER-3405](https://issues.apache.org/jira/browse/ZOOKEEPER-3405) - owasp flagging jackson-databind
* [ZOOKEEPER-3433](https://issues.apache.org/jira/browse/ZOOKEEPER-3433) - zkpython build broken after maven migration
* [ZOOKEEPER-3498](https://issues.apache.org/jira/browse/ZOOKEEPER-3498) - In zookeeper-jute project generated source should not be in target\classes folder
* [ZOOKEEPER-3510](https://issues.apache.org/jira/browse/ZOOKEEPER-3510) - Frequent 'zkServer.sh stop' failures when running C test suite
* [ZOOKEEPER-3518](https://issues.apache.org/jira/browse/ZOOKEEPER-3518) - owasp check flagging jackson-databind 2.9.9.1

## Improvement

* [ZOOKEEPER-3263](https://issues.apache.org/jira/browse/ZOOKEEPER-3263) - Illegal reflective access in zookeer's kerberosUtil
* [ZOOKEEPER-3370](https://issues.apache.org/jira/browse/ZOOKEEPER-3370) - Remove SVN specific revision generation
* [ZOOKEEPER-3494](https://issues.apache.org/jira/browse/ZOOKEEPER-3494) - No need to depend on netty-all (SSL)
* [ZOOKEEPER-3519](https://issues.apache.org/jira/browse/ZOOKEEPER-3519) - upgrade dependency-check to 5.2.1

## Test

* [ZOOKEEPER-3455](https://issues.apache.org/jira/browse/ZOOKEEPER-3455) - Java 13 build failure on trunk: UnifiedServerSocketTest.testConnectWithoutSSLToStrictServer

## Task

* [ZOOKEEPER-3362](https://issues.apache.org/jira/browse/ZOOKEEPER-3362) - Create a simple checkstyle file
* [ZOOKEEPER-3441](https://issues.apache.org/jira/browse/ZOOKEEPER-3441) - OWASP is flagging jackson-databind-2.9.9.jar for CVE-2019-12814
* [ZOOKEEPER-3463](https://issues.apache.org/jira/browse/ZOOKEEPER-3463) - Enable warning messages in maven compiler plugin
* [ZOOKEEPER-3539](https://issues.apache.org/jira/browse/ZOOKEEPER-3539) - Fix branch-3.5 after upgrade on ASF CI
* [ZOOKEEPER-3440](https://issues.apache.org/jira/browse/ZOOKEEPER-3440) - Fix Apache RAT check by excluding binary files (images)
* [ZOOKEEPER-3542](https://issues.apache.org/jira/browse/ZOOKEEPER-3542) - X509UtilTest#testClientRenegotiationFails is flaky on JDK8 + linux on machines with 2 cores

# Release Notes - ZooKeeper - Version 3.5.5

Java 8 users: if you are going to compile with Java 1.8, you should use a
recent release at u211 or above. 

## Sub-task
* [ZOOKEEPER-2168](https://issues.apache.org/jira/browse/ZOOKEEPER-2168) - Add C APIs for new createContainer Methods
* [ZOOKEEPER-2481](https://issues.apache.org/jira/browse/ZOOKEEPER-2481) - Flaky Test: testZeroWeightQuorum
* [ZOOKEEPER-2485](https://issues.apache.org/jira/browse/ZOOKEEPER-2485) - Flaky Test: org.apache.zookeeper.test.FourLetterWordsTest.testFourLetterWords
* [ZOOKEEPER-2497](https://issues.apache.org/jira/browse/ZOOKEEPER-2497) - Flaky Test: org.apache.zookeeper.test.QuorumTest.testMultipleWatcherObjs
* [ZOOKEEPER-2499](https://issues.apache.org/jira/browse/ZOOKEEPER-2499) - Flaky Test: org.apache.zookeeper.test.SSLTest.testSecureQuorumServer 
* [ZOOKEEPER-2538](https://issues.apache.org/jira/browse/ZOOKEEPER-2538) - Flaky Test: org.apache.zookeeper.server.quorum.Zab1_0Test.testNormalObserverRun
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
* [ZOOKEEPER-3153](https://issues.apache.org/jira/browse/ZOOKEEPER-3153) - Create MarkDown files and build process for them
* [ZOOKEEPER-3154](https://issues.apache.org/jira/browse/ZOOKEEPER-3154) - Update release process to use the MarkDown solution
* [ZOOKEEPER-3155](https://issues.apache.org/jira/browse/ZOOKEEPER-3155) - Remove Forrest XMLs and their build process from the project
* [ZOOKEEPER-3171](https://issues.apache.org/jira/browse/ZOOKEEPER-3171) - Create pom.xml for recipes and contrib
* [ZOOKEEPER-3193](https://issues.apache.org/jira/browse/ZOOKEEPER-3193) - Flaky: org.apache.zookeeper.test.SaslAuthFailNotifyTest
* [ZOOKEEPER-3202](https://issues.apache.org/jira/browse/ZOOKEEPER-3202) - Flaky test: org.apache.zookeeper.test.ClientSSLTest.testClientServerSSL
* [ZOOKEEPER-3222](https://issues.apache.org/jira/browse/ZOOKEEPER-3222) - Flaky: multiple intermittent segfaults in C++ tests
* [ZOOKEEPER-3223](https://issues.apache.org/jira/browse/ZOOKEEPER-3223) - Configure Spotbugs
* [ZOOKEEPER-3224](https://issues.apache.org/jira/browse/ZOOKEEPER-3224) - CI integration with maven
* [ZOOKEEPER-3225](https://issues.apache.org/jira/browse/ZOOKEEPER-3225) - Create code coverage analysis with maven build
* [ZOOKEEPER-3226](https://issues.apache.org/jira/browse/ZOOKEEPER-3226) - Activate C Client with a profile, disabled by default
* [ZOOKEEPER-3256](https://issues.apache.org/jira/browse/ZOOKEEPER-3256) - Enable OWASP checks  to Maven build
* [ZOOKEEPER-3275](https://issues.apache.org/jira/browse/ZOOKEEPER-3275) - Fix release targets: package, tar, mvn-deploy
* [ZOOKEEPER-3285](https://issues.apache.org/jira/browse/ZOOKEEPER-3285) - Move assembly into its own sub-module
        
## Bug
* [ZOOKEEPER-1392](https://issues.apache.org/jira/browse/ZOOKEEPER-1392) - Should not allow to read ACL when not authorized to read node
* [ZOOKEEPER-1636](https://issues.apache.org/jira/browse/ZOOKEEPER-1636) - c-client crash when zoo_amulti failed 
* [ZOOKEEPER-1818](https://issues.apache.org/jira/browse/ZOOKEEPER-1818) - Fix don&#39;t care for trunk
* [ZOOKEEPER-1823](https://issues.apache.org/jira/browse/ZOOKEEPER-1823) - zkTxnLogToolkit -dump should support printing transaction data as a string
* [ZOOKEEPER-1919](https://issues.apache.org/jira/browse/ZOOKEEPER-1919) - Update the C implementation of removeWatches to have it match ZOOKEEPER-1910
* [ZOOKEEPER-1990](https://issues.apache.org/jira/browse/ZOOKEEPER-1990) - suspicious instantiation of java Random instances
* [ZOOKEEPER-2184](https://issues.apache.org/jira/browse/ZOOKEEPER-2184) - Zookeeper Client should re-resolve hosts when connection attempts fail
* [ZOOKEEPER-2251](https://issues.apache.org/jira/browse/ZOOKEEPER-2251) - Add Client side packet response timeout to avoid infinite wait.
* [ZOOKEEPER-2261](https://issues.apache.org/jira/browse/ZOOKEEPER-2261) - When only secureClientPort is configured connections, configuration, connection_stat_reset, and stats admin commands throw NullPointerException
* [ZOOKEEPER-2284](https://issues.apache.org/jira/browse/ZOOKEEPER-2284) - LogFormatter and SnapshotFormatter does not handle FileNotFoundException gracefully
* [ZOOKEEPER-2317](https://issues.apache.org/jira/browse/ZOOKEEPER-2317) - Non-OSGi compatible version
* [ZOOKEEPER-2474](https://issues.apache.org/jira/browse/ZOOKEEPER-2474) - add a way for client to reattach to a session when using ZKClientConfig
* [ZOOKEEPER-2621](https://issues.apache.org/jira/browse/ZOOKEEPER-2621) - ZooKeeper doesn&#39;t start on MINGW32 (Windows)
* [ZOOKEEPER-2750](https://issues.apache.org/jira/browse/ZOOKEEPER-2750) - Document SSL Support for Atomic Broadcast protocol
* [ZOOKEEPER-2778](https://issues.apache.org/jira/browse/ZOOKEEPER-2778) - Potential server deadlock between follower sync with leader and follower receiving external connection requests.
* [ZOOKEEPER-2822](https://issues.apache.org/jira/browse/ZOOKEEPER-2822) - Wrong `ObjectName` about `MBeanServer` in JMX module
* [ZOOKEEPER-2913](https://issues.apache.org/jira/browse/ZOOKEEPER-2913) - testEphemeralNodeDeletion is flaky
* [ZOOKEEPER-2920](https://issues.apache.org/jira/browse/ZOOKEEPER-2920) - Upgrade OWASP Dependency Check to 3.2.1
* [ZOOKEEPER-2993](https://issues.apache.org/jira/browse/ZOOKEEPER-2993) - .ignore file prevents adding src/java/main/org/apache/jute/compiler/generated dir to git repo
* [ZOOKEEPER-3009](https://issues.apache.org/jira/browse/ZOOKEEPER-3009) - Potential NPE in NIOServerCnxnFactory
* [ZOOKEEPER-3034](https://issues.apache.org/jira/browse/ZOOKEEPER-3034) - Facing issues while building from source
* [ZOOKEEPER-3041](https://issues.apache.org/jira/browse/ZOOKEEPER-3041) - Typo in error message, affects log analysis
* [ZOOKEEPER-3042](https://issues.apache.org/jira/browse/ZOOKEEPER-3042) - testFailedTxnAsPartOfQuorumLoss is flaky
* [ZOOKEEPER-3050](https://issues.apache.org/jira/browse/ZOOKEEPER-3050) - owasp ant target is highlighting jetty version needs to be updated
* [ZOOKEEPER-3051](https://issues.apache.org/jira/browse/ZOOKEEPER-3051) - owasp complaining about jackson version used
* [ZOOKEEPER-3059](https://issues.apache.org/jira/browse/ZOOKEEPER-3059) - EventThread leak in case of Sasl AuthFailed
* [ZOOKEEPER-3093](https://issues.apache.org/jira/browse/ZOOKEEPER-3093) - sync zerror(int rc) with newest error definitions
* [ZOOKEEPER-3113](https://issues.apache.org/jira/browse/ZOOKEEPER-3113) - EphemeralType.get() fails to verify ephemeralOwner when currentElapsedTime() is small enough
* [ZOOKEEPER-3125](https://issues.apache.org/jira/browse/ZOOKEEPER-3125) - Pzxid inconsistent issue when replaying a txn for a deleted node
* [ZOOKEEPER-3127](https://issues.apache.org/jira/browse/ZOOKEEPER-3127) - Fixing potential data inconsistency due to update last processed zxid with partial multi-op txn
* [ZOOKEEPER-3131](https://issues.apache.org/jira/browse/ZOOKEEPER-3131) - org.apache.zookeeper.server.WatchManager resource leak
* [ZOOKEEPER-3156](https://issues.apache.org/jira/browse/ZOOKEEPER-3156) - ZOOKEEPER-2184 causes kerberos principal to not have resolved host name
* [ZOOKEEPER-3162](https://issues.apache.org/jira/browse/ZOOKEEPER-3162) - Broken lock semantics in C client lock-recipe
* [ZOOKEEPER-3165](https://issues.apache.org/jira/browse/ZOOKEEPER-3165) - Java 9: X509UtilTest.testCreateSSLContextWithoutTrustStorePassword fails
* [ZOOKEEPER-3194](https://issues.apache.org/jira/browse/ZOOKEEPER-3194) - Quorum TLS - fix copy/paste bug in ZKTrustManager
* [ZOOKEEPER-3210](https://issues.apache.org/jira/browse/ZOOKEEPER-3210) - Typo in zookeeperInternals doc
* [ZOOKEEPER-3217](https://issues.apache.org/jira/browse/ZOOKEEPER-3217) - owasp job flagging slf4j on trunk
* [ZOOKEEPER-3253](https://issues.apache.org/jira/browse/ZOOKEEPER-3253) - client should not send requests with cxid=-4, -2, or -1
* [ZOOKEEPER-3265](https://issues.apache.org/jira/browse/ZOOKEEPER-3265) - Build failure on branch-3.4
        
## New Feature
* [ZOOKEEPER-236](https://issues.apache.org/jira/browse/ZOOKEEPER-236) - SSL Support for Atomic Broadcast protocol
* [ZOOKEEPER-2933](https://issues.apache.org/jira/browse/ZOOKEEPER-2933) - Ability to monitor the jute.maxBuffer usage in real-time
* [ZOOKEEPER-3066](https://issues.apache.org/jira/browse/ZOOKEEPER-3066) - Expose on JMX of Followers the id of the current leader
        
## Improvement
* [ZOOKEEPER-1908](https://issues.apache.org/jira/browse/ZOOKEEPER-1908) - setAcl should be have a recursive function
* [ZOOKEEPER-2368](https://issues.apache.org/jira/browse/ZOOKEEPER-2368) - Client watches are not disconnected on close
* [ZOOKEEPER-2825](https://issues.apache.org/jira/browse/ZOOKEEPER-2825) - 1. Remove unnecessary import; 2. `contains` instead of `indexOf &gt; -1` for more readable; 3. Standardize `StringBuilder#append` usage for CLIENT module
* [ZOOKEEPER-2826](https://issues.apache.org/jira/browse/ZOOKEEPER-2826) - Code refactoring for `CLI` module
* [ZOOKEEPER-2873](https://issues.apache.org/jira/browse/ZOOKEEPER-2873) - print error and/or abort on invalid server definition
* [ZOOKEEPER-3019](https://issues.apache.org/jira/browse/ZOOKEEPER-3019) - Add a metric to track number of slow fsyncs
* [ZOOKEEPER-3021](https://issues.apache.org/jira/browse/ZOOKEEPER-3021) - Umbrella: Migrate project structure to Maven build
* [ZOOKEEPER-3043](https://issues.apache.org/jira/browse/ZOOKEEPER-3043) - QuorumKerberosHostBasedAuthTest fails on Linux box: Unable to parse:includedir /etc/krb5.conf.d/
* [ZOOKEEPER-3063](https://issues.apache.org/jira/browse/ZOOKEEPER-3063) - Track outstanding changes with ArrayDeque
* [ZOOKEEPER-3077](https://issues.apache.org/jira/browse/ZOOKEEPER-3077) - Build native C library outside of source directory
* [ZOOKEEPER-3083](https://issues.apache.org/jira/browse/ZOOKEEPER-3083) - Remove some redundant and noisy log lines
* [ZOOKEEPER-3094](https://issues.apache.org/jira/browse/ZOOKEEPER-3094) - Make BufferSizeTest reliable
* [ZOOKEEPER-3097](https://issues.apache.org/jira/browse/ZOOKEEPER-3097) - Use Runnable instead of Thread for working items in WorkerService to improve the throughput of CommitProcessor
* [ZOOKEEPER-3110](https://issues.apache.org/jira/browse/ZOOKEEPER-3110) - Improve the closeSession throughput in PrepRequestProcessor
* [ZOOKEEPER-3152](https://issues.apache.org/jira/browse/ZOOKEEPER-3152) - Port ZK netty stack to netty 4
* [ZOOKEEPER-3159](https://issues.apache.org/jira/browse/ZOOKEEPER-3159) - Flaky: ClientRequestTimeoutTest.testClientRequestTimeout
* [ZOOKEEPER-3172](https://issues.apache.org/jira/browse/ZOOKEEPER-3172) - Quorum TLS - fix port unification to allow rolling upgrades
* [ZOOKEEPER-3173](https://issues.apache.org/jira/browse/ZOOKEEPER-3173) - Quorum TLS - support PEM trust/key stores
* [ZOOKEEPER-3174](https://issues.apache.org/jira/browse/ZOOKEEPER-3174) - Quorum TLS - support reloading trust/key store
* [ZOOKEEPER-3175](https://issues.apache.org/jira/browse/ZOOKEEPER-3175) - Quorum TLS - test improvements
* [ZOOKEEPER-3176](https://issues.apache.org/jira/browse/ZOOKEEPER-3176) - Quorum TLS - add SSL config options
* [ZOOKEEPER-3195](https://issues.apache.org/jira/browse/ZOOKEEPER-3195) - TLS - disable client-initiated renegotiation
* [ZOOKEEPER-3228](https://issues.apache.org/jira/browse/ZOOKEEPER-3228) - [TLS] Fix key usage extension in test certs
* [ZOOKEEPER-3229](https://issues.apache.org/jira/browse/ZOOKEEPER-3229) - [TLS] add AES-256 ciphers to default cipher list
* [ZOOKEEPER-3235](https://issues.apache.org/jira/browse/ZOOKEEPER-3235) - Enable secure processing and disallow DTDs in the SAXParserFactory
* [ZOOKEEPER-3236](https://issues.apache.org/jira/browse/ZOOKEEPER-3236) - Upgrade BouncyCastle
* [ZOOKEEPER-3250](https://issues.apache.org/jira/browse/ZOOKEEPER-3250) - typo in doc - zookeeperInternals
* [ZOOKEEPER-3262](https://issues.apache.org/jira/browse/ZOOKEEPER-3262) - Update dependencies flagged by OWASP report
* [ZOOKEEPER-3272](https://issues.apache.org/jira/browse/ZOOKEEPER-3272) - Clean up netty4 code per Norman Maurer&#39;s review comments
* [ZOOKEEPER-3273](https://issues.apache.org/jira/browse/ZOOKEEPER-3273) - Sync BouncyCastle version in Maven build and Ant  build
* [ZOOKEEPER-3274](https://issues.apache.org/jira/browse/ZOOKEEPER-3274) - Use CompositeByteBuf to queue data in NettyServerCnxn
* [ZOOKEEPER-3276](https://issues.apache.org/jira/browse/ZOOKEEPER-3276) - Make X509UtilTest.testCreateSSLServerSocketWithPort less flaky
* [ZOOKEEPER-3277](https://issues.apache.org/jira/browse/ZOOKEEPER-3277) - Add trace listener in NettyServerCnxnFactory only if trace logging is enabled
* [ZOOKEEPER-3312](https://issues.apache.org/jira/browse/ZOOKEEPER-3312) - Upgrade Jetty to 9.4.15.v20190215
    
## Test
* [ZOOKEEPER-1441](https://issues.apache.org/jira/browse/ZOOKEEPER-1441) - Some test cases are failing because Port bind issue.
* [ZOOKEEPER-2955](https://issues.apache.org/jira/browse/ZOOKEEPER-2955) - Enable Clover code coverage report
* [ZOOKEEPER-2968](https://issues.apache.org/jira/browse/ZOOKEEPER-2968) - Add C client code coverage tests
* [ZOOKEEPER-3074](https://issues.apache.org/jira/browse/ZOOKEEPER-3074) - Flaky test:org.apache.zookeeper.server.ServerStatsTest.testLatencyMetrics
* [ZOOKEEPER-3204](https://issues.apache.org/jira/browse/ZOOKEEPER-3204) - Reconfig tests are constantly failing on 3.5 after applying Java 11 fix
        
## Task
* [ZOOKEEPER-925](https://issues.apache.org/jira/browse/ZOOKEEPER-925) - Consider maven site generation to replace our forrest site and documentation generation
* [ZOOKEEPER-3062](https://issues.apache.org/jira/browse/ZOOKEEPER-3062) - introduce fsync.warningthresholdms constant for FileTxnLog LOG.warn message
* [ZOOKEEPER-3120](https://issues.apache.org/jira/browse/ZOOKEEPER-3120) - add NetBeans nbproject directory to .gitignore
* [ZOOKEEPER-3197](https://issues.apache.org/jira/browse/ZOOKEEPER-3197) - Improve documentation in ZooKeeperServer.superSecret
* [ZOOKEEPER-3230](https://issues.apache.org/jira/browse/ZOOKEEPER-3230) - Add Apache NetBeans Maven project files to .gitignore
* [ZOOKEEPER-3254](https://issues.apache.org/jira/browse/ZOOKEEPER-3254) - Drop &#39;beta&#39; qualifier from Branch 3.5

# Release Notes - ZooKeeper - Version 3.5.4

Release 3.5.3 added a new feature [ZOOKEEPER-2169](https://issues.apache.org/jira/browse/ZOOKEEPER-2169)
"Enable creation of nodes with TTLs". There was a major oversight when
TTL nodes were implemented. The session ID generator for each server
is seeded with the configured Server ID in the high byte. TTL Nodes
were using the highest bit to denote a TTL node when used in the
ephemeral owner. This meant that Server IDs > 127 that created
ephemeral nodes would have those nodes always considered TTL nodes
(with the TTL being essentially a random number).

[ZOOKEEPER-2901](https://issues.apache.org/jira/browse/ZOOKEEPER-2901)
fixes the issue. By default TTL is disabled and must now be enabled in
zoo.cfg. When TTL Nodes are enabled, the max Server ID changes from
255 to 254. See the documentation for TTL in the administrator guide
(or the referenced JIRAs) for more details.

## Sub-task
* [ZOOKEEPER-2754](https://issues.apache.org/jira/browse/ZOOKEEPER-2754) - Set up Apache Jenkins job that runs the flaky test analyzer script.
* [ZOOKEEPER-2792](https://issues.apache.org/jira/browse/ZOOKEEPER-2792) - [QP MutualAuth]: Port ZOOKEEPER-1045 implementation from branch-3.4 to branch-3.5
* [ZOOKEEPER-2903](https://issues.apache.org/jira/browse/ZOOKEEPER-2903) - Port ZOOKEEPER-2901 to 3.5.4
* [ZOOKEEPER-2939](https://issues.apache.org/jira/browse/ZOOKEEPER-2939) - Deal with maxbuffer as it relates to proposals
* [ZOOKEEPER-2981](https://issues.apache.org/jira/browse/ZOOKEEPER-2981) - Fix build on branch-3.5 for ZOOKEEPER-2939

## Bug
* [ZOOKEEPER-1580](https://issues.apache.org/jira/browse/ZOOKEEPER-1580) - QuorumPeer.setRunning is not used
* [ZOOKEEPER-1782](https://issues.apache.org/jira/browse/ZOOKEEPER-1782) - zookeeper.superUser is not as super as superDigest
* [ZOOKEEPER-1807](https://issues.apache.org/jira/browse/ZOOKEEPER-1807) - Observers spam each other creating connections to the election addr
* [ZOOKEEPER-2101](https://issues.apache.org/jira/browse/ZOOKEEPER-2101) - Transaction larger than max buffer of jute makes zookeeper unavailable
* [ZOOKEEPER-2249](https://issues.apache.org/jira/browse/ZOOKEEPER-2249) - CRC check failed when preAllocSize smaller than node data
* [ZOOKEEPER-2316](https://issues.apache.org/jira/browse/ZOOKEEPER-2316) - comment does not match code logic
* [ZOOKEEPER-2338](https://issues.apache.org/jira/browse/ZOOKEEPER-2338) - c bindings should create socket&#39;s with SOCK_CLOEXEC to avoid fd leaks on fork/exec
* [ZOOKEEPER-2349](https://issues.apache.org/jira/browse/ZOOKEEPER-2349) - Update documentation for snapCount
* [ZOOKEEPER-2355](https://issues.apache.org/jira/browse/ZOOKEEPER-2355) - Ephemeral node is never deleted if follower fails while reading the proposal packet
* [ZOOKEEPER-2491](https://issues.apache.org/jira/browse/ZOOKEEPER-2491) - C client build error in vs 2015 
* [ZOOKEEPER-2581](https://issues.apache.org/jira/browse/ZOOKEEPER-2581) - Not handled NullPointerException while creating key manager and trustManager
* [ZOOKEEPER-2690](https://issues.apache.org/jira/browse/ZOOKEEPER-2690) - Update documentation source for ZOOKEEPER-2574
* [ZOOKEEPER-2722](https://issues.apache.org/jira/browse/ZOOKEEPER-2722) - Flaky Test: org.apache.zookeeper.test.ReadOnlyModeTest.testSessionEstablishment
* [ZOOKEEPER-2725](https://issues.apache.org/jira/browse/ZOOKEEPER-2725) - Upgrading to a global session fails with a multiop
* [ZOOKEEPER-2743](https://issues.apache.org/jira/browse/ZOOKEEPER-2743) - Netty connection leaks JMX connection bean upon connection close in certain race conditions.
* [ZOOKEEPER-2747](https://issues.apache.org/jira/browse/ZOOKEEPER-2747) - Fix ZooKeeperAdmin Compilation Warning
* [ZOOKEEPER-2757](https://issues.apache.org/jira/browse/ZOOKEEPER-2757) - Incorrect path crashes zkCli
* [ZOOKEEPER-2758](https://issues.apache.org/jira/browse/ZOOKEEPER-2758) - Typo: transasction --&gt; transaction
* [ZOOKEEPER-2775](https://issues.apache.org/jira/browse/ZOOKEEPER-2775) - ZK Client not able to connect with Xid out of order error 
* [ZOOKEEPER-2777](https://issues.apache.org/jira/browse/ZOOKEEPER-2777) - There is a typo in zk.py which prevents from using/compiling it.
* [ZOOKEEPER-2783](https://issues.apache.org/jira/browse/ZOOKEEPER-2783) - follower disconnects and cannot reconnect
* [ZOOKEEPER-2785](https://issues.apache.org/jira/browse/ZOOKEEPER-2785) - Server inappropriately throttles connections under load before SASL completes
* [ZOOKEEPER-2786](https://issues.apache.org/jira/browse/ZOOKEEPER-2786) - Flaky test: org.apache.zookeeper.test.ClientTest.testNonExistingOpCode
* [ZOOKEEPER-2797](https://issues.apache.org/jira/browse/ZOOKEEPER-2797) - Invalid TTL from misbehaving client nukes zookeeper
* [ZOOKEEPER-2798](https://issues.apache.org/jira/browse/ZOOKEEPER-2798) - Fix flaky test: org.apache.zookeeper.test.ReadOnlyModeTest.testConnectionEvents
* [ZOOKEEPER-2806](https://issues.apache.org/jira/browse/ZOOKEEPER-2806) - Flaky test: org.apache.zookeeper.server.quorum.FLEBackwardElectionRoundTest.testBackwardElectionRound
* [ZOOKEEPER-2808](https://issues.apache.org/jira/browse/ZOOKEEPER-2808) - ACL with index 1 might be removed if it&#39;s only being used once
* [ZOOKEEPER-2818](https://issues.apache.org/jira/browse/ZOOKEEPER-2818) - Improve the ZooKeeper#setACL  java doc
* [ZOOKEEPER-2819](https://issues.apache.org/jira/browse/ZOOKEEPER-2819) - Changing membership configuration via rolling restart does not work on 3.5.x.
* [ZOOKEEPER-2841](https://issues.apache.org/jira/browse/ZOOKEEPER-2841) - ZooKeeper public include files leak porting changes
* [ZOOKEEPER-2845](https://issues.apache.org/jira/browse/ZOOKEEPER-2845) - Data inconsistency issue due to retain database in leader election
* [ZOOKEEPER-2852](https://issues.apache.org/jira/browse/ZOOKEEPER-2852) - Snapshot size factor is not read from system property
* [ZOOKEEPER-2853](https://issues.apache.org/jira/browse/ZOOKEEPER-2853) - The lastZxidSeen in FileTxnLog.java is never being assigned
* [ZOOKEEPER-2859](https://issues.apache.org/jira/browse/ZOOKEEPER-2859) - CMake build doesn&#39;t support OS X
* [ZOOKEEPER-2861](https://issues.apache.org/jira/browse/ZOOKEEPER-2861) - Main-Class JAR manifest attribute is incorrect
* [ZOOKEEPER-2862](https://issues.apache.org/jira/browse/ZOOKEEPER-2862) - Incorrect javadoc syntax for web links in StaticHostProvider.java
* [ZOOKEEPER-2874](https://issues.apache.org/jira/browse/ZOOKEEPER-2874) - Windows Debug builds don&#39;t link with `/MTd`
* [ZOOKEEPER-2890](https://issues.apache.org/jira/browse/ZOOKEEPER-2890) - Local automatic variable is left uninitialized and then freed.
* [ZOOKEEPER-2893](https://issues.apache.org/jira/browse/ZOOKEEPER-2893) - very poor choice of logging if client fails to connect to server
* [ZOOKEEPER-2901](https://issues.apache.org/jira/browse/ZOOKEEPER-2901) - Session ID that is negative causes mis-calculation of Ephemeral Type
* [ZOOKEEPER-2905](https://issues.apache.org/jira/browse/ZOOKEEPER-2905) - Don&#39;t include `config.h` in `zookeeper.h`
* [ZOOKEEPER-2906](https://issues.apache.org/jira/browse/ZOOKEEPER-2906) - The OWASP dependency check jar should not be included in the default classpath
* [ZOOKEEPER-2908](https://issues.apache.org/jira/browse/ZOOKEEPER-2908) - quorum.auth.MiniKdcTest.testKerberosLogin failing with NPE on java 9
* [ZOOKEEPER-2909](https://issues.apache.org/jira/browse/ZOOKEEPER-2909) - Create ant task to generate ivy dependency reports
* [ZOOKEEPER-2914](https://issues.apache.org/jira/browse/ZOOKEEPER-2914) - compiler warning using java 9
* [ZOOKEEPER-2923](https://issues.apache.org/jira/browse/ZOOKEEPER-2923) - The comment of the variable matchSyncs in class CommitProcessor has a mistake.
* [ZOOKEEPER-2924](https://issues.apache.org/jira/browse/ZOOKEEPER-2924) - Flaky Test: org.apache.zookeeper.test.LoadFromLogTest.testRestoreWithTransactionErrors
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
* [ZOOKEEPER-2997](https://issues.apache.org/jira/browse/ZOOKEEPER-2997) - CMake should not force static CRT linking
* [ZOOKEEPER-3001](https://issues.apache.org/jira/browse/ZOOKEEPER-3001) - Incorrect log message when try to delete container node
* [ZOOKEEPER-3006](https://issues.apache.org/jira/browse/ZOOKEEPER-3006) - Potential NPE in ZKDatabase#calculateTxnLogSizeLimit
* [ZOOKEEPER-3007](https://issues.apache.org/jira/browse/ZOOKEEPER-3007) - Potential NPE in ReferenceCountedACLCache#deserialize 
* [ZOOKEEPER-3025](https://issues.apache.org/jira/browse/ZOOKEEPER-3025) - cmake windows build is broken on jenkins
* [ZOOKEEPER-3027](https://issues.apache.org/jira/browse/ZOOKEEPER-3027) - Accidently removed public API of FileTxnLog.setPreallocSize()
* [ZOOKEEPER-3038](https://issues.apache.org/jira/browse/ZOOKEEPER-3038) - Cleanup some nitpicks in TTL implementation
* [ZOOKEEPER-3039](https://issues.apache.org/jira/browse/ZOOKEEPER-3039) - TxnLogToolkit uses Scanner badly

## New Feature
* [ZOOKEEPER-1703](https://issues.apache.org/jira/browse/ZOOKEEPER-1703) - Please add instructions for running the tutorial
* [ZOOKEEPER-2875](https://issues.apache.org/jira/browse/ZOOKEEPER-2875) - Add ant task for running OWASP dependency report
* [ZOOKEEPER-2994](https://issues.apache.org/jira/browse/ZOOKEEPER-2994) - Tool required to recover log and snapshot entries with CRC errors

## Improvement
* [ZOOKEEPER-1748](https://issues.apache.org/jira/browse/ZOOKEEPER-1748) - TCP keepalive for leader election connections
* [ZOOKEEPER-2359](https://issues.apache.org/jira/browse/ZOOKEEPER-2359) - ZooKeeper client has unnecessary logs for watcher removal errors
* [ZOOKEEPER-2638](https://issues.apache.org/jira/browse/ZOOKEEPER-2638) - ZooKeeper should log which serverCnxnFactory is used during startup
* [ZOOKEEPER-2662](https://issues.apache.org/jira/browse/ZOOKEEPER-2662) - Export a metric for txn log sync times
* [ZOOKEEPER-2697](https://issues.apache.org/jira/browse/ZOOKEEPER-2697) - Handle graceful stop of ZookKeeper client
* [ZOOKEEPER-2744](https://issues.apache.org/jira/browse/ZOOKEEPER-2744) - Typos in the comments of ZooKeeper class
* [ZOOKEEPER-2767](https://issues.apache.org/jira/browse/ZOOKEEPER-2767) - Correct the exception messages in X509Util if truststore location or password is not configured
* [ZOOKEEPER-2788](https://issues.apache.org/jira/browse/ZOOKEEPER-2788) - The define of MAX_CONNECTION_ATTEMPTS in QuorumCnxManager.java seems useless, should it be removed?
* [ZOOKEEPER-2815](https://issues.apache.org/jira/browse/ZOOKEEPER-2815) - 1. Using try clause to close resource; 2. Others code refactoring for PERSISTENCE module
* [ZOOKEEPER-2816](https://issues.apache.org/jira/browse/ZOOKEEPER-2816) - Code refactoring for `ZK_SERVER` module
* [ZOOKEEPER-2824](https://issues.apache.org/jira/browse/ZOOKEEPER-2824) - `FileChannel#size` info should be added to `FileTxnLog#commit` to solve the confuse that reason is too large log or too busy disk I/O
* [ZOOKEEPER-2829](https://issues.apache.org/jira/browse/ZOOKEEPER-2829) - Interface usability / compatibility improvements through Java annotation.
* [ZOOKEEPER-2856](https://issues.apache.org/jira/browse/ZOOKEEPER-2856) - ZooKeeperSaslClient#respondToServer should log exception message of SaslException
* [ZOOKEEPER-2864](https://issues.apache.org/jira/browse/ZOOKEEPER-2864) - Add script to run a java api compatibility tool
* [ZOOKEEPER-2865](https://issues.apache.org/jira/browse/ZOOKEEPER-2865) - Reconfig Causes Inconsistent Configuration file among the nodes
* [ZOOKEEPER-2870](https://issues.apache.org/jira/browse/ZOOKEEPER-2870) - Improve the efficiency of AtomicFileOutputStream
* [ZOOKEEPER-2880](https://issues.apache.org/jira/browse/ZOOKEEPER-2880) - Rename README.txt to README.md
* [ZOOKEEPER-2887](https://issues.apache.org/jira/browse/ZOOKEEPER-2887) - define dependency versions in build.xml to be easily overridden in build.properties
* [ZOOKEEPER-2896](https://issues.apache.org/jira/browse/ZOOKEEPER-2896) - Remove unused imports from org.apache.zookeeper.test.CreateTest.java
* [ZOOKEEPER-2904](https://issues.apache.org/jira/browse/ZOOKEEPER-2904) - Remove unused imports from org.apache.zookeeper.server.quorum.WatchLeakTest
* [ZOOKEEPER-2915](https://issues.apache.org/jira/browse/ZOOKEEPER-2915) - Use &quot;strict&quot; conflict management in ivy
* [ZOOKEEPER-2950](https://issues.apache.org/jira/browse/ZOOKEEPER-2950) - Add keys for the Zxid from the stat command to check_zookeeper.py
* [ZOOKEEPER-2952](https://issues.apache.org/jira/browse/ZOOKEEPER-2952) - Upgrade third party libraries to address vulnerabilities
* [ZOOKEEPER-2967](https://issues.apache.org/jira/browse/ZOOKEEPER-2967) - Add check to validate dataDir and dataLogDir parameters at startup
* [ZOOKEEPER-2971](https://issues.apache.org/jira/browse/ZOOKEEPER-2971) - Create release notes for 3.5.4
* [ZOOKEEPER-2999](https://issues.apache.org/jira/browse/ZOOKEEPER-2999) - CMake build should use target-level commands
* [ZOOKEEPER-3012](https://issues.apache.org/jira/browse/ZOOKEEPER-3012) - Fix unit test: testDataDirAndDataLogDir should not use hardcode test folders

## Test
* [ZOOKEEPER-2415](https://issues.apache.org/jira/browse/ZOOKEEPER-2415) - SessionTest is using Thread deprecated API.
* [ZOOKEEPER-2577](https://issues.apache.org/jira/browse/ZOOKEEPER-2577) - Flaky Test: org.apache.zookeeper.server.quorum.ReconfigDuringLeaderSyncTest.testDuringLeaderSync
* [ZOOKEEPER-2742](https://issues.apache.org/jira/browse/ZOOKEEPER-2742) - Few test cases of org.apache.zookeeper.ZooKeeperTest fails in Windows
* [ZOOKEEPER-2746](https://issues.apache.org/jira/browse/ZOOKEEPER-2746) - Leader hand-off during dynamic reconfig is best effort, while test always expects it
* [ZOOKEEPER-2796](https://issues.apache.org/jira/browse/ZOOKEEPER-2796) - Test org.apache.zookeeper.ZooKeeperTest.testCreateNodeWithoutData is broken by ZOOKEEPER-2757

## Wish
* [ZOOKEEPER-2795](https://issues.apache.org/jira/browse/ZOOKEEPER-2795) - Change log level for &quot;ZKShutdownHandler is not registered&quot; error message

## Task
* [ZOOKEEPER-2713](https://issues.apache.org/jira/browse/ZOOKEEPER-2713) - Create CVE text for ZOOKEEPER-2693 &quot;DOS attack on wchp/wchc four letter words (4lw)&quot;
* [ZOOKEEPER-3002](https://issues.apache.org/jira/browse/ZOOKEEPER-3002) - Upgrade branches 3.5 and trunk to Java 1.8
* [ZOOKEEPER-3017](https://issues.apache.org/jira/browse/ZOOKEEPER-3017) - Link libm in CMake on FreeBSD

# Release Notes - ZooKeeper - Version 3.5.3

## Sub-task
* [ZOOKEEPER-2080](https://issues.apache.org/jira/browse/ZOOKEEPER-2080) - Fix deadlock in dynamic reconfiguration
* [ZOOKEEPER-2152](https://issues.apache.org/jira/browse/ZOOKEEPER-2152) - Intermittent failure in TestReconfig.cc
* [ZOOKEEPER-2692](https://issues.apache.org/jira/browse/ZOOKEEPER-2692) - Fix race condition in testWatchAutoResetWithPending

## Bug
* [ZOOKEEPER-1256](https://issues.apache.org/jira/browse/ZOOKEEPER-1256) - ClientPortBindTest is failing on Mac OS X
* [ZOOKEEPER-1806](https://issues.apache.org/jira/browse/ZOOKEEPER-1806) - testCurrentServersAreObserversInNextConfig failing frequently on trunk with non-jdk6
* [ZOOKEEPER-1898](https://issues.apache.org/jira/browse/ZOOKEEPER-1898) - ZooKeeper Java cli shell always returns &quot;0&quot; as exit code
* [ZOOKEEPER-1927](https://issues.apache.org/jira/browse/ZOOKEEPER-1927) - zkServer.sh fails to read dataDir (and others) from zoo.cfg on Solaris 10 (grep issue, manifests as FAILED TO WRITE PID).  
* [ZOOKEEPER-2014](https://issues.apache.org/jira/browse/ZOOKEEPER-2014) - Only admin should be allowed to reconfig a cluster
* [ZOOKEEPER-2074](https://issues.apache.org/jira/browse/ZOOKEEPER-2074) - Incorrect exit codes for &quot;./zkCli.sh cmd arg&quot;
* [ZOOKEEPER-2172](https://issues.apache.org/jira/browse/ZOOKEEPER-2172) - Cluster crashes when reconfig a new node as a participant
* [ZOOKEEPER-2247](https://issues.apache.org/jira/browse/ZOOKEEPER-2247) - Zookeeper service becomes unavailable when leader fails to write transaction log
* [ZOOKEEPER-2383](https://issues.apache.org/jira/browse/ZOOKEEPER-2383) - Startup race in ZooKeeperServer
* [ZOOKEEPER-2442](https://issues.apache.org/jira/browse/ZOOKEEPER-2442) - Socket leak in QuorumCnxManager connectOne
* [ZOOKEEPER-2460](https://issues.apache.org/jira/browse/ZOOKEEPER-2460) - Remove javacc dependency from public Maven pom
* [ZOOKEEPER-2463](https://issues.apache.org/jira/browse/ZOOKEEPER-2463) - TestMulti is broken in the C client
* [ZOOKEEPER-2464](https://issues.apache.org/jira/browse/ZOOKEEPER-2464) - NullPointerException on ContainerManager
* [ZOOKEEPER-2465](https://issues.apache.org/jira/browse/ZOOKEEPER-2465) - Documentation copyright notice is out of date.
* [ZOOKEEPER-2467](https://issues.apache.org/jira/browse/ZOOKEEPER-2467) - NullPointerException when redo Command is passed negative value
* [ZOOKEEPER-2470](https://issues.apache.org/jira/browse/ZOOKEEPER-2470) - ServerConfig#parse(String[])  ignores tickTime
* [ZOOKEEPER-2477](https://issues.apache.org/jira/browse/ZOOKEEPER-2477) - documentation should refer to Java cli shell and not C cli shell
* [ZOOKEEPER-2498](https://issues.apache.org/jira/browse/ZOOKEEPER-2498) - Potential resource leak in C client when processing unexpected / out of order response
* [ZOOKEEPER-2500](https://issues.apache.org/jira/browse/ZOOKEEPER-2500) - Fix compilation warnings for CliException classes
* [ZOOKEEPER-2517](https://issues.apache.org/jira/browse/ZOOKEEPER-2517) - jute.maxbuffer is ignored
* [ZOOKEEPER-2536](https://issues.apache.org/jira/browse/ZOOKEEPER-2536) - When provide path for &quot;dataDir&quot; with trailing space, it is taking correct path (by trucating space) for snapshot but creating temporary file with some junk folder name for zookeeper_server.pid
* [ZOOKEEPER-2537](https://issues.apache.org/jira/browse/ZOOKEEPER-2537) - When provide path for &quot;dataDir&quot; with heading space, it is taking correct path (by trucating space) for snapshot but zookeeper_server.pid is getting created in root (/) folder
* [ZOOKEEPER-2539](https://issues.apache.org/jira/browse/ZOOKEEPER-2539) - Throwing nullpointerException when run the command &quot;config -c&quot; when client port is mentioned as separate and not like new style
* [ZOOKEEPER-2548](https://issues.apache.org/jira/browse/ZOOKEEPER-2548) - zooInspector does not start on Windows
* [ZOOKEEPER-2558](https://issues.apache.org/jira/browse/ZOOKEEPER-2558) - Potential memory leak in recordio.c
* [ZOOKEEPER-2573](https://issues.apache.org/jira/browse/ZOOKEEPER-2573) - Modify Info.REVISION to adapt git repo
* [ZOOKEEPER-2574](https://issues.apache.org/jira/browse/ZOOKEEPER-2574) - PurgeTxnLog can inadvertently delete required txn log files
* [ZOOKEEPER-2579](https://issues.apache.org/jira/browse/ZOOKEEPER-2579) - ZooKeeper server should verify that dataDir and snapDir are writeable before starting
* [ZOOKEEPER-2606](https://issues.apache.org/jira/browse/ZOOKEEPER-2606) - SaslServerCallbackHandler#handleAuthorizeCallback() should log the exception
* [ZOOKEEPER-2611](https://issues.apache.org/jira/browse/ZOOKEEPER-2611) - zoo_remove_watchers - can remove the wrong watch 
* [ZOOKEEPER-2617](https://issues.apache.org/jira/browse/ZOOKEEPER-2617) - correct a few spelling typos
* [ZOOKEEPER-2622](https://issues.apache.org/jira/browse/ZOOKEEPER-2622) - ZooTrace.logQuorumPacket does nothing
* [ZOOKEEPER-2627](https://issues.apache.org/jira/browse/ZOOKEEPER-2627) - Remove ZRWSERVERFOUND from C client and replace handle_error with something more semantically explicit for r/w server reconnect.
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
* [ZOOKEEPER-2687](https://issues.apache.org/jira/browse/ZOOKEEPER-2687) - Deadlock while shutting down the Leader server.
* [ZOOKEEPER-2693](https://issues.apache.org/jira/browse/ZOOKEEPER-2693) - DOS attack on wchp/wchc four letter words (4lw)
* [ZOOKEEPER-2726](https://issues.apache.org/jira/browse/ZOOKEEPER-2726) - Patch for ZOOKEEPER-2693 introduces potential race condition
* [ZOOKEEPER-2737](https://issues.apache.org/jira/browse/ZOOKEEPER-2737) - NettyServerCnxFactory leaks connection if exception happens while writing to a channel.

## Improvement
* [ZOOKEEPER-2479](https://issues.apache.org/jira/browse/ZOOKEEPER-2479) - Add &#39;electionTimeTaken&#39; value in LeaderMXBean and FollowerMXBean
* [ZOOKEEPER-2489](https://issues.apache.org/jira/browse/ZOOKEEPER-2489) - Upgrade Jetty dependency to a recent stable release version.
* [ZOOKEEPER-2505](https://issues.apache.org/jira/browse/ZOOKEEPER-2505) - Use shared library instead of static library in C client unit test
* [ZOOKEEPER-2507](https://issues.apache.org/jira/browse/ZOOKEEPER-2507) - C unit test improvement: line break between &#39;ZooKeeper server started&#39; and &#39;Running&#39;
* [ZOOKEEPER-2511](https://issues.apache.org/jira/browse/ZOOKEEPER-2511) - Implement AutoCloseable in ZooKeeper.java
* [ZOOKEEPER-2557](https://issues.apache.org/jira/browse/ZOOKEEPER-2557) - Update gitignore to account for other file extensions
* [ZOOKEEPER-2594](https://issues.apache.org/jira/browse/ZOOKEEPER-2594) - Use TLS for downloading artifacts during build
* [ZOOKEEPER-2620](https://issues.apache.org/jira/browse/ZOOKEEPER-2620) - Add comments to testReadOnlySnapshotDir and testReadOnlyTxnLogDir indicating that the tests will fail when run as root
* [ZOOKEEPER-2655](https://issues.apache.org/jira/browse/ZOOKEEPER-2655) - Improve NIOServerCnxn#isZKServerRunning to reflect the semantics correctly
* [ZOOKEEPER-2672](https://issues.apache.org/jira/browse/ZOOKEEPER-2672) - Remove CHANGE.txt
* [ZOOKEEPER-2682](https://issues.apache.org/jira/browse/ZOOKEEPER-2682) - Make it optional to fail build on test failure
* [ZOOKEEPER-2724](https://issues.apache.org/jira/browse/ZOOKEEPER-2724) - Skip cert files for releaseaudit target.

## New Feature
* [ZOOKEEPER-1962](https://issues.apache.org/jira/browse/ZOOKEEPER-1962) - Add a CLI command to recursively list a znode and children
* [ZOOKEEPER-2719](https://issues.apache.org/jira/browse/ZOOKEEPER-2719) - Port ZOOKEEPER-2169 to 3.5 branch

## Task
* [ZOOKEEPER-2658](https://issues.apache.org/jira/browse/ZOOKEEPER-2658) - Trunk / branch-3.5 build broken.
* [ZOOKEEPER-2709](https://issues.apache.org/jira/browse/ZOOKEEPER-2709) - Clarify documentation around &quot;auth&quot; ACL scheme
* [ZOOKEEPER-2734](https://issues.apache.org/jira/browse/ZOOKEEPER-2734) - 3.5.3 should be a beta release instead of alpha release.

## Test
* [ZOOKEEPER-2482](https://issues.apache.org/jira/browse/ZOOKEEPER-2482) - Flaky Test: org.apache.zookeeper.test.ClientPortBindTest.testBindByAddress
* [ZOOKEEPER-2483](https://issues.apache.org/jira/browse/ZOOKEEPER-2483) - Flaky Test: org.apache.zookeeper.test.LETest.testLE
* [ZOOKEEPER-2484](https://issues.apache.org/jira/browse/ZOOKEEPER-2484) - Flaky Test: org.apache.zookeeper.test.LoadFromLogTest.testLoadFailure
* [ZOOKEEPER-2508](https://issues.apache.org/jira/browse/ZOOKEEPER-2508) - Many ZooKeeper tests are flaky because they proceed with zk operation without connecting to ZooKeeper server.
* [ZOOKEEPER-2656](https://issues.apache.org/jira/browse/ZOOKEEPER-2656) - Fix ServerConfigTest#testValidArguments test case failures
* [ZOOKEEPER-2664](https://issues.apache.org/jira/browse/ZOOKEEPER-2664) - ClientPortBindTest#testBindByAddress may fail due to &quot;No such device&quot; exception
* [ZOOKEEPER-2665](https://issues.apache.org/jira/browse/ZOOKEEPER-2665) - Port QA github pull request build to branch 3.4 and 3.5
* [ZOOKEEPER-2716](https://issues.apache.org/jira/browse/ZOOKEEPER-2716) - Flaky Test: org.apache.zookeeper.server.SessionTrackerTest.testAddSessionAfterSessionExpiry
* [ZOOKEEPER-2718](https://issues.apache.org/jira/browse/ZOOKEEPER-2718) - org.apache.zookeeper.server.quorum.StandaloneDisabledTest fails intermittently

# Release Notes - ZooKeeper - Version 3.5.2

## Sub-task
* [ZOOKEEPER-1872](https://issues.apache.org/jira/browse/ZOOKEEPER-1872) - QuorumPeer is not shutdown in few cases
* [ZOOKEEPER-2094](https://issues.apache.org/jira/browse/ZOOKEEPER-2094) - SSL feature on Netty
* [ZOOKEEPER-2137](https://issues.apache.org/jira/browse/ZOOKEEPER-2137) - Make testPortChange() less flaky
* [ZOOKEEPER-2396](https://issues.apache.org/jira/browse/ZOOKEEPER-2396) - Login object in ZooKeeperSaslClient is static

## Bug
* [ZOOKEEPER-412](https://issues.apache.org/jira/browse/ZOOKEEPER-412) - checkstyle target fails trunk build
* [ZOOKEEPER-706](https://issues.apache.org/jira/browse/ZOOKEEPER-706) - large numbers of watches can cause session re-establishment to fail
* [ZOOKEEPER-1029](https://issues.apache.org/jira/browse/ZOOKEEPER-1029) - C client bug in zookeeper_init (if bad hostname is given)
* [ZOOKEEPER-1077](https://issues.apache.org/jira/browse/ZOOKEEPER-1077) - C client lib doesn&#39;t build on Solaris
* [ZOOKEEPER-1371](https://issues.apache.org/jira/browse/ZOOKEEPER-1371) - Remove dependency on log4j in the source code.
* [ZOOKEEPER-1460](https://issues.apache.org/jira/browse/ZOOKEEPER-1460) - IPv6 literal address not supported for quorum members
* [ZOOKEEPER-1676](https://issues.apache.org/jira/browse/ZOOKEEPER-1676) - C client zookeeper_interest returning ZOK on Connection Loss
* [ZOOKEEPER-1803](https://issues.apache.org/jira/browse/ZOOKEEPER-1803) - Add description for pzxid in programmer&#39;s guide.
* [ZOOKEEPER-1853](https://issues.apache.org/jira/browse/ZOOKEEPER-1853) - zkCli.sh can&#39;t issue a CREATE command containing spaces in the data
* [ZOOKEEPER-1927](https://issues.apache.org/jira/browse/ZOOKEEPER-1927) - zkServer.sh fails to read dataDir (and others) from zoo.cfg on Solaris 10 (grep issue, manifests as FAILED TO WRITE PID).  
* [ZOOKEEPER-1929](https://issues.apache.org/jira/browse/ZOOKEEPER-1929) - std::length_error on update children
* [ZOOKEEPER-1991](https://issues.apache.org/jira/browse/ZOOKEEPER-1991) - zkServer.sh returns with a zero exit status when a ZooKeeper process is already running
* [ZOOKEEPER-2133](https://issues.apache.org/jira/browse/ZOOKEEPER-2133) - zkperl: Segmentation fault if getting a node with null value
* [ZOOKEEPER-2141](https://issues.apache.org/jira/browse/ZOOKEEPER-2141) - ACL cache in DataTree never removes entries
* [ZOOKEEPER-2142](https://issues.apache.org/jira/browse/ZOOKEEPER-2142) - JMX ObjectName is incorrect for observers
* [ZOOKEEPER-2156](https://issues.apache.org/jira/browse/ZOOKEEPER-2156) - If JAVA_HOME is not set zk startup and fetching status command execution result misleads user.
* [ZOOKEEPER-2174](https://issues.apache.org/jira/browse/ZOOKEEPER-2174) - JUnit4ZKTestRunner logs test failure for all exceptions even if the test method is annotated with an expected exception.
* [ZOOKEEPER-2195](https://issues.apache.org/jira/browse/ZOOKEEPER-2195) - fsync.warningthresholdms in zoo.cfg not working
* [ZOOKEEPER-2201](https://issues.apache.org/jira/browse/ZOOKEEPER-2201) - Network issues can cause cluster to hang due to near-deadlock
* [ZOOKEEPER-2211](https://issues.apache.org/jira/browse/ZOOKEEPER-2211) - PurgeTxnLog does not correctly purge when snapshots and logs are at different locations
* [ZOOKEEPER-2227](https://issues.apache.org/jira/browse/ZOOKEEPER-2227) - stmk four-letter word fails execution at server while reading trace mask argument.
* [ZOOKEEPER-2229](https://issues.apache.org/jira/browse/ZOOKEEPER-2229) - Several four-letter words are undocumented.
* [ZOOKEEPER-2235](https://issues.apache.org/jira/browse/ZOOKEEPER-2235) - License update
* [ZOOKEEPER-2239](https://issues.apache.org/jira/browse/ZOOKEEPER-2239) - JMX State from LocalPeerBean incorrect
* [ZOOKEEPER-2243](https://issues.apache.org/jira/browse/ZOOKEEPER-2243) - Supported platforms is completely out of date
* [ZOOKEEPER-2244](https://issues.apache.org/jira/browse/ZOOKEEPER-2244) - On Windows zookeeper fails to restart
* [ZOOKEEPER-2245](https://issues.apache.org/jira/browse/ZOOKEEPER-2245) - SimpleSysTest test cases fails
* [ZOOKEEPER-2252](https://issues.apache.org/jira/browse/ZOOKEEPER-2252) - Random test case failure in org.apache.zookeeper.test.StaticHostProviderTest
* [ZOOKEEPER-2256](https://issues.apache.org/jira/browse/ZOOKEEPER-2256) - Zookeeper is not using specified JMX port in zkEnv.sh
* [ZOOKEEPER-2264](https://issues.apache.org/jira/browse/ZOOKEEPER-2264) - Wrong error message when secureClientPortAddress is configured but secureClientPort is not configured 
* [ZOOKEEPER-2268](https://issues.apache.org/jira/browse/ZOOKEEPER-2268) - Zookeeper doc creation fails on windows
* [ZOOKEEPER-2269](https://issues.apache.org/jira/browse/ZOOKEEPER-2269) - NullPointerException  in RemotePeerBean
* [ZOOKEEPER-2279](https://issues.apache.org/jira/browse/ZOOKEEPER-2279) - QuorumPeer  loadDataBase() error message is incorrect
* [ZOOKEEPER-2281](https://issues.apache.org/jira/browse/ZOOKEEPER-2281) - ZK Server startup fails if there are spaces in the JAVA_HOME path
* [ZOOKEEPER-2283](https://issues.apache.org/jira/browse/ZOOKEEPER-2283) - traceFile property is not used in the ZooKeeper,  it should be removed from documentation
* [ZOOKEEPER-2294](https://issues.apache.org/jira/browse/ZOOKEEPER-2294) - Ant target generate-clover-reports is broken
* [ZOOKEEPER-2295](https://issues.apache.org/jira/browse/ZOOKEEPER-2295) - TGT refresh time logic is wrong
* [ZOOKEEPER-2297](https://issues.apache.org/jira/browse/ZOOKEEPER-2297) - NPE is thrown while creating &quot;key manager&quot; and &quot;trust manager&quot; 
* [ZOOKEEPER-2299](https://issues.apache.org/jira/browse/ZOOKEEPER-2299) - NullPointerException in LocalPeerBean for ClientAddress
* [ZOOKEEPER-2301](https://issues.apache.org/jira/browse/ZOOKEEPER-2301) - QuorumPeer does not listen on passed client IP in the constructor
* [ZOOKEEPER-2302](https://issues.apache.org/jira/browse/ZOOKEEPER-2302) - Some test cases are not running because wrongly named
* [ZOOKEEPER-2304](https://issues.apache.org/jira/browse/ZOOKEEPER-2304) - JMX ClientPort from ZooKeeperServerBean incorrect
* [ZOOKEEPER-2311](https://issues.apache.org/jira/browse/ZOOKEEPER-2311) - assert in setup_random
* [ZOOKEEPER-2329](https://issues.apache.org/jira/browse/ZOOKEEPER-2329) - Clear javac and javadoc warning from zookeeper
* [ZOOKEEPER-2330](https://issues.apache.org/jira/browse/ZOOKEEPER-2330) - ZooKeeper close API does not close Login thread.
* [ZOOKEEPER-2337](https://issues.apache.org/jira/browse/ZOOKEEPER-2337) - Fake &quot;invalid&quot; hostnames used in tests are sometimes valid
* [ZOOKEEPER-2340](https://issues.apache.org/jira/browse/ZOOKEEPER-2340) - JMX is disabled even if JMXDISABLE is false
* [ZOOKEEPER-2360](https://issues.apache.org/jira/browse/ZOOKEEPER-2360) - Update commons collections version used by tests/releaseaudit
* [ZOOKEEPER-2364](https://issues.apache.org/jira/browse/ZOOKEEPER-2364) - &quot;ant docs&quot; fails on branch-3.5 due to missing releasenotes.xml.
* [ZOOKEEPER-2366](https://issues.apache.org/jira/browse/ZOOKEEPER-2366) - Reconfiguration of client port causes a socket leak
* [ZOOKEEPER-2375](https://issues.apache.org/jira/browse/ZOOKEEPER-2375) - Prevent multiple initialization of login object in each ZooKeeperSaslClient instance
* [ZOOKEEPER-2379](https://issues.apache.org/jira/browse/ZOOKEEPER-2379) - recent commit broke findbugs qabot check
* [ZOOKEEPER-2380](https://issues.apache.org/jira/browse/ZOOKEEPER-2380) - Deadlock between leader shutdown and forwarding ACK to the leader
* [ZOOKEEPER-2385](https://issues.apache.org/jira/browse/ZOOKEEPER-2385) - Zookeeper trunk build is failing on windows
* [ZOOKEEPER-2388](https://issues.apache.org/jira/browse/ZOOKEEPER-2388) - Unit tests failing on Solaris
* [ZOOKEEPER-2393](https://issues.apache.org/jira/browse/ZOOKEEPER-2393) - Revert run-time dependency on log4j and slf4j-log4j12
* [ZOOKEEPER-2405](https://issues.apache.org/jira/browse/ZOOKEEPER-2405) - getTGT() in Login.java mishandles confidential information
* [ZOOKEEPER-2413](https://issues.apache.org/jira/browse/ZOOKEEPER-2413) - ContainerManager doesn&#39;t close the Timer it creates when stop() is called
* [ZOOKEEPER-2450](https://issues.apache.org/jira/browse/ZOOKEEPER-2450) - Upgrade Netty version due to security vulnerability (CVE-2014-3488)
* [ZOOKEEPER-2457](https://issues.apache.org/jira/browse/ZOOKEEPER-2457) - Remove license file for servlet-api dependency
* [ZOOKEEPER-2458](https://issues.apache.org/jira/browse/ZOOKEEPER-2458) - Remove license file for servlet-api dependency
* [ZOOKEEPER-2459](https://issues.apache.org/jira/browse/ZOOKEEPER-2459) - Update NOTICE file with Netty notice

## Improvement
* [ZOOKEEPER-2040](https://issues.apache.org/jira/browse/ZOOKEEPER-2040) - Server to log underlying cause of SASL connection problems
* [ZOOKEEPER-2087](https://issues.apache.org/jira/browse/ZOOKEEPER-2087) - Few UX improvements in ZooInspector
* [ZOOKEEPER-2139](https://issues.apache.org/jira/browse/ZOOKEEPER-2139) - Support multiple ZooKeeper client, with different configurations, in a single JVM
* [ZOOKEEPER-2191](https://issues.apache.org/jira/browse/ZOOKEEPER-2191) - Continue supporting prior Ant versions that don&#39;t implement the threads attribute for the JUnit task.
* [ZOOKEEPER-2240](https://issues.apache.org/jira/browse/ZOOKEEPER-2240) - Make the three-node minimum more explicit in documentation and on website
* [ZOOKEEPER-2300](https://issues.apache.org/jira/browse/ZOOKEEPER-2300) - Expose SecureClientPort and SecureClientAddress JMX properties
* [ZOOKEEPER-2306](https://issues.apache.org/jira/browse/ZOOKEEPER-2306) - Remove file delete duplicate  code from test code
* [ZOOKEEPER-2315](https://issues.apache.org/jira/browse/ZOOKEEPER-2315) - Change client connect zk service timeout log level from Info to Warn level
* [ZOOKEEPER-2326](https://issues.apache.org/jira/browse/ZOOKEEPER-2326) - Include connected server address:port in log
* [ZOOKEEPER-2373](https://issues.apache.org/jira/browse/ZOOKEEPER-2373) - Licenses section missing from pom file
* [ZOOKEEPER-2378](https://issues.apache.org/jira/browse/ZOOKEEPER-2378) - upgrade ivy to recent version
* [ZOOKEEPER-2392](https://issues.apache.org/jira/browse/ZOOKEEPER-2392) - Update netty to 3.7.1.Final
* [ZOOKEEPER-2402](https://issues.apache.org/jira/browse/ZOOKEEPER-2402) - Document client side properties
* [ZOOKEEPER-2410](https://issues.apache.org/jira/browse/ZOOKEEPER-2410) - add time unit to &#39;ELECTION TOOK&#39; log.info message
* [ZOOKEEPER-2433](https://issues.apache.org/jira/browse/ZOOKEEPER-2433) - ZooKeeperSaslServer: allow user principals in subject

## Task
* [ZOOKEEPER-1604](https://issues.apache.org/jira/browse/ZOOKEEPER-1604) - remove rpm/deb/... packaging

# Release Notes - ZooKeeper - Version 3.5.1

## Sub-task
* [ZOOKEEPER-1626](https://issues.apache.org/jira/browse/ZOOKEEPER-1626) - Zookeeper C client should be tolerant of clock adjustments 
* [ZOOKEEPER-1660](https://issues.apache.org/jira/browse/ZOOKEEPER-1660) - Add documentation for dynamic reconfiguration
* [ZOOKEEPER-2047](https://issues.apache.org/jira/browse/ZOOKEEPER-2047) - testTruncationNullLog fails on windows
* [ZOOKEEPER-2069](https://issues.apache.org/jira/browse/ZOOKEEPER-2069) - Netty Support for ClientCnxnSocket
* [ZOOKEEPER-2119](https://issues.apache.org/jira/browse/ZOOKEEPER-2119) - Netty client docs
* [ZOOKEEPER-2123](https://issues.apache.org/jira/browse/ZOOKEEPER-2123) - Provide implementation of X509 AuthenticationProvider
* [ZOOKEEPER-2125](https://issues.apache.org/jira/browse/ZOOKEEPER-2125) - SSL on Netty client-server communication
* [ZOOKEEPER-2134](https://issues.apache.org/jira/browse/ZOOKEEPER-2134) - AsyncHammerTest.testHammer fails intermittently
* [ZOOKEEPER-2153](https://issues.apache.org/jira/browse/ZOOKEEPER-2153) - X509 Authentication Documentation

## Bug
* [ZOOKEEPER-1366](https://issues.apache.org/jira/browse/ZOOKEEPER-1366) - Zookeeper should be tolerant of clock adjustments
* [ZOOKEEPER-1784](https://issues.apache.org/jira/browse/ZOOKEEPER-1784) - Logic to process INFORMANDACTIVATE packets in syncWithLeader seems bogus
* [ZOOKEEPER-1893](https://issues.apache.org/jira/browse/ZOOKEEPER-1893) - automake: use serial-tests option
* [ZOOKEEPER-1917](https://issues.apache.org/jira/browse/ZOOKEEPER-1917) - Apache Zookeeper logs cleartext admin passwords
* [ZOOKEEPER-1949](https://issues.apache.org/jira/browse/ZOOKEEPER-1949) - recipes jar not included in the distribution package
* [ZOOKEEPER-1952](https://issues.apache.org/jira/browse/ZOOKEEPER-1952) - Default log directory and file name can be changed
* [ZOOKEEPER-1987](https://issues.apache.org/jira/browse/ZOOKEEPER-1987) - unable to restart 3 node cluster
* [ZOOKEEPER-2006](https://issues.apache.org/jira/browse/ZOOKEEPER-2006) - Standalone mode won&#39;t take client port from dynamic config
* [ZOOKEEPER-2008](https://issues.apache.org/jira/browse/ZOOKEEPER-2008) - System test fails due to missing leader election port
* [ZOOKEEPER-2013](https://issues.apache.org/jira/browse/ZOOKEEPER-2013) - typos in zookeeperProgrammers
* [ZOOKEEPER-2026](https://issues.apache.org/jira/browse/ZOOKEEPER-2026) - Startup order in ServerCnxnFactory-ies is wrong
* [ZOOKEEPER-2029](https://issues.apache.org/jira/browse/ZOOKEEPER-2029) - Leader.LearnerCnxAcceptor should handle exceptions in run()
* [ZOOKEEPER-2030](https://issues.apache.org/jira/browse/ZOOKEEPER-2030) - dynamicConfigFile should have an absolute path, not a relative path, to the dynamic configuration file
* [ZOOKEEPER-2039](https://issues.apache.org/jira/browse/ZOOKEEPER-2039) - Jute compareBytes incorrect comparison index
* [ZOOKEEPER-2049](https://issues.apache.org/jira/browse/ZOOKEEPER-2049) - Yosemite build failure: htonll conflict
* [ZOOKEEPER-2052](https://issues.apache.org/jira/browse/ZOOKEEPER-2052) - Unable to delete a node when the node has no children
* [ZOOKEEPER-2056](https://issues.apache.org/jira/browse/ZOOKEEPER-2056) - Zookeeper 3.4.x and 3.5.0-alpha is not OSGi compliant
* [ZOOKEEPER-2060](https://issues.apache.org/jira/browse/ZOOKEEPER-2060) - Trace bug in NettyServerCnxnFactory
* [ZOOKEEPER-2062](https://issues.apache.org/jira/browse/ZOOKEEPER-2062) - RemoveWatchesTest takes forever to run
* [ZOOKEEPER-2064](https://issues.apache.org/jira/browse/ZOOKEEPER-2064) - Prevent resource leak in various classes
* [ZOOKEEPER-2072](https://issues.apache.org/jira/browse/ZOOKEEPER-2072) - Netty Server Should Configure Child Channel Pipeline By Specifying ChannelPipelineFactory
* [ZOOKEEPER-2073](https://issues.apache.org/jira/browse/ZOOKEEPER-2073) - Memory leak on zookeeper_close
* [ZOOKEEPER-2096](https://issues.apache.org/jira/browse/ZOOKEEPER-2096) - C client builds with incorrect error codes in VisualStudio 2010+
* [ZOOKEEPER-2109](https://issues.apache.org/jira/browse/ZOOKEEPER-2109) - Typo in src/c/src/load_gen.c
* [ZOOKEEPER-2111](https://issues.apache.org/jira/browse/ZOOKEEPER-2111) - Not isAlive states should be synchronized in ClientCnxn
* [ZOOKEEPER-2114](https://issues.apache.org/jira/browse/ZOOKEEPER-2114) - jute generated allocate_* functions are not externally visible
* [ZOOKEEPER-2124](https://issues.apache.org/jira/browse/ZOOKEEPER-2124) - Allow Zookeeper version string to have underscore &#39;_&#39;
* [ZOOKEEPER-2146](https://issues.apache.org/jira/browse/ZOOKEEPER-2146) - BinaryInputArchive readString should check length before allocating memory
* [ZOOKEEPER-2157](https://issues.apache.org/jira/browse/ZOOKEEPER-2157) - Upgrade option should be removed from zkServer.sh usage
* [ZOOKEEPER-2171](https://issues.apache.org/jira/browse/ZOOKEEPER-2171) - avoid reverse lookups in QuorumCnxManager
* [ZOOKEEPER-2173](https://issues.apache.org/jira/browse/ZOOKEEPER-2173) - ZK startup failure should be handled with proper error message
* [ZOOKEEPER-2178](https://issues.apache.org/jira/browse/ZOOKEEPER-2178) - Native client fails compilation on Windows.
* [ZOOKEEPER-2182](https://issues.apache.org/jira/browse/ZOOKEEPER-2182) - Several test suites are not running during pre-commit, because their names do not end with &quot;Test&quot;.
* [ZOOKEEPER-2186](https://issues.apache.org/jira/browse/ZOOKEEPER-2186) - QuorumCnxManager#receiveConnection may crash with random input
* [ZOOKEEPER-2187](https://issues.apache.org/jira/browse/ZOOKEEPER-2187) - remove duplicated code between CreateRequest{,2}
* [ZOOKEEPER-2190](https://issues.apache.org/jira/browse/ZOOKEEPER-2190) - In StandaloneDisabledTest, testReconfig() shouldn&#39;t take leaving servers as joining servers
* [ZOOKEEPER-2193](https://issues.apache.org/jira/browse/ZOOKEEPER-2193) - reconfig command completes even if parameter is wrong obviously
* [ZOOKEEPER-2197](https://issues.apache.org/jira/browse/ZOOKEEPER-2197) - non-ascii character in FinalRequestProcessor.java
* [ZOOKEEPER-2198](https://issues.apache.org/jira/browse/ZOOKEEPER-2198) - Set default test.junit.threads to 1.
* [ZOOKEEPER-2199](https://issues.apache.org/jira/browse/ZOOKEEPER-2199) - Don&#39;t include unistd.h in windows
* [ZOOKEEPER-2210](https://issues.apache.org/jira/browse/ZOOKEEPER-2210) - clock_gettime is not available in os x
* [ZOOKEEPER-2212](https://issues.apache.org/jira/browse/ZOOKEEPER-2212) - distributed race condition related to QV version
* [ZOOKEEPER-2213](https://issues.apache.org/jira/browse/ZOOKEEPER-2213) - Empty path in Set crashes server and prevents restart
* [ZOOKEEPER-2221](https://issues.apache.org/jira/browse/ZOOKEEPER-2221) - Zookeeper JettyAdminServer server should start on configured IP.
* [ZOOKEEPER-2224](https://issues.apache.org/jira/browse/ZOOKEEPER-2224) - Four letter command hangs when network is slow
* [ZOOKEEPER-2235](https://issues.apache.org/jira/browse/ZOOKEEPER-2235) - License update

## Improvement
* [ZOOKEEPER-1423](https://issues.apache.org/jira/browse/ZOOKEEPER-1423) - 4lw and jmx should expose the size of the datadir/datalogdir
* [ZOOKEEPER-1506](https://issues.apache.org/jira/browse/ZOOKEEPER-1506) - Re-try DNS hostname -&gt; IP resolution if node connection fails
* [ZOOKEEPER-1907](https://issues.apache.org/jira/browse/ZOOKEEPER-1907) - Improve Thread handling
* [ZOOKEEPER-1948](https://issues.apache.org/jira/browse/ZOOKEEPER-1948) - Enable JMX remote monitoring
* [ZOOKEEPER-1963](https://issues.apache.org/jira/browse/ZOOKEEPER-1963) - Make JDK 7 the minimum requirement for Zookeeper
* [ZOOKEEPER-1994](https://issues.apache.org/jira/browse/ZOOKEEPER-1994) - Backup config files.
* [ZOOKEEPER-2066](https://issues.apache.org/jira/browse/ZOOKEEPER-2066) - Updates to README.txt
* [ZOOKEEPER-2079](https://issues.apache.org/jira/browse/ZOOKEEPER-2079) - Stop daemon with &quot;kill&quot; rather than &quot;kill -9&quot;
* [ZOOKEEPER-2098](https://issues.apache.org/jira/browse/ZOOKEEPER-2098) - QuorumCnxManager: use BufferedOutputStream for initial msg
* [ZOOKEEPER-2107](https://issues.apache.org/jira/browse/ZOOKEEPER-2107) - zookeeper client should support custom HostProviders
* [ZOOKEEPER-2110](https://issues.apache.org/jira/browse/ZOOKEEPER-2110) - Typo fixes in the ZK documentation
* [ZOOKEEPER-2126](https://issues.apache.org/jira/browse/ZOOKEEPER-2126) - Improve exit log messsage of EventThread and SendThread by adding SessionId
* [ZOOKEEPER-2140](https://issues.apache.org/jira/browse/ZOOKEEPER-2140) - NettyServerCnxn and NIOServerCnxn code should be improved
* [ZOOKEEPER-2149](https://issues.apache.org/jira/browse/ZOOKEEPER-2149) - Logging of client address when socket connection established
* [ZOOKEEPER-2176](https://issues.apache.org/jira/browse/ZOOKEEPER-2176) - Unclear error message should be info not error
* [ZOOKEEPER-2183](https://issues.apache.org/jira/browse/ZOOKEEPER-2183) - Concurrent Testing Processes and Port Assignments
* [ZOOKEEPER-2185](https://issues.apache.org/jira/browse/ZOOKEEPER-2185) - Run server with -XX:+HeapDumpOnOutOfMemoryError and -XX:OnOutOfMemoryError=&#39;kill %p&#39;.
* [ZOOKEEPER-2194](https://issues.apache.org/jira/browse/ZOOKEEPER-2194) - Let DataNode.getChildren() return an unmodifiable view of its children set
* [ZOOKEEPER-2205](https://issues.apache.org/jira/browse/ZOOKEEPER-2205) - Log type of unexpected quorum packet in learner handler loop
* [ZOOKEEPER-2206](https://issues.apache.org/jira/browse/ZOOKEEPER-2206) - Add missing packet types to LearnerHandler.packetToString()
* [ZOOKEEPER-2207](https://issues.apache.org/jira/browse/ZOOKEEPER-2207) - Enhance error logs with LearnerHandler.packetToString()
* [ZOOKEEPER-2208](https://issues.apache.org/jira/browse/ZOOKEEPER-2208) - Log type of unexpected quorum packet in observer loop
* [ZOOKEEPER-2214](https://issues.apache.org/jira/browse/ZOOKEEPER-2214) - Findbugs warning: LearnerHandler.packetToString Dead store to local variable
* [ZOOKEEPER-2223](https://issues.apache.org/jira/browse/ZOOKEEPER-2223) - support method-level JUnit testcase

## New Feature
* [ZOOKEEPER-2163](https://issues.apache.org/jira/browse/ZOOKEEPER-2163) - Introduce new ZNode type: container

## Test
* [ZOOKEEPER-2017](https://issues.apache.org/jira/browse/ZOOKEEPER-2017) - New tests for reconfig failure cases
* [ZOOKEEPER-2032](https://issues.apache.org/jira/browse/ZOOKEEPER-2032) - ReconfigBackupTest didn&#39;t clean up resources.
* [ZOOKEEPER-2204](https://issues.apache.org/jira/browse/ZOOKEEPER-2204) - LearnerSnapshotThrottlerTest.testHighContentionWithTimeout fails occasionally

# Release Notes - ZooKeeper - Version 3.5.0

## Sub-task
* [ZOOKEEPER-442](https://issues.apache.org/jira/browse/ZOOKEEPER-442) - need a way to remove watches that are no longer of interest
* [ZOOKEEPER-762](https://issues.apache.org/jira/browse/ZOOKEEPER-762) - Allow dynamic addition/removal of server nodes in the client API
* [ZOOKEEPER-827](https://issues.apache.org/jira/browse/ZOOKEEPER-827) - enable r/o mode in C client library
* [ZOOKEEPER-837](https://issues.apache.org/jira/browse/ZOOKEEPER-837) - cyclic dependency ClientCnxn, ZooKeeper
* [ZOOKEEPER-878](https://issues.apache.org/jira/browse/ZOOKEEPER-878) - finishPacket and conLossPacket should be methods of Packet
* [ZOOKEEPER-910](https://issues.apache.org/jira/browse/ZOOKEEPER-910) - Use SelectionKey.isXYZ() methods instead of complicated binary logic
* [ZOOKEEPER-932](https://issues.apache.org/jira/browse/ZOOKEEPER-932) - Move blocking read/write calls to SendWorker and RecvWorker Threads
* [ZOOKEEPER-933](https://issues.apache.org/jira/browse/ZOOKEEPER-933) - Remove wildcard  QuorumPeer.OBSERVER_ID
* [ZOOKEEPER-934](https://issues.apache.org/jira/browse/ZOOKEEPER-934) - Add sanity check for server ID
* [ZOOKEEPER-1044](https://issues.apache.org/jira/browse/ZOOKEEPER-1044) - Allow dynamic changes to roles of a peer
* [ZOOKEEPER-1113](https://issues.apache.org/jira/browse/ZOOKEEPER-1113) - QuorumMaj counts the number of ACKs but does not check who sent the ACK
* [ZOOKEEPER-1191](https://issues.apache.org/jira/browse/ZOOKEEPER-1191) - Synchronization issue - wait not in guarded block
* [ZOOKEEPER-1200](https://issues.apache.org/jira/browse/ZOOKEEPER-1200) - Remove obsolete DataTreeBuilder
* [ZOOKEEPER-1201](https://issues.apache.org/jira/browse/ZOOKEEPER-1201) - Clean SaslServerCallbackHandler.java
* [ZOOKEEPER-1213](https://issues.apache.org/jira/browse/ZOOKEEPER-1213) - ZooKeeper server startup fails if configured only with the &#39;minSessionTimeout&#39; and not &#39;maxSessionTimeout&#39;
* [ZOOKEEPER-1216](https://issues.apache.org/jira/browse/ZOOKEEPER-1216) - Fix more eclipse compiler warnings, also in Tests
* [ZOOKEEPER-1221](https://issues.apache.org/jira/browse/ZOOKEEPER-1221) - Provide accessors for Request.{hdr|txn}
* [ZOOKEEPER-1227](https://issues.apache.org/jira/browse/ZOOKEEPER-1227) - Zookeeper logs is showing -1 as min/max session timeout if there is no sessiontimeout value configured
* [ZOOKEEPER-1235](https://issues.apache.org/jira/browse/ZOOKEEPER-1235) - store KeeperException messages in the Code enum
* [ZOOKEEPER-1246](https://issues.apache.org/jira/browse/ZOOKEEPER-1246) - Dead code in PrepRequestProcessor catch Exception block
* [ZOOKEEPER-1247](https://issues.apache.org/jira/browse/ZOOKEEPER-1247) - dead code in PrepRequestProcessor.pRequest multi case
* [ZOOKEEPER-1248](https://issues.apache.org/jira/browse/ZOOKEEPER-1248) - multi transaction sets request.exception without reason
* [ZOOKEEPER-1252](https://issues.apache.org/jira/browse/ZOOKEEPER-1252) - remove unused method o.a.z.test.AxyncTest.restart()
* [ZOOKEEPER-1253](https://issues.apache.org/jira/browse/ZOOKEEPER-1253) - return value of DataTree.createNode is never used
* [ZOOKEEPER-1259](https://issues.apache.org/jira/browse/ZOOKEEPER-1259) - central mapping from type to txn record class
* [ZOOKEEPER-1282](https://issues.apache.org/jira/browse/ZOOKEEPER-1282) - Learner.java not following Zab 1.0 protocol - setCurrentEpoch should be done upon receipt of NEWLEADER (before acking it) and not upon receipt of UPTODATE
* [ZOOKEEPER-1291](https://issues.apache.org/jira/browse/ZOOKEEPER-1291) - AcceptedEpoch not updated at leader before it proposes the epoch to followers
* [ZOOKEEPER-1347](https://issues.apache.org/jira/browse/ZOOKEEPER-1347) - Fix the cnxns to use a concurrent data structures
* [ZOOKEEPER-1411](https://issues.apache.org/jira/browse/ZOOKEEPER-1411) - Consolidate membership management, distinguish between static and dynamic configuration parameters
* [ZOOKEEPER-1414](https://issues.apache.org/jira/browse/ZOOKEEPER-1414) - QuorumPeerMainTest.testQuorum, testBadPackets are failing intermittently
* [ZOOKEEPER-1459](https://issues.apache.org/jira/browse/ZOOKEEPER-1459) - Standalone ZooKeeperServer is not closing the transaction log files on shutdown
* [ZOOKEEPER-1626](https://issues.apache.org/jira/browse/ZOOKEEPER-1626) - Zookeeper C client should be tolerant of clock adjustments 
* [ZOOKEEPER-1660](https://issues.apache.org/jira/browse/ZOOKEEPER-1660) - Add documentation for dynamic reconfiguration
* [ZOOKEEPER-1730](https://issues.apache.org/jira/browse/ZOOKEEPER-1730) - Make ZooKeeper easier to test - support simulating a session expiration
* [ZOOKEEPER-1761](https://issues.apache.org/jira/browse/ZOOKEEPER-1761) - Expose &#39;check&#39; version api in ZooKeeper client
* [ZOOKEEPER-1762](https://issues.apache.org/jira/browse/ZOOKEEPER-1762) - Implement &#39;check&#39; version cli command
* [ZOOKEEPER-1830](https://issues.apache.org/jira/browse/ZOOKEEPER-1830) - Support command line shell for removing watches
* [ZOOKEEPER-1831](https://issues.apache.org/jira/browse/ZOOKEEPER-1831) - Document remove watches details to the guide
* [ZOOKEEPER-1834](https://issues.apache.org/jira/browse/ZOOKEEPER-1834) - Catch IOException in FileTxnLog
* [ZOOKEEPER-1837](https://issues.apache.org/jira/browse/ZOOKEEPER-1837) - Fix JMXEnv checks (potential race conditions)
* [ZOOKEEPER-1849](https://issues.apache.org/jira/browse/ZOOKEEPER-1849) - Need to properly tear down tests in various cases
* [ZOOKEEPER-1852](https://issues.apache.org/jira/browse/ZOOKEEPER-1852) - ServerCnxnFactory instance is not properly cleanedup
* [ZOOKEEPER-1854](https://issues.apache.org/jira/browse/ZOOKEEPER-1854) - ClientBase ZooKeeper server clean-up
* [ZOOKEEPER-1857](https://issues.apache.org/jira/browse/ZOOKEEPER-1857) - PrepRequestProcessotTest doesn&#39;t shutdown ZooKeeper server
* [ZOOKEEPER-1858](https://issues.apache.org/jira/browse/ZOOKEEPER-1858) - JMX checks - potential race conditions while stopping and starting server
* [ZOOKEEPER-1867](https://issues.apache.org/jira/browse/ZOOKEEPER-1867) - Bug in ZkDatabaseCorruptionTest
* [ZOOKEEPER-1872](https://issues.apache.org/jira/browse/ZOOKEEPER-1872) - QuorumPeer is not shutdown in few cases
* [ZOOKEEPER-1873](https://issues.apache.org/jira/browse/ZOOKEEPER-1873) - Unnecessarily InstanceNotFoundException is coming when unregister failed jmxbeans
* [ZOOKEEPER-1874](https://issues.apache.org/jira/browse/ZOOKEEPER-1874) - Add proper teardown/cleanups in ReconfigTest to shutdown quorumpeer
* [ZOOKEEPER-1904](https://issues.apache.org/jira/browse/ZOOKEEPER-1904) - WatcherTest#testWatchAutoResetWithPending is failing
* [ZOOKEEPER-1972](https://issues.apache.org/jira/browse/ZOOKEEPER-1972) - Fix invalid volatile long/int increment (++)
* [ZOOKEEPER-1975](https://issues.apache.org/jira/browse/ZOOKEEPER-1975) - Turn off &quot;internationalization warnings&quot; in findbugs exclude file
* [ZOOKEEPER-1978](https://issues.apache.org/jira/browse/ZOOKEEPER-1978) - Fix Multithreaded correctness Warnings
* [ZOOKEEPER-1979](https://issues.apache.org/jira/browse/ZOOKEEPER-1979) - Fix Performance Warnings found by Findbugs 2.0.3
* [ZOOKEEPER-1981](https://issues.apache.org/jira/browse/ZOOKEEPER-1981) - Fix Dodgy Code Warnings identified by findbugs 2.0.3
* [ZOOKEEPER-1988](https://issues.apache.org/jira/browse/ZOOKEEPER-1988) - new test patch to verify dynamic reconfig backward compatibility
* [ZOOKEEPER-1989](https://issues.apache.org/jira/browse/ZOOKEEPER-1989) - backward compatibility of zoo.cfg
* [ZOOKEEPER-1993](https://issues.apache.org/jira/browse/ZOOKEEPER-1993) - Keep the client port upon parsing config
* [ZOOKEEPER-1995](https://issues.apache.org/jira/browse/ZOOKEEPER-1995) - Safely remove client port in old config file on reconfig itself

## Bug
* [ZOOKEEPER-10](https://issues.apache.org/jira/browse/ZOOKEEPER-10) - Bad error message
* [ZOOKEEPER-87](https://issues.apache.org/jira/browse/ZOOKEEPER-87) - Follower does not shut itself down if its too far behind the leader.
* [ZOOKEEPER-366](https://issues.apache.org/jira/browse/ZOOKEEPER-366) - Session timeout detection can go wrong if the leader system time changes
* [ZOOKEEPER-445](https://issues.apache.org/jira/browse/ZOOKEEPER-445) - Potential bug in leader code
* [ZOOKEEPER-463](https://issues.apache.org/jira/browse/ZOOKEEPER-463) - C++ tests can&#39;t be built on Mac OS using XCode command line tools
* [ZOOKEEPER-492](https://issues.apache.org/jira/browse/ZOOKEEPER-492) - the tests should have their own log4j.properties
* [ZOOKEEPER-513](https://issues.apache.org/jira/browse/ZOOKEEPER-513) - C client disconnect with stand-alone server abnormally
* [ZOOKEEPER-515](https://issues.apache.org/jira/browse/ZOOKEEPER-515) - Zookeeper quorum didn&#39;t provide service when restart after an &quot;Out of memory&quot; crash
* [ZOOKEEPER-602](https://issues.apache.org/jira/browse/ZOOKEEPER-602) - log all exceptions not caught by ZK threads
* [ZOOKEEPER-642](https://issues.apache.org/jira/browse/ZOOKEEPER-642) - &quot;exceeded deadline by N ms&quot; floods logs
* [ZOOKEEPER-649](https://issues.apache.org/jira/browse/ZOOKEEPER-649) - testObserver timed out once on Hudson
* [ZOOKEEPER-653](https://issues.apache.org/jira/browse/ZOOKEEPER-653) - hudson failure in LETest
* [ZOOKEEPER-675](https://issues.apache.org/jira/browse/ZOOKEEPER-675) - LETest thread fails to join
* [ZOOKEEPER-697](https://issues.apache.org/jira/browse/ZOOKEEPER-697) - TestQuotaQuorum is failing on Hudson
* [ZOOKEEPER-705](https://issues.apache.org/jira/browse/ZOOKEEPER-705) - Fails to Build due to unknown opcode &#39;lock&#39; in mt_adaptor.c
* [ZOOKEEPER-706](https://issues.apache.org/jira/browse/ZOOKEEPER-706) - large numbers of watches can cause session re-establishment to fail
* [ZOOKEEPER-714](https://issues.apache.org/jira/browse/ZOOKEEPER-714) - snapshotting doesn&#39;t handle runtime exceptions (like out of memory) well
* [ZOOKEEPER-732](https://issues.apache.org/jira/browse/ZOOKEEPER-732) - Improper translation of error into Python exception
* [ZOOKEEPER-752](https://issues.apache.org/jira/browse/ZOOKEEPER-752) - address use of &quot;recoverable&quot; vs &quot;revocable&quot; in lock recipes documentation
* [ZOOKEEPER-770](https://issues.apache.org/jira/browse/ZOOKEEPER-770) - Slow add_auth calls with multi-threaded client
* [ZOOKEEPER-780](https://issues.apache.org/jira/browse/ZOOKEEPER-780) - zkCli.sh  generates a ArrayIndexOutOfBoundsException 
* [ZOOKEEPER-786](https://issues.apache.org/jira/browse/ZOOKEEPER-786) - Exception in ZooKeeper.toString
* [ZOOKEEPER-832](https://issues.apache.org/jira/browse/ZOOKEEPER-832) - Invalid session id causes infinite loop during automatic reconnect
* [ZOOKEEPER-847](https://issues.apache.org/jira/browse/ZOOKEEPER-847) - Missing acl check in zookeeper create
* [ZOOKEEPER-856](https://issues.apache.org/jira/browse/ZOOKEEPER-856) - Connection imbalance leads to overloaded ZK instances
* [ZOOKEEPER-857](https://issues.apache.org/jira/browse/ZOOKEEPER-857) - clarify client vs. server view of session expiration event
* [ZOOKEEPER-872](https://issues.apache.org/jira/browse/ZOOKEEPER-872) - Small fixes to PurgeTxnLog 
* [ZOOKEEPER-876](https://issues.apache.org/jira/browse/ZOOKEEPER-876) - Unnecessary snapshot transfers between new leader and followers
* [ZOOKEEPER-877](https://issues.apache.org/jira/browse/ZOOKEEPER-877) - zkpython does not work with python3.1
* [ZOOKEEPER-885](https://issues.apache.org/jira/browse/ZOOKEEPER-885) - Zookeeper drops connections under moderate IO load
* [ZOOKEEPER-900](https://issues.apache.org/jira/browse/ZOOKEEPER-900) - FLE implementation should be improved to use non-blocking sockets
* [ZOOKEEPER-915](https://issues.apache.org/jira/browse/ZOOKEEPER-915) - Errors that happen during sync() processing at the leader do not get propagated back to the client.
* [ZOOKEEPER-936](https://issues.apache.org/jira/browse/ZOOKEEPER-936) - zkpython is leaking ACL_vector
* [ZOOKEEPER-972](https://issues.apache.org/jira/browse/ZOOKEEPER-972) - perl Net::ZooKeeper segfaults when setting a watcher on get_children
* [ZOOKEEPER-973](https://issues.apache.org/jira/browse/ZOOKEEPER-973) - bind() could fail on Leader because it does not setReuseAddress on its ServerSocket 
* [ZOOKEEPER-978](https://issues.apache.org/jira/browse/ZOOKEEPER-978) - ZookeeperServer does not close zk database on shutdwon
* [ZOOKEEPER-982](https://issues.apache.org/jira/browse/ZOOKEEPER-982) - zkServer.sh won&#39;t start zookeeper on an ubuntu 10.10 system due to a bug in the startup script.
* [ZOOKEEPER-984](https://issues.apache.org/jira/browse/ZOOKEEPER-984) - jenkins failure in testSessionMoved - NPE in quorum
* [ZOOKEEPER-986](https://issues.apache.org/jira/browse/ZOOKEEPER-986) - In QuoromCnxManager we are adding sent messgae to lastMessageSent, but we are never removing that message from it after sending it, so this will lead to sending the same message again in next round
* [ZOOKEEPER-991](https://issues.apache.org/jira/browse/ZOOKEEPER-991) - QuoromPeer.OBSERVER_ID
* [ZOOKEEPER-1002](https://issues.apache.org/jira/browse/ZOOKEEPER-1002) - The Barrier sample code should create a EPHEMERAL znode instead of EPHEMERAL_SEQUENTIAL znode
* [ZOOKEEPER-1005](https://issues.apache.org/jira/browse/ZOOKEEPER-1005) - Zookeeper servers fail to elect a leader succesfully.
* [ZOOKEEPER-1023](https://issues.apache.org/jira/browse/ZOOKEEPER-1023) - zkpython: add_auth can deadlock the interpreter
* [ZOOKEEPER-1048](https://issues.apache.org/jira/browse/ZOOKEEPER-1048) - addauth command does not work in cli_mt/cli_st
* [ZOOKEEPER-1050](https://issues.apache.org/jira/browse/ZOOKEEPER-1050) - zooinspector shell scripts do not work
* [ZOOKEEPER-1057](https://issues.apache.org/jira/browse/ZOOKEEPER-1057) - zookeeper c-client, connection to offline server fails to successfully fallback to second zk host
* [ZOOKEEPER-1062](https://issues.apache.org/jira/browse/ZOOKEEPER-1062) - Net-ZooKeeper: Net::ZooKeeper consumes 100% cpu on wait
* [ZOOKEEPER-1077](https://issues.apache.org/jira/browse/ZOOKEEPER-1077) - C client lib doesn&#39;t build on Solaris
* [ZOOKEEPER-1089](https://issues.apache.org/jira/browse/ZOOKEEPER-1089) - zkServer.sh status does not work due to invalid option of nc
* [ZOOKEEPER-1100](https://issues.apache.org/jira/browse/ZOOKEEPER-1100) - Killed (or missing) SendThread will cause hanging threads
* [ZOOKEEPER-1105](https://issues.apache.org/jira/browse/ZOOKEEPER-1105) - c client zookeeper_close not send CLOSE_OP request to server
* [ZOOKEEPER-1125](https://issues.apache.org/jira/browse/ZOOKEEPER-1125) - Intermittent java core test failures
* [ZOOKEEPER-1159](https://issues.apache.org/jira/browse/ZOOKEEPER-1159) - ClientCnxn does not propagate session expiration indication
* [ZOOKEEPER-1163](https://issues.apache.org/jira/browse/ZOOKEEPER-1163) - Memory leak in zk_hashtable.c:do_insert_watcher_object()
* [ZOOKEEPER-1167](https://issues.apache.org/jira/browse/ZOOKEEPER-1167) - C api lacks synchronous version of sync() call.
* [ZOOKEEPER-1174](https://issues.apache.org/jira/browse/ZOOKEEPER-1174) - FD leak when network unreachable
* [ZOOKEEPER-1179](https://issues.apache.org/jira/browse/ZOOKEEPER-1179) - NettyServerCnxn does not properly close socket on 4 letter word requests
* [ZOOKEEPER-1181](https://issues.apache.org/jira/browse/ZOOKEEPER-1181) - Fix problems with Kerberos TGT renewal
* [ZOOKEEPER-1184](https://issues.apache.org/jira/browse/ZOOKEEPER-1184) - jute generated files are not being cleaned up via &quot;ant clean&quot;
* [ZOOKEEPER-1185](https://issues.apache.org/jira/browse/ZOOKEEPER-1185) - Send AuthFailed event to client if SASL authentication fails
* [ZOOKEEPER-1189](https://issues.apache.org/jira/browse/ZOOKEEPER-1189) - For an invalid snapshot file(less than 10bytes size) RandomAccessFile stream is leaking.
* [ZOOKEEPER-1190](https://issues.apache.org/jira/browse/ZOOKEEPER-1190) - ant package is not including many of the bin scripts in the package (zkServer.sh for example)
* [ZOOKEEPER-1192](https://issues.apache.org/jira/browse/ZOOKEEPER-1192) - Leader.waitForEpochAck() checks waitingForNewEpoch instead of checking electionFinished
* [ZOOKEEPER-1194](https://issues.apache.org/jira/browse/ZOOKEEPER-1194) - Two possible race conditions during leader establishment
* [ZOOKEEPER-1197](https://issues.apache.org/jira/browse/ZOOKEEPER-1197) - Incorrect socket handling of 4 letter words for NIO
* [ZOOKEEPER-1203](https://issues.apache.org/jira/browse/ZOOKEEPER-1203) - Zookeeper systest is missing Junit Classes 
* [ZOOKEEPER-1206](https://issues.apache.org/jira/browse/ZOOKEEPER-1206) - Sequential node creation does not use always use digits in node name given certain Locales.
* [ZOOKEEPER-1207](https://issues.apache.org/jira/browse/ZOOKEEPER-1207) - strange ReadOnlyZooKeeperServer ERROR when starting ensemble
* [ZOOKEEPER-1208](https://issues.apache.org/jira/browse/ZOOKEEPER-1208) - Ephemeral node not removed after the client session is long gone
* [ZOOKEEPER-1209](https://issues.apache.org/jira/browse/ZOOKEEPER-1209) - LeaderElection recipe doesn&#39;t handle the split-brain issue, n/w disconnection can bring both the client nodes to be in ELECTED
* [ZOOKEEPER-1212](https://issues.apache.org/jira/browse/ZOOKEEPER-1212) - zkServer.sh stop action is not conformat with LSB para 20.2 Init Script Actions
* [ZOOKEEPER-1214](https://issues.apache.org/jira/browse/ZOOKEEPER-1214) - QuorumPeer should unregister only its previsously registered MBeans instead of use MBeanRegistry.unregisterAll() method.
* [ZOOKEEPER-1220](https://issues.apache.org/jira/browse/ZOOKEEPER-1220) - ./zkCli.sh &#39;create&#39; command is throwing ArrayIndexOutOfBoundsException
* [ZOOKEEPER-1222](https://issues.apache.org/jira/browse/ZOOKEEPER-1222) - getACL should only call DataTree.copyStat when passed in stat is not null
* [ZOOKEEPER-1224](https://issues.apache.org/jira/browse/ZOOKEEPER-1224) - problem across zookeeper clients when reading data written by other clients
* [ZOOKEEPER-1225](https://issues.apache.org/jira/browse/ZOOKEEPER-1225) - Successive invocation of LeaderElectionSupport.start() will bring the ELECTED node to READY and cause no one in ELECTED state.
* [ZOOKEEPER-1236](https://issues.apache.org/jira/browse/ZOOKEEPER-1236) - Security uses proprietary Sun APIs
* [ZOOKEEPER-1237](https://issues.apache.org/jira/browse/ZOOKEEPER-1237) - ERRORs being logged when queued responses are sent after socket has closed.
* [ZOOKEEPER-1238](https://issues.apache.org/jira/browse/ZOOKEEPER-1238) - when the linger time was changed for NIO the patch missed Netty
* [ZOOKEEPER-1241](https://issues.apache.org/jira/browse/ZOOKEEPER-1241) - Typo in ZooKeeper Recipes and Solutions documentation
* [ZOOKEEPER-1256](https://issues.apache.org/jira/browse/ZOOKEEPER-1256) - ClientPortBindTest is failing on Mac OS X
* [ZOOKEEPER-1262](https://issues.apache.org/jira/browse/ZOOKEEPER-1262) - Documentation for Lock recipe has major flaw
* [ZOOKEEPER-1264](https://issues.apache.org/jira/browse/ZOOKEEPER-1264) - FollowerResyncConcurrencyTest failing intermittently
* [ZOOKEEPER-1268](https://issues.apache.org/jira/browse/ZOOKEEPER-1268) - problems with read only mode, intermittent test failures and ERRORs in the log
* [ZOOKEEPER-1269](https://issues.apache.org/jira/browse/ZOOKEEPER-1269) - Multi deserialization issues
* [ZOOKEEPER-1270](https://issues.apache.org/jira/browse/ZOOKEEPER-1270) - testEarlyLeaderAbandonment failing intermittently, quorum formed, no serving.
* [ZOOKEEPER-1271](https://issues.apache.org/jira/browse/ZOOKEEPER-1271) - testEarlyLeaderAbandonment failing on solaris - clients not retrying connection
* [ZOOKEEPER-1273](https://issues.apache.org/jira/browse/ZOOKEEPER-1273) - Copy&#39;n&#39;pasted unit test
* [ZOOKEEPER-1274](https://issues.apache.org/jira/browse/ZOOKEEPER-1274) - Support child watches to be displayed with 4 letter zookeeper commands (i.e. wchs, wchp and wchc)
* [ZOOKEEPER-1277](https://issues.apache.org/jira/browse/ZOOKEEPER-1277) - servers stop serving when lower 32bits of zxid roll over
* [ZOOKEEPER-1294](https://issues.apache.org/jira/browse/ZOOKEEPER-1294) - One of the zookeeper server is not accepting any requests
* [ZOOKEEPER-1300](https://issues.apache.org/jira/browse/ZOOKEEPER-1300) - Rat complains about incosistent licenses in the src files.
* [ZOOKEEPER-1303](https://issues.apache.org/jira/browse/ZOOKEEPER-1303) - Observer LearnerHandlers are not removed from Leader collection.
* [ZOOKEEPER-1305](https://issues.apache.org/jira/browse/ZOOKEEPER-1305) - zookeeper.c:prepend_string func can dereference null ptr
* [ZOOKEEPER-1307](https://issues.apache.org/jira/browse/ZOOKEEPER-1307) - zkCli.sh is exiting when an Invalid ACL exception is thrown from setACL command through client
* [ZOOKEEPER-1311](https://issues.apache.org/jira/browse/ZOOKEEPER-1311) - ZooKeeper test jar is broken
* [ZOOKEEPER-1315](https://issues.apache.org/jira/browse/ZOOKEEPER-1315) - zookeeper_init always reports sessionPasswd=&lt;hidden&gt;
* [ZOOKEEPER-1316](https://issues.apache.org/jira/browse/ZOOKEEPER-1316) - zookeeper_init leaks memory if chroot is just &#39;/&#39;
* [ZOOKEEPER-1317](https://issues.apache.org/jira/browse/ZOOKEEPER-1317) - Possible segfault in zookeeper_init
* [ZOOKEEPER-1318](https://issues.apache.org/jira/browse/ZOOKEEPER-1318) - In Python binding, get_children (and get and exists, and probably others) with expired session doesn&#39;t raise exception properly
* [ZOOKEEPER-1319](https://issues.apache.org/jira/browse/ZOOKEEPER-1319) - Missing data after restarting+expanding a cluster
* [ZOOKEEPER-1323](https://issues.apache.org/jira/browse/ZOOKEEPER-1323) - c client doesn&#39;t compile on freebsd
* [ZOOKEEPER-1327](https://issues.apache.org/jira/browse/ZOOKEEPER-1327) - there are still remnants of hadoop urls
* [ZOOKEEPER-1330](https://issues.apache.org/jira/browse/ZOOKEEPER-1330) - Zookeeper server not serving the client request even after completion of Leader election
* [ZOOKEEPER-1331](https://issues.apache.org/jira/browse/ZOOKEEPER-1331) - Typo in docs: acheive -&gt; achieve
* [ZOOKEEPER-1333](https://issues.apache.org/jira/browse/ZOOKEEPER-1333) - NPE in FileTxnSnapLog when restarting a cluster
* [ZOOKEEPER-1334](https://issues.apache.org/jira/browse/ZOOKEEPER-1334) - Zookeeper 3.4.x is not OSGi compliant - MANIFEST.MF is flawed
* [ZOOKEEPER-1336](https://issues.apache.org/jira/browse/ZOOKEEPER-1336) - javadoc for multi is confusing, references functionality that doesn&#39;t seem to exist 
* [ZOOKEEPER-1338](https://issues.apache.org/jira/browse/ZOOKEEPER-1338) - class cast exceptions may be thrown by multi ErrorResult class (invalid equals)
* [ZOOKEEPER-1339](https://issues.apache.org/jira/browse/ZOOKEEPER-1339) - C clien doesn&#39;t build with --enable-debug
* [ZOOKEEPER-1340](https://issues.apache.org/jira/browse/ZOOKEEPER-1340) - multi problem - typical user operations are generating ERROR level messages in the server
* [ZOOKEEPER-1343](https://issues.apache.org/jira/browse/ZOOKEEPER-1343) - getEpochToPropose should check if lastAcceptedEpoch is greater or equal than epoch
* [ZOOKEEPER-1344](https://issues.apache.org/jira/browse/ZOOKEEPER-1344) - ZooKeeper client multi-update command is not considering the Chroot request
* [ZOOKEEPER-1351](https://issues.apache.org/jira/browse/ZOOKEEPER-1351) - invalid test verification in MultiTransactionTest
* [ZOOKEEPER-1352](https://issues.apache.org/jira/browse/ZOOKEEPER-1352) - server.InvalidSnapshotTest is using connection timeouts that are too short
* [ZOOKEEPER-1353](https://issues.apache.org/jira/browse/ZOOKEEPER-1353) - C client test suite fails consistently
* [ZOOKEEPER-1354](https://issues.apache.org/jira/browse/ZOOKEEPER-1354) - AuthTest.testBadAuthThenSendOtherCommands fails intermittently
* [ZOOKEEPER-1357](https://issues.apache.org/jira/browse/ZOOKEEPER-1357) - Zab1_0Test uses hard-wired port numbers. Specifically, it uses the same port for leader in two different tests. The second test periodically fails complaining that the port is still in use.
* [ZOOKEEPER-1358](https://issues.apache.org/jira/browse/ZOOKEEPER-1358) - In StaticHostProviderTest.java, testNextDoesNotSleepForZero tests that hostProvider.next(0) doesn&#39;t sleep by checking that the latency of this call is less than 10sec
* [ZOOKEEPER-1360](https://issues.apache.org/jira/browse/ZOOKEEPER-1360) - QuorumTest.testNoLogBeforeLeaderEstablishment has several problems
* [ZOOKEEPER-1361](https://issues.apache.org/jira/browse/ZOOKEEPER-1361) - Leader.lead iterates over &#39;learners&#39; set without proper synchronisation
* [ZOOKEEPER-1366](https://issues.apache.org/jira/browse/ZOOKEEPER-1366) - Zookeeper should be tolerant of clock adjustments
* [ZOOKEEPER-1367](https://issues.apache.org/jira/browse/ZOOKEEPER-1367) - Data inconsistencies and unexpired ephemeral nodes after cluster restart
* [ZOOKEEPER-1371](https://issues.apache.org/jira/browse/ZOOKEEPER-1371) - Remove dependency on log4j in the source code.
* [ZOOKEEPER-1373](https://issues.apache.org/jira/browse/ZOOKEEPER-1373) - Hardcoded SASL login context name clashes with Hadoop security configuration override
* [ZOOKEEPER-1374](https://issues.apache.org/jira/browse/ZOOKEEPER-1374) - C client multi-threaded test suite fails to compile on ARM architectures.
* [ZOOKEEPER-1379](https://issues.apache.org/jira/browse/ZOOKEEPER-1379) - &#39;printwatches, redo, history and connect &#39;. client commands always print usage. This is not necessary
* [ZOOKEEPER-1380](https://issues.apache.org/jira/browse/ZOOKEEPER-1380) - zkperl: _zk_release_watch doesn&#39;t remove items properly from the watch list
* [ZOOKEEPER-1382](https://issues.apache.org/jira/browse/ZOOKEEPER-1382) - Zookeeper server holds onto dead/expired session ids in the watch data structures
* [ZOOKEEPER-1384](https://issues.apache.org/jira/browse/ZOOKEEPER-1384) - test-cppunit overrides LD_LIBRARY_PATH and fails if gcc is in non-standard location
* [ZOOKEEPER-1386](https://issues.apache.org/jira/browse/ZOOKEEPER-1386) - avoid flaky URL redirection in &quot;ant javadoc&quot; : replace &quot;http://java.sun.com/javase/6/docs/api/&quot; with &quot;http://download.oracle.com/javase/6/docs/api/&quot; 
* [ZOOKEEPER-1387](https://issues.apache.org/jira/browse/ZOOKEEPER-1387) - Wrong epoch file created
* [ZOOKEEPER-1388](https://issues.apache.org/jira/browse/ZOOKEEPER-1388) - Client side &#39;PathValidation&#39; is missing for the multi-transaction api.
* [ZOOKEEPER-1391](https://issues.apache.org/jira/browse/ZOOKEEPER-1391) - zkCli dies on NoAuth
* [ZOOKEEPER-1395](https://issues.apache.org/jira/browse/ZOOKEEPER-1395) - node-watcher double-free redux
* [ZOOKEEPER-1403](https://issues.apache.org/jira/browse/ZOOKEEPER-1403) - zkCli.sh script quoting issue
* [ZOOKEEPER-1406](https://issues.apache.org/jira/browse/ZOOKEEPER-1406) - dpkg init scripts don&#39;t restart - missing check_priv_sep_dir
* [ZOOKEEPER-1412](https://issues.apache.org/jira/browse/ZOOKEEPER-1412) - java client watches inconsistently triggered on reconnect
* [ZOOKEEPER-1417](https://issues.apache.org/jira/browse/ZOOKEEPER-1417) - investigate differences in client last zxid handling btw c and java clients
* [ZOOKEEPER-1419](https://issues.apache.org/jira/browse/ZOOKEEPER-1419) - Leader election never settles for a 5-node cluster
* [ZOOKEEPER-1427](https://issues.apache.org/jira/browse/ZOOKEEPER-1427) - Writing to local files is done non-atomically
* [ZOOKEEPER-1431](https://issues.apache.org/jira/browse/ZOOKEEPER-1431) - zkpython: async calls leak memory
* [ZOOKEEPER-1437](https://issues.apache.org/jira/browse/ZOOKEEPER-1437) - Client uses session before SASL authentication complete
* [ZOOKEEPER-1439](https://issues.apache.org/jira/browse/ZOOKEEPER-1439) - c sdk: core in log_env for lack of checking the output argument *pwp* of getpwuid_r
* [ZOOKEEPER-1440](https://issues.apache.org/jira/browse/ZOOKEEPER-1440) - Spurious log error messages when QuorumCnxManager is shutting down
* [ZOOKEEPER-1448](https://issues.apache.org/jira/browse/ZOOKEEPER-1448) - Node+Quota creation in transaction log can crash leader startup
* [ZOOKEEPER-1451](https://issues.apache.org/jira/browse/ZOOKEEPER-1451) - C API improperly logs getaddrinfo failures on Linux when using glibc
* [ZOOKEEPER-1463](https://issues.apache.org/jira/browse/ZOOKEEPER-1463) - external inline function is not compatible with C99
* [ZOOKEEPER-1465](https://issues.apache.org/jira/browse/ZOOKEEPER-1465) - Cluster availability following new leader election takes a long time with large datasets - is correlated to dataset size
* [ZOOKEEPER-1466](https://issues.apache.org/jira/browse/ZOOKEEPER-1466) - QuorumCnxManager.shutdown missing synchronization
* [ZOOKEEPER-1471](https://issues.apache.org/jira/browse/ZOOKEEPER-1471) - Jute generates invalid C++ code
* [ZOOKEEPER-1473](https://issues.apache.org/jira/browse/ZOOKEEPER-1473) - Committed proposal log retains triple the memory it needs to
* [ZOOKEEPER-1474](https://issues.apache.org/jira/browse/ZOOKEEPER-1474) - Cannot build Zookeeper with IBM Java: use of Sun MXBean classes
* [ZOOKEEPER-1478](https://issues.apache.org/jira/browse/ZOOKEEPER-1478) - Small bug in QuorumTest.testFollowersStartAfterLeader( )
* [ZOOKEEPER-1479](https://issues.apache.org/jira/browse/ZOOKEEPER-1479) - C Client: zoo_add_auth() doesn&#39;t wake up the IO thread
* [ZOOKEEPER-1480](https://issues.apache.org/jira/browse/ZOOKEEPER-1480) - ClientCnxn(1161) can&#39;t get the current zk server add, so that - Session 0x for server null, unexpected error
* [ZOOKEEPER-1483](https://issues.apache.org/jira/browse/ZOOKEEPER-1483) - Fix leader election recipe documentation
* [ZOOKEEPER-1489](https://issues.apache.org/jira/browse/ZOOKEEPER-1489) - Data loss after truncate on transaction log
* [ZOOKEEPER-1490](https://issues.apache.org/jira/browse/ZOOKEEPER-1490) -  If the configured log directory does not exist zookeeper will not start. Better to create the directory and start
* [ZOOKEEPER-1493](https://issues.apache.org/jira/browse/ZOOKEEPER-1493) - C Client: zookeeper_process doesn&#39;t invoke completion callback if zookeeper_close has been called
* [ZOOKEEPER-1494](https://issues.apache.org/jira/browse/ZOOKEEPER-1494) - C client: socket leak after receive timeout in zookeeper_interest()
* [ZOOKEEPER-1495](https://issues.apache.org/jira/browse/ZOOKEEPER-1495) - ZK client hangs when using a function not available on the server.
* [ZOOKEEPER-1496](https://issues.apache.org/jira/browse/ZOOKEEPER-1496) - Ephemeral node not getting cleared even after client has exited
* [ZOOKEEPER-1499](https://issues.apache.org/jira/browse/ZOOKEEPER-1499) - clientPort config changes not backwards-compatible
* [ZOOKEEPER-1501](https://issues.apache.org/jira/browse/ZOOKEEPER-1501) - Nagios plugin always returns OK when it cannot connect to zookeeper
* [ZOOKEEPER-1513](https://issues.apache.org/jira/browse/ZOOKEEPER-1513) - &quot;Unreasonable length&quot; exception while starting a server.
* [ZOOKEEPER-1514](https://issues.apache.org/jira/browse/ZOOKEEPER-1514) - FastLeaderElection - leader ignores the round information when joining a quorum
* [ZOOKEEPER-1519](https://issues.apache.org/jira/browse/ZOOKEEPER-1519) - Zookeeper Async calls can reference free()&#39;d memory
* [ZOOKEEPER-1520](https://issues.apache.org/jira/browse/ZOOKEEPER-1520) - A txn log record with a corrupt sentinel byte looks like EOF
* [ZOOKEEPER-1521](https://issues.apache.org/jira/browse/ZOOKEEPER-1521) - LearnerHandler initLimit/syncLimit problems specifying follower socket timeout limits
* [ZOOKEEPER-1522](https://issues.apache.org/jira/browse/ZOOKEEPER-1522) - intermittent failures in Zab test due to NPE in recursiveDelete test function
* [ZOOKEEPER-1531](https://issues.apache.org/jira/browse/ZOOKEEPER-1531) - Correct the documentation of the args for the JavaExample doc.
* [ZOOKEEPER-1533](https://issues.apache.org/jira/browse/ZOOKEEPER-1533) - Correct the documentation of the args for the JavaExample doc.
* [ZOOKEEPER-1535](https://issues.apache.org/jira/browse/ZOOKEEPER-1535) - ZK Shell/Cli re-executes last command on exit
* [ZOOKEEPER-1536](https://issues.apache.org/jira/browse/ZOOKEEPER-1536) - c client : memory leak in winport.c
* [ZOOKEEPER-1538](https://issues.apache.org/jira/browse/ZOOKEEPER-1538) - Improve space handling in zkServer.sh and zkEnv.sh
* [ZOOKEEPER-1540](https://issues.apache.org/jira/browse/ZOOKEEPER-1540) - ZOOKEEPER-1411 breaks backwards compatibility
* [ZOOKEEPER-1549](https://issues.apache.org/jira/browse/ZOOKEEPER-1549) - Data inconsistency when follower is receiving a DIFF with a dirty snapshot
* [ZOOKEEPER-1551](https://issues.apache.org/jira/browse/ZOOKEEPER-1551) - Observers ignore txns that come after snapshot and UPTODATE 
* [ZOOKEEPER-1553](https://issues.apache.org/jira/browse/ZOOKEEPER-1553) - Findbugs configuration is missing some dependencies
* [ZOOKEEPER-1554](https://issues.apache.org/jira/browse/ZOOKEEPER-1554) - Can&#39;t use zookeeper client without SASL
* [ZOOKEEPER-1557](https://issues.apache.org/jira/browse/ZOOKEEPER-1557) - jenkins jdk7 test failure in testBadSaslAuthNotifiesWatch
* [ZOOKEEPER-1560](https://issues.apache.org/jira/browse/ZOOKEEPER-1560) - Zookeeper client hangs on creation of large nodes
* [ZOOKEEPER-1561](https://issues.apache.org/jira/browse/ZOOKEEPER-1561) - Zookeeper client may hang on a server restart
* [ZOOKEEPER-1562](https://issues.apache.org/jira/browse/ZOOKEEPER-1562) - Memory leaks in zoo_multi API
* [ZOOKEEPER-1573](https://issues.apache.org/jira/browse/ZOOKEEPER-1573) - Unable to load database due to missing parent node
* [ZOOKEEPER-1575](https://issues.apache.org/jira/browse/ZOOKEEPER-1575) - adding .gitattributes to prevent CRLF and LF mismatches for source and text files
* [ZOOKEEPER-1576](https://issues.apache.org/jira/browse/ZOOKEEPER-1576) - Zookeeper cluster - failed to connect to cluster if one of the provided IPs causes java.net.UnknownHostException
* [ZOOKEEPER-1578](https://issues.apache.org/jira/browse/ZOOKEEPER-1578) - org.apache.zookeeper.server.quorum.Zab1_0Test failed due to hard code with 33556 port
* [ZOOKEEPER-1581](https://issues.apache.org/jira/browse/ZOOKEEPER-1581) - change copyright in notice to 2012
* [ZOOKEEPER-1585](https://issues.apache.org/jira/browse/ZOOKEEPER-1585) - make dist for src/c broken in trunk
* [ZOOKEEPER-1590](https://issues.apache.org/jira/browse/ZOOKEEPER-1590) - Patch to add zk.updateServerList(newServerList) broke the build
* [ZOOKEEPER-1591](https://issues.apache.org/jira/browse/ZOOKEEPER-1591) - Windows build is broken because inttypes.h doesn&#39;t exist
* [ZOOKEEPER-1596](https://issues.apache.org/jira/browse/ZOOKEEPER-1596) - Zab1_0Test should ensure that the file is closed
* [ZOOKEEPER-1597](https://issues.apache.org/jira/browse/ZOOKEEPER-1597) - Windows build failing
* [ZOOKEEPER-1602](https://issues.apache.org/jira/browse/ZOOKEEPER-1602) - a change to QuorumPeerConfig&#39;s API broke compatibility with HBase
* [ZOOKEEPER-1603](https://issues.apache.org/jira/browse/ZOOKEEPER-1603) - StaticHostProviderTest testUpdateClientMigrateOrNot hangs
* [ZOOKEEPER-1606](https://issues.apache.org/jira/browse/ZOOKEEPER-1606) - intermittent failures in ZkDatabaseCorruptionTest on jenkins
* [ZOOKEEPER-1610](https://issues.apache.org/jira/browse/ZOOKEEPER-1610) - Some classes are using == or != to compare Long/String objects instead of .equals()
* [ZOOKEEPER-1613](https://issues.apache.org/jira/browse/ZOOKEEPER-1613) - The documentation still points to 2008 in the copyright notice
* [ZOOKEEPER-1620](https://issues.apache.org/jira/browse/ZOOKEEPER-1620) - NIOServerCnxnFactory (new code introduced in ZK-1504) opens selectors but never closes them
* [ZOOKEEPER-1621](https://issues.apache.org/jira/browse/ZOOKEEPER-1621) - ZooKeeper does not recover from crash when disk was full
* [ZOOKEEPER-1622](https://issues.apache.org/jira/browse/ZOOKEEPER-1622) - session ids will be negative in the year 2022
* [ZOOKEEPER-1624](https://issues.apache.org/jira/browse/ZOOKEEPER-1624) - PrepRequestProcessor abort multi-operation incorrectly
* [ZOOKEEPER-1625](https://issues.apache.org/jira/browse/ZOOKEEPER-1625) - zkServer.sh is looking for clientPort in config file, but it may no longer be there with ZK-1411
* [ZOOKEEPER-1628](https://issues.apache.org/jira/browse/ZOOKEEPER-1628) - Documented list of allowable characters in ZK doc not in line with code
* [ZOOKEEPER-1629](https://issues.apache.org/jira/browse/ZOOKEEPER-1629) - testTransactionLogCorruption occasionally fails
* [ZOOKEEPER-1632](https://issues.apache.org/jira/browse/ZOOKEEPER-1632) - fix memory leaks in cli_st 
* [ZOOKEEPER-1641](https://issues.apache.org/jira/browse/ZOOKEEPER-1641) - Using slope=positive results in a jagged ganglia graph of packets rcvd/sent
* [ZOOKEEPER-1642](https://issues.apache.org/jira/browse/ZOOKEEPER-1642) - Leader loading database twice
* [ZOOKEEPER-1643](https://issues.apache.org/jira/browse/ZOOKEEPER-1643) - Windows: fetch_and_add not 64bit-compatible, may not be correct
* [ZOOKEEPER-1645](https://issues.apache.org/jira/browse/ZOOKEEPER-1645) - ZooKeeper OSGi package imports not complete
* [ZOOKEEPER-1646](https://issues.apache.org/jira/browse/ZOOKEEPER-1646) - mt c client tests fail on Ubuntu Raring
* [ZOOKEEPER-1647](https://issues.apache.org/jira/browse/ZOOKEEPER-1647) - OSGi package import/export changes not applied to bin-jar
* [ZOOKEEPER-1648](https://issues.apache.org/jira/browse/ZOOKEEPER-1648) - Fix WatcherTest in JDK7
* [ZOOKEEPER-1650](https://issues.apache.org/jira/browse/ZOOKEEPER-1650) - testServerCnxnExpiry failing consistently on solaris apache jenkins
* [ZOOKEEPER-1655](https://issues.apache.org/jira/browse/ZOOKEEPER-1655) - Make jline dependency optional in maven pom
* [ZOOKEEPER-1657](https://issues.apache.org/jira/browse/ZOOKEEPER-1657) - Increased CPU usage by unnecessary SASL checks
* [ZOOKEEPER-1659](https://issues.apache.org/jira/browse/ZOOKEEPER-1659) - Add JMX support for dynamic reconfiguration
* [ZOOKEEPER-1662](https://issues.apache.org/jira/browse/ZOOKEEPER-1662) - Fix to two small bugs in ReconfigTest.testPortChange()
* [ZOOKEEPER-1663](https://issues.apache.org/jira/browse/ZOOKEEPER-1663) - scripts don&#39;t work when path contains spaces
* [ZOOKEEPER-1667](https://issues.apache.org/jira/browse/ZOOKEEPER-1667) - Watch event isn&#39;t handled correctly when a client reestablish to a server
* [ZOOKEEPER-1670](https://issues.apache.org/jira/browse/ZOOKEEPER-1670) - zookeeper should set a default value for SERVER_JVMFLAGS and CLIENT_JVMFLAGS so that memory usage is controlled
* [ZOOKEEPER-1672](https://issues.apache.org/jira/browse/ZOOKEEPER-1672) - zookeeper client does not accept &quot;-members&quot; option in reconfig command
* [ZOOKEEPER-1673](https://issues.apache.org/jira/browse/ZOOKEEPER-1673) - Zookeeper don&#39;t support cidr in expression in ACL with ip scheme
* [ZOOKEEPER-1677](https://issues.apache.org/jira/browse/ZOOKEEPER-1677) - Misuse of INET_ADDRSTRLEN
* [ZOOKEEPER-1683](https://issues.apache.org/jira/browse/ZOOKEEPER-1683) - ZooKeeper client NPE when updating server list on disconnected client
* [ZOOKEEPER-1684](https://issues.apache.org/jira/browse/ZOOKEEPER-1684) - Failure to update socket addresses on immedate connection
* [ZOOKEEPER-1694](https://issues.apache.org/jira/browse/ZOOKEEPER-1694) - ZooKeeper Leader sends a repeated NEWLEADER quorum packet to followers
* [ZOOKEEPER-1695](https://issues.apache.org/jira/browse/ZOOKEEPER-1695) - Inconsistent error code and type for new errors introduced by dynamic reconfiguration  
* [ZOOKEEPER-1696](https://issues.apache.org/jira/browse/ZOOKEEPER-1696) - Fail to run zookeeper client on Weblogic application server
* [ZOOKEEPER-1697](https://issues.apache.org/jira/browse/ZOOKEEPER-1697) - large snapshots can cause continuous quorum failure
* [ZOOKEEPER-1699](https://issues.apache.org/jira/browse/ZOOKEEPER-1699) - Leader should timeout and give up leadership when losing quorum of last proposed configuration
* [ZOOKEEPER-1700](https://issues.apache.org/jira/browse/ZOOKEEPER-1700) - FLETest consistently failing - setLastSeenQuorumVerifier seems to be hanging
* [ZOOKEEPER-1702](https://issues.apache.org/jira/browse/ZOOKEEPER-1702) - ZooKeeper client may write operation packets before receiving successful response to connection request, can cause TCP RST
* [ZOOKEEPER-1706](https://issues.apache.org/jira/browse/ZOOKEEPER-1706) - Typo in Double Barriers example
* [ZOOKEEPER-1713](https://issues.apache.org/jira/browse/ZOOKEEPER-1713) - wrong time calculation in zkfuse.cc
* [ZOOKEEPER-1714](https://issues.apache.org/jira/browse/ZOOKEEPER-1714) - perl client segfaults if ZOO_READ_ACL_UNSAFE constant is used
* [ZOOKEEPER-1719](https://issues.apache.org/jira/browse/ZOOKEEPER-1719) - zkCli.sh, zkServer.sh and zkEnv.sh regression caused by ZOOKEEPER-1663
* [ZOOKEEPER-1725](https://issues.apache.org/jira/browse/ZOOKEEPER-1725) - Zookeeper Dynamic Conf writes out hostnames when IPs are supplied
* [ZOOKEEPER-1732](https://issues.apache.org/jira/browse/ZOOKEEPER-1732) - ZooKeeper server unable to join established ensemble
* [ZOOKEEPER-1733](https://issues.apache.org/jira/browse/ZOOKEEPER-1733) - FLETest#testLE is flaky on windows boxes
* [ZOOKEEPER-1742](https://issues.apache.org/jira/browse/ZOOKEEPER-1742) - &quot;make check&quot; doesn&#39;t work on macos
* [ZOOKEEPER-1744](https://issues.apache.org/jira/browse/ZOOKEEPER-1744) - clientPortAddress breaks &quot;zkServer.sh status&quot; 
* [ZOOKEEPER-1745](https://issues.apache.org/jira/browse/ZOOKEEPER-1745) - Wrong Import-Package in the META-INF/MANIFEST.MF of zookeeper 3.4.5 bundle
* [ZOOKEEPER-1750](https://issues.apache.org/jira/browse/ZOOKEEPER-1750) - Race condition producing NPE in NIOServerCnxn.toString
* [ZOOKEEPER-1751](https://issues.apache.org/jira/browse/ZOOKEEPER-1751) - ClientCnxn#run could miss the second ping or connection get dropped before a ping
* [ZOOKEEPER-1753](https://issues.apache.org/jira/browse/ZOOKEEPER-1753) - ClientCnxn is not properly releasing the resources, which are used to ping RwServer
* [ZOOKEEPER-1754](https://issues.apache.org/jira/browse/ZOOKEEPER-1754) - Read-only server allows to create znode
* [ZOOKEEPER-1755](https://issues.apache.org/jira/browse/ZOOKEEPER-1755) - Concurrent operations of four letter &#39;dump&#39; ephemeral command and killSession causing NPE
* [ZOOKEEPER-1756](https://issues.apache.org/jira/browse/ZOOKEEPER-1756) - zookeeper_interest() in C client can return a timeval of 0
* [ZOOKEEPER-1765](https://issues.apache.org/jira/browse/ZOOKEEPER-1765) - Update code conventions link on &quot;How to contribute&quot; page
* [ZOOKEEPER-1768](https://issues.apache.org/jira/browse/ZOOKEEPER-1768) - Cluster fails election loop until the device is full
* [ZOOKEEPER-1769](https://issues.apache.org/jira/browse/ZOOKEEPER-1769) - ZooInspector can&#39;t display node data/metadata/ACLs
* [ZOOKEEPER-1770](https://issues.apache.org/jira/browse/ZOOKEEPER-1770) - NullPointerException in SnapshotFormatter
* [ZOOKEEPER-1773](https://issues.apache.org/jira/browse/ZOOKEEPER-1773) - incorrect reference to jline version/lib in docs
* [ZOOKEEPER-1774](https://issues.apache.org/jira/browse/ZOOKEEPER-1774) - QuorumPeerMainTest fails consistently with &quot;complains about host&quot; assertion failure
* [ZOOKEEPER-1775](https://issues.apache.org/jira/browse/ZOOKEEPER-1775) - Ephemeral nodes not present in one of the members of the ensemble
* [ZOOKEEPER-1776](https://issues.apache.org/jira/browse/ZOOKEEPER-1776) - Ephemeral nodes not present in one of the members of the ensemble
* [ZOOKEEPER-1777](https://issues.apache.org/jira/browse/ZOOKEEPER-1777) - Missing ephemeral nodes in one of the members of the ensemble
* [ZOOKEEPER-1779](https://issues.apache.org/jira/browse/ZOOKEEPER-1779) - ReconfigTest littering the source root with test files
* [ZOOKEEPER-1781](https://issues.apache.org/jira/browse/ZOOKEEPER-1781) - ZooKeeper Server fails if snapCount is set to 1 
* [ZOOKEEPER-1783](https://issues.apache.org/jira/browse/ZOOKEEPER-1783) - Distinguish initial configuration from first established configuration
* [ZOOKEEPER-1784](https://issues.apache.org/jira/browse/ZOOKEEPER-1784) - Logic to process INFORMANDACTIVATE packets in syncWithLeader seems bogus
* [ZOOKEEPER-1785](https://issues.apache.org/jira/browse/ZOOKEEPER-1785) - Small fix in zkServer.sh to support new configuration format
* [ZOOKEEPER-1786](https://issues.apache.org/jira/browse/ZOOKEEPER-1786) - ZooKeeper data model documentation is incorrect
* [ZOOKEEPER-1789](https://issues.apache.org/jira/browse/ZOOKEEPER-1789) - 3.4.x observer causes NPE on 3.5.0 (trunk) participants
* [ZOOKEEPER-1790](https://issues.apache.org/jira/browse/ZOOKEEPER-1790) - Deal with special ObserverId in QuorumCnxManager.receiveConnection
* [ZOOKEEPER-1791](https://issues.apache.org/jira/browse/ZOOKEEPER-1791) - ZooKeeper package includes unnecessary jars that are part of the package.
* [ZOOKEEPER-1795](https://issues.apache.org/jira/browse/ZOOKEEPER-1795) - unable to build c client on ubuntu
* [ZOOKEEPER-1797](https://issues.apache.org/jira/browse/ZOOKEEPER-1797) - PurgeTxnLog may delete data logs during roll
* [ZOOKEEPER-1798](https://issues.apache.org/jira/browse/ZOOKEEPER-1798) - Fix race condition in testNormalObserverRun
* [ZOOKEEPER-1799](https://issues.apache.org/jira/browse/ZOOKEEPER-1799) - SaslAuthFailDesignatedClientTest.testAuth fails frequently on SUSE
* [ZOOKEEPER-1800](https://issues.apache.org/jira/browse/ZOOKEEPER-1800) - jenkins failure in testGetProposalFromTxn
* [ZOOKEEPER-1801](https://issues.apache.org/jira/browse/ZOOKEEPER-1801) - TestReconfig failure
* [ZOOKEEPER-1806](https://issues.apache.org/jira/browse/ZOOKEEPER-1806) - testCurrentServersAreObserversInNextConfig failing frequently on trunk with non-jdk6
* [ZOOKEEPER-1807](https://issues.apache.org/jira/browse/ZOOKEEPER-1807) - Observers spam each other creating connections to the election addr
* [ZOOKEEPER-1810](https://issues.apache.org/jira/browse/ZOOKEEPER-1810) - Add version to FLE notifications for trunk
* [ZOOKEEPER-1811](https://issues.apache.org/jira/browse/ZOOKEEPER-1811) - The ZooKeeperSaslClient service name principal is hardcoded to &quot;zookeeper&quot;
* [ZOOKEEPER-1812](https://issues.apache.org/jira/browse/ZOOKEEPER-1812) - ZooInspector reconnection always fails if first connection fails
* [ZOOKEEPER-1813](https://issues.apache.org/jira/browse/ZOOKEEPER-1813) - Zookeeper restart fails due to missing node from snapshot
* [ZOOKEEPER-1814](https://issues.apache.org/jira/browse/ZOOKEEPER-1814) - Reduction of waiting time during Fast Leader Election
* [ZOOKEEPER-1818](https://issues.apache.org/jira/browse/ZOOKEEPER-1818) - Fix don&#39;t care for trunk
* [ZOOKEEPER-1819](https://issues.apache.org/jira/browse/ZOOKEEPER-1819) - DeserializationPerfTest calls method with wrong arguments
* [ZOOKEEPER-1821](https://issues.apache.org/jira/browse/ZOOKEEPER-1821) - very ugly warning when compiling load_gen.c
* [ZOOKEEPER-1823](https://issues.apache.org/jira/browse/ZOOKEEPER-1823) - LogFormatter should support printing transaction data as a string
* [ZOOKEEPER-1835](https://issues.apache.org/jira/browse/ZOOKEEPER-1835) - dynamic configuration file renaming fails on Windows
* [ZOOKEEPER-1836](https://issues.apache.org/jira/browse/ZOOKEEPER-1836) - addrvec_next() fails to set next parameter if addrvec_hasnext() returns false
* [ZOOKEEPER-1839](https://issues.apache.org/jira/browse/ZOOKEEPER-1839) - Deadlock in NettyServerCnxn
* [ZOOKEEPER-1843](https://issues.apache.org/jira/browse/ZOOKEEPER-1843) - Oddity in ByteBufferInputStream skip
* [ZOOKEEPER-1844](https://issues.apache.org/jira/browse/ZOOKEEPER-1844) - TruncateTest fails on windows
* [ZOOKEEPER-1847](https://issues.apache.org/jira/browse/ZOOKEEPER-1847) - Normalize line endings in repository
* [ZOOKEEPER-1848](https://issues.apache.org/jira/browse/ZOOKEEPER-1848) - [WINDOWS] Java NIO socket channels does not work with Windows ipv6 on JDK6
* [ZOOKEEPER-1850](https://issues.apache.org/jira/browse/ZOOKEEPER-1850) - cppunit test testNonexistingHost in TestZookeeperInit is failing on Unbuntu
* [ZOOKEEPER-1851](https://issues.apache.org/jira/browse/ZOOKEEPER-1851) - Follower and Observer Request Processors Do Not Forward create2 Requests
* [ZOOKEEPER-1855](https://issues.apache.org/jira/browse/ZOOKEEPER-1855) - calls to zoo_set_server() fail to flush outstanding request queue.
* [ZOOKEEPER-1860](https://issues.apache.org/jira/browse/ZOOKEEPER-1860) - Async versions of reconfig don&#39;t actually throw KeeperException nor InterruptedException
* [ZOOKEEPER-1861](https://issues.apache.org/jira/browse/ZOOKEEPER-1861) - ConcurrentHashMap isn&#39;t used properly in QuorumCnxManager
* [ZOOKEEPER-1862](https://issues.apache.org/jira/browse/ZOOKEEPER-1862) - ServerCnxnTest.testServerCnxnExpiry is intermittently failing
* [ZOOKEEPER-1863](https://issues.apache.org/jira/browse/ZOOKEEPER-1863) - Race condition in commit processor leading to out of order request completion, xid mismatch on client.
* [ZOOKEEPER-1864](https://issues.apache.org/jira/browse/ZOOKEEPER-1864) - quorumVerifier is null when creating a QuorumPeerConfig from parsing a Properties object
* [ZOOKEEPER-1865](https://issues.apache.org/jira/browse/ZOOKEEPER-1865) - Fix retry logic in Learner.connectToLeader() 
* [ZOOKEEPER-1870](https://issues.apache.org/jira/browse/ZOOKEEPER-1870) - flakey test in StandaloneDisabledTest.startSingleServerTest
* [ZOOKEEPER-1875](https://issues.apache.org/jira/browse/ZOOKEEPER-1875) - NullPointerException in ClientCnxn$EventThread.processEvent
* [ZOOKEEPER-1877](https://issues.apache.org/jira/browse/ZOOKEEPER-1877) - Malformed ACL Id can crash server with skipACL=yes
* [ZOOKEEPER-1878](https://issues.apache.org/jira/browse/ZOOKEEPER-1878) - Inconsistent behavior in autocreation of dataDir and dataLogDir
* [ZOOKEEPER-1883](https://issues.apache.org/jira/browse/ZOOKEEPER-1883) - C client unit test failures
* [ZOOKEEPER-1888](https://issues.apache.org/jira/browse/ZOOKEEPER-1888) - ZkCli.cmd commands fail with &quot;&#39;java&#39; is not recognized as an internal or external command&quot;
* [ZOOKEEPER-1891](https://issues.apache.org/jira/browse/ZOOKEEPER-1891) - StaticHostProviderTest.testUpdateLoadBalancing times out
* [ZOOKEEPER-1892](https://issues.apache.org/jira/browse/ZOOKEEPER-1892) - addrvec_next gets called twice when failing over to the next server
* [ZOOKEEPER-1894](https://issues.apache.org/jira/browse/ZOOKEEPER-1894) - ObserverTest.testObserver fails consistently
* [ZOOKEEPER-1895](https://issues.apache.org/jira/browse/ZOOKEEPER-1895) - update all notice files, copyright, etc... with the new year - 2014
* [ZOOKEEPER-1896](https://issues.apache.org/jira/browse/ZOOKEEPER-1896) - Reconfig error messages when upgrading from 3.4.6 to 3.5.0
* [ZOOKEEPER-1897](https://issues.apache.org/jira/browse/ZOOKEEPER-1897) - ZK Shell/Cli not processing commands
* [ZOOKEEPER-1900](https://issues.apache.org/jira/browse/ZOOKEEPER-1900) -  NullPointerException in truncate
* [ZOOKEEPER-1901](https://issues.apache.org/jira/browse/ZOOKEEPER-1901) - [JDK8] Sort children for comparison in AsyncOps tests
* [ZOOKEEPER-1906](https://issues.apache.org/jira/browse/ZOOKEEPER-1906) - zkpython: invalid data in GetData for empty node
* [ZOOKEEPER-1909](https://issues.apache.org/jira/browse/ZOOKEEPER-1909) - removeWatches doesn&#39;t return NOWATCHER when there is no watch set
* [ZOOKEEPER-1910](https://issues.apache.org/jira/browse/ZOOKEEPER-1910) - RemoveWatches wrongly removes the watcher if multiple watches exists on a path
* [ZOOKEEPER-1911](https://issues.apache.org/jira/browse/ZOOKEEPER-1911) - REST contrib module does not include all required files when packaged
* [ZOOKEEPER-1913](https://issues.apache.org/jira/browse/ZOOKEEPER-1913) - Invalid manifest files due to bogus revision property value
* [ZOOKEEPER-1917](https://issues.apache.org/jira/browse/ZOOKEEPER-1917) - Apache Zookeeper logs cleartext admin passwords
* [ZOOKEEPER-1919](https://issues.apache.org/jira/browse/ZOOKEEPER-1919) - Update the C implementation of removeWatches to have it match ZOOKEEPER-1910
* [ZOOKEEPER-1923](https://issues.apache.org/jira/browse/ZOOKEEPER-1923) - A typo in zookeeperStarted document
* [ZOOKEEPER-1926](https://issues.apache.org/jira/browse/ZOOKEEPER-1926) - Unit tests should only use build/test/data for data
* [ZOOKEEPER-1932](https://issues.apache.org/jira/browse/ZOOKEEPER-1932) - org.apache.zookeeper.test.LETest.testLE fails once in a while
* [ZOOKEEPER-1933](https://issues.apache.org/jira/browse/ZOOKEEPER-1933) - Windows release build of zk client cannot connect to zk server
* [ZOOKEEPER-1939](https://issues.apache.org/jira/browse/ZOOKEEPER-1939) - ReconfigRecoveryTest.testNextConfigUnreachable is failing
* [ZOOKEEPER-1945](https://issues.apache.org/jira/browse/ZOOKEEPER-1945) - deb - zkCli.sh, zkServer.sh and zkEnv.sh regression caused by ZOOKEEPER-1663
* [ZOOKEEPER-1949](https://issues.apache.org/jira/browse/ZOOKEEPER-1949) - recipes jar not included in the distribution package
* [ZOOKEEPER-1950](https://issues.apache.org/jira/browse/ZOOKEEPER-1950) - configBackwardCompatibilityMode breaks compatibility
* [ZOOKEEPER-1964](https://issues.apache.org/jira/browse/ZOOKEEPER-1964) - Fix Flaky Test in ReconfigTest.java
* [ZOOKEEPER-1966](https://issues.apache.org/jira/browse/ZOOKEEPER-1966) - VS and line breaks
* [ZOOKEEPER-1969](https://issues.apache.org/jira/browse/ZOOKEEPER-1969) - Fix Port Already In Use for JettyAdminServerTest
* [ZOOKEEPER-1973](https://issues.apache.org/jira/browse/ZOOKEEPER-1973) - Jetty Server changes broke ibm6 support
* [ZOOKEEPER-1974](https://issues.apache.org/jira/browse/ZOOKEEPER-1974) - winvs2008 jenkins job failing with &quot;unresolved external symbol&quot;
* [ZOOKEEPER-1983](https://issues.apache.org/jira/browse/ZOOKEEPER-1983) - Append to zookeeper.out (not overwrite) to support logrotation
* [ZOOKEEPER-1984](https://issues.apache.org/jira/browse/ZOOKEEPER-1984) - testLeaderTimesoutOnNewQuorum is a flakey test 
* [ZOOKEEPER-1985](https://issues.apache.org/jira/browse/ZOOKEEPER-1985) - Memory leak in C client
* [ZOOKEEPER-1987](https://issues.apache.org/jira/browse/ZOOKEEPER-1987) - unable to restart 3 node cluster
* [ZOOKEEPER-1990](https://issues.apache.org/jira/browse/ZOOKEEPER-1990) - suspicious instantiation of java Random instances
* [ZOOKEEPER-1991](https://issues.apache.org/jira/browse/ZOOKEEPER-1991) - zkServer.sh returns with a zero exit status when a ZooKeeper process is already running
* [ZOOKEEPER-1992](https://issues.apache.org/jira/browse/ZOOKEEPER-1992) - backward compatibility of zoo.cfg
* [ZOOKEEPER-1998](https://issues.apache.org/jira/browse/ZOOKEEPER-1998) - C library calls getaddrinfo unconditionally from zookeeper_interest
* [ZOOKEEPER-1999](https://issues.apache.org/jira/browse/ZOOKEEPER-1999) - Converting CRLF to LF in DynamicConfigBackwardCompatibilityTest
* [ZOOKEEPER-2000](https://issues.apache.org/jira/browse/ZOOKEEPER-2000) - Fix ReconfigTest.testPortChange

## Improvement
* [ZOOKEEPER-74](https://issues.apache.org/jira/browse/ZOOKEEPER-74) - Cleaning/restructuring up Zookeeper server code
* [ZOOKEEPER-107](https://issues.apache.org/jira/browse/ZOOKEEPER-107) - Allow dynamic changes to server cluster membership
* [ZOOKEEPER-216](https://issues.apache.org/jira/browse/ZOOKEEPER-216) - Improve logging in C client
* [ZOOKEEPER-271](https://issues.apache.org/jira/browse/ZOOKEEPER-271) - Better command line parsing in ZookeeperMain.
* [ZOOKEEPER-297](https://issues.apache.org/jira/browse/ZOOKEEPER-297) - centralize version numbering in the source/build
* [ZOOKEEPER-304](https://issues.apache.org/jira/browse/ZOOKEEPER-304) - factor out common methods from zookeeper.java
* [ZOOKEEPER-556](https://issues.apache.org/jira/browse/ZOOKEEPER-556) - Startup messages should account for common error of missing leading slash in config files
* [ZOOKEEPER-657](https://issues.apache.org/jira/browse/ZOOKEEPER-657) - Cut down the running time of ZKDatabase corruption.
* [ZOOKEEPER-715](https://issues.apache.org/jira/browse/ZOOKEEPER-715) - add better reporting for initLimit being reached
* [ZOOKEEPER-716](https://issues.apache.org/jira/browse/ZOOKEEPER-716) - dump server memory detail to the log during startup
* [ZOOKEEPER-721](https://issues.apache.org/jira/browse/ZOOKEEPER-721) - Minor cleanup related to the log4j version change from 1.2.15 -&gt; 1.2.16
* [ZOOKEEPER-748](https://issues.apache.org/jira/browse/ZOOKEEPER-748) - zkPython&#39;s NodeExistsException should include information about the node that exists
* [ZOOKEEPER-751](https://issues.apache.org/jira/browse/ZOOKEEPER-751) - Recipe heading refers to &#39;recoverable&#39; but should be &#39;revocable&#39;
* [ZOOKEEPER-755](https://issues.apache.org/jira/browse/ZOOKEEPER-755) - Improve c client documentation to reflect that zookeeper_init() creates its own copy of list of host.
* [ZOOKEEPER-756](https://issues.apache.org/jira/browse/ZOOKEEPER-756) - some cleanup and improvements for zooinspector
* [ZOOKEEPER-759](https://issues.apache.org/jira/browse/ZOOKEEPER-759) - Stop accepting connections when close to file descriptor limit
* [ZOOKEEPER-760](https://issues.apache.org/jira/browse/ZOOKEEPER-760) - Improved string encoding and decoding performance
* [ZOOKEEPER-761](https://issues.apache.org/jira/browse/ZOOKEEPER-761) - Remove *synchronous* calls from the *single-threaded* C clieant API, since they are documented not to work
* [ZOOKEEPER-767](https://issues.apache.org/jira/browse/ZOOKEEPER-767) - Submitting Demo/Recipe Shared / Exclusive Lock Code
* [ZOOKEEPER-776](https://issues.apache.org/jira/browse/ZOOKEEPER-776) - API should sanity check sessionTimeout argument
* [ZOOKEEPER-802](https://issues.apache.org/jira/browse/ZOOKEEPER-802) - Improved LogGraph filters + documentation
* [ZOOKEEPER-845](https://issues.apache.org/jira/browse/ZOOKEEPER-845) - remove duplicate code from netty and nio ServerCnxn classes
* [ZOOKEEPER-860](https://issues.apache.org/jira/browse/ZOOKEEPER-860) - Add alternative search-provider to ZK site
* [ZOOKEEPER-896](https://issues.apache.org/jira/browse/ZOOKEEPER-896) - Improve C client to support dynamic authentication schemes
* [ZOOKEEPER-906](https://issues.apache.org/jira/browse/ZOOKEEPER-906) - Improve C client connection reliability by making it sleep between reconnect attempts as in Java Client
* [ZOOKEEPER-912](https://issues.apache.org/jira/browse/ZOOKEEPER-912) - ZooKeeper client logs trace and debug messages at level INFO
* [ZOOKEEPER-922](https://issues.apache.org/jira/browse/ZOOKEEPER-922) - enable faster timeout of sessions in case of unexpected socket disconnect
* [ZOOKEEPER-927](https://issues.apache.org/jira/browse/ZOOKEEPER-927) - there are currently 24 RAT warnings in the build -- address directly or via exclusions
* [ZOOKEEPER-935](https://issues.apache.org/jira/browse/ZOOKEEPER-935) - Concurrent primitives library - shared lock
* [ZOOKEEPER-955](https://issues.apache.org/jira/browse/ZOOKEEPER-955) - Use Atomic(Integer|Long) for (Z)Xid
* [ZOOKEEPER-1000](https://issues.apache.org/jira/browse/ZOOKEEPER-1000) - Provide SSL in zookeeper to be able to run cross colos.
* [ZOOKEEPER-1019](https://issues.apache.org/jira/browse/ZOOKEEPER-1019) - zkfuse doesn&#39;t list dependency on boost in README
* [ZOOKEEPER-1032](https://issues.apache.org/jira/browse/ZOOKEEPER-1032) - speed up recovery from leader failure
* [ZOOKEEPER-1054](https://issues.apache.org/jira/browse/ZOOKEEPER-1054) - Drop connections from servers not in the cluster configuration
* [ZOOKEEPER-1067](https://issues.apache.org/jira/browse/ZOOKEEPER-1067) - the doxygen doc should be generated as part of the release
* [ZOOKEEPER-1096](https://issues.apache.org/jira/browse/ZOOKEEPER-1096) - Leader communication should listen on specified IP, not wildcard address
* [ZOOKEEPER-1147](https://issues.apache.org/jira/browse/ZOOKEEPER-1147) - Add support for local sessions
* [ZOOKEEPER-1162](https://issues.apache.org/jira/browse/ZOOKEEPER-1162) - consistent handling of jute.maxbuffer when attempting to read large zk &quot;directories&quot;
* [ZOOKEEPER-1170](https://issues.apache.org/jira/browse/ZOOKEEPER-1170) - Fix compiler (eclipse) warnings: unused imports, unused variables, missing generics
* [ZOOKEEPER-1175](https://issues.apache.org/jira/browse/ZOOKEEPER-1175) - DataNode references parent node for no reason
* [ZOOKEEPER-1177](https://issues.apache.org/jira/browse/ZOOKEEPER-1177) - Enabling a large number of watches for a large number of clients
* [ZOOKEEPER-1178](https://issues.apache.org/jira/browse/ZOOKEEPER-1178) - Add eclipse target for supporting Apache IvyDE
* [ZOOKEEPER-1205](https://issues.apache.org/jira/browse/ZOOKEEPER-1205) - Add a unit test for Kerberos Ticket-Granting Ticket (TGT) renewal
* [ZOOKEEPER-1219](https://issues.apache.org/jira/browse/ZOOKEEPER-1219) - LeaderElectionSupport recipe is unnecessarily dispatching the READY_START event even if the ELECTED node stopped/expired simultaneously.
* [ZOOKEEPER-1229](https://issues.apache.org/jira/browse/ZOOKEEPER-1229) - C client hashtable_remove redundantly calls hash function
* [ZOOKEEPER-1232](https://issues.apache.org/jira/browse/ZOOKEEPER-1232) - remove unused o.a.z.server.util.Profiler
* [ZOOKEEPER-1239](https://issues.apache.org/jira/browse/ZOOKEEPER-1239) - add logging/stats to identify fsync stalls
* [ZOOKEEPER-1261](https://issues.apache.org/jira/browse/ZOOKEEPER-1261) - Make ZooKeeper code mode Dependency Injection compliant.
* [ZOOKEEPER-1292](https://issues.apache.org/jira/browse/ZOOKEEPER-1292) - FLETest is flaky
* [ZOOKEEPER-1293](https://issues.apache.org/jira/browse/ZOOKEEPER-1293) - Remove unused readyToStart from Leader.java
* [ZOOKEEPER-1296](https://issues.apache.org/jira/browse/ZOOKEEPER-1296) - Add zookeeper-setup-conf.sh script
* [ZOOKEEPER-1321](https://issues.apache.org/jira/browse/ZOOKEEPER-1321) - Add number of client connections metric in JMX and srvr
* [ZOOKEEPER-1322](https://issues.apache.org/jira/browse/ZOOKEEPER-1322) - Cleanup/fix logging in Quorum code.
* [ZOOKEEPER-1324](https://issues.apache.org/jira/browse/ZOOKEEPER-1324) - Remove Duplicate NEWLEADER packets from the Leader to the Follower.
* [ZOOKEEPER-1335](https://issues.apache.org/jira/browse/ZOOKEEPER-1335) - Add support for --config to zkEnv.sh to specify a config directory different than what is expected
* [ZOOKEEPER-1342](https://issues.apache.org/jira/browse/ZOOKEEPER-1342) - quorum Listener &amp; LearnerCnxAcceptor are missing thread names
* [ZOOKEEPER-1345](https://issues.apache.org/jira/browse/ZOOKEEPER-1345) - Add a .gitignore file with general exclusions and Eclipse project files excluded
* [ZOOKEEPER-1346](https://issues.apache.org/jira/browse/ZOOKEEPER-1346) - Add Jetty HTTP server support for four letter words.
* [ZOOKEEPER-1350](https://issues.apache.org/jira/browse/ZOOKEEPER-1350) - Please make JMX registration optional in LearnerZooKeeperServer
* [ZOOKEEPER-1377](https://issues.apache.org/jira/browse/ZOOKEEPER-1377) - add support for dumping a snapshot file content (similar to LogFormatter)
* [ZOOKEEPER-1389](https://issues.apache.org/jira/browse/ZOOKEEPER-1389) - it would be nice if start-foreground used exec $JAVA in order to get rid of the intermediate shell process
* [ZOOKEEPER-1390](https://issues.apache.org/jira/browse/ZOOKEEPER-1390) - some expensive debug code not protected by a check for debug
* [ZOOKEEPER-1397](https://issues.apache.org/jira/browse/ZOOKEEPER-1397) - Remove BookKeeper documentation links
* [ZOOKEEPER-1400](https://issues.apache.org/jira/browse/ZOOKEEPER-1400) - Allow logging via callback instead of raw FILE pointer
* [ZOOKEEPER-1407](https://issues.apache.org/jira/browse/ZOOKEEPER-1407) - Support GetData and GetChildren in Multi
* [ZOOKEEPER-1408](https://issues.apache.org/jira/browse/ZOOKEEPER-1408) - CLI: sort output of ls command
* [ZOOKEEPER-1409](https://issues.apache.org/jira/browse/ZOOKEEPER-1409) - CLI: deprecate ls2 command
* [ZOOKEEPER-1413](https://issues.apache.org/jira/browse/ZOOKEEPER-1413) - Use on-disk transaction log for learner sync up
* [ZOOKEEPER-1426](https://issues.apache.org/jira/browse/ZOOKEEPER-1426) - add version command to the zookeeper server
* [ZOOKEEPER-1432](https://issues.apache.org/jira/browse/ZOOKEEPER-1432) - Add javadoc and debug logging for checkACL() method in PrepRequestProcessor
* [ZOOKEEPER-1433](https://issues.apache.org/jira/browse/ZOOKEEPER-1433) - improve ZxidRolloverTest (test seems flakey)
* [ZOOKEEPER-1435](https://issues.apache.org/jira/browse/ZOOKEEPER-1435) - cap space usage of default log4j rolling policy
* [ZOOKEEPER-1445](https://issues.apache.org/jira/browse/ZOOKEEPER-1445) - Add support for binary data for zktreeutil
* [ZOOKEEPER-1452](https://issues.apache.org/jira/browse/ZOOKEEPER-1452) - zoo_multi() &amp; zoo_amulti() update operations for zkpython
* [ZOOKEEPER-1454](https://issues.apache.org/jira/browse/ZOOKEEPER-1454) - Document how to run autoreconf if cppunit is installed in a non-standard directory
* [ZOOKEEPER-1469](https://issues.apache.org/jira/browse/ZOOKEEPER-1469) - Adding Cross-Realm support for secure Zookeeper client authentication
* [ZOOKEEPER-1481](https://issues.apache.org/jira/browse/ZOOKEEPER-1481) - allow the C cli to run exists with a watcher
* [ZOOKEEPER-1497](https://issues.apache.org/jira/browse/ZOOKEEPER-1497) - Allow server-side SASL login with JAAS configuration to be programmatically set (rather than only by reading JAAS configuration file)
* [ZOOKEEPER-1502](https://issues.apache.org/jira/browse/ZOOKEEPER-1502) - Prevent multiple zookeeper servers from using the same data directory
* [ZOOKEEPER-1503](https://issues.apache.org/jira/browse/ZOOKEEPER-1503) - remove redundant JAAS configuration code in SaslAuthTest and SaslAuthFailTest
* [ZOOKEEPER-1504](https://issues.apache.org/jira/browse/ZOOKEEPER-1504) - Multi-thread NIOServerCnxn
* [ZOOKEEPER-1505](https://issues.apache.org/jira/browse/ZOOKEEPER-1505) - Multi-thread CommitProcessor
* [ZOOKEEPER-1506](https://issues.apache.org/jira/browse/ZOOKEEPER-1506) - Re-try DNS hostname -&gt; IP resolution if node connection fails
* [ZOOKEEPER-1510](https://issues.apache.org/jira/browse/ZOOKEEPER-1510) - Should not log SASL errors for non-secure usage
* [ZOOKEEPER-1525](https://issues.apache.org/jira/browse/ZOOKEEPER-1525) - Plumb ZooKeeperServer object into auth plugins
* [ZOOKEEPER-1532](https://issues.apache.org/jira/browse/ZOOKEEPER-1532) - Correct the documentation of the args for the JavaExample doc.
* [ZOOKEEPER-1552](https://issues.apache.org/jira/browse/ZOOKEEPER-1552) - Enable sync request processor in Observer
* [ZOOKEEPER-1564](https://issues.apache.org/jira/browse/ZOOKEEPER-1564) - Allow JUnit test build with IBM Java
* [ZOOKEEPER-1572](https://issues.apache.org/jira/browse/ZOOKEEPER-1572) - Add an async interface for multi request
* [ZOOKEEPER-1574](https://issues.apache.org/jira/browse/ZOOKEEPER-1574) - mismatched CR/LF endings in text files
* [ZOOKEEPER-1583](https://issues.apache.org/jira/browse/ZOOKEEPER-1583) - Document maxClientCnxns in conf/zoo_sample.cfg
* [ZOOKEEPER-1584](https://issues.apache.org/jira/browse/ZOOKEEPER-1584) - Adding mvn-install target for deploying the zookeeper artifacts to .m2 repository.
* [ZOOKEEPER-1598](https://issues.apache.org/jira/browse/ZOOKEEPER-1598) - Ability to support more digits in the version string
* [ZOOKEEPER-1601](https://issues.apache.org/jira/browse/ZOOKEEPER-1601) - document changes for multi-threaded CommitProcessor and NIOServerCnxn
* [ZOOKEEPER-1615](https://issues.apache.org/jira/browse/ZOOKEEPER-1615) - minor typos in ZooKeeper Programmer&#39;s Guide web page
* [ZOOKEEPER-1619](https://issues.apache.org/jira/browse/ZOOKEEPER-1619) - Allow spaces in URL
* [ZOOKEEPER-1627](https://issues.apache.org/jira/browse/ZOOKEEPER-1627) - Add org.apache.zookeeper.common to exported packages in OSGi MANIFEST headers
* [ZOOKEEPER-1634](https://issues.apache.org/jira/browse/ZOOKEEPER-1634) - A new feature proposal to ZooKeeper: authentication enforcement
* [ZOOKEEPER-1635](https://issues.apache.org/jira/browse/ZOOKEEPER-1635) - ZooKeeper C client doesn&#39;t compile on 64 bit Windows
* [ZOOKEEPER-1638](https://issues.apache.org/jira/browse/ZOOKEEPER-1638) - Redundant zk.getZKDatabase().clear();
* [ZOOKEEPER-1666](https://issues.apache.org/jira/browse/ZOOKEEPER-1666) - Avoid Reverse DNS lookup if the hostname in connection string is literal IP address.
* [ZOOKEEPER-1679](https://issues.apache.org/jira/browse/ZOOKEEPER-1679) - c client: use -Wdeclaration-after-statement
* [ZOOKEEPER-1681](https://issues.apache.org/jira/browse/ZOOKEEPER-1681) - ZooKeeper 3.4.x can optionally use netty for nio but the pom does not declare the dep as optional
* [ZOOKEEPER-1691](https://issues.apache.org/jira/browse/ZOOKEEPER-1691) - Add a flag to disable standalone mode
* [ZOOKEEPER-1701](https://issues.apache.org/jira/browse/ZOOKEEPER-1701) - When new and old config have the same version, no need to write new config to disk or create new connections
* [ZOOKEEPER-1715](https://issues.apache.org/jira/browse/ZOOKEEPER-1715) - Upgrade netty version
* [ZOOKEEPER-1724](https://issues.apache.org/jira/browse/ZOOKEEPER-1724) - Support Kerberos authentication for non-SUN JDK
* [ZOOKEEPER-1728](https://issues.apache.org/jira/browse/ZOOKEEPER-1728) - Better error message when reconfig invoked in standalone mode
* [ZOOKEEPER-1746](https://issues.apache.org/jira/browse/ZOOKEEPER-1746) - AsyncCallback.*Callback don&#39;t have any Javadoc
* [ZOOKEEPER-1748](https://issues.apache.org/jira/browse/ZOOKEEPER-1748) - TCP keepalive for leader election connections
* [ZOOKEEPER-1749](https://issues.apache.org/jira/browse/ZOOKEEPER-1749) - Login outside of Zookeeper client
* [ZOOKEEPER-1758](https://issues.apache.org/jira/browse/ZOOKEEPER-1758) - Add documentation for zookeeper.observer.syncEnabled flag
* [ZOOKEEPER-1759](https://issues.apache.org/jira/browse/ZOOKEEPER-1759) - Adding ability to allow READ operations for authenticated users,  versus keeping ACLs wide open for READ
* [ZOOKEEPER-1766](https://issues.apache.org/jira/browse/ZOOKEEPER-1766) - Consistent log severity level guards and statements
* [ZOOKEEPER-1771](https://issues.apache.org/jira/browse/ZOOKEEPER-1771) - ZooInspector authentication
* [ZOOKEEPER-1778](https://issues.apache.org/jira/browse/ZOOKEEPER-1778) - Use static final Logger objects
* [ZOOKEEPER-1796](https://issues.apache.org/jira/browse/ZOOKEEPER-1796) - Move common code from {Follower, Observer}ZooKeeperServer into LearnerZooKeeperServer
* [ZOOKEEPER-1815](https://issues.apache.org/jira/browse/ZOOKEEPER-1815) - Tolerate incorrectly set system hostname in tests
* [ZOOKEEPER-1876](https://issues.apache.org/jira/browse/ZOOKEEPER-1876) - Add support for installing windows services in .cmd scripts
* [ZOOKEEPER-1879](https://issues.apache.org/jira/browse/ZOOKEEPER-1879) - improve the correctness checking of txn log replay
* [ZOOKEEPER-1881](https://issues.apache.org/jira/browse/ZOOKEEPER-1881) - Shutdown server immediately upon PrivilegedActionException
* [ZOOKEEPER-1907](https://issues.apache.org/jira/browse/ZOOKEEPER-1907) - Improve Thread handling
* [ZOOKEEPER-1915](https://issues.apache.org/jira/browse/ZOOKEEPER-1915) - Use $(ProjectDir) macro to specify include directories
* [ZOOKEEPER-1946](https://issues.apache.org/jira/browse/ZOOKEEPER-1946) - Server logging should reflect dynamically reconfigured address
* [ZOOKEEPER-1948](https://issues.apache.org/jira/browse/ZOOKEEPER-1948) - Enable JMX remote monitoring - Updated patch for review comments
* [ZOOKEEPER-1953](https://issues.apache.org/jira/browse/ZOOKEEPER-1953) - Add solution and project files to enable build with current Visual Studio editions (VS 2012/2013) - 32-bit and 64-bit.
* [ZOOKEEPER-1968](https://issues.apache.org/jira/browse/ZOOKEEPER-1968) - Make Jetty dependencies optional in ivy.xml
* [ZOOKEEPER-1970](https://issues.apache.org/jira/browse/ZOOKEEPER-1970) - Fix Findbugs Warnings
* [ZOOKEEPER-1982](https://issues.apache.org/jira/browse/ZOOKEEPER-1982) - Refactor (touch|add)Session in SessionTrackerImpl.java
* [ZOOKEEPER-1986](https://issues.apache.org/jira/browse/ZOOKEEPER-1986) - refactor log trace on touchSession
* [ZOOKEEPER-1994](https://issues.apache.org/jira/browse/ZOOKEEPER-1994) - Backup config files.

## New Feature
* [ZOOKEEPER-364](https://issues.apache.org/jira/browse/ZOOKEEPER-364) - command line interface for zookeeper.
* [ZOOKEEPER-679](https://issues.apache.org/jira/browse/ZOOKEEPER-679) - Offers a node design for interacting with the Java Zookeeper client.
* [ZOOKEEPER-781](https://issues.apache.org/jira/browse/ZOOKEEPER-781) - provide a generalized &quot;connection strategy&quot; for ZooKeeper clients
* [ZOOKEEPER-823](https://issues.apache.org/jira/browse/ZOOKEEPER-823) - update ZooKeeper java client to optionally use Netty for connections
* [ZOOKEEPER-911](https://issues.apache.org/jira/browse/ZOOKEEPER-911) - move operations from methods to individual classes
* [ZOOKEEPER-920](https://issues.apache.org/jira/browse/ZOOKEEPER-920) - L7 (application layer) ping support
* [ZOOKEEPER-1080](https://issues.apache.org/jira/browse/ZOOKEEPER-1080) - Provide a Leader Election framework based on Zookeeper recipe
* [ZOOKEEPER-1098](https://issues.apache.org/jira/browse/ZOOKEEPER-1098) - Upload native libraries as Maven artifacts
* [ZOOKEEPER-1161](https://issues.apache.org/jira/browse/ZOOKEEPER-1161) - Provide an option for disabling auto-creation of the data directory
* [ZOOKEEPER-1297](https://issues.apache.org/jira/browse/ZOOKEEPER-1297) - Add stat information to create() call
* [ZOOKEEPER-1355](https://issues.apache.org/jira/browse/ZOOKEEPER-1355) - Add zk.updateServerList(newServerList) 
* [ZOOKEEPER-1482](https://issues.apache.org/jira/browse/ZOOKEEPER-1482) - Batch get to improve perfermance
* [ZOOKEEPER-1760](https://issues.apache.org/jira/browse/ZOOKEEPER-1760) - Provide an interface for check version of a node
* [ZOOKEEPER-1829](https://issues.apache.org/jira/browse/ZOOKEEPER-1829) - Umbrella jira for removing watches that are no longer of interest
* [ZOOKEEPER-1887](https://issues.apache.org/jira/browse/ZOOKEEPER-1887) - C implementation of removeWatches
* [ZOOKEEPER-1962](https://issues.apache.org/jira/browse/ZOOKEEPER-1962) - Add a CLI command to recursively list a znode and children

## Task
* [ZOOKEEPER-852](https://issues.apache.org/jira/browse/ZOOKEEPER-852) - Check path validation in C client
* [ZOOKEEPER-899](https://issues.apache.org/jira/browse/ZOOKEEPER-899) - Update Netty version in trunk to 3.2.2
* [ZOOKEEPER-1072](https://issues.apache.org/jira/browse/ZOOKEEPER-1072) - Support for embedded ZooKeeper
* [ZOOKEEPER-1149](https://issues.apache.org/jira/browse/ZOOKEEPER-1149) - users cannot migrate from 3.4-&gt;3.3-&gt;3.4 server code against a single datadir
* [ZOOKEEPER-1176](https://issues.apache.org/jira/browse/ZOOKEEPER-1176) - Remove dead code and basic cleanup in DataTree
* [ZOOKEEPER-1182](https://issues.apache.org/jira/browse/ZOOKEEPER-1182) - Make findbugs usable in Eclipse
* [ZOOKEEPER-1193](https://issues.apache.org/jira/browse/ZOOKEEPER-1193) - Remove upgrade code
* [ZOOKEEPER-1263](https://issues.apache.org/jira/browse/ZOOKEEPER-1263) - fix handling of min/max session timeout value initialization
* [ZOOKEEPER-1378](https://issues.apache.org/jira/browse/ZOOKEEPER-1378) - Provide option to turn off sending of diffs
* [ZOOKEEPER-1430](https://issues.apache.org/jira/browse/ZOOKEEPER-1430) - add maven deploy support to the build
* [ZOOKEEPER-1509](https://issues.apache.org/jira/browse/ZOOKEEPER-1509) - Please update documentation to reflect updated FreeBSD support.
* [ZOOKEEPER-1604](https://issues.apache.org/jira/browse/ZOOKEEPER-1604) - remove rpm/deb/... packaging
* [ZOOKEEPER-1918](https://issues.apache.org/jira/browse/ZOOKEEPER-1918) - Add 64 bit Windows as a supported development platform
* [ZOOKEEPER-1938](https://issues.apache.org/jira/browse/ZOOKEEPER-1938) - bump version in the C library as we prepare for 3.5.0 release

## Test
* [ZOOKEEPER-1328](https://issues.apache.org/jira/browse/ZOOKEEPER-1328) - Misplaced assertion for the test case &#39;FLELostMessageTest&#39; and not identifying misfunctions
* [ZOOKEEPER-1337](https://issues.apache.org/jira/browse/ZOOKEEPER-1337) - multi&#39;s &quot;Transaction&quot; class is missing tests.
* [ZOOKEEPER-1718](https://issues.apache.org/jira/browse/ZOOKEEPER-1718) - Support JLine 2

## Wish
* [ZOOKEEPER-964](https://issues.apache.org/jira/browse/ZOOKEEPER-964) - How to avoid dead nodes generated? These nodes can&#39;t be deleted because there parent don&#39;t have delete and setacl permission.
* [ZOOKEEPER-1326](https://issues.apache.org/jira/browse/ZOOKEEPER-1326) - The CLI commands &quot;delete&quot; and &quot;rmr&quot; are confusing. Can we have &quot;delete&quot; + &quot;deleteall&quot; instead?
* [ZOOKEEPER-1727](https://issues.apache.org/jira/browse/ZOOKEEPER-1727) - Doc request: The right way to expand a cluster
