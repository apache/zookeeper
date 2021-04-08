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



# Release Notes - ZooKeeper - Version 3.6.3
    
## Bug

* [ZOOKEEPER-2307](https://issues.apache.org/jira/browse/ZOOKEEPER-2307) - ZooKeeper not starting because acceptedEpoch is less than the currentEpoch
* [ZOOKEEPER-3128](https://issues.apache.org/jira/browse/ZOOKEEPER-3128) - Get CLI Command displays Authentication error for Authorization error
* [ZOOKEEPER-3877](https://issues.apache.org/jira/browse/ZOOKEEPER-3877) - JMX Bean RemotePeerBean should enclose IPV6 host in square bracket same as LocalPeerBean
* [ZOOKEEPER-3887](https://issues.apache.org/jira/browse/ZOOKEEPER-3887) - In SSL-only server zkServer.sh status command should use secureClientPortAddress instead of clientPortAddress
* [ZOOKEEPER-3911](https://issues.apache.org/jira/browse/ZOOKEEPER-3911) - Data inconsistency caused by DIFF sync uncommitted log
* [ZOOKEEPER-3931](https://issues.apache.org/jira/browse/ZOOKEEPER-3931) - "zkServer.sh version" returns a trailing dash
* [ZOOKEEPER-3954](https://issues.apache.org/jira/browse/ZOOKEEPER-3954) - use of uninitialized data in zookeeper-client/zookeeper-client-c/src/zookeeper.c:free_auth_completion
* [ZOOKEEPER-3955](https://issues.apache.org/jira/browse/ZOOKEEPER-3955) - added a shebang or a 'shell' directive to lastRevision.sh
* [ZOOKEEPER-3983](https://issues.apache.org/jira/browse/ZOOKEEPER-3983) - C client test suite hangs forever 'sss' is configured in /etc/nsswitch.conf
* [ZOOKEEPER-3991](https://issues.apache.org/jira/browse/ZOOKEEPER-3991) - QuorumCnxManager Listener port bind retry does not retry DNS lookup
* [ZOOKEEPER-3992](https://issues.apache.org/jira/browse/ZOOKEEPER-3992) - addWatch api should check the null watch
* [ZOOKEEPER-4011](https://issues.apache.org/jira/browse/ZOOKEEPER-4011) - Maven build fails on branch-3.6 because of jUnit 5 usage in DIFFSyncConsistencyTest
* [ZOOKEEPER-4045](https://issues.apache.org/jira/browse/ZOOKEEPER-4045) - CVE-2020-25649 - Upgrade jackson databind to 2.10.5.1
* [ZOOKEEPER-4055](https://issues.apache.org/jira/browse/ZOOKEEPER-4055) - Dockerfile can't build Zookeeper C client library
* [ZOOKEEPER-4194](https://issues.apache.org/jira/browse/ZOOKEEPER-4194) - ZooInspector throws NullPointerExceptions to console when node data is null
* [ZOOKEEPER-4205](https://issues.apache.org/jira/browse/ZOOKEEPER-4205) - Test fails when port 8080 is in use
* [ZOOKEEPER-4207](https://issues.apache.org/jira/browse/ZOOKEEPER-4207) - New CI pipeline checks out master in branch builds too
* [ZOOKEEPER-4220](https://issues.apache.org/jira/browse/ZOOKEEPER-4220) - Potential redundant connection attempts during leader election
* [ZOOKEEPER-4222](https://issues.apache.org/jira/browse/ZOOKEEPER-4222) - Backport ZOOKEEPER-2307 to branch-3.6
* [ZOOKEEPER-4223](https://issues.apache.org/jira/browse/ZOOKEEPER-4223) - Backport ZOOKEEPER-3706 to branch-3.6
* [ZOOKEEPER-4224](https://issues.apache.org/jira/browse/ZOOKEEPER-4224) - Backport ZOOKEEPER-3891 to branch-3.6
* [ZOOKEEPER-4225](https://issues.apache.org/jira/browse/ZOOKEEPER-4225) - Backport ZOOKEEPER-3642 to branch-3.6
* [ZOOKEEPER-4227](https://issues.apache.org/jira/browse/ZOOKEEPER-4227) - X509AuthFailureTest is failing consistently
* [ZOOKEEPER-4230](https://issues.apache.org/jira/browse/ZOOKEEPER-4230) - Use dynamic temp folder instead of static temp folder in RestMain
* [ZOOKEEPER-4232](https://issues.apache.org/jira/browse/ZOOKEEPER-4232) - InvalidSnapshotTest corrupts its own test data
* [ZOOKEEPER-4260](https://issues.apache.org/jira/browse/ZOOKEEPER-4260) - Backport ZOOKEEPER-3575 to branch-3.6
* [ZOOKEEPER-4267](https://issues.apache.org/jira/browse/ZOOKEEPER-4267) - Fix check-style issues
* [ZOOKEEPER-4269](https://issues.apache.org/jira/browse/ZOOKEEPER-4269) - acceptedEpoch.tmp rename failure will cause server startup error
* [ZOOKEEPER-4272](https://issues.apache.org/jira/browse/ZOOKEEPER-4272) - Upgrade Netty library to > 4.1.60 due to security vulnerability CVE-2021-21295
* [ZOOKEEPER-4277](https://issues.apache.org/jira/browse/ZOOKEEPER-4277) - dependency-check:check failing - jetty-server-9.4.38 CVE-2021-28165
* [ZOOKEEPER-4278](https://issues.apache.org/jira/browse/ZOOKEEPER-4278) - dependency-check:check failing - netty-transport-4.1.60.Final CVE-2021-21409

## Improvement

* [ZOOKEEPER-1871](https://issues.apache.org/jira/browse/ZOOKEEPER-1871) - Add an option to zkCli to wait for connection before executing commands
* [ZOOKEEPER-3671](https://issues.apache.org/jira/browse/ZOOKEEPER-3671) - Use ThreadLocalConcurrent to Replace Random and Math.random
* [ZOOKEEPER-3808](https://issues.apache.org/jira/browse/ZOOKEEPER-3808) - correct the documentation about digest.enabled
* [ZOOKEEPER-3858](https://issues.apache.org/jira/browse/ZOOKEEPER-3858) - Add metrics to track server unavailable time
* [ZOOKEEPER-3935](https://issues.apache.org/jira/browse/ZOOKEEPER-3935) - Handle float metrics in check_zookeeper
* [ZOOKEEPER-3950](https://issues.apache.org/jira/browse/ZOOKEEPER-3950) - Add support for BCFKS key/trust store format
* [ZOOKEEPER-3952](https://issues.apache.org/jira/browse/ZOOKEEPER-3952) - Remove commons-lang from ZooKeeper
* [ZOOKEEPER-3960](https://issues.apache.org/jira/browse/ZOOKEEPER-3960) - Update ZooKeeper client documentation about key file format parameters
* [ZOOKEEPER-3978](https://issues.apache.org/jira/browse/ZOOKEEPER-3978) - Adding additional security metrics to zookeeper
* [ZOOKEEPER-4209](https://issues.apache.org/jira/browse/ZOOKEEPER-4209) - Update Netty version to 4.1.53.Final on 3.5 branch
* [ZOOKEEPER-4231](https://issues.apache.org/jira/browse/ZOOKEEPER-4231) - Add document for snapshot compression config
* [ZOOKEEPER-4259](https://issues.apache.org/jira/browse/ZOOKEEPER-4259) - Allow AdminServer to force https
           
## Task

* [ZOOKEEPER-3957](https://issues.apache.org/jira/browse/ZOOKEEPER-3957) - Create Owasp check build on new Jenkins instance
* [ZOOKEEPER-3980](https://issues.apache.org/jira/browse/ZOOKEEPER-3980) - Fix Jenkinsfiles with new tool names
* [ZOOKEEPER-3981](https://issues.apache.org/jira/browse/ZOOKEEPER-3981) - Flaky test MultipleAddressTest::testGetValidAddressWithNotValid
* [ZOOKEEPER-4017](https://issues.apache.org/jira/browse/ZOOKEEPER-4017) - Owasp check failing - Jetty 9.4.32 - CVE-2020-27216
* [ZOOKEEPER-4023](https://issues.apache.org/jira/browse/ZOOKEEPER-4023) - dependency-check:check failing - Jetty 9.4.34.v20201102 - CVE-2020-27218
* [ZOOKEEPER-4056](https://issues.apache.org/jira/browse/ZOOKEEPER-4056) - Update copyright notices from 2020 to 2021
* [ZOOKEEPER-4233](https://issues.apache.org/jira/browse/ZOOKEEPER-4233) - dependency-check:check failing - Jetty 9.4.35.v20201120 - CVE-2020-2722
                                                                                                                                                
## Sub-task

* [ZOOKEEPER-4251](https://issues.apache.org/jira/browse/ZOOKEEPER-4251) - Flaky test: org.apache.zookeeper.test.WatcherTest
* [ZOOKEEPER-4270](https://issues.apache.org/jira/browse/ZOOKEEPER-4270) - Flaky test: QuorumPeerMainTest#testLeaderOutOfView




