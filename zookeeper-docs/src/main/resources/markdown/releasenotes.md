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



# Release Notes - ZooKeeper - Version 3.6.2
    
## Bug

* [ZOOKEEPER-3112](https://issues.apache.org/jira/browse/ZOOKEEPER-3112) - fd leak due to UnresolvedAddressException on connect.
* [ZOOKEEPER-3215](https://issues.apache.org/jira/browse/ZOOKEEPER-3215) - Handle Java 9/11 additions of covariant return types to java.nio.ByteBuffer methods
* [ZOOKEEPER-3772](https://issues.apache.org/jira/browse/ZOOKEEPER-3772) - JettyAdminServer should not allow HTTP TRACE method
* [ZOOKEEPER-3792](https://issues.apache.org/jira/browse/ZOOKEEPER-3792) - Reconcile document site in 3.5.7 &amp; 3.6.0
* [ZOOKEEPER-3801](https://issues.apache.org/jira/browse/ZOOKEEPER-3801) - Fix Jenkins link in pom
* [ZOOKEEPER-3814](https://issues.apache.org/jira/browse/ZOOKEEPER-3814) - ZooKeeper config propagates even with disabled dynamic reconfig
* [ZOOKEEPER-3818](https://issues.apache.org/jira/browse/ZOOKEEPER-3818) - fix zkServer.sh status command to support SSL-only server
* [ZOOKEEPER-3829](https://issues.apache.org/jira/browse/ZOOKEEPER-3829) - Zookeeper refuses request after node expansion
* [ZOOKEEPER-3830](https://issues.apache.org/jira/browse/ZOOKEEPER-3830) - After add a new node, zookeeper cluster won&#39;t commit any proposal if this new node is leader
* [ZOOKEEPER-3832](https://issues.apache.org/jira/browse/ZOOKEEPER-3832) - ZKHostnameVerifier rejects valid certificates with subjectAltNames
* [ZOOKEEPER-3842](https://issues.apache.org/jira/browse/ZOOKEEPER-3842) - Rolling scale up of zookeeper cluster does not work with reconfigEnabled=false
* [ZOOKEEPER-3857](https://issues.apache.org/jira/browse/ZOOKEEPER-3857) - ZooKeeper 3.6 doesn&#39;t build after Curator test committed
* [ZOOKEEPER-3865](https://issues.apache.org/jira/browse/ZOOKEEPER-3865) - fix backward-compatibility for ZooKeeperServer constructor
* [ZOOKEEPER-3876](https://issues.apache.org/jira/browse/ZOOKEEPER-3876) - zkServer.sh status command fails when IPV6 is configured
* [ZOOKEEPER-3878](https://issues.apache.org/jira/browse/ZOOKEEPER-3878) - Client connection fails if IPV6 is not enclosed in square brackets
* [ZOOKEEPER-3885](https://issues.apache.org/jira/browse/ZOOKEEPER-3885) - zoo_aremove_watches segfault: zk_hashtable needs locking!
* [ZOOKEEPER-3895](https://issues.apache.org/jira/browse/ZOOKEEPER-3895) - Client side NullPointerException in case of empty Multi operation
* [ZOOKEEPER-3905](https://issues.apache.org/jira/browse/ZOOKEEPER-3905) - Race condition causes sessions to be created for clients even though their certificate authentication has failed
                
## Improvement

* [ZOOKEEPER-3678](https://issues.apache.org/jira/browse/ZOOKEEPER-3678) - Remove Redundant GroupID from Maven POMs
* [ZOOKEEPER-3679](https://issues.apache.org/jira/browse/ZOOKEEPER-3679) - Upgrade maven-compiler-plugin For ZooKeeper-jute
* [ZOOKEEPER-3761](https://issues.apache.org/jira/browse/ZOOKEEPER-3761) - upgrade JLine jar dependency
* [ZOOKEEPER-3790](https://issues.apache.org/jira/browse/ZOOKEEPER-3790) - zkpython: Minor compilation and testing issues
* [ZOOKEEPER-3831](https://issues.apache.org/jira/browse/ZOOKEEPER-3831) - Add a test that does a minimal validation of Apache Curator
* [ZOOKEEPER-3834](https://issues.apache.org/jira/browse/ZOOKEEPER-3834) - Do Not Set Explicit Test Includes in POM
* [ZOOKEEPER-3844](https://issues.apache.org/jira/browse/ZOOKEEPER-3844) - Add useful metrics for ZK servers
* [ZOOKEEPER-3893](https://issues.apache.org/jira/browse/ZOOKEEPER-3893) - Enhance documentation for property ssl.clientAuth 
* [ZOOKEEPER-3913](https://issues.apache.org/jira/browse/ZOOKEEPER-3913) - Upgrade to Netty 4.1.50.Final
            
## Task

* [ZOOKEEPER-3817](https://issues.apache.org/jira/browse/ZOOKEEPER-3817) - owasp failing due to CVE-2020-9488
* [ZOOKEEPER-3896](https://issues.apache.org/jira/browse/ZOOKEEPER-3896) - Migrate Jenkins jobs to ci-hadoop.apache.org
* [ZOOKEEPER-3924](https://issues.apache.org/jira/browse/ZOOKEEPER-3924) - Netty and JLine Licenses are inconsistent with jars in the binary tarball in 3.6.2 rc0
                                                                                                                                                
## Sub-task

* [ZOOKEEPER-3845](https://issues.apache.org/jira/browse/ZOOKEEPER-3845) - Add metric JVM_PAUSE_TIME
* [ZOOKEEPER-3846](https://issues.apache.org/jira/browse/ZOOKEEPER-3846) - Add a couple TLS related metrics
* [ZOOKEEPER-3847](https://issues.apache.org/jira/browse/ZOOKEEPER-3847) - Add a couple metrics to help track Netty memory usage
* [ZOOKEEPER-3856](https://issues.apache.org/jira/browse/ZOOKEEPER-3856) - Add a couple metrics to track inflight diff syncs and snap syncs




