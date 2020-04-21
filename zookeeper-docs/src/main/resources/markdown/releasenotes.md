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


# Release Notes - ZooKeeper - Version 3.6.1

## Bug

* [ZOOKEEPER-2164](https://issues.apache.org/jira/browse/ZOOKEEPER-2164) - fast leader election keeps failing
* [ZOOKEEPER-3706](https://issues.apache.org/jira/browse/ZOOKEEPER-3706) - ZooKeeper.close() would leak SendThread when the network is broken
* [ZOOKEEPER-3737](https://issues.apache.org/jira/browse/ZOOKEEPER-3737) - Unable to eliminate log4j1 transitive dependency
* [ZOOKEEPER-3738](https://issues.apache.org/jira/browse/ZOOKEEPER-3738) - Avoid use of broken codehaus properties-maven-plugin
* [ZOOKEEPER-3739](https://issues.apache.org/jira/browse/ZOOKEEPER-3739) - Remove use of com.sun.nio.file.SensitivityWatchEventModifier
* [ZOOKEEPER-3745](https://issues.apache.org/jira/browse/ZOOKEEPER-3745) - Update copyright notices from 2019 to 2020
* [ZOOKEEPER-3758](https://issues.apache.org/jira/browse/ZOOKEEPER-3758) - Update from 3.5.7 to 3.6.0 does not work
* [ZOOKEEPER-3760](https://issues.apache.org/jira/browse/ZOOKEEPER-3760) - remove a useless throwing CliException
* [ZOOKEEPER-3769](https://issues.apache.org/jira/browse/ZOOKEEPER-3769) - fast leader election does not end if leader is taken down
* [ZOOKEEPER-3776](https://issues.apache.org/jira/browse/ZOOKEEPER-3776) - Cluster stuck not forming up quorum
* [ZOOKEEPER-3778](https://issues.apache.org/jira/browse/ZOOKEEPER-3778) - Cannot upgrade from 3.5.7 to 3.6.0 due to multiAddress.reachabilityCheckEnabled
* [ZOOKEEPER-3780](https://issues.apache.org/jira/browse/ZOOKEEPER-3780) - restore Version.getRevision() to be backward compatible
* [ZOOKEEPER-3793](https://issues.apache.org/jira/browse/ZOOKEEPER-3793) - Request throttling is broken when RequestThrottler is disabled or configured incorrectly.
* [ZOOKEEPER-3726](https://issues.apache.org/jira/browse/ZOOKEEPER-3726) - invalid ipv6 address comparison in C client
* [ZOOKEEPER-3797](https://issues.apache.org/jira/browse/ZOOKEEPER-3797) - Conflict between fatjar and full-build Maven profiles in branch-3.6
* [ZOOKEEPER-3802](https://issues.apache.org/jira/browse/ZOOKEEPER-3802) - Fix rat checks in full-build and fatjar

## New Feature

* [ZOOKEEPER-3689](https://issues.apache.org/jira/browse/ZOOKEEPER-3689) - zkCli/ZooKeeperMain relies on system properties for TLS config
* [ZOOKEEPER-3712](https://issues.apache.org/jira/browse/ZOOKEEPER-3712) - Add setKeepAlive support for NIOServerCnxn

## Improvement

* [ZOOKEEPER-3685](https://issues.apache.org/jira/browse/ZOOKEEPER-3685) - Use JDK Arrays Equals for Jute
* [ZOOKEEPER-3686](https://issues.apache.org/jira/browse/ZOOKEEPER-3686) - Use JDK Arrays hashCode for Jute
* [ZOOKEEPER-3708](https://issues.apache.org/jira/browse/ZOOKEEPER-3708) - Move Logging Code into Logging Guard in Learner
* [ZOOKEEPER-3741](https://issues.apache.org/jira/browse/ZOOKEEPER-3741) - Fix ZooKeeper 3.5 C client build on Fedora8
* [ZOOKEEPER-3755](https://issues.apache.org/jira/browse/ZOOKEEPER-3755) - Use maven to create fatjar
* [ZOOKEEPER-3756](https://issues.apache.org/jira/browse/ZOOKEEPER-3756) - Members failing to rejoin quorum
* [ZOOKEEPER-3785](https://issues.apache.org/jira/browse/ZOOKEEPER-3785) - Make sources buildable with JDK14

## Wish

* [ZOOKEEPER-3763](https://issues.apache.org/jira/browse/ZOOKEEPER-3763) - Restore ZKUtil.deleteRecursive in order to help compatibility of applications with 3.5 and 3.6

## Task

* [ZOOKEEPER-3669](https://issues.apache.org/jira/browse/ZOOKEEPER-3669) - Use switch Statement in ClientCnxn SendThread
* [ZOOKEEPER-3677](https://issues.apache.org/jira/browse/ZOOKEEPER-3677) - owasp checker failing for - CVE-2019-17571 Apache Log4j 1.2 deserialization of untrusted data in SocketServer
* [ZOOKEEPER-3751](https://issues.apache.org/jira/browse/ZOOKEEPER-3751) - upgrade jackson-databind to 2.10 from 2.9
* [ZOOKEEPER-3794](https://issues.apache.org/jira/browse/ZOOKEEPER-3794) - upgrade netty to address CVE-2020-11612






