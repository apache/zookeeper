<!--
Copyright 2002-2023 The Apache Software Foundation

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


# Release Notes - ZooKeeper - Version 3.9.4

## Breaking changes

* [ZOOKEEPER-4891](https://issues.apache.org/jira/browse/ZOOKEEPER-4891) updates `logback-classic` to `1.3.15` to solve cve issues and
`slf4j-api` to `2.0.13` to meet compatibilty requirement of logback.

This could cause slf4j to complain "No SLF4J providers were found" and output no further logs in certain conditions.
1. For library or client usage, this could happen if you specify and inherit incompatible slf4j and logback versions, say, `slf4j-api:2.0.13` from
   `org.apache.zookeeper:zookeeper` and `logback-classic:1.2.13` from customized project dependencies.
2. For application or deployment usage, this could happen if you custom and inherit incompatible slf4j and logback versions in classpath, say,
   `slf4j-api:2.0.13` from zookeeper distribution and `logback-classic:1.2.13` from customization.

This could be solved by specifying compatiable slf4j and logback versions in classpath, say, `slf4j-api:2.0.13` and `logback-classic:1.3.15`.
                
## Bug

* [ZOOKEEPER-4020](https://issues.apache.org/jira/browse/ZOOKEEPER-4020) - Memory leak in  Zookeeper C Client
* [ZOOKEEPER-4240](https://issues.apache.org/jira/browse/ZOOKEEPER-4240) - IPV6 support in ZooKeeper ACL
* [ZOOKEEPER-4604](https://issues.apache.org/jira/browse/ZOOKEEPER-4604) - Creating a COMPLETION_STRING_STAT would set acl_result completion
* [ZOOKEEPER-4699](https://issues.apache.org/jira/browse/ZOOKEEPER-4699) - zh-&gt;hostname heap-use-after-free in zookeeper_interest
* [ZOOKEEPER-4725](https://issues.apache.org/jira/browse/ZOOKEEPER-4725) - TTL node creations do not appear in audit log
* [ZOOKEEPER-4787](https://issues.apache.org/jira/browse/ZOOKEEPER-4787) - Failed to establish connection between zookeeper
* [ZOOKEEPER-4810](https://issues.apache.org/jira/browse/ZOOKEEPER-4810) - Fix data race in format_endpoint_info()
* [ZOOKEEPER-4819](https://issues.apache.org/jira/browse/ZOOKEEPER-4819) - Can&#39;t seek for writable tls server if connected to readonly server
* [ZOOKEEPER-4846](https://issues.apache.org/jira/browse/ZOOKEEPER-4846) - Failure to reload database due to missing ACL
* [ZOOKEEPER-4848](https://issues.apache.org/jira/browse/ZOOKEEPER-4848) - Possible stack overflow in setup_random
* [ZOOKEEPER-4858](https://issues.apache.org/jira/browse/ZOOKEEPER-4858) - Remove the lock contention between snapshotting and the sync operation
* [ZOOKEEPER-4872](https://issues.apache.org/jira/browse/ZOOKEEPER-4872) - SnapshotCommand should not perform fastForwardFromEdits 
* [ZOOKEEPER-4886](https://issues.apache.org/jira/browse/ZOOKEEPER-4886) - observer with small myid can&#39;t join SASL quorum
* [ZOOKEEPER-4889](https://issues.apache.org/jira/browse/ZOOKEEPER-4889) - Fallback to DIGEST-MD5 auth mech should be disabled in Fips mode
* [ZOOKEEPER-4900](https://issues.apache.org/jira/browse/ZOOKEEPER-4900) - Bump patch release of jetty to include CVE fix for CVE-2024-6763
* [ZOOKEEPER-4907](https://issues.apache.org/jira/browse/ZOOKEEPER-4907) - Shouldn&#39;t throw &quot;Len error&quot; when server closing cause confusion
* [ZOOKEEPER-4909](https://issues.apache.org/jira/browse/ZOOKEEPER-4909) - When a spurious wakeup occurs, the client’s waiting time may exceed requestTimeout.
* [ZOOKEEPER-4919](https://issues.apache.org/jira/browse/ZOOKEEPER-4919) - ResponseCache supposed to be a LRU cache
* [ZOOKEEPER-4921](https://issues.apache.org/jira/browse/ZOOKEEPER-4921) - Zookeeper Client 3.9.3 Fails to Reconnect After Network Failures
* [ZOOKEEPER-4925](https://issues.apache.org/jira/browse/ZOOKEEPER-4925) - Diff sync introduce hole in stale follower&#39;s committedLog which cause data loss in leading
* [ZOOKEEPER-4928](https://issues.apache.org/jira/browse/ZOOKEEPER-4928) - Version in zookeeper_version.h is not updated
* [ZOOKEEPER-4933](https://issues.apache.org/jira/browse/ZOOKEEPER-4933) - Connection throttle exception causing all connections to be rejected
* [ZOOKEEPER-4940](https://issues.apache.org/jira/browse/ZOOKEEPER-4940) - Enabling zookeeper.ssl.ocsp with JRE TLS provider errors out
* [ZOOKEEPER-4953](https://issues.apache.org/jira/browse/ZOOKEEPER-4953) - Fixing Typo In ZooKeeper Programmer&#39;s Guide
* [ZOOKEEPER-4960](https://issues.apache.org/jira/browse/ZOOKEEPER-4960) - Upgrade OWASP plugin to 12.1.3 due to recent parsing errors
            
## New Feature

* [ZOOKEEPER-4895](https://issues.apache.org/jira/browse/ZOOKEEPER-4895) - Introduce a helper function for C client to generate password for SASL authentication
    
## Improvement

* [ZOOKEEPER-4790](https://issues.apache.org/jira/browse/ZOOKEEPER-4790) - TLS Quorum hostname verification breaks in some scenarios
* [ZOOKEEPER-4852](https://issues.apache.org/jira/browse/ZOOKEEPER-4852) - Fix the bad "*uuuuu" mark in the ASF license
* [ZOOKEEPER-4891](https://issues.apache.org/jira/browse/ZOOKEEPER-4891) - Update logback to 1.3.15 to fix CVE-2024-12798.
* [ZOOKEEPER-4902](https://issues.apache.org/jira/browse/ZOOKEEPER-4902) - Document that read-only mode also enables isro 4lw
* [ZOOKEEPER-4906](https://issues.apache.org/jira/browse/ZOOKEEPER-4906) - Log full exception details for server JAAS config failure
* [ZOOKEEPER-4944](https://issues.apache.org/jira/browse/ZOOKEEPER-4944) - Cache zookeeper dists for end to end compatibility tests
* [ZOOKEEPER-4954](https://issues.apache.org/jira/browse/ZOOKEEPER-4954) - Use FIPS style hostname verification when no custom truststore is specified
* [ZOOKEEPER-4964](https://issues.apache.org/jira/browse/ZOOKEEPER-4964) - Check permissions individually during admin server auth
            
## Task

* [ZOOKEEPER-4897](https://issues.apache.org/jira/browse/ZOOKEEPER-4897) - Upgrade Netty to fix CVE-2025-24970 in ZooKeeper 3.9.3
* [ZOOKEEPER-4959](https://issues.apache.org/jira/browse/ZOOKEEPER-4959) - Fix license files after logback/slf4j upgrade
                                                                                                                                                        

&nbsp;


# Release Notes - ZooKeeper - Version 3.9.3
                
## Bug

* [ZOOKEEPER-2332](https://issues.apache.org/jira/browse/ZOOKEEPER-2332) - Zookeeper failed to start for empty txn log
* [ZOOKEEPER-2623](https://issues.apache.org/jira/browse/ZOOKEEPER-2623) - CheckVersion outside of Multi causes NullPointerException
* [ZOOKEEPER-4293](https://issues.apache.org/jira/browse/ZOOKEEPER-4293) - Lock Contention in ClientCnxnSocketNetty (possible deadlock)
* [ZOOKEEPER-4394](https://issues.apache.org/jira/browse/ZOOKEEPER-4394) - Learner.syncWithLeader got NullPointerException
* [ZOOKEEPER-4409](https://issues.apache.org/jira/browse/ZOOKEEPER-4409) - NullPointerException in SendAckRequestProcessor
* [ZOOKEEPER-4508](https://issues.apache.org/jira/browse/ZOOKEEPER-4508) - ZooKeeper client run to endless loop in ClientCnxn.SendThread.run if all server down
* [ZOOKEEPER-4712](https://issues.apache.org/jira/browse/ZOOKEEPER-4712) - Follower.shutdown() and Observer.shutdown() do not correctly shutdown the syncProcessor, which may lead to data inconsistency
* [ZOOKEEPER-4733](https://issues.apache.org/jira/browse/ZOOKEEPER-4733) - non-return function error and asan error in CPPUNIT TESTs
* [ZOOKEEPER-4752](https://issues.apache.org/jira/browse/ZOOKEEPER-4752) - Remove version files  in zookeeper-server/src/main from .gitignore
* [ZOOKEEPER-4804](https://issues.apache.org/jira/browse/ZOOKEEPER-4804) - Use daemon threads for Netty client
* [ZOOKEEPER-4814](https://issues.apache.org/jira/browse/ZOOKEEPER-4814) - Protocol desynchronization after Connect for (some) old clients
* [ZOOKEEPER-4839](https://issues.apache.org/jira/browse/ZOOKEEPER-4839) - When DigestMD5 is used to enable mandatory client authentication,Users that do not exist can log in
* [ZOOKEEPER-4843](https://issues.apache.org/jira/browse/ZOOKEEPER-4843) - Encountering an &#39;Unreasonable Length&#39; error when configuring jute.maxbuffer to 1GB or more
* [ZOOKEEPER-4876](https://issues.apache.org/jira/browse/ZOOKEEPER-4876) - jetty-http-9.4.53.v20231009.jar: CVE-2024-6763(3.7)

## New Feature

* [ZOOKEEPER-4747](https://issues.apache.org/jira/browse/ZOOKEEPER-4747) - Java api lacks synchronous version of sync() call
    
## Improvement

* [ZOOKEEPER-4850](https://issues.apache.org/jira/browse/ZOOKEEPER-4850) - Enhance zkCli Tool to Support Reading and Writing Binary Data
* [ZOOKEEPER-4851](https://issues.apache.org/jira/browse/ZOOKEEPER-4851) - Honor X-Forwarded-For optionally in IPAuthenticationProvider
* [ZOOKEEPER-4860](https://issues.apache.org/jira/browse/ZOOKEEPER-4860) - Disable X-Forwarded-For in IPAuthenticationProvider by default
    
## Test

* [ZOOKEEPER-4859](https://issues.apache.org/jira/browse/ZOOKEEPER-4859) - C client tests hang to be cancelled quite often
        
## Task

* [ZOOKEEPER-4820](https://issues.apache.org/jira/browse/ZOOKEEPER-4820) - zookeeper pom leaks logback dependency
* [ZOOKEEPER-4868](https://issues.apache.org/jira/browse/ZOOKEEPER-4868) - Bump commons-io library to 2.14.0


&nbsp;

                                                                                                                                                        

# Release Notes - ZooKeeper - Version 3.9.2

## Sub-task

* [ZOOKEEPER-910](https://issues.apache.org/jira/browse/ZOOKEEPER-910) - Use SelectionKey.isXYZ() methods instead of complicated binary logic
* [ZOOKEEPER-4728](https://issues.apache.org/jira/browse/ZOOKEEPER-4728) - Zookeepr cannot bind to itself forever if DNS is not ready when startup

## Bug

* [ZOOKEEPER-2590](https://issues.apache.org/jira/browse/ZOOKEEPER-2590) - exists() should check read ACL permission
* [ZOOKEEPER-4236](https://issues.apache.org/jira/browse/ZOOKEEPER-4236) - Java Client SendThread create many unnecessary Login objects
* [ZOOKEEPER-4415](https://issues.apache.org/jira/browse/ZOOKEEPER-4415) - Zookeeper 3.7.0 : The client supported protocol versions [TLSv1.3] are not accepted by server preferences
* [ZOOKEEPER-4730](https://issues.apache.org/jira/browse/ZOOKEEPER-4730) - Incorrect datadir and logdir size reported from admin and 4lw dirs command
* [ZOOKEEPER-4785](https://issues.apache.org/jira/browse/ZOOKEEPER-4785) - Txn loss due to race condition in Learner.syncWithLeader() during DIFF sync

## Improvement

* [ZOOKEEPER-3486](https://issues.apache.org/jira/browse/ZOOKEEPER-3486) - add the doc about how to configure SSL/TLS for the admin server
* [ZOOKEEPER-4756](https://issues.apache.org/jira/browse/ZOOKEEPER-4756) - Merge script should use GitHub api to merge pull requests
* [ZOOKEEPER-4778](https://issues.apache.org/jira/browse/ZOOKEEPER-4778) - Patch jetty, netty, and logback to remove high severity vulnerabilities
* [ZOOKEEPER-4794](https://issues.apache.org/jira/browse/ZOOKEEPER-4794) - Reduce the ZKDatabase#committedLog memory usage
* [ZOOKEEPER-4801](https://issues.apache.org/jira/browse/ZOOKEEPER-4801) - Add memory size limitation policy for ZkDataBase#committedLog
* [ZOOKEEPER-4799](https://issues.apache.org/jira/browse/ZOOKEEPER-4799) - Refactor ACL check in addWatch command

## Wish

* [ZOOKEEPER-4807](https://issues.apache.org/jira/browse/ZOOKEEPER-4807) - Add sid for the leader goodbyte log


&nbsp;



# Release Notes - ZooKeeper - Version 3.9.1

## Improvement

* [ZOOKEEPER-4732](https://issues.apache.org/jira/browse/ZOOKEEPER-4732) - improve Reproducible Builds
* [ZOOKEEPER-4753](https://issues.apache.org/jira/browse/ZOOKEEPER-4753) - Explicit handling of DIGEST-MD5 vs GSSAPI in quorum auth

## Task

* [ZOOKEEPER-4751](https://issues.apache.org/jira/browse/ZOOKEEPER-4751) - Update snappy-java to 1.1.10.5 to address CVE-2023-43642
* [ZOOKEEPER-4754](https://issues.apache.org/jira/browse/ZOOKEEPER-4754) - Update Jetty to avoid CVE-2023-36479, CVE-2023-40167, and CVE-2023-41900
* [ZOOKEEPER-4755](https://issues.apache.org/jira/browse/ZOOKEEPER-4755) - Handle Netty CVE-2023-4586


&nbsp;



# Release Notes - ZooKeeper - Version 3.9.0
    
## Sub-task

* [ZOOKEEPER-4327](https://issues.apache.org/jira/browse/ZOOKEEPER-4327) - Flaky test: RequestThrottlerTest
            
## Bug

* [ZOOKEEPER-2108](https://issues.apache.org/jira/browse/ZOOKEEPER-2108) - Compilation error in ZkAdaptor.cc with GCC 4.7 or later
* [ZOOKEEPER-3652](https://issues.apache.org/jira/browse/ZOOKEEPER-3652) - Improper synchronization in ClientCnxn
* [ZOOKEEPER-3908](https://issues.apache.org/jira/browse/ZOOKEEPER-3908) - zktreeutil multiple issues
* [ZOOKEEPER-3996](https://issues.apache.org/jira/browse/ZOOKEEPER-3996) - Flaky test: ReadOnlyModeTest.testConnectionEvents
* [ZOOKEEPER-4026](https://issues.apache.org/jira/browse/ZOOKEEPER-4026) - CREATE2 requests embeded in a MULTI request only get a regular CREATE response
* [ZOOKEEPER-4296](https://issues.apache.org/jira/browse/ZOOKEEPER-4296) - NullPointerException when ClientCnxnSocketNetty is closed without being opened
* [ZOOKEEPER-4308](https://issues.apache.org/jira/browse/ZOOKEEPER-4308) - Flaky test: EagerACLFilterTest.testSetDataFail
* [ZOOKEEPER-4393](https://issues.apache.org/jira/browse/ZOOKEEPER-4393) - Problem to connect to zookeeper in FIPS mode
* [ZOOKEEPER-4466](https://issues.apache.org/jira/browse/ZOOKEEPER-4466) - Support different watch modes on same path
* [ZOOKEEPER-4471](https://issues.apache.org/jira/browse/ZOOKEEPER-4471) - Remove WatcherType.Children break persistent watcher&#39;s child events
* [ZOOKEEPER-4473](https://issues.apache.org/jira/browse/ZOOKEEPER-4473) - zooInspector create root node fail with path validate
* [ZOOKEEPER-4475](https://issues.apache.org/jira/browse/ZOOKEEPER-4475) - Persistent recursive watcher got NodeChildrenChanged event
* [ZOOKEEPER-4477](https://issues.apache.org/jira/browse/ZOOKEEPER-4477) - Single Kerberos ticket renewal failure can prevent all future renewals since Java 9
* [ZOOKEEPER-4504](https://issues.apache.org/jira/browse/ZOOKEEPER-4504) - ZKUtil#deleteRecursive causing deadlock in HDFS HA functionality
* [ZOOKEEPER-4505](https://issues.apache.org/jira/browse/ZOOKEEPER-4505) - CVE-2020-36518 - Upgrade jackson databind to 2.13.2.1
* [ZOOKEEPER-4511](https://issues.apache.org/jira/browse/ZOOKEEPER-4511) - Flaky test: FileTxnSnapLogMetricsTest.testFileTxnSnapLogMetrics
* [ZOOKEEPER-4514](https://issues.apache.org/jira/browse/ZOOKEEPER-4514) - ClientCnxnSocketNetty throwing NPE
* [ZOOKEEPER-4515](https://issues.apache.org/jira/browse/ZOOKEEPER-4515) - ZK Cli quit command always logs error
* [ZOOKEEPER-4537](https://issues.apache.org/jira/browse/ZOOKEEPER-4537) - Race between SyncThread and CommitProcessor thread
* [ZOOKEEPER-4549](https://issues.apache.org/jira/browse/ZOOKEEPER-4549) - ProviderRegistry may be repeatedly initialized
* [ZOOKEEPER-4565](https://issues.apache.org/jira/browse/ZOOKEEPER-4565) - Config watch path get truncated abnormally and fail chroot zookeeper client
* [ZOOKEEPER-4647](https://issues.apache.org/jira/browse/ZOOKEEPER-4647) - Tests don&#39;t pass on JDK20 because we try to mock InetAddress
* [ZOOKEEPER-4654](https://issues.apache.org/jira/browse/ZOOKEEPER-4654) - Fix C client test compilation error in Util.cc.
* [ZOOKEEPER-4674](https://issues.apache.org/jira/browse/ZOOKEEPER-4674) - C client tests don&#39;t pass on CI
* [ZOOKEEPER-4719](https://issues.apache.org/jira/browse/ZOOKEEPER-4719) - Use bouncycastle jdk18on instead of jdk15on
* [ZOOKEEPER-4721](https://issues.apache.org/jira/browse/ZOOKEEPER-4721) - Upgrade OWASP Dependency Check to 8.3.1
        
## New Feature

* [ZOOKEEPER-4570](https://issues.apache.org/jira/browse/ZOOKEEPER-4570) - Admin server API for taking snapshot and stream out the data
* [ZOOKEEPER-4655](https://issues.apache.org/jira/browse/ZOOKEEPER-4655) - Communicate the Zxid that triggered a WatchEvent to fire
        
## Improvement

* [ZOOKEEPER-3731](https://issues.apache.org/jira/browse/ZOOKEEPER-3731) - Disable HTTP TRACE Method
* [ZOOKEEPER-3806](https://issues.apache.org/jira/browse/ZOOKEEPER-3806) - TLS - dynamic loading for client trust/key store
* [ZOOKEEPER-3860](https://issues.apache.org/jira/browse/ZOOKEEPER-3860) - Avoid reverse DNS lookup for hostname verification when hostnames are provided in the connection url
* [ZOOKEEPER-4289](https://issues.apache.org/jira/browse/ZOOKEEPER-4289) - Reduce the performance impact of Prometheus metrics
* [ZOOKEEPER-4303](https://issues.apache.org/jira/browse/ZOOKEEPER-4303) - ZooKeeperServerEmbedded could auto-assign and expose ports
* [ZOOKEEPER-4464](https://issues.apache.org/jira/browse/ZOOKEEPER-4464) - zooinspector display &quot;Ephemeral Owner&quot; in hex for easy match to jmx session
* [ZOOKEEPER-4467](https://issues.apache.org/jira/browse/ZOOKEEPER-4467) - Missing op code (addWatch) in Request.op2String
* [ZOOKEEPER-4472](https://issues.apache.org/jira/browse/ZOOKEEPER-4472) - Support persistent watchers removing individually
* [ZOOKEEPER-4474](https://issues.apache.org/jira/browse/ZOOKEEPER-4474) - ZooDefs.opNames is unused
* [ZOOKEEPER-4490](https://issues.apache.org/jira/browse/ZOOKEEPER-4490) - Publish Clover results to SonarQube
* [ZOOKEEPER-4491](https://issues.apache.org/jira/browse/ZOOKEEPER-4491) - Adding SSL support to Zktreeutil 
* [ZOOKEEPER-4492](https://issues.apache.org/jira/browse/ZOOKEEPER-4492) - Merge readOnly field into ConnectRequest and Response
* [ZOOKEEPER-4494](https://issues.apache.org/jira/browse/ZOOKEEPER-4494) - Fix error message format
* [ZOOKEEPER-4518](https://issues.apache.org/jira/browse/ZOOKEEPER-4518) - remove useless log in the PrepRequestProcessor#pRequest method
* [ZOOKEEPER-4519](https://issues.apache.org/jira/browse/ZOOKEEPER-4519) - Testable interface should have a testableCloseSocket() method
* [ZOOKEEPER-4529](https://issues.apache.org/jira/browse/ZOOKEEPER-4529) - Upgrade netty to 4.1.76.Final
* [ZOOKEEPER-4531](https://issues.apache.org/jira/browse/ZOOKEEPER-4531) - Revert Netty TCNative change
* [ZOOKEEPER-4551](https://issues.apache.org/jira/browse/ZOOKEEPER-4551) - Do not log spammy stacktrace when a client closes its connection
* [ZOOKEEPER-4566](https://issues.apache.org/jira/browse/ZOOKEEPER-4566) - Create tool for recursive snapshot analysis
* [ZOOKEEPER-4573](https://issues.apache.org/jira/browse/ZOOKEEPER-4573) - Encapsulate request bytebuffer in Request
* [ZOOKEEPER-4575](https://issues.apache.org/jira/browse/ZOOKEEPER-4575) - ZooKeeperServer#processPacket take record instead of bytes
* [ZOOKEEPER-4616](https://issues.apache.org/jira/browse/ZOOKEEPER-4616) - Upgrade docker image for the dev enviroment to resolve CVEs
* [ZOOKEEPER-4622](https://issues.apache.org/jira/browse/ZOOKEEPER-4622) - Add Netty-TcNative OpenSSL Support
* [ZOOKEEPER-4636](https://issues.apache.org/jira/browse/ZOOKEEPER-4636) - Fix zkServer.sh for AIX
* [ZOOKEEPER-4657](https://issues.apache.org/jira/browse/ZOOKEEPER-4657) - Publish SBOM artifacts
* [ZOOKEEPER-4659](https://issues.apache.org/jira/browse/ZOOKEEPER-4659) - Upgrade Commons CLI to 1.5.0 due to OWASP failing on 1.4 CVE-2021-37533
* [ZOOKEEPER-4660](https://issues.apache.org/jira/browse/ZOOKEEPER-4660) - Suppress false positive OWASP failure for CVE-2021-37533
* [ZOOKEEPER-4661](https://issues.apache.org/jira/browse/ZOOKEEPER-4661) - Upgrade Jackson Databind to 2.13.4.2 for CVE-2022-42003 CVE-2022-42004
* [ZOOKEEPER-4705](https://issues.apache.org/jira/browse/ZOOKEEPER-4705) - Restrict GitHub merge button to allow squash commit only
* [ZOOKEEPER-4717](https://issues.apache.org/jira/browse/ZOOKEEPER-4717) - Cache serialize data in the request to avoid repeat serialize.
* [ZOOKEEPER-4718](https://issues.apache.org/jira/browse/ZOOKEEPER-4718) - Removing unnecessary heap memory allocation in serialization can help reduce GC pressure.
    
## Test

* [ZOOKEEPER-4630](https://issues.apache.org/jira/browse/ZOOKEEPER-4630) - Fix the NPE from ConnectionMetricsTest.testRevalidateCount
* [ZOOKEEPER-4676](https://issues.apache.org/jira/browse/ZOOKEEPER-4676) - ReadOnlyModeTest doesn&#39;t not compile on JDK20 (Thread.suspend has been removed)
    
## Wish

* [ZOOKEEPER-3615](https://issues.apache.org/jira/browse/ZOOKEEPER-3615) - write a TLA+ specification to verify Zab protocol
* [ZOOKEEPER-4710](https://issues.apache.org/jira/browse/ZOOKEEPER-4710) - Fix ZkUtil deleteInBatch() by releasing semaphore after set flag
* [ZOOKEEPER-4714](https://issues.apache.org/jira/browse/ZOOKEEPER-4714) - Improve syncRequestProcessor performance
* [ZOOKEEPER-4715](https://issues.apache.org/jira/browse/ZOOKEEPER-4715) - Verify file size and position in testGetCurrentLogSize.
    
## Task

* [ZOOKEEPER-4479](https://issues.apache.org/jira/browse/ZOOKEEPER-4479) - Tests: C client test TestOperations.cc testTimeoutCausedByWatches1 is very flaky on CI
* [ZOOKEEPER-4482](https://issues.apache.org/jira/browse/ZOOKEEPER-4482) - Fix LICENSE FILES for commons-io and commons-cli
* [ZOOKEEPER-4599](https://issues.apache.org/jira/browse/ZOOKEEPER-4599) - Upgrade Jetty to avoid CVE-2022-2048
* [ZOOKEEPER-4641](https://issues.apache.org/jira/browse/ZOOKEEPER-4641) - GH CI fails with error: implicit declaration of function FIPS_mode
* [ZOOKEEPER-4642](https://issues.apache.org/jira/browse/ZOOKEEPER-4642) - Remove Travis CI
* [ZOOKEEPER-4649](https://issues.apache.org/jira/browse/ZOOKEEPER-4649) - Upgrade netty to 4.1.86 because of CVE-2022-41915
* [ZOOKEEPER-4669](https://issues.apache.org/jira/browse/ZOOKEEPER-4669) - Upgrade snappy-java to 1.1.9.1 (in order to support M1 macs)
* [ZOOKEEPER-4688](https://issues.apache.org/jira/browse/ZOOKEEPER-4688) - Upgrade `cyclonedx-maven-plugin` to 2.7.6
* [ZOOKEEPER-4700](https://issues.apache.org/jira/browse/ZOOKEEPER-4700) - Update Jetty for fixing CVE-2023-26048 and CVE-2023-26049
* [ZOOKEEPER-4707](https://issues.apache.org/jira/browse/ZOOKEEPER-4707) - Update snappy-java to address multiple CVEs
* [ZOOKEEPER-4709](https://issues.apache.org/jira/browse/ZOOKEEPER-4709) - Upgrade Netty to 4.1.94.Final
* [ZOOKEEPER-4716](https://issues.apache.org/jira/browse/ZOOKEEPER-4716) - Upgrade jackson to 2.15.2, suppress two false positive CVE errors


&nbsp;


