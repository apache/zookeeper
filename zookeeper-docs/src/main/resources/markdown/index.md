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

## ZooKeeper: Because Coordinating Distributed Systems is a Zoo

ZooKeeper is a high-performance coordination service for
distributed applications.  It exposes common services - such as
naming, configuration management, synchronization, and group
services - in a simple interface so you don't have to write them
from scratch.  You can use it off-the-shelf to implement
consensus, group management, leader election, and presence
protocols. And you can build on it for your own, specific needs.

The following documents describe concepts and procedures to get
you started using ZooKeeper. If you have more questions, please
ask the [mailing list](http://zookeeper.apache.org/mailing_lists.html) or browse the
archives.

+ **ZooKeeper Overview**
    Technical Overview Documents for Client Developers, Adminstrators, and Contributors
    + [Overview](zookeeperOver.html) - a bird's eye view of ZooKeeper, including design concepts and architecture
    + [Getting Started](zookeeperStarted.html) - a tutorial-style guide for developers to install, run, and program to ZooKeeper
    + [Release Notes](releasenotes.html) - new developer and user facing features, improvements, and incompatibilities
+ **Developers**
    Documents for Developers using the ZooKeeper Client API
    + [API Docs](api/index.html) - the technical reference to ZooKeeper Client APIs
    + [Programmer's Guide](zookeeperProgrammers.html) - a client application developer's guide to ZooKeeper
    + [ZooKeeper Java Example](javaExample.html) - a simple Zookeeper client appplication, written in Java
    + [Barrier and Queue Tutorial](zookeeperTutorial.html) - sample implementations of barriers and queues
    + [ZooKeeper Recipes](recipes.html) - higher level solutions to common problems in distributed applications
+ **Administrators & Operators**
    Documents for Administrators and Operations Engineers of ZooKeeper Deployments
    + [Administrator's Guide](zookeeperAdmin.html) - a guide for system administrators and anyone else who might deploy ZooKeeper
    + [Quota Guide](zookeeperQuotas.html) - a guide for system administrators on Quotas in ZooKeeper.
    + [JMX](zookeeperJMX.html) - how to enable JMX in ZooKeeper
    + [Hierarchical quorums](zookeeperHierarchicalQuorums.html)
    + [Observers](zookeeperObservers.html) - non-voting ensemble members that easily improve ZooKeeper's scalability
+ **Contributors**
    Documents for Developers Contributing to the ZooKeeper Open Source Project
    + [ZooKeeper Internals](zookeeperInternals.html) - assorted topics on the inner workings of ZooKeeper
+ **Miscellaneous ZooKeeper Documentation**
    + [Wiki](https://cwiki.apache.org/confluence/display/ZOOKEEPER)
    + [FAQ](https://cwiki.apache.org/confluence/display/ZOOKEEPER/FAQ)
+ **BookKeeper Documentation**
    BookKeeper is a highly-available system that implements high-performance write-ahead logging. It uses ZooKeeper for metadata, which is the main reason for being a ZooKeeper contrib.
    + [henn, what's it again?](bookkeeperOverview.html)
    + [Ok, now how do I try it out](bookkeeperStarted.html)
    + [Awesome, but how do I integrate it with my app?](bookkeeperProgrammer.html)
    + [Can I stream bytes instead of entries?](bookkeeperStream.html)
