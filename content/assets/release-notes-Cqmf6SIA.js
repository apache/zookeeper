import{j as e}from"./chunk-EPOLDU6W-BACfhBcx.js";let a=`# Release Notes - ZooKeeper - Version 3.9.5

## Sub-task

* [ZOOKEEPER-842](https://issues.apache.org/jira/browse/ZOOKEEPER-842) - stat calls static method on org.apache.zookeeper.server.DataTree

## Bug

* [ZOOKEEPER-4736](https://issues.apache.org/jira/browse/ZOOKEEPER-4736) - socket fd leak
* [ZOOKEEPER-4871](https://issues.apache.org/jira/browse/ZOOKEEPER-4871) - ZooKeeper python module (zkpython) is incompatible with Python 3.12
* [ZOOKEEPER-4958](https://issues.apache.org/jira/browse/ZOOKEEPER-4958) - "ssl.clientHostnameVerification" is ignored if "ssl.authProvider" is configured to "x509"
* [ZOOKEEPER-4974](https://issues.apache.org/jira/browse/ZOOKEEPER-4974) - Remove enforced JDK 17 compilation warnings
* [ZOOKEEPER-4984](https://issues.apache.org/jira/browse/ZOOKEEPER-4984) - Upgrade OWASP plugin to 12.1.6 due to breaking changes in the API
* [ZOOKEEPER-4986](https://issues.apache.org/jira/browse/ZOOKEEPER-4986) - Disable reverse DNS lookup in TLS client and server
* [ZOOKEEPER-4989](https://issues.apache.org/jira/browse/ZOOKEEPER-4989) - Compilation of client on Windows with MSVC is broken

## Improvement

* [ZOOKEEPER-3938](https://issues.apache.org/jira/browse/ZOOKEEPER-3938) - Upgrade jline to version 3.x.
* [ZOOKEEPER-4955](https://issues.apache.org/jira/browse/ZOOKEEPER-4955) - Fix interference with jvm ssl properties for ssl.crl and ssl.ocsp
* [ZOOKEEPER-4962](https://issues.apache.org/jira/browse/ZOOKEEPER-4962) - Add getPort and getSecurePort for ZooKeeperServerEmbedded
* [ZOOKEEPER-4965](https://issues.apache.org/jira/browse/ZOOKEEPER-4965) - Drop unnecessary \`@SuppressWarnings("deprecation")\`
* [ZOOKEEPER-4970](https://issues.apache.org/jira/browse/ZOOKEEPER-4970) - Deprecate methods of ZKConfig which throw QuorumPeerConfig.ConfigException

## Test

* [ZOOKEEPER-4780](https://issues.apache.org/jira/browse/ZOOKEEPER-4780) - Avoid creating temporary files in source directory.

## Task

* [ZOOKEEPER-4976](https://issues.apache.org/jira/browse/ZOOKEEPER-4976) - Update Netty to fix CVE-2025-58057
* [ZOOKEEPER-5017](https://issues.apache.org/jira/browse/ZOOKEEPER-5017) - Upgrade Netty to 4.1.130.Final to address CVE-2025-67735
* [ZOOKEEPER-5018](https://issues.apache.org/jira/browse/ZOOKEEPER-5018) - Upgrade Jetty to 9.4.58.v20250814 in order to fix CVE-2025-5115

***

# Release Notes - ZooKeeper - Version 3.9.4

## Breaking Changes

[ZOOKEEPER-4891](https://issues.apache.org/jira/browse/ZOOKEEPER-4891) updates \`logback-classic\` to \`1.3.15\` to solve CVE issues and \`slf4j-api\` to \`2.0.13\` to meet the compatibility requirement of logback.

This could cause slf4j to complain "No SLF4J providers were found" and output no further logs in certain conditions:

1. For library or client usage, this could happen if you specify and inherit incompatible slf4j and logback versions, say, \`slf4j-api:2.0.13\` from \`org.apache.zookeeper:zookeeper\` and \`logback-classic:1.2.13\` from customized project dependencies.
2. For application or deployment usage, this could happen if you custom and inherit incompatible slf4j and logback versions in classpath, say, \`slf4j-api:2.0.13\` from zookeeper distribution and \`logback-classic:1.2.13\` from customization.

This can be resolved by specifying compatible slf4j and logback versions in classpath, say, \`slf4j-api:2.0.13\` and \`logback-classic:1.3.15\`.

## Bug

* [ZOOKEEPER-4020](https://issues.apache.org/jira/browse/ZOOKEEPER-4020) - Memory leak in Zookeeper C Client
* [ZOOKEEPER-4240](https://issues.apache.org/jira/browse/ZOOKEEPER-4240) - IPV6 support in ZooKeeper ACL
* [ZOOKEEPER-4604](https://issues.apache.org/jira/browse/ZOOKEEPER-4604) - Creating a COMPLETION\\_STRING\\_STAT would set acl\\_result completion
* [ZOOKEEPER-4699](https://issues.apache.org/jira/browse/ZOOKEEPER-4699) - zh->hostname heap-use-after-free in zookeeper\\_interest
* [ZOOKEEPER-4725](https://issues.apache.org/jira/browse/ZOOKEEPER-4725) - TTL node creations do not appear in audit log
* [ZOOKEEPER-4787](https://issues.apache.org/jira/browse/ZOOKEEPER-4787) - Failed to establish connection between zookeeper
* [ZOOKEEPER-4810](https://issues.apache.org/jira/browse/ZOOKEEPER-4810) - Fix data race in format\\_endpoint\\_info()
* [ZOOKEEPER-4819](https://issues.apache.org/jira/browse/ZOOKEEPER-4819) - Can't seek for writable tls server if connected to readonly server
* [ZOOKEEPER-4846](https://issues.apache.org/jira/browse/ZOOKEEPER-4846) - Failure to reload database due to missing ACL
* [ZOOKEEPER-4848](https://issues.apache.org/jira/browse/ZOOKEEPER-4848) - Possible stack overflow in setup\\_random
* [ZOOKEEPER-4858](https://issues.apache.org/jira/browse/ZOOKEEPER-4858) - Remove the lock contention between snapshotting and the sync operation
* [ZOOKEEPER-4872](https://issues.apache.org/jira/browse/ZOOKEEPER-4872) - SnapshotCommand should not perform fastForwardFromEdits
* [ZOOKEEPER-4886](https://issues.apache.org/jira/browse/ZOOKEEPER-4886) - observer with small myid can't join SASL quorum
* [ZOOKEEPER-4889](https://issues.apache.org/jira/browse/ZOOKEEPER-4889) - Fallback to DIGEST-MD5 auth mech should be disabled in Fips mode
* [ZOOKEEPER-4900](https://issues.apache.org/jira/browse/ZOOKEEPER-4900) - Bump patch release of jetty to include CVE fix for CVE-2024-6763
* [ZOOKEEPER-4907](https://issues.apache.org/jira/browse/ZOOKEEPER-4907) - Shouldn't throw "Len error" when server closing cause confusion
* [ZOOKEEPER-4909](https://issues.apache.org/jira/browse/ZOOKEEPER-4909) - When a spurious wakeup occurs, the client's waiting time may exceed requestTimeout.
* [ZOOKEEPER-4919](https://issues.apache.org/jira/browse/ZOOKEEPER-4919) - ResponseCache supposed to be a LRU cache
* [ZOOKEEPER-4921](https://issues.apache.org/jira/browse/ZOOKEEPER-4921) - Zookeeper Client 3.9.3 Fails to Reconnect After Network Failures
* [ZOOKEEPER-4925](https://issues.apache.org/jira/browse/ZOOKEEPER-4925) - Diff sync introduce hole in stale follower's committedLog which cause data loss in leading
* [ZOOKEEPER-4928](https://issues.apache.org/jira/browse/ZOOKEEPER-4928) - Version in zookeeper\\_version.h is not updated
* [ZOOKEEPER-4933](https://issues.apache.org/jira/browse/ZOOKEEPER-4933) - Connection throttle exception causing all connections to be rejected
* [ZOOKEEPER-4940](https://issues.apache.org/jira/browse/ZOOKEEPER-4940) - Enabling zookeeper.ssl.ocsp with JRE TLS provider errors out
* [ZOOKEEPER-4953](https://issues.apache.org/jira/browse/ZOOKEEPER-4953) - Fixing Typo In ZooKeeper Programmer's Guide
* [ZOOKEEPER-4960](https://issues.apache.org/jira/browse/ZOOKEEPER-4960) - Upgrade OWASP plugin to 12.1.3 due to recent parsing errors

## New Feature

* [ZOOKEEPER-4895](https://issues.apache.org/jira/browse/ZOOKEEPER-4895) - Introduce a helper function for C client to generate password for SASL authentication

## Improvement

* [ZOOKEEPER-4790](https://issues.apache.org/jira/browse/ZOOKEEPER-4790) - TLS Quorum hostname verification breaks in some scenarios
* [ZOOKEEPER-4852](https://issues.apache.org/jira/browse/ZOOKEEPER-4852) - Fix the bad "\\*uuuuu" mark in the ASF license
* [ZOOKEEPER-4891](https://issues.apache.org/jira/browse/ZOOKEEPER-4891) - Update logback to 1.3.15 to fix CVE-2024-12798.
* [ZOOKEEPER-4902](https://issues.apache.org/jira/browse/ZOOKEEPER-4902) - Document that read-only mode also enables isro 4lw
* [ZOOKEEPER-4906](https://issues.apache.org/jira/browse/ZOOKEEPER-4906) - Log full exception details for server JAAS config failure
* [ZOOKEEPER-4944](https://issues.apache.org/jira/browse/ZOOKEEPER-4944) - Cache zookeeper dists for end to end compatibility tests
* [ZOOKEEPER-4954](https://issues.apache.org/jira/browse/ZOOKEEPER-4954) - Use FIPS style hostname verification when no custom truststore is specified
* [ZOOKEEPER-4964](https://issues.apache.org/jira/browse/ZOOKEEPER-4964) - Check permissions individually during admin server auth

## Task

* [ZOOKEEPER-4897](https://issues.apache.org/jira/browse/ZOOKEEPER-4897) - Upgrade Netty to fix CVE-2025-24970 in ZooKeeper 3.9.3
* [ZOOKEEPER-4959](https://issues.apache.org/jira/browse/ZOOKEEPER-4959) - Fix license files after logback/slf4j upgrade

***

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
* [ZOOKEEPER-4752](https://issues.apache.org/jira/browse/ZOOKEEPER-4752) - Remove version files in zookeeper-server/src/main from .gitignore
* [ZOOKEEPER-4804](https://issues.apache.org/jira/browse/ZOOKEEPER-4804) - Use daemon threads for Netty client
* [ZOOKEEPER-4814](https://issues.apache.org/jira/browse/ZOOKEEPER-4814) - Protocol desynchronization after Connect for (some) old clients
* [ZOOKEEPER-4839](https://issues.apache.org/jira/browse/ZOOKEEPER-4839) - When DigestMD5 is used to enable mandatory client authentication, users that do not exist can log in
* [ZOOKEEPER-4843](https://issues.apache.org/jira/browse/ZOOKEEPER-4843) - Encountering an 'Unreasonable Length' error when configuring jute.maxbuffer to 1GB or more
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

***

# Release Notes - ZooKeeper - Version 3.9.2

## Sub-task

* [ZOOKEEPER-910](https://issues.apache.org/jira/browse/ZOOKEEPER-910) - Use SelectionKey.isXYZ() methods instead of complicated binary logic
* [ZOOKEEPER-4728](https://issues.apache.org/jira/browse/ZOOKEEPER-4728) - Zookeeper cannot bind to itself forever if DNS is not ready when startup

## Bug

* [ZOOKEEPER-2590](https://issues.apache.org/jira/browse/ZOOKEEPER-2590) - exists() should check read ACL permission
* [ZOOKEEPER-4236](https://issues.apache.org/jira/browse/ZOOKEEPER-4236) - Java Client SendThread create many unnecessary Login objects
* [ZOOKEEPER-4415](https://issues.apache.org/jira/browse/ZOOKEEPER-4415) - Zookeeper 3.7.0 : The client supported protocol versions \\[TLSv1.3] are not accepted by server preferences
* [ZOOKEEPER-4730](https://issues.apache.org/jira/browse/ZOOKEEPER-4730) - Incorrect datadir and logdir size reported from admin and 4lw dirs command
* [ZOOKEEPER-4785](https://issues.apache.org/jira/browse/ZOOKEEPER-4785) - Txn loss due to race condition in Learner.syncWithLeader() during DIFF sync

## Improvement

* [ZOOKEEPER-3486](https://issues.apache.org/jira/browse/ZOOKEEPER-3486) - add the doc about how to configure SSL/TLS for the admin server
* [ZOOKEEPER-4756](https://issues.apache.org/jira/browse/ZOOKEEPER-4756) - Merge script should use GitHub api to merge pull requests
* [ZOOKEEPER-4778](https://issues.apache.org/jira/browse/ZOOKEEPER-4778) - Patch jetty, netty, and logback to remove high severity vulnerabilities
* [ZOOKEEPER-4794](https://issues.apache.org/jira/browse/ZOOKEEPER-4794) - Reduce the ZKDatabase#committedLog memory usage
* [ZOOKEEPER-4799](https://issues.apache.org/jira/browse/ZOOKEEPER-4799) - Refactor ACL check in addWatch command
* [ZOOKEEPER-4801](https://issues.apache.org/jira/browse/ZOOKEEPER-4801) - Add memory size limitation policy for ZkDataBase#committedLog

## Wish

* [ZOOKEEPER-4807](https://issues.apache.org/jira/browse/ZOOKEEPER-4807) - Add sid for the leader goodbye log

***

# Release Notes - ZooKeeper - Version 3.9.1

## Improvement

* [ZOOKEEPER-4732](https://issues.apache.org/jira/browse/ZOOKEEPER-4732) - improve Reproducible Builds
* [ZOOKEEPER-4753](https://issues.apache.org/jira/browse/ZOOKEEPER-4753) - Explicit handling of DIGEST-MD5 vs GSSAPI in quorum auth

## Task

* [ZOOKEEPER-4751](https://issues.apache.org/jira/browse/ZOOKEEPER-4751) - Update snappy-java to 1.1.10.5 to address CVE-2023-43642
* [ZOOKEEPER-4754](https://issues.apache.org/jira/browse/ZOOKEEPER-4754) - Update Jetty to avoid CVE-2023-36479, CVE-2023-40167, and CVE-2023-41900
* [ZOOKEEPER-4755](https://issues.apache.org/jira/browse/ZOOKEEPER-4755) - Handle Netty CVE-2023-4586

***

# Release Notes - ZooKeeper - Version 3.9.0

## Sub-task

* [ZOOKEEPER-4327](https://issues.apache.org/jira/browse/ZOOKEEPER-4327) - Flaky test: RequestThrottlerTest

## Bug

* [ZOOKEEPER-2108](https://issues.apache.org/jira/browse/ZOOKEEPER-2108) - Compilation error in ZkAdaptor.cc with GCC 4.7 or later
* [ZOOKEEPER-3652](https://issues.apache.org/jira/browse/ZOOKEEPER-3652) - Improper synchronization in ClientCnxn
* [ZOOKEEPER-3908](https://issues.apache.org/jira/browse/ZOOKEEPER-3908) - zktreeutil multiple issues
* [ZOOKEEPER-3996](https://issues.apache.org/jira/browse/ZOOKEEPER-3996) - Flaky test: ReadOnlyModeTest.testConnectionEvents
* [ZOOKEEPER-4026](https://issues.apache.org/jira/browse/ZOOKEEPER-4026) - CREATE2 requests embedded in a MULTI request only get a regular CREATE response
* [ZOOKEEPER-4296](https://issues.apache.org/jira/browse/ZOOKEEPER-4296) - NullPointerException when ClientCnxnSocketNetty is closed without being opened
* [ZOOKEEPER-4308](https://issues.apache.org/jira/browse/ZOOKEEPER-4308) - Flaky test: EagerACLFilterTest.testSetDataFail
* [ZOOKEEPER-4393](https://issues.apache.org/jira/browse/ZOOKEEPER-4393) - Problem to connect to zookeeper in FIPS mode
* [ZOOKEEPER-4466](https://issues.apache.org/jira/browse/ZOOKEEPER-4466) - Support different watch modes on same path
* [ZOOKEEPER-4471](https://issues.apache.org/jira/browse/ZOOKEEPER-4471) - Remove WatcherType.Children break persistent watcher's child events
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
* [ZOOKEEPER-4647](https://issues.apache.org/jira/browse/ZOOKEEPER-4647) - Tests don't pass on JDK20 because we try to mock InetAddress
* [ZOOKEEPER-4654](https://issues.apache.org/jira/browse/ZOOKEEPER-4654) - Fix C client test compilation error in Util.cc.
* [ZOOKEEPER-4674](https://issues.apache.org/jira/browse/ZOOKEEPER-4674) - C client tests don't pass on CI
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
* [ZOOKEEPER-4464](https://issues.apache.org/jira/browse/ZOOKEEPER-4464) - zooinspector display "Ephemeral Owner" in hex for easy match to jmx session
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
* [ZOOKEEPER-4616](https://issues.apache.org/jira/browse/ZOOKEEPER-4616) - Upgrade docker image for the dev environment to resolve CVEs
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
* [ZOOKEEPER-4676](https://issues.apache.org/jira/browse/ZOOKEEPER-4676) - ReadOnlyModeTest doesn't compile on JDK20 (Thread.suspend has been removed)

## Wish

* [ZOOKEEPER-3615](https://issues.apache.org/jira/browse/ZOOKEEPER-3615) - write a TLA+ specification to verify Zab protocol
* [ZOOKEEPER-4710](https://issues.apache.org/jira/browse/ZOOKEEPER-4710) - Fix ZkUtil deleteInBatch() by releasing semaphore after set flag
* [ZOOKEEPER-4714](https://issues.apache.org/jira/browse/ZOOKEEPER-4714) - Improve syncRequestProcessor performance
* [ZOOKEEPER-4715](https://issues.apache.org/jira/browse/ZOOKEEPER-4715) - Verify file size and position in testGetCurrentLogSize.

## Task

* [ZOOKEEPER-4479](https://issues.apache.org/jira/browse/ZOOKEEPER-4479) - Tests: C client test TestOperations.cc testTimeoutCausedByWatches1 is very flaky on CI
* [ZOOKEEPER-4482](https://issues.apache.org/jira/browse/ZOOKEEPER-4482) - Fix LICENSE FILES for commons-io and commons-cli
* [ZOOKEEPER-4599](https://issues.apache.org/jira/browse/ZOOKEEPER-4599) - Upgrade Jetty to avoid CVE-2022-2048
* [ZOOKEEPER-4641](https://issues.apache.org/jira/browse/ZOOKEEPER-4641) - GH CI fails with error: implicit declaration of function FIPS\\_mode
* [ZOOKEEPER-4642](https://issues.apache.org/jira/browse/ZOOKEEPER-4642) - Remove Travis CI
* [ZOOKEEPER-4649](https://issues.apache.org/jira/browse/ZOOKEEPER-4649) - Upgrade netty to 4.1.86 because of CVE-2022-41915
* [ZOOKEEPER-4669](https://issues.apache.org/jira/browse/ZOOKEEPER-4669) - Upgrade snappy-java to 1.1.9.1 (in order to support M1 macs)
* [ZOOKEEPER-4688](https://issues.apache.org/jira/browse/ZOOKEEPER-4688) - Upgrade \`cyclonedx-maven-plugin\` to 2.7.6
* [ZOOKEEPER-4700](https://issues.apache.org/jira/browse/ZOOKEEPER-4700) - Update Jetty for fixing CVE-2023-26048 and CVE-2023-26049
* [ZOOKEEPER-4707](https://issues.apache.org/jira/browse/ZOOKEEPER-4707) - Update snappy-java to address multiple CVEs
* [ZOOKEEPER-4709](https://issues.apache.org/jira/browse/ZOOKEEPER-4709) - Upgrade Netty to 4.1.94.Final
* [ZOOKEEPER-4716](https://issues.apache.org/jira/browse/ZOOKEEPER-4716) - Upgrade jackson to 2.15.2, suppress two false positive CVE errors
`,o={title:"Release Notes",description:"Release notes for ZooKeeper 3.9.x, including new features, bug fixes, improvements, and breaking changes."},n=[{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-842"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4736"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4871"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4958"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4974"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4984"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4986"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4989"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3938"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4955"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4962"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4965"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4970"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4780"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4976"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-5017"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-5018"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4891"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4020"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4240"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4604"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4699"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4725"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4787"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4810"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4819"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4846"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4848"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4858"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4872"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4886"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4889"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4900"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4907"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4909"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4919"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4921"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4925"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4928"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4933"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4940"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4953"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4960"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4895"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4790"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4852"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4891"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4902"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4906"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4944"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4954"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4964"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4897"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4959"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-2332"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-2623"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4293"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4394"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4409"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4508"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4712"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4733"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4752"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4804"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4814"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4839"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4843"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4876"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4747"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4850"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4851"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4860"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4859"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4820"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4868"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-910"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4728"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-2590"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4236"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4415"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4730"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4785"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3486"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4756"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4778"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4794"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4799"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4801"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4807"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4732"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4753"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4751"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4754"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4755"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4327"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-2108"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3652"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3908"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3996"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4026"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4296"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4308"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4393"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4466"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4471"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4473"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4475"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4477"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4504"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4505"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4511"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4514"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4515"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4537"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4549"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4565"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4647"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4654"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4674"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4719"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4721"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4570"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4655"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3731"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3806"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3860"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4289"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4303"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4464"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4467"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4472"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4474"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4490"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4491"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4492"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4494"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4518"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4519"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4529"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4531"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4551"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4566"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4573"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4575"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4616"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4622"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4636"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4657"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4659"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4660"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4661"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4705"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4717"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4718"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4630"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4676"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3615"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4710"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4714"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4715"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4479"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4482"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4599"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4641"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4642"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4649"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4669"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4688"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4700"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4707"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4709"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4716"}],E={contents:[{heading:"sub-task",content:"ZOOKEEPER-842 - stat calls static method on org.apache.zookeeper.server.DataTree"},{heading:"bug",content:"ZOOKEEPER-4736 - socket fd leak"},{heading:"bug",content:"ZOOKEEPER-4871 - ZooKeeper python module (zkpython) is incompatible with Python 3.12"},{heading:"bug",content:'ZOOKEEPER-4958 - "ssl.clientHostnameVerification" is ignored if "ssl.authProvider" is configured to "x509"'},{heading:"bug",content:"ZOOKEEPER-4974 - Remove enforced JDK 17 compilation warnings"},{heading:"bug",content:"ZOOKEEPER-4984 - Upgrade OWASP plugin to 12.1.6 due to breaking changes in the API"},{heading:"bug",content:"ZOOKEEPER-4986 - Disable reverse DNS lookup in TLS client and server"},{heading:"bug",content:"ZOOKEEPER-4989 - Compilation of client on Windows with MSVC is broken"},{heading:"improvement",content:"ZOOKEEPER-3938 - Upgrade jline to version 3.x."},{heading:"improvement",content:"ZOOKEEPER-4955 - Fix interference with jvm ssl properties for ssl.crl and ssl.ocsp"},{heading:"improvement",content:"ZOOKEEPER-4962 - Add getPort and getSecurePort for ZooKeeperServerEmbedded"},{heading:"improvement",content:'ZOOKEEPER-4965 - Drop unnecessary @SuppressWarnings("deprecation")'},{heading:"improvement",content:"ZOOKEEPER-4970 - Deprecate methods of ZKConfig which throw QuorumPeerConfig.ConfigException"},{heading:"test",content:"ZOOKEEPER-4780 - Avoid creating temporary files in source directory."},{heading:"task",content:"ZOOKEEPER-4976 - Update Netty to fix CVE-2025-58057"},{heading:"task",content:"ZOOKEEPER-5017 - Upgrade Netty to 4.1.130.Final to address CVE-2025-67735"},{heading:"task",content:"ZOOKEEPER-5018 - Upgrade Jetty to 9.4.58.v20250814 in order to fix CVE-2025-5115"},{heading:"breaking-changes",content:"ZOOKEEPER-4891 updates logback-classic to 1.3.15 to solve CVE issues and slf4j-api to 2.0.13 to meet the compatibility requirement of logback."},{heading:"breaking-changes",content:'This could cause slf4j to complain "No SLF4J providers were found" and output no further logs in certain conditions:'},{heading:"breaking-changes",content:"For library or client usage, this could happen if you specify and inherit incompatible slf4j and logback versions, say, slf4j-api:2.0.13 from org.apache.zookeeper:zookeeper and logback-classic:1.2.13 from customized project dependencies."},{heading:"breaking-changes",content:"For application or deployment usage, this could happen if you custom and inherit incompatible slf4j and logback versions in classpath, say, slf4j-api:2.0.13 from zookeeper distribution and logback-classic:1.2.13 from customization."},{heading:"breaking-changes",content:"This can be resolved by specifying compatible slf4j and logback versions in classpath, say, slf4j-api:2.0.13 and logback-classic:1.3.15."},{heading:"bug-1",content:"ZOOKEEPER-4020 - Memory leak in Zookeeper C Client"},{heading:"bug-1",content:"ZOOKEEPER-4240 - IPV6 support in ZooKeeper ACL"},{heading:"bug-1",content:"ZOOKEEPER-4604 - Creating a COMPLETION_STRING_STAT would set acl_result completion"},{heading:"bug-1",content:"ZOOKEEPER-4699 - zh->hostname heap-use-after-free in zookeeper_interest"},{heading:"bug-1",content:"ZOOKEEPER-4725 - TTL node creations do not appear in audit log"},{heading:"bug-1",content:"ZOOKEEPER-4787 - Failed to establish connection between zookeeper"},{heading:"bug-1",content:"ZOOKEEPER-4810 - Fix data race in format_endpoint_info()"},{heading:"bug-1",content:"ZOOKEEPER-4819 - Can't seek for writable tls server if connected to readonly server"},{heading:"bug-1",content:"ZOOKEEPER-4846 - Failure to reload database due to missing ACL"},{heading:"bug-1",content:"ZOOKEEPER-4848 - Possible stack overflow in setup_random"},{heading:"bug-1",content:"ZOOKEEPER-4858 - Remove the lock contention between snapshotting and the sync operation"},{heading:"bug-1",content:"ZOOKEEPER-4872 - SnapshotCommand should not perform fastForwardFromEdits"},{heading:"bug-1",content:"ZOOKEEPER-4886 - observer with small myid can't join SASL quorum"},{heading:"bug-1",content:"ZOOKEEPER-4889 - Fallback to DIGEST-MD5 auth mech should be disabled in Fips mode"},{heading:"bug-1",content:"ZOOKEEPER-4900 - Bump patch release of jetty to include CVE fix for CVE-2024-6763"},{heading:"bug-1",content:`ZOOKEEPER-4907 - Shouldn't throw "Len error" when server closing cause confusion`},{heading:"bug-1",content:"ZOOKEEPER-4909 - When a spurious wakeup occurs, the client's waiting time may exceed requestTimeout."},{heading:"bug-1",content:"ZOOKEEPER-4919 - ResponseCache supposed to be a LRU cache"},{heading:"bug-1",content:"ZOOKEEPER-4921 - Zookeeper Client 3.9.3 Fails to Reconnect After Network Failures"},{heading:"bug-1",content:"ZOOKEEPER-4925 - Diff sync introduce hole in stale follower's committedLog which cause data loss in leading"},{heading:"bug-1",content:"ZOOKEEPER-4928 - Version in zookeeper_version.h is not updated"},{heading:"bug-1",content:"ZOOKEEPER-4933 - Connection throttle exception causing all connections to be rejected"},{heading:"bug-1",content:"ZOOKEEPER-4940 - Enabling zookeeper.ssl.ocsp with JRE TLS provider errors out"},{heading:"bug-1",content:"ZOOKEEPER-4953 - Fixing Typo In ZooKeeper Programmer's Guide"},{heading:"bug-1",content:"ZOOKEEPER-4960 - Upgrade OWASP plugin to 12.1.3 due to recent parsing errors"},{heading:"new-feature",content:"ZOOKEEPER-4895 - Introduce a helper function for C client to generate password for SASL authentication"},{heading:"improvement-1",content:"ZOOKEEPER-4790 - TLS Quorum hostname verification breaks in some scenarios"},{heading:"improvement-1",content:'ZOOKEEPER-4852 - Fix the bad "*uuuuu" mark in the ASF license'},{heading:"improvement-1",content:"ZOOKEEPER-4891 - Update logback to 1.3.15 to fix CVE-2024-12798."},{heading:"improvement-1",content:"ZOOKEEPER-4902 - Document that read-only mode also enables isro 4lw"},{heading:"improvement-1",content:"ZOOKEEPER-4906 - Log full exception details for server JAAS config failure"},{heading:"improvement-1",content:"ZOOKEEPER-4944 - Cache zookeeper dists for end to end compatibility tests"},{heading:"improvement-1",content:"ZOOKEEPER-4954 - Use FIPS style hostname verification when no custom truststore is specified"},{heading:"improvement-1",content:"ZOOKEEPER-4964 - Check permissions individually during admin server auth"},{heading:"task-1",content:"ZOOKEEPER-4897 - Upgrade Netty to fix CVE-2025-24970 in ZooKeeper 3.9.3"},{heading:"task-1",content:"ZOOKEEPER-4959 - Fix license files after logback/slf4j upgrade"},{heading:"bug-2",content:"ZOOKEEPER-2332 - Zookeeper failed to start for empty txn log"},{heading:"bug-2",content:"ZOOKEEPER-2623 - CheckVersion outside of Multi causes NullPointerException"},{heading:"bug-2",content:"ZOOKEEPER-4293 - Lock Contention in ClientCnxnSocketNetty (possible deadlock)"},{heading:"bug-2",content:"ZOOKEEPER-4394 - Learner.syncWithLeader got NullPointerException"},{heading:"bug-2",content:"ZOOKEEPER-4409 - NullPointerException in SendAckRequestProcessor"},{heading:"bug-2",content:"ZOOKEEPER-4508 - ZooKeeper client run to endless loop in ClientCnxn.SendThread.run if all server down"},{heading:"bug-2",content:"ZOOKEEPER-4712 - Follower.shutdown() and Observer.shutdown() do not correctly shutdown the syncProcessor, which may lead to data inconsistency"},{heading:"bug-2",content:"ZOOKEEPER-4733 - non-return function error and asan error in CPPUNIT TESTs"},{heading:"bug-2",content:"ZOOKEEPER-4752 - Remove version files in zookeeper-server/src/main from .gitignore"},{heading:"bug-2",content:"ZOOKEEPER-4804 - Use daemon threads for Netty client"},{heading:"bug-2",content:"ZOOKEEPER-4814 - Protocol desynchronization after Connect for (some) old clients"},{heading:"bug-2",content:"ZOOKEEPER-4839 - When DigestMD5 is used to enable mandatory client authentication, users that do not exist can log in"},{heading:"bug-2",content:"ZOOKEEPER-4843 - Encountering an 'Unreasonable Length' error when configuring jute.maxbuffer to 1GB or more"},{heading:"bug-2",content:"ZOOKEEPER-4876 - jetty-http-9.4.53.v20231009.jar: CVE-2024-6763(3.7)"},{heading:"new-feature-1",content:"ZOOKEEPER-4747 - Java api lacks synchronous version of sync() call"},{heading:"improvement-2",content:"ZOOKEEPER-4850 - Enhance zkCli Tool to Support Reading and Writing Binary Data"},{heading:"improvement-2",content:"ZOOKEEPER-4851 - Honor X-Forwarded-For optionally in IPAuthenticationProvider"},{heading:"improvement-2",content:"ZOOKEEPER-4860 - Disable X-Forwarded-For in IPAuthenticationProvider by default"},{heading:"test-1",content:"ZOOKEEPER-4859 - C client tests hang to be cancelled quite often"},{heading:"task-2",content:"ZOOKEEPER-4820 - zookeeper pom leaks logback dependency"},{heading:"task-2",content:"ZOOKEEPER-4868 - Bump commons-io library to 2.14.0"},{heading:"sub-task-1",content:"ZOOKEEPER-910 - Use SelectionKey.isXYZ() methods instead of complicated binary logic"},{heading:"sub-task-1",content:"ZOOKEEPER-4728 - Zookeeper cannot bind to itself forever if DNS is not ready when startup"},{heading:"bug-3",content:"ZOOKEEPER-2590 - exists() should check read ACL permission"},{heading:"bug-3",content:"ZOOKEEPER-4236 - Java Client SendThread create many unnecessary Login objects"},{heading:"bug-3",content:"ZOOKEEPER-4415 - Zookeeper 3.7.0 : The client supported protocol versions [TLSv1.3] are not accepted by server preferences"},{heading:"bug-3",content:"ZOOKEEPER-4730 - Incorrect datadir and logdir size reported from admin and 4lw dirs command"},{heading:"bug-3",content:"ZOOKEEPER-4785 - Txn loss due to race condition in Learner.syncWithLeader() during DIFF sync"},{heading:"improvement-3",content:"ZOOKEEPER-3486 - add the doc about how to configure SSL/TLS for the admin server"},{heading:"improvement-3",content:"ZOOKEEPER-4756 - Merge script should use GitHub api to merge pull requests"},{heading:"improvement-3",content:"ZOOKEEPER-4778 - Patch jetty, netty, and logback to remove high severity vulnerabilities"},{heading:"improvement-3",content:"ZOOKEEPER-4794 - Reduce the ZKDatabase#committedLog memory usage"},{heading:"improvement-3",content:"ZOOKEEPER-4799 - Refactor ACL check in addWatch command"},{heading:"improvement-3",content:"ZOOKEEPER-4801 - Add memory size limitation policy for ZkDataBase#committedLog"},{heading:"wish",content:"ZOOKEEPER-4807 - Add sid for the leader goodbye log"},{heading:"improvement-4",content:"ZOOKEEPER-4732 - improve Reproducible Builds"},{heading:"improvement-4",content:"ZOOKEEPER-4753 - Explicit handling of DIGEST-MD5 vs GSSAPI in quorum auth"},{heading:"task-3",content:"ZOOKEEPER-4751 - Update snappy-java to 1.1.10.5 to address CVE-2023-43642"},{heading:"task-3",content:"ZOOKEEPER-4754 - Update Jetty to avoid CVE-2023-36479, CVE-2023-40167, and CVE-2023-41900"},{heading:"task-3",content:"ZOOKEEPER-4755 - Handle Netty CVE-2023-4586"},{heading:"sub-task-2",content:"ZOOKEEPER-4327 - Flaky test: RequestThrottlerTest"},{heading:"bug-4",content:"ZOOKEEPER-2108 - Compilation error in ZkAdaptor.cc with GCC 4.7 or later"},{heading:"bug-4",content:"ZOOKEEPER-3652 - Improper synchronization in ClientCnxn"},{heading:"bug-4",content:"ZOOKEEPER-3908 - zktreeutil multiple issues"},{heading:"bug-4",content:"ZOOKEEPER-3996 - Flaky test: ReadOnlyModeTest.testConnectionEvents"},{heading:"bug-4",content:"ZOOKEEPER-4026 - CREATE2 requests embedded in a MULTI request only get a regular CREATE response"},{heading:"bug-4",content:"ZOOKEEPER-4296 - NullPointerException when ClientCnxnSocketNetty is closed without being opened"},{heading:"bug-4",content:"ZOOKEEPER-4308 - Flaky test: EagerACLFilterTest.testSetDataFail"},{heading:"bug-4",content:"ZOOKEEPER-4393 - Problem to connect to zookeeper in FIPS mode"},{heading:"bug-4",content:"ZOOKEEPER-4466 - Support different watch modes on same path"},{heading:"bug-4",content:"ZOOKEEPER-4471 - Remove WatcherType.Children break persistent watcher's child events"},{heading:"bug-4",content:"ZOOKEEPER-4473 - zooInspector create root node fail with path validate"},{heading:"bug-4",content:"ZOOKEEPER-4475 - Persistent recursive watcher got NodeChildrenChanged event"},{heading:"bug-4",content:"ZOOKEEPER-4477 - Single Kerberos ticket renewal failure can prevent all future renewals since Java 9"},{heading:"bug-4",content:"ZOOKEEPER-4504 - ZKUtil#deleteRecursive causing deadlock in HDFS HA functionality"},{heading:"bug-4",content:"ZOOKEEPER-4505 - CVE-2020-36518 - Upgrade jackson databind to 2.13.2.1"},{heading:"bug-4",content:"ZOOKEEPER-4511 - Flaky test: FileTxnSnapLogMetricsTest.testFileTxnSnapLogMetrics"},{heading:"bug-4",content:"ZOOKEEPER-4514 - ClientCnxnSocketNetty throwing NPE"},{heading:"bug-4",content:"ZOOKEEPER-4515 - ZK Cli quit command always logs error"},{heading:"bug-4",content:"ZOOKEEPER-4537 - Race between SyncThread and CommitProcessor thread"},{heading:"bug-4",content:"ZOOKEEPER-4549 - ProviderRegistry may be repeatedly initialized"},{heading:"bug-4",content:"ZOOKEEPER-4565 - Config watch path get truncated abnormally and fail chroot zookeeper client"},{heading:"bug-4",content:"ZOOKEEPER-4647 - Tests don't pass on JDK20 because we try to mock InetAddress"},{heading:"bug-4",content:"ZOOKEEPER-4654 - Fix C client test compilation error in Util.cc."},{heading:"bug-4",content:"ZOOKEEPER-4674 - C client tests don't pass on CI"},{heading:"bug-4",content:"ZOOKEEPER-4719 - Use bouncycastle jdk18on instead of jdk15on"},{heading:"bug-4",content:"ZOOKEEPER-4721 - Upgrade OWASP Dependency Check to 8.3.1"},{heading:"new-feature-2",content:"ZOOKEEPER-4570 - Admin server API for taking snapshot and stream out the data"},{heading:"new-feature-2",content:"ZOOKEEPER-4655 - Communicate the Zxid that triggered a WatchEvent to fire"},{heading:"improvement-5",content:"ZOOKEEPER-3731 - Disable HTTP TRACE Method"},{heading:"improvement-5",content:"ZOOKEEPER-3806 - TLS - dynamic loading for client trust/key store"},{heading:"improvement-5",content:"ZOOKEEPER-3860 - Avoid reverse DNS lookup for hostname verification when hostnames are provided in the connection url"},{heading:"improvement-5",content:"ZOOKEEPER-4289 - Reduce the performance impact of Prometheus metrics"},{heading:"improvement-5",content:"ZOOKEEPER-4303 - ZooKeeperServerEmbedded could auto-assign and expose ports"},{heading:"improvement-5",content:'ZOOKEEPER-4464 - zooinspector display "Ephemeral Owner" in hex for easy match to jmx session'},{heading:"improvement-5",content:"ZOOKEEPER-4467 - Missing op code (addWatch) in Request.op2String"},{heading:"improvement-5",content:"ZOOKEEPER-4472 - Support persistent watchers removing individually"},{heading:"improvement-5",content:"ZOOKEEPER-4474 - ZooDefs.opNames is unused"},{heading:"improvement-5",content:"ZOOKEEPER-4490 - Publish Clover results to SonarQube"},{heading:"improvement-5",content:"ZOOKEEPER-4491 - Adding SSL support to Zktreeutil"},{heading:"improvement-5",content:"ZOOKEEPER-4492 - Merge readOnly field into ConnectRequest and Response"},{heading:"improvement-5",content:"ZOOKEEPER-4494 - Fix error message format"},{heading:"improvement-5",content:"ZOOKEEPER-4518 - remove useless log in the PrepRequestProcessor#pRequest method"},{heading:"improvement-5",content:"ZOOKEEPER-4519 - Testable interface should have a testableCloseSocket() method"},{heading:"improvement-5",content:"ZOOKEEPER-4529 - Upgrade netty to 4.1.76.Final"},{heading:"improvement-5",content:"ZOOKEEPER-4531 - Revert Netty TCNative change"},{heading:"improvement-5",content:"ZOOKEEPER-4551 - Do not log spammy stacktrace when a client closes its connection"},{heading:"improvement-5",content:"ZOOKEEPER-4566 - Create tool for recursive snapshot analysis"},{heading:"improvement-5",content:"ZOOKEEPER-4573 - Encapsulate request bytebuffer in Request"},{heading:"improvement-5",content:"ZOOKEEPER-4575 - ZooKeeperServer#processPacket take record instead of bytes"},{heading:"improvement-5",content:"ZOOKEEPER-4616 - Upgrade docker image for the dev environment to resolve CVEs"},{heading:"improvement-5",content:"ZOOKEEPER-4622 - Add Netty-TcNative OpenSSL Support"},{heading:"improvement-5",content:"ZOOKEEPER-4636 - Fix zkServer.sh for AIX"},{heading:"improvement-5",content:"ZOOKEEPER-4657 - Publish SBOM artifacts"},{heading:"improvement-5",content:"ZOOKEEPER-4659 - Upgrade Commons CLI to 1.5.0 due to OWASP failing on 1.4 CVE-2021-37533"},{heading:"improvement-5",content:"ZOOKEEPER-4660 - Suppress false positive OWASP failure for CVE-2021-37533"},{heading:"improvement-5",content:"ZOOKEEPER-4661 - Upgrade Jackson Databind to 2.13.4.2 for CVE-2022-42003 CVE-2022-42004"},{heading:"improvement-5",content:"ZOOKEEPER-4705 - Restrict GitHub merge button to allow squash commit only"},{heading:"improvement-5",content:"ZOOKEEPER-4717 - Cache serialize data in the request to avoid repeat serialize."},{heading:"improvement-5",content:"ZOOKEEPER-4718 - Removing unnecessary heap memory allocation in serialization can help reduce GC pressure."},{heading:"test-2",content:"ZOOKEEPER-4630 - Fix the NPE from ConnectionMetricsTest.testRevalidateCount"},{heading:"test-2",content:"ZOOKEEPER-4676 - ReadOnlyModeTest doesn't compile on JDK20 (Thread.suspend has been removed)"},{heading:"wish-1",content:"ZOOKEEPER-3615 - write a TLA+ specification to verify Zab protocol"},{heading:"wish-1",content:"ZOOKEEPER-4710 - Fix ZkUtil deleteInBatch() by releasing semaphore after set flag"},{heading:"wish-1",content:"ZOOKEEPER-4714 - Improve syncRequestProcessor performance"},{heading:"wish-1",content:"ZOOKEEPER-4715 - Verify file size and position in testGetCurrentLogSize."},{heading:"task-4",content:"ZOOKEEPER-4479 - Tests: C client test TestOperations.cc testTimeoutCausedByWatches1 is very flaky on CI"},{heading:"task-4",content:"ZOOKEEPER-4482 - Fix LICENSE FILES for commons-io and commons-cli"},{heading:"task-4",content:"ZOOKEEPER-4599 - Upgrade Jetty to avoid CVE-2022-2048"},{heading:"task-4",content:"ZOOKEEPER-4641 - GH CI fails with error: implicit declaration of function FIPS_mode"},{heading:"task-4",content:"ZOOKEEPER-4642 - Remove Travis CI"},{heading:"task-4",content:"ZOOKEEPER-4649 - Upgrade netty to 4.1.86 because of CVE-2022-41915"},{heading:"task-4",content:"ZOOKEEPER-4669 - Upgrade snappy-java to 1.1.9.1 (in order to support M1 macs)"},{heading:"task-4",content:"ZOOKEEPER-4688 - Upgrade cyclonedx-maven-plugin to 2.7.6"},{heading:"task-4",content:"ZOOKEEPER-4700 - Update Jetty for fixing CVE-2023-26048 and CVE-2023-26049"},{heading:"task-4",content:"ZOOKEEPER-4707 - Update snappy-java to address multiple CVEs"},{heading:"task-4",content:"ZOOKEEPER-4709 - Upgrade Netty to 4.1.94.Final"},{heading:"task-4",content:"ZOOKEEPER-4716 - Upgrade jackson to 2.15.2, suppress two false positive CVE errors"}],headings:[{id:"release-notes---zookeeper---version-395",content:"Release Notes - ZooKeeper - Version 3.9.5"},{id:"sub-task",content:"Sub-task"},{id:"bug",content:"Bug"},{id:"improvement",content:"Improvement"},{id:"test",content:"Test"},{id:"task",content:"Task"},{id:"release-notes---zookeeper---version-394",content:"Release Notes - ZooKeeper - Version 3.9.4"},{id:"breaking-changes",content:"Breaking Changes"},{id:"bug-1",content:"Bug"},{id:"new-feature",content:"New Feature"},{id:"improvement-1",content:"Improvement"},{id:"task-1",content:"Task"},{id:"release-notes---zookeeper---version-393",content:"Release Notes - ZooKeeper - Version 3.9.3"},{id:"bug-2",content:"Bug"},{id:"new-feature-1",content:"New Feature"},{id:"improvement-2",content:"Improvement"},{id:"test-1",content:"Test"},{id:"task-2",content:"Task"},{id:"release-notes---zookeeper---version-392",content:"Release Notes - ZooKeeper - Version 3.9.2"},{id:"sub-task-1",content:"Sub-task"},{id:"bug-3",content:"Bug"},{id:"improvement-3",content:"Improvement"},{id:"wish",content:"Wish"},{id:"release-notes---zookeeper---version-391",content:"Release Notes - ZooKeeper - Version 3.9.1"},{id:"improvement-4",content:"Improvement"},{id:"task-3",content:"Task"},{id:"release-notes---zookeeper---version-390",content:"Release Notes - ZooKeeper - Version 3.9.0"},{id:"sub-task-2",content:"Sub-task"},{id:"bug-4",content:"Bug"},{id:"new-feature-2",content:"New Feature"},{id:"improvement-5",content:"Improvement"},{id:"test-2",content:"Test"},{id:"wish-1",content:"Wish"},{id:"task-4",content:"Task"}]};const h=[{depth:1,url:"#release-notes---zookeeper---version-395",title:e.jsx(e.Fragment,{children:"Release Notes - ZooKeeper - Version 3.9.5"})},{depth:2,url:"#sub-task",title:e.jsx(e.Fragment,{children:"Sub-task"})},{depth:2,url:"#bug",title:e.jsx(e.Fragment,{children:"Bug"})},{depth:2,url:"#improvement",title:e.jsx(e.Fragment,{children:"Improvement"})},{depth:2,url:"#test",title:e.jsx(e.Fragment,{children:"Test"})},{depth:2,url:"#task",title:e.jsx(e.Fragment,{children:"Task"})},{depth:1,url:"#release-notes---zookeeper---version-394",title:e.jsx(e.Fragment,{children:"Release Notes - ZooKeeper - Version 3.9.4"})},{depth:2,url:"#breaking-changes",title:e.jsx(e.Fragment,{children:"Breaking Changes"})},{depth:2,url:"#bug-1",title:e.jsx(e.Fragment,{children:"Bug"})},{depth:2,url:"#new-feature",title:e.jsx(e.Fragment,{children:"New Feature"})},{depth:2,url:"#improvement-1",title:e.jsx(e.Fragment,{children:"Improvement"})},{depth:2,url:"#task-1",title:e.jsx(e.Fragment,{children:"Task"})},{depth:1,url:"#release-notes---zookeeper---version-393",title:e.jsx(e.Fragment,{children:"Release Notes - ZooKeeper - Version 3.9.3"})},{depth:2,url:"#bug-2",title:e.jsx(e.Fragment,{children:"Bug"})},{depth:2,url:"#new-feature-1",title:e.jsx(e.Fragment,{children:"New Feature"})},{depth:2,url:"#improvement-2",title:e.jsx(e.Fragment,{children:"Improvement"})},{depth:2,url:"#test-1",title:e.jsx(e.Fragment,{children:"Test"})},{depth:2,url:"#task-2",title:e.jsx(e.Fragment,{children:"Task"})},{depth:1,url:"#release-notes---zookeeper---version-392",title:e.jsx(e.Fragment,{children:"Release Notes - ZooKeeper - Version 3.9.2"})},{depth:2,url:"#sub-task-1",title:e.jsx(e.Fragment,{children:"Sub-task"})},{depth:2,url:"#bug-3",title:e.jsx(e.Fragment,{children:"Bug"})},{depth:2,url:"#improvement-3",title:e.jsx(e.Fragment,{children:"Improvement"})},{depth:2,url:"#wish",title:e.jsx(e.Fragment,{children:"Wish"})},{depth:1,url:"#release-notes---zookeeper---version-391",title:e.jsx(e.Fragment,{children:"Release Notes - ZooKeeper - Version 3.9.1"})},{depth:2,url:"#improvement-4",title:e.jsx(e.Fragment,{children:"Improvement"})},{depth:2,url:"#task-3",title:e.jsx(e.Fragment,{children:"Task"})},{depth:1,url:"#release-notes---zookeeper---version-390",title:e.jsx(e.Fragment,{children:"Release Notes - ZooKeeper - Version 3.9.0"})},{depth:2,url:"#sub-task-2",title:e.jsx(e.Fragment,{children:"Sub-task"})},{depth:2,url:"#bug-4",title:e.jsx(e.Fragment,{children:"Bug"})},{depth:2,url:"#new-feature-2",title:e.jsx(e.Fragment,{children:"New Feature"})},{depth:2,url:"#improvement-5",title:e.jsx(e.Fragment,{children:"Improvement"})},{depth:2,url:"#test-2",title:e.jsx(e.Fragment,{children:"Test"})},{depth:2,url:"#wish-1",title:e.jsx(e.Fragment,{children:"Wish"})},{depth:2,url:"#task-4",title:e.jsx(e.Fragment,{children:"Task"})}];function t(r){const s={a:"a",code:"code",h1:"h1",h2:"h2",hr:"hr",li:"li",ol:"ol",p:"p",ul:"ul",...r.components};return e.jsxs(e.Fragment,{children:[e.jsx(s.h1,{id:"release-notes---zookeeper---version-395",children:"Release Notes - ZooKeeper - Version 3.9.5"}),`
`,e.jsx(s.h2,{id:"sub-task",children:"Sub-task"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-842",children:"ZOOKEEPER-842"})," - stat calls static method on org.apache.zookeeper.server.DataTree"]}),`
`]}),`
`,e.jsx(s.h2,{id:"bug",children:"Bug"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4736",children:"ZOOKEEPER-4736"})," - socket fd leak"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4871",children:"ZOOKEEPER-4871"})," - ZooKeeper python module (zkpython) is incompatible with Python 3.12"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4958",children:"ZOOKEEPER-4958"}),' - "ssl.clientHostnameVerification" is ignored if "ssl.authProvider" is configured to "x509"']}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4974",children:"ZOOKEEPER-4974"})," - Remove enforced JDK 17 compilation warnings"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4984",children:"ZOOKEEPER-4984"})," - Upgrade OWASP plugin to 12.1.6 due to breaking changes in the API"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4986",children:"ZOOKEEPER-4986"})," - Disable reverse DNS lookup in TLS client and server"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4989",children:"ZOOKEEPER-4989"})," - Compilation of client on Windows with MSVC is broken"]}),`
`]}),`
`,e.jsx(s.h2,{id:"improvement",children:"Improvement"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3938",children:"ZOOKEEPER-3938"})," - Upgrade jline to version 3.x."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4955",children:"ZOOKEEPER-4955"})," - Fix interference with jvm ssl properties for ssl.crl and ssl.ocsp"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4962",children:"ZOOKEEPER-4962"})," - Add getPort and getSecurePort for ZooKeeperServerEmbedded"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4965",children:"ZOOKEEPER-4965"})," - Drop unnecessary ",e.jsx(s.code,{children:'@SuppressWarnings("deprecation")'})]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4970",children:"ZOOKEEPER-4970"})," - Deprecate methods of ZKConfig which throw QuorumPeerConfig.ConfigException"]}),`
`]}),`
`,e.jsx(s.h2,{id:"test",children:"Test"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4780",children:"ZOOKEEPER-4780"})," - Avoid creating temporary files in source directory."]}),`
`]}),`
`,e.jsx(s.h2,{id:"task",children:"Task"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4976",children:"ZOOKEEPER-4976"})," - Update Netty to fix CVE-2025-58057"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-5017",children:"ZOOKEEPER-5017"})," - Upgrade Netty to 4.1.130.Final to address CVE-2025-67735"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-5018",children:"ZOOKEEPER-5018"})," - Upgrade Jetty to 9.4.58.v20250814 in order to fix CVE-2025-5115"]}),`
`]}),`
`,e.jsx(s.hr,{}),`
`,e.jsx(s.h1,{id:"release-notes---zookeeper---version-394",children:"Release Notes - ZooKeeper - Version 3.9.4"}),`
`,e.jsx(s.h2,{id:"breaking-changes",children:"Breaking Changes"}),`
`,e.jsxs(s.p,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4891",children:"ZOOKEEPER-4891"})," updates ",e.jsx(s.code,{children:"logback-classic"})," to ",e.jsx(s.code,{children:"1.3.15"})," to solve CVE issues and ",e.jsx(s.code,{children:"slf4j-api"})," to ",e.jsx(s.code,{children:"2.0.13"})," to meet the compatibility requirement of logback."]}),`
`,e.jsx(s.p,{children:'This could cause slf4j to complain "No SLF4J providers were found" and output no further logs in certain conditions:'}),`
`,e.jsxs(s.ol,{children:[`
`,e.jsxs(s.li,{children:["For library or client usage, this could happen if you specify and inherit incompatible slf4j and logback versions, say, ",e.jsx(s.code,{children:"slf4j-api:2.0.13"})," from ",e.jsx(s.code,{children:"org.apache.zookeeper:zookeeper"})," and ",e.jsx(s.code,{children:"logback-classic:1.2.13"})," from customized project dependencies."]}),`
`,e.jsxs(s.li,{children:["For application or deployment usage, this could happen if you custom and inherit incompatible slf4j and logback versions in classpath, say, ",e.jsx(s.code,{children:"slf4j-api:2.0.13"})," from zookeeper distribution and ",e.jsx(s.code,{children:"logback-classic:1.2.13"})," from customization."]}),`
`]}),`
`,e.jsxs(s.p,{children:["This can be resolved by specifying compatible slf4j and logback versions in classpath, say, ",e.jsx(s.code,{children:"slf4j-api:2.0.13"})," and ",e.jsx(s.code,{children:"logback-classic:1.3.15"}),"."]}),`
`,e.jsx(s.h2,{id:"bug-1",children:"Bug"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4020",children:"ZOOKEEPER-4020"})," - Memory leak in Zookeeper C Client"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4240",children:"ZOOKEEPER-4240"})," - IPV6 support in ZooKeeper ACL"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4604",children:"ZOOKEEPER-4604"})," - Creating a COMPLETION_STRING_STAT would set acl_result completion"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4699",children:"ZOOKEEPER-4699"})," - zh->hostname heap-use-after-free in zookeeper_interest"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4725",children:"ZOOKEEPER-4725"})," - TTL node creations do not appear in audit log"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4787",children:"ZOOKEEPER-4787"})," - Failed to establish connection between zookeeper"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4810",children:"ZOOKEEPER-4810"})," - Fix data race in format_endpoint_info()"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4819",children:"ZOOKEEPER-4819"})," - Can't seek for writable tls server if connected to readonly server"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4846",children:"ZOOKEEPER-4846"})," - Failure to reload database due to missing ACL"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4848",children:"ZOOKEEPER-4848"})," - Possible stack overflow in setup_random"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4858",children:"ZOOKEEPER-4858"})," - Remove the lock contention between snapshotting and the sync operation"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4872",children:"ZOOKEEPER-4872"})," - SnapshotCommand should not perform fastForwardFromEdits"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4886",children:"ZOOKEEPER-4886"})," - observer with small myid can't join SASL quorum"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4889",children:"ZOOKEEPER-4889"})," - Fallback to DIGEST-MD5 auth mech should be disabled in Fips mode"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4900",children:"ZOOKEEPER-4900"})," - Bump patch release of jetty to include CVE fix for CVE-2024-6763"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4907",children:"ZOOKEEPER-4907"}),` - Shouldn't throw "Len error" when server closing cause confusion`]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4909",children:"ZOOKEEPER-4909"})," - When a spurious wakeup occurs, the client's waiting time may exceed requestTimeout."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4919",children:"ZOOKEEPER-4919"})," - ResponseCache supposed to be a LRU cache"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4921",children:"ZOOKEEPER-4921"})," - Zookeeper Client 3.9.3 Fails to Reconnect After Network Failures"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4925",children:"ZOOKEEPER-4925"})," - Diff sync introduce hole in stale follower's committedLog which cause data loss in leading"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4928",children:"ZOOKEEPER-4928"})," - Version in zookeeper_version.h is not updated"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4933",children:"ZOOKEEPER-4933"})," - Connection throttle exception causing all connections to be rejected"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4940",children:"ZOOKEEPER-4940"})," - Enabling zookeeper.ssl.ocsp with JRE TLS provider errors out"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4953",children:"ZOOKEEPER-4953"})," - Fixing Typo In ZooKeeper Programmer's Guide"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4960",children:"ZOOKEEPER-4960"})," - Upgrade OWASP plugin to 12.1.3 due to recent parsing errors"]}),`
`]}),`
`,e.jsx(s.h2,{id:"new-feature",children:"New Feature"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4895",children:"ZOOKEEPER-4895"})," - Introduce a helper function for C client to generate password for SASL authentication"]}),`
`]}),`
`,e.jsx(s.h2,{id:"improvement-1",children:"Improvement"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4790",children:"ZOOKEEPER-4790"})," - TLS Quorum hostname verification breaks in some scenarios"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4852",children:"ZOOKEEPER-4852"}),' - Fix the bad "*uuuuu" mark in the ASF license']}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4891",children:"ZOOKEEPER-4891"})," - Update logback to 1.3.15 to fix CVE-2024-12798."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4902",children:"ZOOKEEPER-4902"})," - Document that read-only mode also enables isro 4lw"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4906",children:"ZOOKEEPER-4906"})," - Log full exception details for server JAAS config failure"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4944",children:"ZOOKEEPER-4944"})," - Cache zookeeper dists for end to end compatibility tests"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4954",children:"ZOOKEEPER-4954"})," - Use FIPS style hostname verification when no custom truststore is specified"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4964",children:"ZOOKEEPER-4964"})," - Check permissions individually during admin server auth"]}),`
`]}),`
`,e.jsx(s.h2,{id:"task-1",children:"Task"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4897",children:"ZOOKEEPER-4897"})," - Upgrade Netty to fix CVE-2025-24970 in ZooKeeper 3.9.3"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4959",children:"ZOOKEEPER-4959"})," - Fix license files after logback/slf4j upgrade"]}),`
`]}),`
`,e.jsx(s.hr,{}),`
`,e.jsx(s.h1,{id:"release-notes---zookeeper---version-393",children:"Release Notes - ZooKeeper - Version 3.9.3"}),`
`,e.jsx(s.h2,{id:"bug-2",children:"Bug"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-2332",children:"ZOOKEEPER-2332"})," - Zookeeper failed to start for empty txn log"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-2623",children:"ZOOKEEPER-2623"})," - CheckVersion outside of Multi causes NullPointerException"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4293",children:"ZOOKEEPER-4293"})," - Lock Contention in ClientCnxnSocketNetty (possible deadlock)"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4394",children:"ZOOKEEPER-4394"})," - Learner.syncWithLeader got NullPointerException"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4409",children:"ZOOKEEPER-4409"})," - NullPointerException in SendAckRequestProcessor"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4508",children:"ZOOKEEPER-4508"})," - ZooKeeper client run to endless loop in ClientCnxn.SendThread.run if all server down"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4712",children:"ZOOKEEPER-4712"})," - Follower.shutdown() and Observer.shutdown() do not correctly shutdown the syncProcessor, which may lead to data inconsistency"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4733",children:"ZOOKEEPER-4733"})," - non-return function error and asan error in CPPUNIT TESTs"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4752",children:"ZOOKEEPER-4752"})," - Remove version files in zookeeper-server/src/main from .gitignore"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4804",children:"ZOOKEEPER-4804"})," - Use daemon threads for Netty client"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4814",children:"ZOOKEEPER-4814"})," - Protocol desynchronization after Connect for (some) old clients"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4839",children:"ZOOKEEPER-4839"})," - When DigestMD5 is used to enable mandatory client authentication, users that do not exist can log in"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4843",children:"ZOOKEEPER-4843"})," - Encountering an 'Unreasonable Length' error when configuring jute.maxbuffer to 1GB or more"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4876",children:"ZOOKEEPER-4876"})," - jetty-http-9.4.53.v20231009.jar: CVE-2024-6763(3.7)"]}),`
`]}),`
`,e.jsx(s.h2,{id:"new-feature-1",children:"New Feature"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4747",children:"ZOOKEEPER-4747"})," - Java api lacks synchronous version of sync() call"]}),`
`]}),`
`,e.jsx(s.h2,{id:"improvement-2",children:"Improvement"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4850",children:"ZOOKEEPER-4850"})," - Enhance zkCli Tool to Support Reading and Writing Binary Data"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4851",children:"ZOOKEEPER-4851"})," - Honor X-Forwarded-For optionally in IPAuthenticationProvider"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4860",children:"ZOOKEEPER-4860"})," - Disable X-Forwarded-For in IPAuthenticationProvider by default"]}),`
`]}),`
`,e.jsx(s.h2,{id:"test-1",children:"Test"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4859",children:"ZOOKEEPER-4859"})," - C client tests hang to be cancelled quite often"]}),`
`]}),`
`,e.jsx(s.h2,{id:"task-2",children:"Task"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4820",children:"ZOOKEEPER-4820"})," - zookeeper pom leaks logback dependency"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4868",children:"ZOOKEEPER-4868"})," - Bump commons-io library to 2.14.0"]}),`
`]}),`
`,e.jsx(s.hr,{}),`
`,e.jsx(s.h1,{id:"release-notes---zookeeper---version-392",children:"Release Notes - ZooKeeper - Version 3.9.2"}),`
`,e.jsx(s.h2,{id:"sub-task-1",children:"Sub-task"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-910",children:"ZOOKEEPER-910"})," - Use SelectionKey.isXYZ() methods instead of complicated binary logic"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4728",children:"ZOOKEEPER-4728"})," - Zookeeper cannot bind to itself forever if DNS is not ready when startup"]}),`
`]}),`
`,e.jsx(s.h2,{id:"bug-3",children:"Bug"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-2590",children:"ZOOKEEPER-2590"})," - exists() should check read ACL permission"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4236",children:"ZOOKEEPER-4236"})," - Java Client SendThread create many unnecessary Login objects"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4415",children:"ZOOKEEPER-4415"})," - Zookeeper 3.7.0 : The client supported protocol versions [TLSv1.3] are not accepted by server preferences"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4730",children:"ZOOKEEPER-4730"})," - Incorrect datadir and logdir size reported from admin and 4lw dirs command"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4785",children:"ZOOKEEPER-4785"})," - Txn loss due to race condition in Learner.syncWithLeader() during DIFF sync"]}),`
`]}),`
`,e.jsx(s.h2,{id:"improvement-3",children:"Improvement"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3486",children:"ZOOKEEPER-3486"})," - add the doc about how to configure SSL/TLS for the admin server"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4756",children:"ZOOKEEPER-4756"})," - Merge script should use GitHub api to merge pull requests"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4778",children:"ZOOKEEPER-4778"})," - Patch jetty, netty, and logback to remove high severity vulnerabilities"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4794",children:"ZOOKEEPER-4794"})," - Reduce the ZKDatabase#committedLog memory usage"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4799",children:"ZOOKEEPER-4799"})," - Refactor ACL check in addWatch command"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4801",children:"ZOOKEEPER-4801"})," - Add memory size limitation policy for ZkDataBase#committedLog"]}),`
`]}),`
`,e.jsx(s.h2,{id:"wish",children:"Wish"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4807",children:"ZOOKEEPER-4807"})," - Add sid for the leader goodbye log"]}),`
`]}),`
`,e.jsx(s.hr,{}),`
`,e.jsx(s.h1,{id:"release-notes---zookeeper---version-391",children:"Release Notes - ZooKeeper - Version 3.9.1"}),`
`,e.jsx(s.h2,{id:"improvement-4",children:"Improvement"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4732",children:"ZOOKEEPER-4732"})," - improve Reproducible Builds"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4753",children:"ZOOKEEPER-4753"})," - Explicit handling of DIGEST-MD5 vs GSSAPI in quorum auth"]}),`
`]}),`
`,e.jsx(s.h2,{id:"task-3",children:"Task"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4751",children:"ZOOKEEPER-4751"})," - Update snappy-java to 1.1.10.5 to address CVE-2023-43642"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4754",children:"ZOOKEEPER-4754"})," - Update Jetty to avoid CVE-2023-36479, CVE-2023-40167, and CVE-2023-41900"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4755",children:"ZOOKEEPER-4755"})," - Handle Netty CVE-2023-4586"]}),`
`]}),`
`,e.jsx(s.hr,{}),`
`,e.jsx(s.h1,{id:"release-notes---zookeeper---version-390",children:"Release Notes - ZooKeeper - Version 3.9.0"}),`
`,e.jsx(s.h2,{id:"sub-task-2",children:"Sub-task"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4327",children:"ZOOKEEPER-4327"})," - Flaky test: RequestThrottlerTest"]}),`
`]}),`
`,e.jsx(s.h2,{id:"bug-4",children:"Bug"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-2108",children:"ZOOKEEPER-2108"})," - Compilation error in ZkAdaptor.cc with GCC 4.7 or later"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3652",children:"ZOOKEEPER-3652"})," - Improper synchronization in ClientCnxn"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3908",children:"ZOOKEEPER-3908"})," - zktreeutil multiple issues"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3996",children:"ZOOKEEPER-3996"})," - Flaky test: ReadOnlyModeTest.testConnectionEvents"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4026",children:"ZOOKEEPER-4026"})," - CREATE2 requests embedded in a MULTI request only get a regular CREATE response"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4296",children:"ZOOKEEPER-4296"})," - NullPointerException when ClientCnxnSocketNetty is closed without being opened"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4308",children:"ZOOKEEPER-4308"})," - Flaky test: EagerACLFilterTest.testSetDataFail"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4393",children:"ZOOKEEPER-4393"})," - Problem to connect to zookeeper in FIPS mode"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4466",children:"ZOOKEEPER-4466"})," - Support different watch modes on same path"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4471",children:"ZOOKEEPER-4471"})," - Remove WatcherType.Children break persistent watcher's child events"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4473",children:"ZOOKEEPER-4473"})," - zooInspector create root node fail with path validate"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4475",children:"ZOOKEEPER-4475"})," - Persistent recursive watcher got NodeChildrenChanged event"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4477",children:"ZOOKEEPER-4477"})," - Single Kerberos ticket renewal failure can prevent all future renewals since Java 9"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4504",children:"ZOOKEEPER-4504"})," - ZKUtil#deleteRecursive causing deadlock in HDFS HA functionality"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4505",children:"ZOOKEEPER-4505"})," - CVE-2020-36518 - Upgrade jackson databind to 2.13.2.1"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4511",children:"ZOOKEEPER-4511"})," - Flaky test: FileTxnSnapLogMetricsTest.testFileTxnSnapLogMetrics"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4514",children:"ZOOKEEPER-4514"})," - ClientCnxnSocketNetty throwing NPE"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4515",children:"ZOOKEEPER-4515"})," - ZK Cli quit command always logs error"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4537",children:"ZOOKEEPER-4537"})," - Race between SyncThread and CommitProcessor thread"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4549",children:"ZOOKEEPER-4549"})," - ProviderRegistry may be repeatedly initialized"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4565",children:"ZOOKEEPER-4565"})," - Config watch path get truncated abnormally and fail chroot zookeeper client"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4647",children:"ZOOKEEPER-4647"})," - Tests don't pass on JDK20 because we try to mock InetAddress"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4654",children:"ZOOKEEPER-4654"})," - Fix C client test compilation error in Util.cc."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4674",children:"ZOOKEEPER-4674"})," - C client tests don't pass on CI"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4719",children:"ZOOKEEPER-4719"})," - Use bouncycastle jdk18on instead of jdk15on"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4721",children:"ZOOKEEPER-4721"})," - Upgrade OWASP Dependency Check to 8.3.1"]}),`
`]}),`
`,e.jsx(s.h2,{id:"new-feature-2",children:"New Feature"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4570",children:"ZOOKEEPER-4570"})," - Admin server API for taking snapshot and stream out the data"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4655",children:"ZOOKEEPER-4655"})," - Communicate the Zxid that triggered a WatchEvent to fire"]}),`
`]}),`
`,e.jsx(s.h2,{id:"improvement-5",children:"Improvement"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3731",children:"ZOOKEEPER-3731"})," - Disable HTTP TRACE Method"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3806",children:"ZOOKEEPER-3806"})," - TLS - dynamic loading for client trust/key store"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3860",children:"ZOOKEEPER-3860"})," - Avoid reverse DNS lookup for hostname verification when hostnames are provided in the connection url"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4289",children:"ZOOKEEPER-4289"})," - Reduce the performance impact of Prometheus metrics"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4303",children:"ZOOKEEPER-4303"})," - ZooKeeperServerEmbedded could auto-assign and expose ports"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4464",children:"ZOOKEEPER-4464"}),' - zooinspector display "Ephemeral Owner" in hex for easy match to jmx session']}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4467",children:"ZOOKEEPER-4467"})," - Missing op code (addWatch) in Request.op2String"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4472",children:"ZOOKEEPER-4472"})," - Support persistent watchers removing individually"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4474",children:"ZOOKEEPER-4474"})," - ZooDefs.opNames is unused"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4490",children:"ZOOKEEPER-4490"})," - Publish Clover results to SonarQube"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4491",children:"ZOOKEEPER-4491"})," - Adding SSL support to Zktreeutil"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4492",children:"ZOOKEEPER-4492"})," - Merge readOnly field into ConnectRequest and Response"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4494",children:"ZOOKEEPER-4494"})," - Fix error message format"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4518",children:"ZOOKEEPER-4518"})," - remove useless log in the PrepRequestProcessor#pRequest method"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4519",children:"ZOOKEEPER-4519"})," - Testable interface should have a testableCloseSocket() method"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4529",children:"ZOOKEEPER-4529"})," - Upgrade netty to 4.1.76.Final"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4531",children:"ZOOKEEPER-4531"})," - Revert Netty TCNative change"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4551",children:"ZOOKEEPER-4551"})," - Do not log spammy stacktrace when a client closes its connection"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4566",children:"ZOOKEEPER-4566"})," - Create tool for recursive snapshot analysis"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4573",children:"ZOOKEEPER-4573"})," - Encapsulate request bytebuffer in Request"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4575",children:"ZOOKEEPER-4575"})," - ZooKeeperServer#processPacket take record instead of bytes"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4616",children:"ZOOKEEPER-4616"})," - Upgrade docker image for the dev environment to resolve CVEs"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4622",children:"ZOOKEEPER-4622"})," - Add Netty-TcNative OpenSSL Support"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4636",children:"ZOOKEEPER-4636"})," - Fix zkServer.sh for AIX"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4657",children:"ZOOKEEPER-4657"})," - Publish SBOM artifacts"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4659",children:"ZOOKEEPER-4659"})," - Upgrade Commons CLI to 1.5.0 due to OWASP failing on 1.4 CVE-2021-37533"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4660",children:"ZOOKEEPER-4660"})," - Suppress false positive OWASP failure for CVE-2021-37533"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4661",children:"ZOOKEEPER-4661"})," - Upgrade Jackson Databind to 2.13.4.2 for CVE-2022-42003 CVE-2022-42004"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4705",children:"ZOOKEEPER-4705"})," - Restrict GitHub merge button to allow squash commit only"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4717",children:"ZOOKEEPER-4717"})," - Cache serialize data in the request to avoid repeat serialize."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4718",children:"ZOOKEEPER-4718"})," - Removing unnecessary heap memory allocation in serialization can help reduce GC pressure."]}),`
`]}),`
`,e.jsx(s.h2,{id:"test-2",children:"Test"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4630",children:"ZOOKEEPER-4630"})," - Fix the NPE from ConnectionMetricsTest.testRevalidateCount"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4676",children:"ZOOKEEPER-4676"})," - ReadOnlyModeTest doesn't compile on JDK20 (Thread.suspend has been removed)"]}),`
`]}),`
`,e.jsx(s.h2,{id:"wish-1",children:"Wish"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3615",children:"ZOOKEEPER-3615"})," - write a TLA+ specification to verify Zab protocol"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4710",children:"ZOOKEEPER-4710"})," - Fix ZkUtil deleteInBatch() by releasing semaphore after set flag"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4714",children:"ZOOKEEPER-4714"})," - Improve syncRequestProcessor performance"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4715",children:"ZOOKEEPER-4715"})," - Verify file size and position in testGetCurrentLogSize."]}),`
`]}),`
`,e.jsx(s.h2,{id:"task-4",children:"Task"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4479",children:"ZOOKEEPER-4479"})," - Tests: C client test TestOperations.cc testTimeoutCausedByWatches1 is very flaky on CI"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4482",children:"ZOOKEEPER-4482"})," - Fix LICENSE FILES for commons-io and commons-cli"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4599",children:"ZOOKEEPER-4599"})," - Upgrade Jetty to avoid CVE-2022-2048"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4641",children:"ZOOKEEPER-4641"})," - GH CI fails with error: implicit declaration of function FIPS_mode"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4642",children:"ZOOKEEPER-4642"})," - Remove Travis CI"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4649",children:"ZOOKEEPER-4649"})," - Upgrade netty to 4.1.86 because of CVE-2022-41915"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4669",children:"ZOOKEEPER-4669"})," - Upgrade snappy-java to 1.1.9.1 (in order to support M1 macs)"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4688",children:"ZOOKEEPER-4688"})," - Upgrade ",e.jsx(s.code,{children:"cyclonedx-maven-plugin"})," to 2.7.6"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4700",children:"ZOOKEEPER-4700"})," - Update Jetty for fixing CVE-2023-26048 and CVE-2023-26049"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4707",children:"ZOOKEEPER-4707"})," - Update snappy-java to address multiple CVEs"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4709",children:"ZOOKEEPER-4709"})," - Upgrade Netty to 4.1.94.Final"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4716",children:"ZOOKEEPER-4716"})," - Upgrade jackson to 2.15.2, suppress two false positive CVE errors"]}),`
`]})]})}function c(r={}){const{wrapper:s}=r.components||{};return s?e.jsx(s,{...r,children:e.jsx(t,{...r})}):t(r)}export{a as _markdown,c as default,n as extractedReferences,o as frontmatter,E as structuredData,h as toc};
