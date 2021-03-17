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

# Release Notes - ZooKeeper - Version 3.7.0

## New Feature

* [ZOOKEEPER-1112](https://issues.apache.org/jira/browse/ZOOKEEPER-1112) - Add support for C client for SASL authentication
* [ZOOKEEPER-3264](https://issues.apache.org/jira/browse/ZOOKEEPER-3264) - The benchmark tools for zookeeper
* [ZOOKEEPER-3301](https://issues.apache.org/jira/browse/ZOOKEEPER-3301) - Enforce the quota limit
* [ZOOKEEPER-3681](https://issues.apache.org/jira/browse/ZOOKEEPER-3681) - Add s390x support for Travis build
* [ZOOKEEPER-3714](https://issues.apache.org/jira/browse/ZOOKEEPER-3714) - Add (Cyrus) SASL authentication support to Perl client
* [ZOOKEEPER-3874](https://issues.apache.org/jira/browse/ZOOKEEPER-3874) - Official API to start ZooKeeper server from Java
* [ZOOKEEPER-3948](https://issues.apache.org/jira/browse/ZOOKEEPER-3948) - Introduce a deterministic runtime behavior injection framework for ZooKeeperServer testing
* [ZOOKEEPER-3959](https://issues.apache.org/jira/browse/ZOOKEEPER-3959) - Allow multiple superUsers with SASL
* [ZOOKEEPER-3969](https://issues.apache.org/jira/browse/ZOOKEEPER-3969) - Add whoami  API and Cli command
* [ZOOKEEPER-4030](https://issues.apache.org/jira/browse/ZOOKEEPER-4030) - Optionally canonicalize host names in quorum SASL authentication

## Improvement

* [ZOOKEEPER-1871](https://issues.apache.org/jira/browse/ZOOKEEPER-1871) - Add an option to zkCli to wait for connection before executing commands
* [ZOOKEEPER-2272](https://issues.apache.org/jira/browse/ZOOKEEPER-2272) - Code clean up in ZooKeeperServer and KerberosName
* [ZOOKEEPER-2649](https://issues.apache.org/jira/browse/ZOOKEEPER-2649) - The ZooKeeper do not write in log session ID in which the client has been authenticated.
* [ZOOKEEPER-2779](https://issues.apache.org/jira/browse/ZOOKEEPER-2779) - Add option to not set ACL for reconfig node
* [ZOOKEEPER-3101](https://issues.apache.org/jira/browse/ZOOKEEPER-3101) - Add comment reminding users to add cases to zerror when adding values to ZOO_ERRORS
* [ZOOKEEPER-3342](https://issues.apache.org/jira/browse/ZOOKEEPER-3342) - Use StandardCharsets
* [ZOOKEEPER-3411](https://issues.apache.org/jira/browse/ZOOKEEPER-3411) - remove the deprecated CLI: ls2 and rmr
* [ZOOKEEPER-3427](https://issues.apache.org/jira/browse/ZOOKEEPER-3427) - Introduce SnapshotComparer that assists debugging with snapshots.
* [ZOOKEEPER-3482](https://issues.apache.org/jira/browse/ZOOKEEPER-3482) - SASL (Kerberos) Authentication with SSL for clients and Quorum
* [ZOOKEEPER-3561](https://issues.apache.org/jira/browse/ZOOKEEPER-3561) - Generalize target authentication scheme for ZooKeeper authentication enforcement.
* [ZOOKEEPER-3567](https://issues.apache.org/jira/browse/ZOOKEEPER-3567) - Add SSL support for the zk python client
* [ZOOKEEPER-3581](https://issues.apache.org/jira/browse/ZOOKEEPER-3581) - use factory design pattern to refactor ZooKeeperMain
* [ZOOKEEPER-3582](https://issues.apache.org/jira/browse/ZOOKEEPER-3582) - refactor the async api call to lambda style
* [ZOOKEEPER-3638](https://issues.apache.org/jira/browse/ZOOKEEPER-3638) - Update Jetty to 9.4.24.v20191120
* [ZOOKEEPER-3640](https://issues.apache.org/jira/browse/ZOOKEEPER-3640) - Implement &quot;batch mode&quot; in cli_mt
* [ZOOKEEPER-3649](https://issues.apache.org/jira/browse/ZOOKEEPER-3649) - ls -s CLI need a line break
* [ZOOKEEPER-3662](https://issues.apache.org/jira/browse/ZOOKEEPER-3662) - Remove NPE Possibility in Follower Class
* [ZOOKEEPER-3663](https://issues.apache.org/jira/browse/ZOOKEEPER-3663) - Clean Up ZNodeName Class
* [ZOOKEEPER-3666](https://issues.apache.org/jira/browse/ZOOKEEPER-3666) - remove the deprecated LogFormatter tool
* [ZOOKEEPER-3671](https://issues.apache.org/jira/browse/ZOOKEEPER-3671) - Use ThreadLocalConcurrent to Replace Random and Math.random
* [ZOOKEEPER-3678](https://issues.apache.org/jira/browse/ZOOKEEPER-3678) - Remove Redundant GroupID from Maven POMs
* [ZOOKEEPER-3679](https://issues.apache.org/jira/browse/ZOOKEEPER-3679) - Upgrade maven-compiler-plugin For ZooKeeper-jute
* [ZOOKEEPER-3682](https://issues.apache.org/jira/browse/ZOOKEEPER-3682) - Stop initializing new SSL connection if ZK server is shutting down
* [ZOOKEEPER-3683](https://issues.apache.org/jira/browse/ZOOKEEPER-3683) - Discard requests that are delayed longer than a configured threshold
* [ZOOKEEPER-3687](https://issues.apache.org/jira/browse/ZOOKEEPER-3687) - Jute Use JDK hashCode Methods for Native Types
* [ZOOKEEPER-3688](https://issues.apache.org/jira/browse/ZOOKEEPER-3688) - Use StandardCharsets UTF-8 in Jute toString
* [ZOOKEEPER-3690](https://issues.apache.org/jira/browse/ZOOKEEPER-3690) - Improving leader efficiency via not processing learner&#39;s requests in commit processor
* [ZOOKEEPER-3691](https://issues.apache.org/jira/browse/ZOOKEEPER-3691) - Use JDK String Join Method in ZK StringUtils
* [ZOOKEEPER-3694](https://issues.apache.org/jira/browse/ZOOKEEPER-3694) - Use Map computeIfAbsent in AvgMinMaxCounterSet Class
* [ZOOKEEPER-3708](https://issues.apache.org/jira/browse/ZOOKEEPER-3708) - Move Logging Code into Logging Guard in Learner
* [ZOOKEEPER-3722](https://issues.apache.org/jira/browse/ZOOKEEPER-3722) - make logs of ResponseCache more readable
* [ZOOKEEPER-3728](https://issues.apache.org/jira/browse/ZOOKEEPER-3728) - move traceMask calculation logic into the trace log in the FinalRequestProcessor#processRequest
* [ZOOKEEPER-3741](https://issues.apache.org/jira/browse/ZOOKEEPER-3741) - Fix ZooKeeper 3.5 C client build on Fedora8
* [ZOOKEEPER-3761](https://issues.apache.org/jira/browse/ZOOKEEPER-3761) - upgrade JLine jar dependency
* [ZOOKEEPER-3767](https://issues.apache.org/jira/browse/ZOOKEEPER-3767) - fix a large amount of maven build warnings
* [ZOOKEEPER-3785](https://issues.apache.org/jira/browse/ZOOKEEPER-3785) - Make sources buildable with JDK14
* [ZOOKEEPER-3786](https://issues.apache.org/jira/browse/ZOOKEEPER-3786) - Simplify generation of VersionInfoMain and Info
* [ZOOKEEPER-3788](https://issues.apache.org/jira/browse/ZOOKEEPER-3788) - Add m2e configuration in pom.xml for Eclipse developers
* [ZOOKEEPER-3790](https://issues.apache.org/jira/browse/ZOOKEEPER-3790) - zkpython: Minor compilation and testing issues
* [ZOOKEEPER-3791](https://issues.apache.org/jira/browse/ZOOKEEPER-3791) - Miscellaneous Maven improvements
* [ZOOKEEPER-3796](https://issues.apache.org/jira/browse/ZOOKEEPER-3796) - Skip Learner Request made to ObserverMaster from going to next processor
* [ZOOKEEPER-3805](https://issues.apache.org/jira/browse/ZOOKEEPER-3805) - NIOServerCnxnFactory static block has no used code
* [ZOOKEEPER-3808](https://issues.apache.org/jira/browse/ZOOKEEPER-3808) - correct the documentation about digest.enabled
* [ZOOKEEPER-3811](https://issues.apache.org/jira/browse/ZOOKEEPER-3811) - cleaning up the code,A static field should be directly referred by its class name
* [ZOOKEEPER-3831](https://issues.apache.org/jira/browse/ZOOKEEPER-3831) - Add a test that does a minimal validation of Apache Curator
* [ZOOKEEPER-3833](https://issues.apache.org/jira/browse/ZOOKEEPER-3833) - Do Not Override Plugin Versions from Apache Parent POM
* [ZOOKEEPER-3836](https://issues.apache.org/jira/browse/ZOOKEEPER-3836) - Use Commons and JDK Functions in ClientBase
* [ZOOKEEPER-3839](https://issues.apache.org/jira/browse/ZOOKEEPER-3839) - ReconfigBackupTest Remove getFileContent
* [ZOOKEEPER-3883](https://issues.apache.org/jira/browse/ZOOKEEPER-3883) - new UncaughtExceptionHandler object with lambda
* [ZOOKEEPER-3893](https://issues.apache.org/jira/browse/ZOOKEEPER-3893) - Enhance documentation for property ssl.clientAuth
* [ZOOKEEPER-3913](https://issues.apache.org/jira/browse/ZOOKEEPER-3913) - Upgrade to Netty 4.1.50.Final
* [ZOOKEEPER-3919](https://issues.apache.org/jira/browse/ZOOKEEPER-3919) - Add ARM64 jobs to Travis-CI
* [ZOOKEEPER-3926](https://issues.apache.org/jira/browse/ZOOKEEPER-3926) - make the rc constant in the ClientCnxn
* [ZOOKEEPER-3934](https://issues.apache.org/jira/browse/ZOOKEEPER-3934) - upgrade dependency-check to version 6.0.0
* [ZOOKEEPER-3935](https://issues.apache.org/jira/browse/ZOOKEEPER-3935) - Handle float metrics in check_zookeeper
* [ZOOKEEPER-3941](https://issues.apache.org/jira/browse/ZOOKEEPER-3941) - Upgrade commons-cli to 1.4
* [ZOOKEEPER-3950](https://issues.apache.org/jira/browse/ZOOKEEPER-3950) - Add support for BCFKS key/trust store format
* [ZOOKEEPER-3952](https://issues.apache.org/jira/browse/ZOOKEEPER-3952) - Remove commons-lang from ZooKeeper
* [ZOOKEEPER-3956](https://issues.apache.org/jira/browse/ZOOKEEPER-3956) - Remove json-simple from ZooKeeper
* [ZOOKEEPER-3958](https://issues.apache.org/jira/browse/ZOOKEEPER-3958) - Update dependency versions and eliminate java docs warnings
* [ZOOKEEPER-3960](https://issues.apache.org/jira/browse/ZOOKEEPER-3960) - Update ZooKeeper client documentation about key file format parameters
* [ZOOKEEPER-3971](https://issues.apache.org/jira/browse/ZOOKEEPER-3971) - Auto close resources with try catch block
* [ZOOKEEPER-3978](https://issues.apache.org/jira/browse/ZOOKEEPER-3978) - Adding additional security metrics to zookeeper
* [ZOOKEEPER-3989](https://issues.apache.org/jira/browse/ZOOKEEPER-3989) - GenerateLoad needs to use log for protecting sensitive data
* [ZOOKEEPER-4000](https://issues.apache.org/jira/browse/ZOOKEEPER-4000) - use the computeIfAbsent to simplify the Leader#processSync method
* [ZOOKEEPER-4033](https://issues.apache.org/jira/browse/ZOOKEEPER-4033) - Remove unnecessary judgment of null
* [ZOOKEEPER-4048](https://issues.apache.org/jira/browse/ZOOKEEPER-4048) - Upgrade Mockito to 3.6.28 - allow builds on JDK16
* [ZOOKEEPER-4058](https://issues.apache.org/jira/browse/ZOOKEEPER-4058) - Update checkstyle-strict.xml by the latest version 8.39 of checkstyle
* [ZOOKEEPER-4188](https://issues.apache.org/jira/browse/ZOOKEEPER-4188) - add a doc about whoami CLI
* [ZOOKEEPER-4209](https://issues.apache.org/jira/browse/ZOOKEEPER-4209) - Update Netty version to 4.1.53.Final on 3.5 branch
* [ZOOKEEPER-4221](https://issues.apache.org/jira/browse/ZOOKEEPER-4221) - Improve the error message when message goes above jute.maxbufer size
* [ZOOKEEPER-4231](https://issues.apache.org/jira/browse/ZOOKEEPER-4231) - Add document for snapshot compression config

## Bug

* [ZOOKEEPER-1105](https://issues.apache.org/jira/browse/ZOOKEEPER-1105) - c client zookeeper_close not send CLOSE_OP request to server
* [ZOOKEEPER-1677](https://issues.apache.org/jira/browse/ZOOKEEPER-1677) - Misuse of INET_ADDRSTRLEN
* [ZOOKEEPER-1998](https://issues.apache.org/jira/browse/ZOOKEEPER-1998) - C library calls getaddrinfo unconditionally from zookeeper_interest
* [ZOOKEEPER-2164](https://issues.apache.org/jira/browse/ZOOKEEPER-2164) - fast leader election keeps failing
* [ZOOKEEPER-2307](https://issues.apache.org/jira/browse/ZOOKEEPER-2307) - ZooKeeper not starting because acceptedEpoch is less than the currentEpoch
* [ZOOKEEPER-2475](https://issues.apache.org/jira/browse/ZOOKEEPER-2475) - Include ZKClientConfig API in zoookeeper javadoc
* [ZOOKEEPER-2490](https://issues.apache.org/jira/browse/ZOOKEEPER-2490) - infinitely connect on windows
* [ZOOKEEPER-2836](https://issues.apache.org/jira/browse/ZOOKEEPER-2836) - QuorumCnxManager.Listener Thread Better handling of SocketTimeoutException
* [ZOOKEEPER-3112](https://issues.apache.org/jira/browse/ZOOKEEPER-3112) - fd leak due to UnresolvedAddressException on connect.
* [ZOOKEEPER-3215](https://issues.apache.org/jira/browse/ZOOKEEPER-3215) - Handle Java 9/11 additions of covariant return types to java.nio.ByteBuffer methods
* [ZOOKEEPER-3426](https://issues.apache.org/jira/browse/ZOOKEEPER-3426) - ZK prime_connection(the Handshake) can complete without reading all the payload.
* [ZOOKEEPER-3579](https://issues.apache.org/jira/browse/ZOOKEEPER-3579) - handle NPE gracefully when the watch parameter of zookeeper java client is null
* [ZOOKEEPER-3613](https://issues.apache.org/jira/browse/ZOOKEEPER-3613) - ZKConfig fails to return proper value on getBoolean() when user accidentally includes spaces at the end of the value
* [ZOOKEEPER-3642](https://issues.apache.org/jira/browse/ZOOKEEPER-3642) - Data inconsistency when the leader crashes right after sending SNAP sync
* [ZOOKEEPER-3644](https://issues.apache.org/jira/browse/ZOOKEEPER-3644) - Data loss after upgrading standalone ZK server 3.4.14 to 3.5.6 with snapshot.trust.empty=true
* [ZOOKEEPER-3651](https://issues.apache.org/jira/browse/ZOOKEEPER-3651) - NettyServerCnxnFactoryTest is flaky
* [ZOOKEEPER-3653](https://issues.apache.org/jira/browse/ZOOKEEPER-3653) - Audit Log feature fails in a stand alone zookeeper setup
* [ZOOKEEPER-3654](https://issues.apache.org/jira/browse/ZOOKEEPER-3654) - Incorrect *_CFLAGS handling in Automake
* [ZOOKEEPER-3656](https://issues.apache.org/jira/browse/ZOOKEEPER-3656) - SyncRequestProcessor doesn&#39;t update lastFlushTime correctly on observers
* [ZOOKEEPER-3667](https://issues.apache.org/jira/browse/ZOOKEEPER-3667) - set jute.maxbuffer hexadecimal number throw parseInt error
* [ZOOKEEPER-3698](https://issues.apache.org/jira/browse/ZOOKEEPER-3698) - NoRouteToHostException when starting large ZooKeeper cluster on localhost
* [ZOOKEEPER-3699](https://issues.apache.org/jira/browse/ZOOKEEPER-3699) - upgrade jackson-databind to address CVE-2019-20330
* [ZOOKEEPER-3701](https://issues.apache.org/jira/browse/ZOOKEEPER-3701) - Split brain on log disk full
* [ZOOKEEPER-3710](https://issues.apache.org/jira/browse/ZOOKEEPER-3710) - [trivial bug] fix compile error in PurgeTxnTest introduced by ZOOKEEPER-3231
* [ZOOKEEPER-3726](https://issues.apache.org/jira/browse/ZOOKEEPER-3726) - invalid ipv6 address comparison in C client
* [ZOOKEEPER-3737](https://issues.apache.org/jira/browse/ZOOKEEPER-3737) - Unable to eliminate log4j1 transitive dependency
* [ZOOKEEPER-3738](https://issues.apache.org/jira/browse/ZOOKEEPER-3738) - Avoid use of broken codehaus properties-maven-plugin
* [ZOOKEEPER-3739](https://issues.apache.org/jira/browse/ZOOKEEPER-3739) - Remove use of com.sun.nio.file.SensitivityWatchEventModifier
* [ZOOKEEPER-3745](https://issues.apache.org/jira/browse/ZOOKEEPER-3745) - Update copyright notices from 2019 to 2020
* [ZOOKEEPER-3748](https://issues.apache.org/jira/browse/ZOOKEEPER-3748) - Resolve release requirements in download page
* [ZOOKEEPER-3769](https://issues.apache.org/jira/browse/ZOOKEEPER-3769) - fast leader election does not end if leader is taken down
* [ZOOKEEPER-3772](https://issues.apache.org/jira/browse/ZOOKEEPER-3772) - JettyAdminServer should not allow HTTP TRACE method
* [ZOOKEEPER-3780](https://issues.apache.org/jira/browse/ZOOKEEPER-3780) - restore Version.getRevision() to be backward compatible
* [ZOOKEEPER-3781](https://issues.apache.org/jira/browse/ZOOKEEPER-3781) - Zookeeper 3.5.7 not creating snapshot
* [ZOOKEEPER-3782](https://issues.apache.org/jira/browse/ZOOKEEPER-3782) - Replace filter with list comprehension for returning list in zk-merge-pr.py
* [ZOOKEEPER-3793](https://issues.apache.org/jira/browse/ZOOKEEPER-3793) - Request throttling is broken when RequestThrottler is disabled or configured incorrectly.
* [ZOOKEEPER-3801](https://issues.apache.org/jira/browse/ZOOKEEPER-3801) - Fix Jenkins link in pom
* [ZOOKEEPER-3814](https://issues.apache.org/jira/browse/ZOOKEEPER-3814) - ZooKeeper config propagates even with disabled dynamic reconfig
* [ZOOKEEPER-3818](https://issues.apache.org/jira/browse/ZOOKEEPER-3818) - fix zkServer.sh status command to support SSL-only server
* [ZOOKEEPER-3829](https://issues.apache.org/jira/browse/ZOOKEEPER-3829) - Zookeeper refuses request after node expansion
* [ZOOKEEPER-3830](https://issues.apache.org/jira/browse/ZOOKEEPER-3830) - After add a new node, zookeeper cluster won&#39;t commit any proposal if this new node is leader
* [ZOOKEEPER-3832](https://issues.apache.org/jira/browse/ZOOKEEPER-3832) - ZKHostnameVerifier rejects valid certificates with subjectAltNames
* [ZOOKEEPER-3842](https://issues.apache.org/jira/browse/ZOOKEEPER-3842) - Rolling scale up of zookeeper cluster does not work with reconfigEnabled=false
* [ZOOKEEPER-3863](https://issues.apache.org/jira/browse/ZOOKEEPER-3863) - Do not track global sessions in ReadOnlyZooKeeperServer
* [ZOOKEEPER-3865](https://issues.apache.org/jira/browse/ZOOKEEPER-3865) - fix backward-compatibility for ZooKeeperServer constructor
* [ZOOKEEPER-3876](https://issues.apache.org/jira/browse/ZOOKEEPER-3876) - zkServer.sh status command fails when IPV6 is configured
* [ZOOKEEPER-3877](https://issues.apache.org/jira/browse/ZOOKEEPER-3877) - JMX Bean RemotePeerBean should enclose IPV6 host in square bracket same as LocalPeerBean
* [ZOOKEEPER-3878](https://issues.apache.org/jira/browse/ZOOKEEPER-3878) - Client connection fails if IPV6 is not enclosed in square brackets
* [ZOOKEEPER-3885](https://issues.apache.org/jira/browse/ZOOKEEPER-3885) - zoo_aremove_watches segfault: zk_hashtable needs locking!
* [ZOOKEEPER-3891](https://issues.apache.org/jira/browse/ZOOKEEPER-3891) - ZKCli commands give wrong error message &quot;Authentication is not valid&quot; for insufficient permissions
* [ZOOKEEPER-3895](https://issues.apache.org/jira/browse/ZOOKEEPER-3895) - Client side NullPointerException in case of empty Multi operation
* [ZOOKEEPER-3905](https://issues.apache.org/jira/browse/ZOOKEEPER-3905) - Race condition causes sessions to be created for clients even though their certificate authentication has failed
* [ZOOKEEPER-3911](https://issues.apache.org/jira/browse/ZOOKEEPER-3911) - Data inconsistency caused by DIFF sync uncommitted log
* [ZOOKEEPER-3933](https://issues.apache.org/jira/browse/ZOOKEEPER-3933) - owasp failing with json-simple-1.1.1.jar: CVE-2020-10663, CVE-2020-7712
* [ZOOKEEPER-3937](https://issues.apache.org/jira/browse/ZOOKEEPER-3937) - C client: avoid out-of-order packets during SASL negotiation
* [ZOOKEEPER-3943](https://issues.apache.org/jira/browse/ZOOKEEPER-3943) - Zookeeper Inspector throwing NullPointerExceptions and not displaying properly
* [ZOOKEEPER-3944](https://issues.apache.org/jira/browse/ZOOKEEPER-3944) - zookeeper c api sasl client memory leak
* [ZOOKEEPER-3951](https://issues.apache.org/jira/browse/ZOOKEEPER-3951) - Compile Error in Zookeeper.c without SASL
* [ZOOKEEPER-3954](https://issues.apache.org/jira/browse/ZOOKEEPER-3954) - use of uninitialized data in zookeeper-client/zookeeper-client-c/src/zookeeper.c:free_auth_completion
* [ZOOKEEPER-3955](https://issues.apache.org/jira/browse/ZOOKEEPER-3955) - added a shebang or a &#39;shell&#39; directive to lastRevision.sh
* [ZOOKEEPER-3979](https://issues.apache.org/jira/browse/ZOOKEEPER-3979) - Clients can corrupt the audit log
* [ZOOKEEPER-3983](https://issues.apache.org/jira/browse/ZOOKEEPER-3983) - C client test suite hangs forever &#39;sss&#39; is configured in /etc/nsswitch.conf
* [ZOOKEEPER-3987](https://issues.apache.org/jira/browse/ZOOKEEPER-3987) - Build failures when running surefire tests concurrently due to bind address already in use
* [ZOOKEEPER-3991](https://issues.apache.org/jira/browse/ZOOKEEPER-3991) - QuorumCnxManager Listener port bind retry does not retry DNS lookup
* [ZOOKEEPER-3992](https://issues.apache.org/jira/browse/ZOOKEEPER-3992) - addWatch api should check the null watch
* [ZOOKEEPER-3994](https://issues.apache.org/jira/browse/ZOOKEEPER-3994) - disconnect reason wrong
* [ZOOKEEPER-4045](https://issues.apache.org/jira/browse/ZOOKEEPER-4045) - CVE-2020-25649 - Upgrade jackson databind to 2.10.5.1
* [ZOOKEEPER-4050](https://issues.apache.org/jira/browse/ZOOKEEPER-4050) - Zookeeper Inspector reports &quot;List of default node viewers is empty&quot; when not specifically run from the zookeeper-contrib/zookeeper-contrib-zooinspector directory
* [ZOOKEEPER-4055](https://issues.apache.org/jira/browse/ZOOKEEPER-4055) - Dockerfile can&#39;t build Zookeeper C client library
* [ZOOKEEPER-4191](https://issues.apache.org/jira/browse/ZOOKEEPER-4191) - Missing executable bits in source release tarball
* [ZOOKEEPER-4199](https://issues.apache.org/jira/browse/ZOOKEEPER-4199) - Avoid thread leak in QuorumRequestPipelineTest
* [ZOOKEEPER-4200](https://issues.apache.org/jira/browse/ZOOKEEPER-4200) - WatcherCleanerTest often fails on macOS Catalina
* [ZOOKEEPER-4201](https://issues.apache.org/jira/browse/ZOOKEEPER-4201) - C client: SASL-related compilation issues on macOS Catalina
* [ZOOKEEPER-4205](https://issues.apache.org/jira/browse/ZOOKEEPER-4205) - Test fails when port 8080 is in use
* [ZOOKEEPER-4207](https://issues.apache.org/jira/browse/ZOOKEEPER-4207) - New CI pipeline checks out master in branch builds too
* [ZOOKEEPER-4219](https://issues.apache.org/jira/browse/ZOOKEEPER-4219) - Quota checks break setData in multi transactions
* [ZOOKEEPER-4220](https://issues.apache.org/jira/browse/ZOOKEEPER-4220) - Potential redundant connection attempts during leader election
* [ZOOKEEPER-4230](https://issues.apache.org/jira/browse/ZOOKEEPER-4230) - Use dynamic temp folder instead of static temp folder in RestMain
* [ZOOKEEPER-4232](https://issues.apache.org/jira/browse/ZOOKEEPER-4232) - InvalidSnapshotTest corrupts its own test data

## Test

* [ZOOKEEPER-3664](https://issues.apache.org/jira/browse/ZOOKEEPER-3664) - test

## Wish

* [ZOOKEEPER-3415](https://issues.apache.org/jira/browse/ZOOKEEPER-3415) - convert internal logic to use java 8 streams
* [ZOOKEEPER-3763](https://issues.apache.org/jira/browse/ZOOKEEPER-3763) - Restore ZKUtil.deleteRecursive in order to help compatibility of applications with 3.5 and 3.6

## Task

* [ZOOKEEPER-3669](https://issues.apache.org/jira/browse/ZOOKEEPER-3669) - Use switch Statement in ClientCnxn SendThread
* [ZOOKEEPER-3677](https://issues.apache.org/jira/browse/ZOOKEEPER-3677) - owasp checker failing for - CVE-2019-17571 Apache Log4j 1.2 deserialization of untrusted data in SocketServer
* [ZOOKEEPER-3695](https://issues.apache.org/jira/browse/ZOOKEEPER-3695) - Source release tarball does not match repository in 3.6.0
* [ZOOKEEPER-3696](https://issues.apache.org/jira/browse/ZOOKEEPER-3696) - Support alternative algorithms for ACL digest
* [ZOOKEEPER-3704](https://issues.apache.org/jira/browse/ZOOKEEPER-3704) - upgrade maven dependency-check to 5.3.0
* [ZOOKEEPER-3733](https://issues.apache.org/jira/browse/ZOOKEEPER-3733) - Fix issues reported in 3.6.0rc3
* [ZOOKEEPER-3734](https://issues.apache.org/jira/browse/ZOOKEEPER-3734) - upgrade jackson-databind to address CVE-2020-8840
* [ZOOKEEPER-3751](https://issues.apache.org/jira/browse/ZOOKEEPER-3751) - upgrade jackson-databind to 2.10 from 2.9
* [ZOOKEEPER-3794](https://issues.apache.org/jira/browse/ZOOKEEPER-3794) - upgrade netty to address CVE-2020-11612
* [ZOOKEEPER-3817](https://issues.apache.org/jira/browse/ZOOKEEPER-3817) - owasp failing due to CVE-2020-9488
* [ZOOKEEPER-3896](https://issues.apache.org/jira/browse/ZOOKEEPER-3896) - Migrate Jenkins jobs to ci-hadoop.apache.org
* [ZOOKEEPER-3957](https://issues.apache.org/jira/browse/ZOOKEEPER-3957) - Create Owasp check build on new Jenkins instance
* [ZOOKEEPER-3962](https://issues.apache.org/jira/browse/ZOOKEEPER-3962) - Create .asf.yaml file for ZooKeeper repo
* [ZOOKEEPER-3967](https://issues.apache.org/jira/browse/ZOOKEEPER-3967) - Jetty License Update
* [ZOOKEEPER-3973](https://issues.apache.org/jira/browse/ZOOKEEPER-3973) - Create configuration files GitHub Actions CI builds
* [ZOOKEEPER-3980](https://issues.apache.org/jira/browse/ZOOKEEPER-3980) - Fix Jenkinsfiles with new tool names
* [ZOOKEEPER-3981](https://issues.apache.org/jira/browse/ZOOKEEPER-3981) - Flaky test MultipleAddressTest::testGetValidAddressWithNotValid
* [ZOOKEEPER-4017](https://issues.apache.org/jira/browse/ZOOKEEPER-4017) - Owasp check failing - Jetty 9.4.32 - CVE-2020-27216
* [ZOOKEEPER-4023](https://issues.apache.org/jira/browse/ZOOKEEPER-4023) - dependency-check:check failing - Jetty 9.4.34.v20201102 - CVE-2020-27218
* [ZOOKEEPER-4056](https://issues.apache.org/jira/browse/ZOOKEEPER-4056) - Update copyright notices from 2020 to 2021
* [ZOOKEEPER-4233](https://issues.apache.org/jira/browse/ZOOKEEPER-4233) - dependency-check:check failing - Jetty 9.4.35.v20201120 - CVE-2020-27223

## Sub-task

* [ZOOKEEPER-837](https://issues.apache.org/jira/browse/ZOOKEEPER-837) - cyclic dependency ClientCnxn, ZooKeeper
* [ZOOKEEPER-3574](https://issues.apache.org/jira/browse/ZOOKEEPER-3574) - Close quorum socket asynchronously to avoid server shutdown stalled by long socket closing time
* [ZOOKEEPER-3575](https://issues.apache.org/jira/browse/ZOOKEEPER-3575) - Moving sending packets in Learner to a separate thread
* [ZOOKEEPER-3845](https://issues.apache.org/jira/browse/ZOOKEEPER-3845) - Add metric JVM_PAUSE_TIME
* [ZOOKEEPER-3852](https://issues.apache.org/jira/browse/ZOOKEEPER-3852) - Upgrade jUnit in ZooKeeper-Jute
* [ZOOKEEPER-3854](https://issues.apache.org/jira/browse/ZOOKEEPER-3854) - Upgrade jUnit in ZooKeeper-Recipes
* [ZOOKEEPER-3855](https://issues.apache.org/jira/browse/ZOOKEEPER-3855) - Upgrade jUnit in ZooKeeper-Metrics-providers
* [ZOOKEEPER-3856](https://issues.apache.org/jira/browse/ZOOKEEPER-3856) - Add a couple metrics to track inflight diff syncs and snap syncs
* [ZOOKEEPER-3859](https://issues.apache.org/jira/browse/ZOOKEEPER-3859) - Add a couple request processor metrics
* [ZOOKEEPER-3862](https://issues.apache.org/jira/browse/ZOOKEEPER-3862) - Re-enable deprecation check after finishing jUnit upgrade
* [ZOOKEEPER-3872](https://issues.apache.org/jira/browse/ZOOKEEPER-3872) - Upgrade jUnit in ZooKeeper-server
* [ZOOKEEPER-3953](https://issues.apache.org/jira/browse/ZOOKEEPER-3953) - Update hamcrest-library to version 2.2
