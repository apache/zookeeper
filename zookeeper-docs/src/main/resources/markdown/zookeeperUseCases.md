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

# ZooKeeper Use Cases

- Applications and organizations using ZooKeeper include (alphabetically) [1].
- If your use case wants to be listed here. Please do not hesitate, submit a pull request or write an email to **dev@zookeeper.apache.org**,
  and then, your use case will be included.
- If this documentation has violated your intellectual property rights or you and your company's privacy, write an email to **dev@zookeeper.apache.org**,
  we will handle them in a timely manner.


## Free Software Projects

### [AdroitLogic UltraESB](http://adroitlogic.org/)
  - Uses ZooKeeper to implement node coordination, in clustering support. This allows the management of the complete cluster,
  or any specific node - from any other node connected via JMX. A Cluster wide command framework developed on top of the
  ZooKeeper coordination allows commands that fail on some nodes to be retried etc. We also support the automated graceful
  round-robin-restart of a complete cluster of nodes using the same framework [1].

### [Akka](http://akka.io/)
  - Akka is the platform for the next generation event-driven, scalable and fault-tolerant architectures on the JVM.
  Or: Akka is a toolkit and runtime for building highly concurrent, distributed, and fault tolerant event-driven applications on the JVM [1].

### [Eclipse Communication Framework](http://www.eclipse.org/ecf)
  - The Eclipse ECF project provides an implementation of its Abstract Discovery services using Zookeeper. ECF itself
  is used in many projects providing base functionallity for communication, all based on OSGi [1].

### [Eclipse Gyrex](http://www.eclipse.org/gyrex)
  - The Eclipse Gyrex project provides a platform for building your own Java OSGi based clouds. 
  - ZooKeeper is used as the core cloud component for node membership and management, coordination of jobs executing among workers,
  a lock service and a simple queue service and a lot more [1].

### [GoldenOrb](http://www.goldenorbos.org/)
  - massive-scale Graph analysis [1].

### [Juju](https://juju.ubuntu.com/)
  - Service deployment and orchestration framework, formerly called Ensemble [1].

### [Katta](http://katta.sourceforge.net/)
  - Katta serves distributed Lucene indexes in a grid environment.
  - Zookeeper is used for node, master and index management in the grid [1].

### [KeptCollections](https://github.com/anthonyu/KeptCollections)
  - KeptCollections is a library of drop-in replacements for the data structures in the Java Collections framework.
  - KeptCollections uses Apache ZooKeeper as a backing store, thus making its data structures distributed and scalable [1].

### [Neo4j](https://neo4j.com/)
  - Neo4j is a Graph Database. It's a disk based, ACID compliant transactional storage engine for big graphs and fast graph traversals,
    using external indicies like Lucene/Solr for global searches.
  - We use ZooKeeper in the Neo4j High Availability components for write-master election,
    read slave coordination and other cool stuff. ZooKeeper is a great and focused project - we like! [1].

### [Norbert](http://sna-projects.com/norbert)
  - Partitioned routing and cluster management [1].

### [spring-cloud-zookeeper](https://spring.io/projects/spring-cloud-zookeeper)
  - Spring Cloud Zookeeper provides Apache Zookeeper integrations for Spring Boot apps through autoconfiguration
    and binding to the Spring Environment and other Spring programming model idioms. With a few simple annotations
    you can quickly enable and configure the common patterns inside your application and build large distributed systems with Zookeeper.
    The patterns provided include Service Discovery and Distributed Configuration [38].

### [spring-statemachine](https://projects.spring.io/spring-statemachine/)
  - Spring Statemachine is a framework for application developers to use state machine concepts with Spring applications.
  - Spring Statemachine can provide this feature:Distributed state machine based on a Zookeeper [31,32].

### [spring-xd](https://projects.spring.io/spring-xd/)
  - Spring XD is a unified, distributed, and extensible system for data ingestion, real time analytics, batch processing, and data export.
    The project’s goal is to simplify the development of big data applications.
  - ZooKeeper - Provides all runtime information for the XD cluster. Tracks running containers, in which containers modules
    and jobs are deployed, stream definitions, deployment manifests, and the like [30,31].

### [Talend ESB](http://www.talend.com/products-application-integration/application-integration-esb-se.php)
  - Talend ESB is a versatile and flexible, enterprise service bus.
  - It uses ZooKeeper as endpoint repository of both REST and SOAP Web services.
    By using ZooKeeper Talend ESB is able to provide failover and load balancing capabilities in a very light-weight manner [1].

### [redis_failover](https://github.com/ryanlecompte/redis_failover)
  - Redis Failover is a ZooKeeper-based automatic master/slave failover solution for Ruby [1].


## Apache Projects

### [Apache Accumulo](https://accumulo.apache.org/)
  - Accumulo is a distributed key/value store that provides expressive, cell-level access labels.
  - Apache ZooKeeper plays a central role within the Accumulo architecture. Its quorum consistency model supports an overall
    Accumulo architecture with no single points of failure. Beyond that, Accumulo leverages ZooKeeper to store and communication 
    configuration information for users and tables, as well as operational states of processes and tablets [2].

### [Apache Atlas](http://atlas.apache.org)
  - Atlas is a scalable and extensible set of core foundational governance services – enabling enterprises to effectively and efficiently meet
    their compliance requirements within Hadoop and allows integration with the whole enterprise data ecosystem.
  - Atlas uses Zookeeper for coordination to provide redundancy and high availability of HBase,Kafka [31,35].

### [Apache BookKeeper](https://bookkeeper.apache.org/)
  - A scalable, fault-tolerant, and low-latency storage service optimized for real-time workloads.
  - BookKeeper requires a metadata storage service to store information related to ledgers and available bookies. BookKeeper currently uses
    ZooKeeper for this and other tasks [3].

### [Apache CXF DOSGi](http://cxf.apache.org/distributed-osgi.html)
  - Apache CXF is an open source services framework. CXF helps you build and develop services using frontend programming
    APIs, like JAX-WS and JAX-RS. These services can speak a variety of protocols such as SOAP, XML/HTTP, RESTful HTTP,
    or CORBA and work over a variety of transports such as HTTP, JMS or JBI.
  - The Distributed OSGi implementation at Apache CXF uses ZooKeeper for its Discovery functionality [4].

### [Apache Drill](http://drill.apache.org/)
  - Schema-free SQL Query Engine for Hadoop, NoSQL and Cloud Storage
  - ZooKeeper maintains ephemeral cluster membership information. The Drillbits use ZooKeeper to find other Drillbits in the cluster,
    and the client uses ZooKeeper to find Drillbits to submit a query [28].

### [Apache Druid(Incubating)](https://druid.apache.org/)
  - Apache Druid (incubating) is a high performance real-time analytics database.
  - Apache Druid (incubating) uses Apache ZooKeeper (ZK) for management of current cluster state. The operations that happen over ZK are [27]:
    - Coordinator leader election
    - Segment "publishing" protocol from Historical and Realtime
    - Segment load/drop protocol between Coordinator and Historical
    - Overlord leader election
    - Overlord and MiddleManager task management

### [Apache Dubbo](http://dubbo.apache.org)
  - Apache Dubbo is a high-performance, java based open source RPC framework.
  - Zookeeper is used for service registration discovery and configuration management in Dubbo [6].

### [Apache Flink](https://flink.apache.org/)
  - Apache Flink is a framework and distributed processing engine for stateful computations over unbounded and bounded data streams.
    Flink has been designed to run in all common cluster environments, perform computations at in-memory speed and at any scale.
  - To enable JobManager High Availability you have to set the high-availability mode to zookeeper, configure a ZooKeeper quorum and set up a masters file with all JobManagers hosts and their web UI ports.
    Flink leverages ZooKeeper for distributed coordination between all running JobManager instances. ZooKeeper is a separate service from Flink,
    which provides highly reliable distributed coordination via leader election and light-weight consistent state storage [23].

### [Apache Flume](https://flume.apache.org/)
  - Flume is a distributed, reliable, and available service for efficiently collecting, aggregating, and moving large amounts
    of log data. It has a simple and flexible architecture based on streaming data flows. It is robust and fault tolerant
    with tunable reliability mechanisms and many failover and recovery mechanisms. It uses a simple extensible data model
    that allows for online analytic application.
  - Flume supports Agent configurations via Zookeeper. This is an experimental feature [5].

### [Apache Fluo](https://fluo.apache.org/)
  - Apache Fluo is a distributed processing system that lets users make incremental updates to large data sets.
  - Apache Fluo is built on Apache Accumulo which uses Apache Zookeeper for consensus [31,37].

### [Apache Griffin](https://griffin.apache.org/)
  - Big Data Quality Solution For Batch and Streaming.
  - Griffin uses Zookeeper for coordination to provide redundancy and high availability of Kafka [31,36].

### [Apache Hadoop](http://hadoop.apache.org/)
  - The Apache Hadoop software library is a framework that allows for the distributed processing of large data sets across
    clusters of computers using simple programming models. It is designed to scale up from single servers to thousands of machines,
    each offering local computation and storage. Rather than rely on hardware to deliver high-availability,
    the library itself is designed to detect and handle failures at the application layer, so delivering a highly-available service on top of a cluster of computers, each of which may be prone to failures.
  - The implementation of automatic HDFS failover relies on ZooKeeper for the following things:
    - **Failure detection** - each of the NameNode machines in the cluster maintains a persistent session in ZooKeeper.
      If the machine crashes, the ZooKeeper session will expire, notifying the other NameNode that a failover should be triggered.
    - **Active NameNode election** - ZooKeeper provides a simple mechanism to exclusively elect a node as active. If the current active NameNode crashes,
      another node may take a special exclusive lock in ZooKeeper indicating that it should become the next active.
  - The ZKFailoverController (ZKFC) is a new component which is a ZooKeeper client which also monitors and manages the state of the NameNode.
    Each of the machines which runs a NameNode also runs a ZKFC, and that ZKFC is responsible for:
    - **Health monitoring** - the ZKFC pings its local NameNode on a periodic basis with a health-check command.
      So long as the NameNode responds in a timely fashion with a healthy status, the ZKFC considers the node healthy.
      If the node has crashed, frozen, or otherwise entered an unhealthy state, the health monitor will mark it as unhealthy.
    - **ZooKeeper session management** - when the local NameNode is healthy, the ZKFC holds a session open in ZooKeeper.
      If the local NameNode is active, it also holds a special “lock” znode. This lock uses ZooKeeper’s support for “ephemeral” nodes;
      if the session expires, the lock node will be automatically deleted.
    - **ZooKeeper-based election** - if the local NameNode is healthy, and the ZKFC sees that no other node currently holds the lock znode,
      it will itself try to acquire the lock. If it succeeds, then it has “won the election”, and is responsible for running a failover to make its local NameNode active.
      The failover process is similar to the manual failover described above: first, the previous active is fenced if necessary,
      and then the local NameNode transitions to active state [7].

### [Apache HBase](https://hbase.apache.org/)
  - HBase is the Hadoop database. It's an open-source, distributed, column-oriented store model.
  - HBase uses ZooKeeper for master election, server lease management, bootstrapping, and coordination between servers.
    A distributed Apache HBase installation depends on a running ZooKeeper cluster. All participating nodes and clients
    need to be able to access the running ZooKeeper ensemble [8].
  - As you can see, ZooKeeper is a fundamental part of HBase. All operations that require coordination, such as Regions
    assignment, Master-Failover, replication, and snapshots, are built on ZooKeeper [20].

### [Apache Helix](http://helix.apache.org/)
  - A cluster management framework for partitioned and replicated distributed resources.
  - We need a distributed store to maintain the state of the cluster and a notification system to notify if there is any change in the cluster state.
    Helix uses Apache ZooKeeper to achieve this functionality [21].
    Zookeeper provides:
    - A way to represent PERSISTENT state which remains until its deleted
    - A way to represent TRANSIENT/EPHEMERAL state which vanishes when the process that created the state dies
    - A notification mechanism when there is a change in PERSISTENT and EPHEMERAL state

### [Apache Hive](https://hive.apache.org)
  - The Apache Hive data warehouse software facilitates reading, writing, and managing large datasets residing in distributed
    storage using SQL. Structure can be projected onto data already in storage. A command line tool and JDBC driver are provided to connect users to Hive.
  - Hive has been using ZooKeeper as distributed lock manager to support concurrency in HiveServer2 [25,26].

### [Apache Ignite](https://ignite.apache.org/)
  - Ignite is a memory-centric distributed database, caching, and processing platform for
    transactional, analytical, and streaming workloads delivering in-memory speeds at petabyte scale
  - Apache Ignite discovery mechanism goes with a ZooKeeper implementations which allows scaling Ignite clusters to 100s and 1000s of nodes
    preserving linear scalability and performance [31,34].​

### [Apache James Mailbox](http://james.apache.org/mailbox/)
  - The Apache James Mailbox is a library providing a flexible Mailbox storage accessible by mail protocols
    (IMAP4, POP3, SMTP,...) and other protocols.
  - Uses Zookeeper and Curator Framework for generating distributed unique ID's [31].

### [Apache Kafka](https://kafka.apache.org/)
  - Kafka is a distributed publish/subscribe messaging system
  - Apache Kafka relies on ZooKeeper for the following things:
    - **Controller election**
    The controller is one of the most important broking entity in a Kafka ecosystem, and it also has the responsibility
    to maintain the leader-follower relationship across all the partitions. If a node by some reason is shutting down,
    it’s the controller’s responsibility to tell all the replicas to act as partition leaders in order to fulfill the
    duties of the partition leaders on the node that is about to fail. So, whenever a node shuts down, a new controller
    can be elected and it can also be made sure that at any given time, there is only one controller and all the follower nodes have agreed on that.
    - **Configuration Of Topics**
    The configuration regarding all the topics including the list of existing topics, the number of partitions for each topic,
    the location of all the replicas, list of configuration overrides for all topics and which node is the preferred leader, etc.
    - **Access control lists**
    Access control lists or ACLs for all the topics are also maintained within Zookeeper.
    - **Membership of the cluster**
    Zookeeper also maintains a list of all the brokers that are functioning at any given moment and are a part of the cluster [9].

### [Apache Kylin](http://kylin.apache.org/)
  - Apache Kylin is an open source Distributed Analytics Engine designed to provide SQL interface and multi-dimensional analysis (OLAP) on Hadoop/Spark supporting extremely large datasets,
    original contributed from eBay Inc.
  - Apache Kylin leverages Zookeeper for job coordination [31,33].

### [Apache Mesos](http://mesos.apache.org/)
  - Apache Mesos abstracts CPU, memory, storage, and other compute resources away from machines (physical or virtual),
    enabling fault-tolerant and elastic distributed systems to easily be built and run effectively.
  - Mesos has a high-availability mode that uses multiple Mesos masters: one active master (called the leader or leading master)
    and several backups in case it fails. The masters elect the leader, with Apache ZooKeeper both coordinating the election
    and handling leader detection by masters, agents, and scheduler drivers [10].

### [Apache Oozie](https://oozie.apache.org)
  - Oozie is a workflow scheduler system to manage Apache Hadoop jobs.
  - the Oozie servers use it for coordinating access to the database and communicating with each other. In order to have full HA,
    there should be at least 3 ZooKeeper servers [29].

### [Apache Pulsar](https://pulsar.apache.org)
  - Apache Pulsar is an open-source distributed pub-sub messaging system originally created at Yahoo and now part of the Apache Software Foundation
  - Pulsar uses Apache Zookeeper for metadata storage, cluster configuration, and coordination. In a Pulsar instance:
    - A configuration store quorum stores configuration for tenants, namespaces, and other entities that need to be globally consistent.
    - Each cluster has its own local ZooKeeper ensemble that stores cluster-specific configuration and coordination such as ownership metadata,
      broker load reports, BookKeeper ledger metadata, and more [24].

### [Apache Solr](https://lucene.apache.org/solr/)
  - Solr is the popular, blazing-fast, open source enterprise search platform built on Apache Lucene.
  - In the "Cloud" edition (v4.x and up) of enterprise search engine Apache Solr, ZooKeeper is used for configuration,
    leader election and more [12,13].

### [Apache Spark](https://spark.apache.org/)
  - Apache Spark is a unified analytics engine for large-scale data processing.
  - Utilizing ZooKeeper to provide leader election and some state storage, you can launch multiple Masters in your cluster connected to the same ZooKeeper instance.
    One will be elected “leader” and the others will remain in standby mode. If the current leader dies, another Master will be elected,
    recover the old Master’s state, and then resume scheduling [14].

### [Apache Storm](http://storm.apache.org)
  - Apache Storm is a free and open source distributed realtime computation system. Apache Storm makes it easy to reliably
    process unbounded streams of data, doing for realtime processing what Hadoop did for batch processing.
    Apache Storm is simple, can be used with any programming language, and is a lot of fun to use!
  - Storm uses Zookeeper for coordinating the cluster [22].


## Companies

### [AGETO](http://www.ageto.de/)
 - The AGETO RnD team uses ZooKeeper in a variety of internal as well as external consulting projects [1].

### [Benipal Technologies](http://www.benipaltechnologies.com/)
 - ZooKeeper is used for internal application development with Solr and Hadoop with Hbase [1].

### [Box](http://box.net/)
 - Box uses ZooKeeper for service discovery, service coordination, Solr and Hadoop support, etc [1].

### [Deepdyve](http://www.deepdyve.com/)
 - We do search for research and provide access to high quality content using advanced search technologies Zookeeper is used to
   manage server state, control index deployment and a myriad other tasks [1].

### [Facebook](https://www.facebook.com/)
 - Facebook uses the Zeus ([17,18]) for configuration management which is a forked version of ZooKeeper, with many scalability
   and performance en- hancements in order to work at the Facebook scale.
   It runs a consensus protocol among servers distributed across mul- tiple regions for resilience. If the leader fails,
   a follower is converted into a new leader.

### [Idium Portal](http://www.idium.no/no/idium_portal/)
 - Idium Portal is a hosted web-publishing system delivered by Norwegian company, Idium AS.
 - ZooKeeper is used for cluster messaging, service bootstrapping, and service coordination [1].

### [Makara](http://www.makara.com/)
 - Using ZooKeeper on 2-node cluster on VMware workstation, Amazon EC2, Zen
 - Using zkpython
 - Looking into expanding into 100 node cluster [1].

### [Midokura](http://www.midokura.com/)
 - We do virtualized networking for the cloud computing era. We use ZooKeeper for various aspects of our distributed control plane [1].

### [Pinterest](https://www.pinterest.com/)
 - Pinterest uses the ZooKeeper for Service discovery and dynamic configuration.Like many large scale web sites, Pinterest’s infrastructure consists of servers that communicate with
   backend services composed of a number of individual servers for managing load and fault tolerance. Ideally, we’d like the configuration to reflect only the active hosts,
   so clients don’t need to deal with bad hosts as often. ZooKeeper provides a well known pattern to solve this problem [19].

### [Rackspace](http://www.rackspace.com/email_hosting)
 - The Email & Apps team uses ZooKeeper to coordinate sharding and responsibility changes in a distributed e-mail client
   that pulls and indexes data for search. ZooKeeper also provides distributed locking for connections to prevent a cluster from overwhelming servers [1].

### [Sematext](http://sematext.com/)
 - Uses ZooKeeper in SPM (which includes ZooKeeper monitoring component, too!), Search Analytics, and Logsene [1].

### [Tubemogul](http://tubemogul.com/)
 - Uses ZooKeeper for leader election, configuration management, locking, group membership [1].

### [Twitter](https://twitter.com/)
 - ZooKeeper is used at Twitter as the source of truth for storing critical metadata. It serves as a coordination kernel to
   provide distributed coordination services, such as leader election and distributed locking.
   Some concrete examples of ZooKeeper in action include [15,16]:
   - ZooKeeper is used to store service registry, which is used by Twitter’s naming service for service discovery.
   - Manhattan (Twitter’s in-house key-value database), Nighthawk (sharded Redis), and Blobstore (in-house photo and video storage),
     stores its cluster topology information in ZooKeeper.
   - EventBus, Twitter’s pub-sub messaging system, stores critical metadata in ZooKeeper and uses ZooKeeper for leader election.
   - Mesos, Twitter’s compute platform, uses ZooKeeper for leader election.

### [Vast.com](http://www.vast.com/)
 - Used internally as a part of sharding services, distributed synchronization of data/index updates, configuration management and failover support [1].

### [Wealthfront](http://wealthfront.com/)
 - Wealthfront uses ZooKeeper for service discovery, leader election and distributed locking among its many backend services.
   ZK is an essential part of Wealthfront's continuous [deployment infrastructure](http://eng.wealthfront.com/2010/05/02/deployment-infrastructure-for-continuous-deployment/) [1].

### [Yahoo!](http://www.yahoo.com/)
 - ZooKeeper is used for a myriad of services inside Yahoo! for doing leader election, configuration management, sharding, locking, group membership etc [1].

### [Zynga](http://www.zynga.com/)
 - ZooKeeper at Zynga is used for a variety of services including configuration management, leader election, sharding and more [1].


#### References
- [1] https://cwiki.apache.org/confluence/display/ZOOKEEPER/PoweredBy
- [2] https://www.youtube.com/watch?v=Ew53T6h9oRw
- [3] https://bookkeeper.apache.org/docs/4.7.3/getting-started/concepts/#ledgers
- [4] http://cxf.apache.org/dosgi-discovery-demo-page.html
- [5] https://flume.apache.org/FlumeUserGuide.html
- [6] http://dubbo.apache.org/en-us/blog/dubbo-zk.html
- [7] https://hadoop.apache.org/docs/r2.7.1/hadoop-project-dist/hadoop-hdfs/HDFSHighAvailabilityWithQJM.html
- [8] https://hbase.apache.org/book.html#zookeeper
- [9] https://www.cloudkarafka.com/blog/2018-07-04-cloudkarafka_what_is_zookeeper.html
- [10] http://mesos.apache.org/documentation/latest/high-availability/
- [11] http://incubator.apache.org/projects/s4.html
- [12] https://lucene.apache.org/solr/guide/6_6/using-zookeeper-to-manage-configuration-files.html#UsingZooKeepertoManageConfigurationFiles-StartupBootstrap
- [13] https://lucene.apache.org/solr/guide/6_6/setting-up-an-external-zookeeper-ensemble.html
- [14] https://spark.apache.org/docs/latest/spark-standalone.html#standby-masters-with-zookeeper
- [15] https://blog.twitter.com/engineering/en_us/topics/infrastructure/2018/zookeeper-at-twitter.html
- [16] https://blog.twitter.com/engineering/en_us/topics/infrastructure/2018/dynamic-configuration-at-twitter.html
- [17] TANG, C., KOOBURAT, T., VENKATACHALAM, P.,CHANDER, A., WEN, Z., NARAYANAN, A., DOWELL,P., AND KARL, R. Holistic Configuration Management
       at Facebook. In Proceedings of the 25th Symposium on Operating System Principles (SOSP’15) (Monterey, CA,USA, Oct. 2015).
- [18] https://www.youtube.com/watch?v=SeZV373gUZc
- [19] https://medium.com/@Pinterest_Engineering/zookeeper-resilience-at-pinterest-adfd8acf2a6b
- [20] https://blog.cloudera.com/what-are-hbase-znodes/
- [21] https://helix.apache.org/Architecture.html
- [22] http://storm.apache.org/releases/current/Setting-up-a-Storm-cluster.html
- [23] https://ci.apache.org/projects/flink/flink-docs-release-1.9/ops/jobmanager_high_availability.html
- [24] https://pulsar.apache.org/docs/en/concepts-architecture-overview/#metadata-store
- [25] https://cwiki.apache.org/confluence/display/Hive/Locking
- [26] *ZooKeeperHiveLockManager* implementation in the [hive](https://github.com/apache/hive/) code base
- [27] https://druid.apache.org/docs/latest/dependencies/zookeeper.html
- [28] https://mapr.com/blog/apache-drill-architecture-ultimate-guide/
- [29] https://oozie.apache.org/docs/4.1.0/AG_Install.html
- [30] https://docs.spring.io/spring-xd/docs/current/reference/html/
- [31] https://cwiki.apache.org/confluence/display/CURATOR/Powered+By
- [32] https://projects.spring.io/spring-statemachine/
- [33] https://www.tigeranalytics.com/blog/apache-kylin-architecture/
- [34] https://apacheignite.readme.io/docs/cluster-discovery
- [35] http://atlas.apache.org/HighAvailability.html
- [36] http://griffin.apache.org/docs/usecases.html
- [37] https://fluo.apache.org/
- [38] https://spring.io/projects/spring-cloud-zookeeper
