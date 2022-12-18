<!--
Copyright 2002-2021 The Apache Software Foundation

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

# Release Notes - ZooKeeper - Version 3.6.4
                
## Bug

* [ZOOKEEPER-1875](https://issues.apache.org/jira/browse/ZOOKEEPER-1875) - NullPointerException in ClientCnxn$EventThread.processEvent
* [ZOOKEEPER-3652](https://issues.apache.org/jira/browse/ZOOKEEPER-3652) - Improper synchronization in ClientCnxn
* [ZOOKEEPER-3781](https://issues.apache.org/jira/browse/ZOOKEEPER-3781) - Zookeeper 3.5.7 not creating snapshot
* [ZOOKEEPER-3988](https://issues.apache.org/jira/browse/ZOOKEEPER-3988) - org.apache.zookeeper.server.NettyServerCnxn.receiveMessage throws NullPointerException
* [ZOOKEEPER-4247](https://issues.apache.org/jira/browse/ZOOKEEPER-4247) - NPE while processing message from restarted quorum member
* [ZOOKEEPER-4275](https://issues.apache.org/jira/browse/ZOOKEEPER-4275) - Slowness in sasl login or subject.doAs() causes zk client to falsely assume that the server did not respond, closes connection and goes to unnecessary retries
* [ZOOKEEPER-4331](https://issues.apache.org/jira/browse/ZOOKEEPER-4331) - zookeeper artifact is not compatible with OSGi runtime
* [ZOOKEEPER-4345](https://issues.apache.org/jira/browse/ZOOKEEPER-4345) - Avoid NoSunchMethodException caused by shaded zookeeper jar
* [ZOOKEEPER-4360](https://issues.apache.org/jira/browse/ZOOKEEPER-4360) - Avoid NPE during metrics execution if the leader is not set on a FOLLOWER node 
* [ZOOKEEPER-4362](https://issues.apache.org/jira/browse/ZOOKEEPER-4362) - ZKDatabase.txnCount logged non transactional requests
* [ZOOKEEPER-4445](https://issues.apache.org/jira/browse/ZOOKEEPER-4445) - branch-3.6 txnLogCountTest use wrong version of Junit Assert import
* [ZOOKEEPER-4446](https://issues.apache.org/jira/browse/ZOOKEEPER-4446) - branch-3.6 txnLogCountTest use wrong version of Junit Assert import
* [ZOOKEEPER-4452](https://issues.apache.org/jira/browse/ZOOKEEPER-4452) - Log4j 1.X CVE-2022-23302/5/7 vulnerabilities
* [ZOOKEEPER-4477](https://issues.apache.org/jira/browse/ZOOKEEPER-4477) - Single Kerberos ticket renewal failure can prevent all future renewals since Java 9
* [ZOOKEEPER-4504](https://issues.apache.org/jira/browse/ZOOKEEPER-4504) - ZKUtil#deleteRecursive causing deadlock in HDFS HA functionality
* [ZOOKEEPER-4505](https://issues.apache.org/jira/browse/ZOOKEEPER-4505) - CVE-2020-36518 - Upgrade jackson databind to 2.13.2.1
* [ZOOKEEPER-4514](https://issues.apache.org/jira/browse/ZOOKEEPER-4514) - ClientCnxnSocketNetty throwing NPE
* [ZOOKEEPER-4515](https://issues.apache.org/jira/browse/ZOOKEEPER-4515) - ZK Cli quit command always logs error
* [ZOOKEEPER-4516](https://issues.apache.org/jira/browse/ZOOKEEPER-4516) - checkstyle:check  is failing
* [ZOOKEEPER-4537](https://issues.apache.org/jira/browse/ZOOKEEPER-4537) - Race between SyncThread and CommitProcessor thread
* [ZOOKEEPER-4654](https://issues.apache.org/jira/browse/ZOOKEEPER-4654) - Fix C client test compilation error in Util.cc.

                
## Improvement

* [ZOOKEEPER-4382](https://issues.apache.org/jira/browse/ZOOKEEPER-4382) - Update Maven Bundle Plugin in order to allow builds on JDK18
* [ZOOKEEPER-4455](https://issues.apache.org/jira/browse/ZOOKEEPER-4455) - Move to https://reload4j.qos.ch/ (remove log4j1)
* [ZOOKEEPER-4462](https://issues.apache.org/jira/browse/ZOOKEEPER-4462) - Upgrade Netty TCNative to 2.0.48
* [ZOOKEEPER-4468](https://issues.apache.org/jira/browse/ZOOKEEPER-4468) - Backport BCFKS key/trust store format support to branch 3.5
* [ZOOKEEPER-4529](https://issues.apache.org/jira/browse/ZOOKEEPER-4529) - Upgrade netty to 4.1.76.Final
* [ZOOKEEPER-4531](https://issues.apache.org/jira/browse/ZOOKEEPER-4531) - Revert Netty TCNative change
* [ZOOKEEPER-4551](https://issues.apache.org/jira/browse/ZOOKEEPER-4551) - Do not log spammy stacktrace when a client closes its connection
* [ZOOKEEPER-4602](https://issues.apache.org/jira/browse/ZOOKEEPER-4602) - Upgrade reload4j due to XXE vulnerability

            
## Task

* [ZOOKEEPER-4315](https://issues.apache.org/jira/browse/ZOOKEEPER-4315) - Fix NOTICE file in the source distribution
* [ZOOKEEPER-4337](https://issues.apache.org/jira/browse/ZOOKEEPER-4337) - CVE-2021-34429 in jetty 9.4.38.v20210224 in zookeeper 3.7.0
* [ZOOKEEPER-4414](https://issues.apache.org/jira/browse/ZOOKEEPER-4414) - Update Netty to 4.1.70.Final
* [ZOOKEEPER-4429](https://issues.apache.org/jira/browse/ZOOKEEPER-4429) - Update jackson-databind to 2.13.1
* [ZOOKEEPER-4454](https://issues.apache.org/jira/browse/ZOOKEEPER-4454) - Upgrade Netty to 4.1.73
* [ZOOKEEPER-4469](https://issues.apache.org/jira/browse/ZOOKEEPER-4469) - Suppress OWASP false positives related to Netty TCNative 
* [ZOOKEEPER-4478](https://issues.apache.org/jira/browse/ZOOKEEPER-4478) - Suppress OWASP false positives zookeeper-jute-3.8.0-SNAPSHOT.jar: CVE-2021-29425, CVE-2021-28164, CVE-2021-34429
* [ZOOKEEPER-4510](https://issues.apache.org/jira/browse/ZOOKEEPER-4510) - dependency-check:check failing - reload4j-1.2.19.jar: CVE-2020-9493, CVE-2022-23307
* [ZOOKEEPER-4641](https://issues.apache.org/jira/browse/ZOOKEEPER-4641) - GH CI fails with error: implicit declaration of function FIPS_mode
* [ZOOKEEPER-4644](https://issues.apache.org/jira/browse/ZOOKEEPER-4644) - Update 3rd party library versions before release 3.6.4
* [ZOOKEEPER-4645](https://issues.apache.org/jira/browse/ZOOKEEPER-4645) - Backport ZOOKEEPER-3941 (commons-cli upgrade) to branch-3.6
* [ZOOKEEPER-4649](https://issues.apache.org/jira/browse/ZOOKEEPER-4649) - Upgrade netty to 4.1.86 because of CVE-2022-41915
* [ZOOKEEPER-4651](https://issues.apache.org/jira/browse/ZOOKEEPER-4651) - Fix checkstyle problems on branch-3.6

