import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";let r=`* Applications and organizations using ZooKeeper include (alphabetically) [^1].
* If your use case wants to be listed here. Please do not hesitate, submit a pull request or write an email to **[dev@zookeeper.apache.org](mailto:dev@zookeeper.apache.org)**,
  and then, your use case will be included.
* If this documentation has violated your intellectual property rights or you and your company's privacy, write an email to **[dev@zookeeper.apache.org](mailto:dev@zookeeper.apache.org)**,
  we will handle them in a timely manner.

## Free Software Projects

### [AdroitLogic UltraESB](http://adroitlogic.org/)

* Uses ZooKeeper to implement node coordination, in clustering support. This allows the management of the complete cluster,
  or any specific node - from any other node connected via JMX. A Cluster wide command framework developed on top of the
  ZooKeeper coordination allows commands that fail on some nodes to be retried etc. We also support the automated graceful
  round-robin-restart of a complete cluster of nodes using the same framework [^1].

### [Akka](http://akka.io/)

* Akka is the platform for the next generation event-driven, scalable and fault-tolerant architectures on the JVM.
  Or: Akka is a toolkit and runtime for building highly concurrent, distributed, and fault tolerant event-driven applications on the JVM [^1].

### [Eclipse Communication Framework](http://www.eclipse.org/ecf)

* The Eclipse ECF project provides an implementation of its Abstract Discovery services using Zookeeper. ECF itself
  is used in many projects providing base functionality for communication, all based on OSGi [^1].

### [Eclipse Gyrex](http://www.eclipse.org/gyrex)

* The Eclipse Gyrex project provides a platform for building your own Java OSGi based clouds.
* ZooKeeper is used as the core cloud component for node membership and management, coordination of jobs executing among workers,
  a lock service and a simple queue service and a lot more [^1].

### [GoldenOrb](http://www.goldenorbos.org/)

* massive-scale Graph analysis [^1].

### [Juju](https://juju.ubuntu.com/)

* Service deployment and orchestration framework, formerly called Ensemble [^1].

### [Katta](http://katta.sourceforge.net/)

* Katta serves distributed Lucene indexes in a grid environment.
* Zookeeper is used for node, master and index management in the grid [^1].

### [KeptCollections](https://github.com/anthonyu/KeptCollections)

* KeptCollections is a library of drop-in replacements for the data structures in the Java Collections framework.
* KeptCollections uses Apache ZooKeeper as a backing store, thus making its data structures distributed and scalable [^1].

### [Neo4j](https://neo4j.com/)

* Neo4j is a Graph Database. It's a disk based, ACID compliant transactional storage engine for big graphs and fast graph traversals,
  using external indices like Lucene/Solr for global searches.
* We use ZooKeeper in the Neo4j High Availability components for write-master election,
  read slave coordination and other cool stuff. ZooKeeper is a great and focused project - we like! [^1].

### [Norbert](http://sna-projects.com/norbert)

* Partitioned routing and cluster management [^1].

### [spring-cloud-zookeeper](https://spring.io/projects/spring-cloud-zookeeper)

* Spring Cloud Zookeeper provides Apache Zookeeper integrations for Spring Boot apps through autoconfiguration
  and binding to the Spring Environment and other Spring programming model idioms. With a few simple annotations
  you can quickly enable and configure the common patterns inside your application and build large distributed systems with Zookeeper.
  The patterns provided include Service Discovery and Distributed Configuration [^38].

### [spring-statemachine](https://projects.spring.io/spring-statemachine/)

* Spring Statemachine is a framework for application developers to use state machine concepts with Spring applications.
* Spring Statemachine can provide this feature:Distributed state machine based on a Zookeeper \\[31,32].

### [spring-xd](https://projects.spring.io/spring-xd/)

* Spring XD is a unified, distributed, and extensible system for data ingestion, real time analytics, batch processing, and data export.
  The project’s goal is to simplify the development of big data applications.
* ZooKeeper - Provides all runtime information for the XD cluster. Tracks running containers, in which containers modules
  and jobs are deployed, stream definitions, deployment manifests, and the like \\[30,31].

### [Talend ESB](http://www.talend.com/products-application-integration/application-integration-esb-se.php)

* Talend ESB is a versatile and flexible, enterprise service bus.
* It uses ZooKeeper as endpoint repository of both REST and SOAP Web services.
  By using ZooKeeper Talend ESB is able to provide failover and load balancing capabilities in a very light-weight manner [^1].

### [redis\\_failover](https://github.com/ryanlecompte/redis_failover)

* Redis Failover is a ZooKeeper-based automatic master/slave failover solution for Ruby [^1].

## Apache Projects

### [Apache Accumulo](https://accumulo.apache.org/)

* Accumulo is a distributed key/value store that provides expressive, cell-level access labels.
* Apache ZooKeeper plays a central role within the Accumulo architecture. Its quorum consistency model supports an overall
  Accumulo architecture with no single points of failure. Beyond that, Accumulo leverages ZooKeeper to store and communication
  configuration information for users and tables, as well as operational states of processes and tablets [^2].

### [Apache Atlas](http://atlas.apache.org)

* Atlas is a scalable and extensible set of core foundational governance services – enabling enterprises to effectively and efficiently meet
  their compliance requirements within Hadoop and allows integration with the whole enterprise data ecosystem.
* Atlas uses Zookeeper for coordination to provide redundancy and high availability of HBase,Kafka \\[31,35].

### [Apache BookKeeper](https://bookkeeper.apache.org/)

* A scalable, fault-tolerant, and low-latency storage service optimized for real-time workloads.
* BookKeeper requires a metadata storage service to store information related to ledgers and available bookies. BookKeeper currently uses
  ZooKeeper for this and other tasks [^3].

### [Apache CXF DOSGi](http://cxf.apache.org/distributed-osgi.html)

* Apache CXF is an open source services framework. CXF helps you build and develop services using frontend programming
  APIs, like JAX-WS and JAX-RS. These services can speak a variety of protocols such as SOAP, XML/HTTP, RESTful HTTP,
  or CORBA and work over a variety of transports such as HTTP, JMS or JBI.
* The Distributed OSGi implementation at Apache CXF uses ZooKeeper for its Discovery functionality [^4].

### [Apache Drill](http://drill.apache.org/)

* Schema-free SQL Query Engine for Hadoop, NoSQL and Cloud Storage
* ZooKeeper maintains ephemeral cluster membership information. The Drillbits use ZooKeeper to find other Drillbits in the cluster,
  and the client uses ZooKeeper to find Drillbits to submit a query [^28].

### [Apache Druid](https://druid.apache.org/)

* Apache Druid is a high performance real-time analytics database.
* Apache Druid uses Apache ZooKeeper (ZK) for management of current cluster state. The operations that happen over ZK are [^27]:
  * Coordinator leader election
  * Segment "publishing" protocol from Historical and Realtime
  * Segment load/drop protocol between Coordinator and Historical
  * Overlord leader election
  * Overlord and MiddleManager task management

### [Apache Dubbo](http://dubbo.apache.org)

* Apache Dubbo is a high-performance, java based open source RPC framework.
* Zookeeper is used for service registration discovery and configuration management in Dubbo [^6].

### [Apache Flink](https://flink.apache.org/)

* Apache Flink is a framework and distributed processing engine for stateful computations over unbounded and bounded data streams.
  Flink has been designed to run in all common cluster environments, perform computations at in-memory speed and at any scale.
* To enable JobManager High Availability you have to set the high-availability mode to zookeeper, configure a ZooKeeper quorum and set up a masters file with all JobManagers hosts and their web UI ports.
  Flink leverages ZooKeeper for distributed coordination between all running JobManager instances. ZooKeeper is a separate service from Flink,
  which provides highly reliable distributed coordination via leader election and light-weight consistent state storage [^23].

### [Apache Flume](https://flume.apache.org/)

* Flume is a distributed, reliable, and available service for efficiently collecting, aggregating, and moving large amounts
  of log data. It has a simple and flexible architecture based on streaming data flows. It is robust and fault tolerant
  with tunable reliability mechanisms and many failover and recovery mechanisms. It uses a simple extensible data model
  that allows for online analytic application.

* Flume supports Agent configurations via Zookeeper. This is an experimental feature [^5].

### [Apache Fluo](https://fluo.apache.org/)

* Apache Fluo is a distributed processing system that lets users make incremental updates to large data sets.
* Apache Fluo is built on Apache Accumulo which uses Apache Zookeeper for consensus \\[31,37].

### [Apache Griffin](https://griffin.apache.org/)

* Big Data Quality Solution For Batch and Streaming.
* Griffin uses Zookeeper for coordination to provide redundancy and high availability of Kafka \\[31,36].

### [Apache Hadoop](http://hadoop.apache.org/)

* The Apache Hadoop software library is a framework that allows for the distributed processing of large data sets across
  clusters of computers using simple programming models. It is designed to scale up from single servers to thousands of machines,
  each offering local computation and storage. Rather than rely on hardware to deliver high-availability,
  the library itself is designed to detect and handle failures at the application layer, so delivering a highly-available service on top of a cluster of computers, each of which may be prone to failures.

* The implementation of automatic HDFS failover relies on ZooKeeper for the following things:
  * **Failure detection** - each of the NameNode machines in the cluster maintains a persistent session in ZooKeeper.
    If the machine crashes, the ZooKeeper session will expire, notifying the other NameNode that a failover should be triggered.
  * **Active NameNode election** - ZooKeeper provides a simple mechanism to exclusively elect a node as active. If the current active NameNode crashes,
    another node may take a special exclusive lock in ZooKeeper indicating that it should become the next active.

* The ZKFailoverController (ZKFC) is a new component which is a ZooKeeper client which also monitors and manages the state of the NameNode.
  Each of the machines which runs a NameNode also runs a ZKFC, and that ZKFC is responsible for:
  * **Health monitoring** - the ZKFC pings its local NameNode on a periodic basis with a health-check command.
    So long as the NameNode responds in a timely fashion with a healthy status, the ZKFC considers the node healthy.
    If the node has crashed, frozen, or otherwise entered an unhealthy state, the health monitor will mark it as unhealthy.
  * **ZooKeeper session management** - when the local NameNode is healthy, the ZKFC holds a session open in ZooKeeper.
    If the local NameNode is active, it also holds a special “lock” znode. This lock uses ZooKeeper’s support for “ephemeral” nodes;
    if the session expires, the lock node will be automatically deleted.
  * **ZooKeeper-based election** - if the local NameNode is healthy, and the ZKFC sees that no other node currently holds the lock znode,
    it will itself try to acquire the lock. If it succeeds, then it has “won the election”, and is responsible for running a failover to make its local NameNode active.
    The failover process is similar to the manual failover described above: first, the previous active is fenced if necessary,
    and then the local NameNode transitions to active state [^7].

### [Apache HBase](https://hbase.apache.org/)

* HBase is the Hadoop database. It's an open-source, distributed, column-oriented store model.
* HBase uses ZooKeeper for master election, server lease management, bootstrapping, and coordination between servers.
  A distributed Apache HBase installation depends on a running ZooKeeper cluster. All participating nodes and clients
  need to be able to access the running ZooKeeper ensemble [^8].
* As you can see, ZooKeeper is a fundamental part of HBase. All operations that require coordination, such as Regions
  assignment, Master-Failover, replication, and snapshots, are built on ZooKeeper [^20].

### [Apache Helix](http://helix.apache.org/)

* A cluster management framework for partitioned and replicated distributed resources.
* We need a distributed store to maintain the state of the cluster and a notification system to notify if there is any change in the cluster state.
  Helix uses Apache ZooKeeper to achieve this functionality [^21].
  Zookeeper provides:
* A way to represent PERSISTENT state which remains until its deleted
* A way to represent TRANSIENT/EPHEMERAL state which vanishes when the process that created the state dies
* A notification mechanism when there is a change in PERSISTENT and EPHEMERAL state

### [Apache Hive](https://hive.apache.org)

* The Apache Hive data warehouse software facilitates reading, writing, and managing large datasets residing in distributed
  storage using SQL. Structure can be projected onto data already in storage. A command line tool and JDBC driver are provided to connect users to Hive.
* Hive has been using ZooKeeper as distributed lock manager to support concurrency in HiveServer2 \\[25,26].

### [Apache Ignite](https://ignite.apache.org/)

* Ignite is a memory-centric distributed database, caching, and processing platform for
  transactional, analytical, and streaming workloads delivering in-memory speeds at petabyte scale
* Apache Ignite discovery mechanism goes with a ZooKeeper implementations which allows scaling Ignite clusters to 100s and 1000s of nodes
  preserving linear scalability and performance \\[31,34].​

### [Apache James Mailbox](http://james.apache.org/mailbox/)

* The Apache James Mailbox is a library providing a flexible Mailbox storage accessible by mail protocols
  (IMAP4, POP3, SMTP,...) and other protocols.
* Uses Zookeeper and Curator Framework for generating distributed unique ID's [^31].

### [Apache Kafka](https://kafka.apache.org/)

* Kafka is a distributed publish/subscribe messaging system
* Apache Kafka relies on ZooKeeper for the following things:
  * **Controller election**
    The controller is one of the most important broking entity in a Kafka ecosystem, and it also has the responsibility
    to maintain the leader-follower relationship across all the partitions. If a node by some reason is shutting down,
    it’s the controller’s responsibility to tell all the replicas to act as partition leaders in order to fulfill the
    duties of the partition leaders on the node that is about to fail. So, whenever a node shuts down, a new controller
    can be elected and it can also be made sure that at any given time, there is only one controller and all the follower nodes have agreed on that.
  * **Configuration Of Topics**
    The configuration regarding all the topics including the list of existing topics, the number of partitions for each topic,
    the location of all the replicas, list of configuration overrides for all topics and which node is the preferred leader, etc.
  * **Access control lists**
    Access control lists or ACLs for all the topics are also maintained within Zookeeper.
  * **Membership of the cluster**
    Zookeeper also maintains a list of all the brokers that are functioning at any given moment and are a part of the cluster [^9].

### [Apache Kylin](http://kylin.apache.org/)

* Apache Kylin is an open source Distributed Analytics Engine designed to provide SQL interface and multi-dimensional analysis (OLAP) on Hadoop/Spark supporting extremely large datasets,
  original contributed from eBay Inc.
* Apache Kylin leverages Zookeeper for job coordination \\[31,33].

### [Apache Mesos](http://mesos.apache.org/)

* Apache Mesos abstracts CPU, memory, storage, and other compute resources away from machines (physical or virtual),
  enabling fault-tolerant and elastic distributed systems to easily be built and run effectively.
* Mesos has a high-availability mode that uses multiple Mesos masters: one active master (called the leader or leading master)
  and several backups in case it fails. The masters elect the leader, with Apache ZooKeeper both coordinating the election
  and handling leader detection by masters, agents, and scheduler drivers [^10].

### [Apache Oozie](https://oozie.apache.org)

* Oozie is a workflow scheduler system to manage Apache Hadoop jobs.
* the Oozie servers use it for coordinating access to the database and communicating with each other. In order to have full HA,
  there should be at least 3 ZooKeeper servers [^29].

### [Apache Pulsar](https://pulsar.apache.org)

* Apache Pulsar is an open-source distributed pub-sub messaging system originally created at Yahoo and now part of the Apache Software Foundation
* Pulsar uses Apache Zookeeper for metadata storage, cluster configuration, and coordination. In a Pulsar instance:
  * A configuration store quorum stores configuration for tenants, namespaces, and other entities that need to be globally consistent.
  * Each cluster has its own local ZooKeeper ensemble that stores cluster-specific configuration and coordination such as ownership metadata,
    broker load reports, BookKeeper ledger metadata, and more [^24].

### [Apache Solr](https://lucene.apache.org/solr/)

* Solr is the popular, blazing-fast, open source enterprise search platform built on Apache Lucene.
* In the "Cloud" edition (v4.x and up) of enterprise search engine Apache Solr, ZooKeeper is used for configuration,
  leader election and more \\[12,13].

### [Apache Spark](https://spark.apache.org/)

* Apache Spark is a unified analytics engine for large-scale data processing.
* Utilizing ZooKeeper to provide leader election and some state storage, you can launch multiple Masters in your cluster connected to the same ZooKeeper instance.
  One will be elected “leader” and the others will remain in standby mode. If the current leader dies, another Master will be elected,
  recover the old Master’s state, and then resume scheduling [^14].

### [Apache Storm](http://storm.apache.org)

* Apache Storm is a free and open source distributed realtime computation system. Apache Storm makes it easy to reliably
  process unbounded streams of data, doing for realtime processing what Hadoop did for batch processing.
  Apache Storm is simple, can be used with any programming language, and is a lot of fun to use!
* Storm uses Zookeeper for coordinating the cluster [^22].

## Companies

### [AGETO](http://www.ageto.de/)

* The AGETO RnD team uses ZooKeeper in a variety of internal as well as external consulting projects [^1].

### [Benipal Technologies](http://www.benipaltechnologies.com/)

* ZooKeeper is used for internal application development with Solr and Hadoop with Hbase [^1].

### [Box](http://box.net/)

* Box uses ZooKeeper for service discovery, service coordination, Solr and Hadoop support, etc [^1].

### [Deepdyve](http://www.deepdyve.com/)

* We do search for research and provide access to high quality content using advanced search technologies Zookeeper is used to
  manage server state, control index deployment and a myriad other tasks [^1].

### [Facebook](https://www.facebook.com/)

* Facebook uses the Zeus (\\[17,18]) for configuration management which is a forked version of ZooKeeper, with many scalability
  and performance en- hancements in order to work at the Facebook scale.
  It runs a consensus protocol among servers distributed across mul- tiple regions for resilience. If the leader fails,
  a follower is converted into a new leader.

### [Idium Portal](http://www.idium.no/no/idium_portal/)

* Idium Portal is a hosted web-publishing system delivered by Norwegian company, Idium AS.
* ZooKeeper is used for cluster messaging, service bootstrapping, and service coordination [^1].

### [Makara](http://www.makara.com/)

* Using ZooKeeper on 2-node cluster on VMware workstation, Amazon EC2, Zen
* Using zkpython
* Looking into expanding into 100 node cluster [^1].

### [Midokura](http://www.midokura.com/)

* We do virtualized networking for the cloud computing era. We use ZooKeeper for various aspects of our distributed control plane [^1].

### [Pinterest](https://www.pinterest.com/)

* Pinterest uses the ZooKeeper for Service discovery and dynamic configuration.Like many large scale web sites, Pinterest’s infrastructure consists of servers that communicate with
  backend services composed of a number of individual servers for managing load and fault tolerance. Ideally, we’d like the configuration to reflect only the active hosts,
  so clients don’t need to deal with bad hosts as often. ZooKeeper provides a well known pattern to solve this problem [^19].

### [Rackspace](http://www.rackspace.com/email_hosting)

* The Email & Apps team uses ZooKeeper to coordinate sharding and responsibility changes in a distributed e-mail client
  that pulls and indexes data for search. ZooKeeper also provides distributed locking for connections to prevent a cluster from overwhelming servers [^1].

### [Sematext](http://sematext.com/)

* Uses ZooKeeper in SPM (which includes ZooKeeper monitoring component, too!), Search Analytics, and Logsene [^1].

### [Tubemogul](http://tubemogul.com/)

* Uses ZooKeeper for leader election, configuration management, locking, group membership [^1].

### [Twitter](https://twitter.com/)

* ZooKeeper is used at Twitter as the source of truth for storing critical metadata. It serves as a coordination kernel to
  provide distributed coordination services, such as leader election and distributed locking.
  Some concrete examples of ZooKeeper in action include \\[15,16]:
  * ZooKeeper is used to store service registry, which is used by Twitter’s naming service for service discovery.
  * Manhattan (Twitter’s in-house key-value database), Nighthawk (sharded Redis), and Blobstore (in-house photo and video storage),
    stores its cluster topology information in ZooKeeper.
  * EventBus, Twitter’s pub-sub messaging system, stores critical metadata in ZooKeeper and uses ZooKeeper for leader election.
  * Mesos, Twitter’s compute platform, uses ZooKeeper for leader election.

### [Vast.com](http://www.vast.com/)

* Used internally as a part of sharding services, distributed synchronization of data/index updates, configuration management and failover support [^1].

### [Wealthfront](http://wealthfront.com/)

* Wealthfront uses ZooKeeper for service discovery, leader election and distributed locking among its many backend services.
  ZK is an essential part of Wealthfront's continuous [deployment infrastructure](http://eng.wealthfront.com/2010/05/02/deployment-infrastructure-for-continuous-deployment/) [^1].

### [Yahoo!](http://www.yahoo.com/)

* ZooKeeper is used for a myriad of services inside Yahoo! for doing leader election, configuration management, sharding, locking, group membership etc [^1].

### [Zynga](http://www.zynga.com/)

* ZooKeeper at Zynga is used for a variety of services including configuration management, leader election, sharding and more [^1].

[^1]: [https://cwiki.apache.org/confluence/display/ZOOKEEPER/PoweredBy](https://cwiki.apache.org/confluence/display/ZOOKEEPER/PoweredBy)

[^2]: [https://www.youtube.com/watch?v=Ew53T6h9oRw](https://www.youtube.com/watch?v=Ew53T6h9oRw)

[^3]: [https://bookkeeper.apache.org/docs/4.7.3/getting-started/concepts/#ledgers](https://bookkeeper.apache.org/docs/4.7.3/getting-started/concepts/#ledgers)

[^4]: [http://cxf.apache.org/dosgi-discovery-demo-page.html](http://cxf.apache.org/dosgi-discovery-demo-page.html)

[^5]: [https://flume.apache.org/FlumeUserGuide.html](https://flume.apache.org/FlumeUserGuide.html)

[^6]: [http://dubbo.apache.org/en-us/blog/dubbo-zk.html](http://dubbo.apache.org/en-us/blog/dubbo-zk.html)

[^7]: [https://hadoop.apache.org/docs/r2.7.1/hadoop-project-dist/hadoop-hdfs/HDFSHighAvailabilityWithQJM.html](https://hadoop.apache.org/docs/r2.7.1/hadoop-project-dist/hadoop-hdfs/HDFSHighAvailabilityWithQJM.html)

[^8]: [https://hbase.apache.org/book.html#zookeeper](https://hbase.apache.org/book.html#zookeeper)

[^9]: [https://www.cloudkarafka.com/blog/2018-07-04-cloudkarafka\\_what\\_is\\_zookeeper.html](https://www.cloudkarafka.com/blog/2018-07-04-cloudkarafka_what_is_zookeeper.html)

[^10]: [http://mesos.apache.org/documentation/latest/high-availability/](http://mesos.apache.org/documentation/latest/high-availability/)

[^11]: [http://incubator.apache.org/projects/s4.html](http://incubator.apache.org/projects/s4.html)

[^12]: [https://lucene.apache.org/solr/guide/6\\_6/using-zookeeper-to-manage-configuration-files.html#UsingZooKeepertoManageConfigurationFiles-StartupBootstrap](https://lucene.apache.org/solr/guide/6_6/using-zookeeper-to-manage-configuration-files.html#UsingZooKeepertoManageConfigurationFiles-StartupBootstrap)

[^13]: [https://lucene.apache.org/solr/guide/6\\_6/setting-up-an-external-zookeeper-ensemble.html](https://lucene.apache.org/solr/guide/6_6/setting-up-an-external-zookeeper-ensemble.html)

[^14]: [https://spark.apache.org/docs/latest/spark-standalone.html#standby-masters-with-zookeeper](https://spark.apache.org/docs/latest/spark-standalone.html#standby-masters-with-zookeeper)

[^15]: [https://blog.twitter.com/engineering/en\\_us/topics/infrastructure/2018/zookeeper-at-twitter.html](https://blog.twitter.com/engineering/en_us/topics/infrastructure/2018/zookeeper-at-twitter.html)

[^16]: [https://blog.twitter.com/engineering/en\\_us/topics/infrastructure/2018/dynamic-configuration-at-twitter.html](https://blog.twitter.com/engineering/en_us/topics/infrastructure/2018/dynamic-configuration-at-twitter.html)

[^17]: TANG, C., KOOBURAT, T., VENKATACHALAM, P.,CHANDER, A., WEN, Z., NARAYANAN, A., DOWELL,P., AND KARL, R. Holistic Configuration Management at Facebook. In Proceedings of the 25th Symposium on Operating System Principles (SOSP’15) (Monterey, CA,USA, Oct. 2015).

[^18]: [https://www.youtube.com/watch?v=SeZV373gUZc](https://www.youtube.com/watch?v=SeZV373gUZc)

[^19]: [https://medium.com/@Pinterest\\_Engineering/zookeeper-resilience-at-pinterest-adfd8acf2a6b](https://medium.com/@Pinterest_Engineering/zookeeper-resilience-at-pinterest-adfd8acf2a6b)

[^20]: [https://blog.cloudera.com/what-are-hbase-znodes/](https://blog.cloudera.com/what-are-hbase-znodes/)

[^21]: [https://helix.apache.org/Architecture.html](https://helix.apache.org/Architecture.html)

[^22]: [http://storm.apache.org/releases/current/Setting-up-a-Storm-cluster.html](http://storm.apache.org/releases/current/Setting-up-a-Storm-cluster.html)

[^23]: [https://ci.apache.org/projects/flink/flink-docs-release-1.9/ops/jobmanager\\_high\\_availability.html](https://ci.apache.org/projects/flink/flink-docs-release-1.9/ops/jobmanager_high_availability.html)

[^24]: [https://pulsar.apache.org/docs/en/concepts-architecture-overview/#metadata-store](https://pulsar.apache.org/docs/en/concepts-architecture-overview/#metadata-store)

[^25]: [https://cwiki.apache.org/confluence/display/Hive/Locking](https://cwiki.apache.org/confluence/display/Hive/Locking)

[^26]: *ZooKeeperHiveLockManager* implementation in the [hive](https://github.com/apache/hive/) code base

[^27]: [https://druid.apache.org/docs/latest/dependencies/zookeeper.html](https://druid.apache.org/docs/latest/dependencies/zookeeper.html)

[^28]: [https://mapr.com/blog/apache-drill-architecture-ultimate-guide/](https://mapr.com/blog/apache-drill-architecture-ultimate-guide/)

[^29]: [https://oozie.apache.org/docs/4.1.0/AG\\_Install.html](https://oozie.apache.org/docs/4.1.0/AG_Install.html)

[^30]: [https://docs.spring.io/spring-xd/docs/current/reference/html/](https://docs.spring.io/spring-xd/docs/current/reference/html/)

[^31]: [https://cwiki.apache.org/confluence/display/CURATOR/Powered+By](https://cwiki.apache.org/confluence/display/CURATOR/Powered+By)

[^32]: [https://projects.spring.io/spring-statemachine/](https://projects.spring.io/spring-statemachine/)

[^33]: [https://www.tigeranalytics.com/blog/apache-kylin-architecture/](https://www.tigeranalytics.com/blog/apache-kylin-architecture/)

[^34]: [https://apacheignite.readme.io/docs/cluster-discovery](https://apacheignite.readme.io/docs/cluster-discovery)

[^35]: [http://atlas.apache.org/HighAvailability.html](http://atlas.apache.org/HighAvailability.html)

[^36]: [http://griffin.apache.org/docs/usecases.html](http://griffin.apache.org/docs/usecases.html)

[^37]: [https://fluo.apache.org/](https://fluo.apache.org/)

[^38]: [https://spring.io/projects/spring-cloud-zookeeper](https://spring.io/projects/spring-cloud-zookeeper)
`,i={title:"Use Cases",description:"A catalog of real-world projects and organizations that use Apache ZooKeeper for distributed coordination."},s=[{href:"mailto:dev@zookeeper.apache.org"},{href:"mailto:dev@zookeeper.apache.org"},{href:"http://adroitlogic.org/"},{href:"http://akka.io/"},{href:"http://www.eclipse.org/ecf"},{href:"http://www.eclipse.org/gyrex"},{href:"http://www.goldenorbos.org/"},{href:"https://juju.ubuntu.com/"},{href:"http://katta.sourceforge.net/"},{href:"https://github.com/anthonyu/KeptCollections"},{href:"https://neo4j.com/"},{href:"http://sna-projects.com/norbert"},{href:"https://spring.io/projects/spring-cloud-zookeeper"},{href:"https://projects.spring.io/spring-statemachine/"},{href:"https://projects.spring.io/spring-xd/"},{href:"http://www.talend.com/products-application-integration/application-integration-esb-se.php"},{href:"https://github.com/ryanlecompte/redis_failover"},{href:"https://accumulo.apache.org/"},{href:"http://atlas.apache.org"},{href:"https://bookkeeper.apache.org/"},{href:"http://cxf.apache.org/distributed-osgi.html"},{href:"http://drill.apache.org/"},{href:"https://druid.apache.org/"},{href:"http://dubbo.apache.org"},{href:"https://flink.apache.org/"},{href:"https://flume.apache.org/"},{href:"https://fluo.apache.org/"},{href:"https://griffin.apache.org/"},{href:"http://hadoop.apache.org/"},{href:"https://hbase.apache.org/"},{href:"http://helix.apache.org/"},{href:"https://hive.apache.org"},{href:"https://ignite.apache.org/"},{href:"http://james.apache.org/mailbox/"},{href:"https://kafka.apache.org/"},{href:"http://kylin.apache.org/"},{href:"http://mesos.apache.org/"},{href:"https://oozie.apache.org"},{href:"https://pulsar.apache.org"},{href:"https://lucene.apache.org/solr/"},{href:"https://spark.apache.org/"},{href:"http://storm.apache.org"},{href:"http://www.ageto.de/"},{href:"http://www.benipaltechnologies.com/"},{href:"http://box.net/"},{href:"http://www.deepdyve.com/"},{href:"https://www.facebook.com/"},{href:"http://www.idium.no/no/idium_portal/"},{href:"http://www.makara.com/"},{href:"http://www.midokura.com/"},{href:"https://www.pinterest.com/"},{href:"http://www.rackspace.com/email_hosting"},{href:"http://sematext.com/"},{href:"http://tubemogul.com/"},{href:"https://twitter.com/"},{href:"http://www.vast.com/"},{href:"http://wealthfront.com/"},{href:"http://eng.wealthfront.com/2010/05/02/deployment-infrastructure-for-continuous-deployment/"},{href:"http://www.yahoo.com/"},{href:"http://www.zynga.com/"},{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/PoweredBy"},{href:"https://www.youtube.com/watch?v=Ew53T6h9oRw"},{href:"https://bookkeeper.apache.org/docs/4.7.3/getting-started/concepts/#ledgers"},{href:"http://cxf.apache.org/dosgi-discovery-demo-page.html"},{href:"https://flume.apache.org/FlumeUserGuide.html"},{href:"http://dubbo.apache.org/en-us/blog/dubbo-zk.html"},{href:"https://hadoop.apache.org/docs/r2.7.1/hadoop-project-dist/hadoop-hdfs/HDFSHighAvailabilityWithQJM.html"},{href:"https://hbase.apache.org/book.html#zookeeper"},{href:"https://www.cloudkarafka.com/blog/2018-07-04-cloudkarafka_what_is_zookeeper.html"},{href:"http://mesos.apache.org/documentation/latest/high-availability/"},{href:"http://incubator.apache.org/projects/s4.html"},{href:"https://lucene.apache.org/solr/guide/6_6/using-zookeeper-to-manage-configuration-files.html#UsingZooKeepertoManageConfigurationFiles-StartupBootstrap"},{href:"https://lucene.apache.org/solr/guide/6_6/setting-up-an-external-zookeeper-ensemble.html"},{href:"https://spark.apache.org/docs/latest/spark-standalone.html#standby-masters-with-zookeeper"},{href:"https://blog.twitter.com/engineering/en_us/topics/infrastructure/2018/zookeeper-at-twitter.html"},{href:"https://blog.twitter.com/engineering/en_us/topics/infrastructure/2018/dynamic-configuration-at-twitter.html"},{href:"https://www.youtube.com/watch?v=SeZV373gUZc"},{href:"https://medium.com/@Pinterest_Engineering/zookeeper-resilience-at-pinterest-adfd8acf2a6b"},{href:"https://blog.cloudera.com/what-are-hbase-znodes/"},{href:"https://helix.apache.org/Architecture.html"},{href:"http://storm.apache.org/releases/current/Setting-up-a-Storm-cluster.html"},{href:"https://ci.apache.org/projects/flink/flink-docs-release-1.9/ops/jobmanager_high_availability.html"},{href:"https://pulsar.apache.org/docs/en/concepts-architecture-overview/#metadata-store"},{href:"https://cwiki.apache.org/confluence/display/Hive/Locking"},{href:"https://github.com/apache/hive/"},{href:"https://druid.apache.org/docs/latest/dependencies/zookeeper.html"},{href:"https://mapr.com/blog/apache-drill-architecture-ultimate-guide/"},{href:"https://oozie.apache.org/docs/4.1.0/AG_Install.html"},{href:"https://docs.spring.io/spring-xd/docs/current/reference/html/"},{href:"https://cwiki.apache.org/confluence/display/CURATOR/Powered+By"},{href:"https://projects.spring.io/spring-statemachine/"},{href:"https://www.tigeranalytics.com/blog/apache-kylin-architecture/"},{href:"https://apacheignite.readme.io/docs/cluster-discovery"},{href:"http://atlas.apache.org/HighAvailability.html"},{href:"http://griffin.apache.org/docs/usecases.html"},{href:"https://fluo.apache.org/"},{href:"https://spring.io/projects/spring-cloud-zookeeper"}],c={contents:[{heading:void 0,content:"Applications and organizations using ZooKeeper include (alphabetically) ."},{heading:void 0,content:`If your use case wants to be listed here. Please do not hesitate, submit a pull request or write an email to dev@zookeeper.apache.org,
and then, your use case will be included.`},{heading:void 0,content:`If this documentation has violated your intellectual property rights or you and your company's privacy, write an email to dev@zookeeper.apache.org,
we will handle them in a timely manner.`},{heading:"adroitlogic-ultraesb",content:`Uses ZooKeeper to implement node coordination, in clustering support. This allows the management of the complete cluster,
or any specific node - from any other node connected via JMX. A Cluster wide command framework developed on top of the
ZooKeeper coordination allows commands that fail on some nodes to be retried etc. We also support the automated graceful
round-robin-restart of a complete cluster of nodes using the same framework .`},{heading:"akka",content:`Akka is the platform for the next generation event-driven, scalable and fault-tolerant architectures on the JVM.
Or: Akka is a toolkit and runtime for building highly concurrent, distributed, and fault tolerant event-driven applications on the JVM .`},{heading:"eclipse-communication-framework",content:`The Eclipse ECF project provides an implementation of its Abstract Discovery services using Zookeeper. ECF itself
is used in many projects providing base functionality for communication, all based on OSGi .`},{heading:"eclipse-gyrex",content:"The Eclipse Gyrex project provides a platform for building your own Java OSGi based clouds."},{heading:"eclipse-gyrex",content:`ZooKeeper is used as the core cloud component for node membership and management, coordination of jobs executing among workers,
a lock service and a simple queue service and a lot more .`},{heading:"goldenorb",content:"massive-scale Graph analysis ."},{heading:"juju",content:"Service deployment and orchestration framework, formerly called Ensemble ."},{heading:"katta",content:"Katta serves distributed Lucene indexes in a grid environment."},{heading:"katta",content:"Zookeeper is used for node, master and index management in the grid ."},{heading:"keptcollections",content:"KeptCollections is a library of drop-in replacements for the data structures in the Java Collections framework."},{heading:"keptcollections",content:"KeptCollections uses Apache ZooKeeper as a backing store, thus making its data structures distributed and scalable ."},{heading:"neo4j",content:`Neo4j is a Graph Database. It's a disk based, ACID compliant transactional storage engine for big graphs and fast graph traversals,
using external indices like Lucene/Solr for global searches.`},{heading:"neo4j",content:`We use ZooKeeper in the Neo4j High Availability components for write-master election,
read slave coordination and other cool stuff. ZooKeeper is a great and focused project - we like! .`},{heading:"norbert",content:"Partitioned routing and cluster management ."},{heading:"spring-cloud-zookeeper",content:`Spring Cloud Zookeeper provides Apache Zookeeper integrations for Spring Boot apps through autoconfiguration
and binding to the Spring Environment and other Spring programming model idioms. With a few simple annotations
you can quickly enable and configure the common patterns inside your application and build large distributed systems with Zookeeper.
The patterns provided include Service Discovery and Distributed Configuration .`},{heading:"spring-statemachine",content:"Spring Statemachine is a framework for application developers to use state machine concepts with Spring applications."},{heading:"spring-statemachine",content:"Spring Statemachine can provide this feature:Distributed state machine based on a Zookeeper [31,32]."},{heading:"spring-xd",content:`Spring XD is a unified, distributed, and extensible system for data ingestion, real time analytics, batch processing, and data export.
The project’s goal is to simplify the development of big data applications.`},{heading:"spring-xd",content:`ZooKeeper - Provides all runtime information for the XD cluster. Tracks running containers, in which containers modules
and jobs are deployed, stream definitions, deployment manifests, and the like [30,31].`},{heading:"talend-esb",content:"Talend ESB is a versatile and flexible, enterprise service bus."},{heading:"talend-esb",content:`It uses ZooKeeper as endpoint repository of both REST and SOAP Web services.
By using ZooKeeper Talend ESB is able to provide failover and load balancing capabilities in a very light-weight manner .`},{heading:"redis_failover",content:"Redis Failover is a ZooKeeper-based automatic master/slave failover solution for Ruby ."},{heading:"apache-accumulo",content:"Accumulo is a distributed key/value store that provides expressive, cell-level access labels."},{heading:"apache-accumulo",content:`Apache ZooKeeper plays a central role within the Accumulo architecture. Its quorum consistency model supports an overall
Accumulo architecture with no single points of failure. Beyond that, Accumulo leverages ZooKeeper to store and communication
configuration information for users and tables, as well as operational states of processes and tablets .`},{heading:"apache-atlas",content:`Atlas is a scalable and extensible set of core foundational governance services – enabling enterprises to effectively and efficiently meet
their compliance requirements within Hadoop and allows integration with the whole enterprise data ecosystem.`},{heading:"apache-atlas",content:"Atlas uses Zookeeper for coordination to provide redundancy and high availability of HBase,Kafka [31,35]."},{heading:"apache-bookkeeper",content:"A scalable, fault-tolerant, and low-latency storage service optimized for real-time workloads."},{heading:"apache-bookkeeper",content:`BookKeeper requires a metadata storage service to store information related to ledgers and available bookies. BookKeeper currently uses
ZooKeeper for this and other tasks .`},{heading:"apache-cxf-dosgi",content:`Apache CXF is an open source services framework. CXF helps you build and develop services using frontend programming
APIs, like JAX-WS and JAX-RS. These services can speak a variety of protocols such as SOAP, XML/HTTP, RESTful HTTP,
or CORBA and work over a variety of transports such as HTTP, JMS or JBI.`},{heading:"apache-cxf-dosgi",content:"The Distributed OSGi implementation at Apache CXF uses ZooKeeper for its Discovery functionality ."},{heading:"apache-drill",content:"Schema-free SQL Query Engine for Hadoop, NoSQL and Cloud Storage"},{heading:"apache-drill",content:`ZooKeeper maintains ephemeral cluster membership information. The Drillbits use ZooKeeper to find other Drillbits in the cluster,
and the client uses ZooKeeper to find Drillbits to submit a query .`},{heading:"apache-druid",content:"Apache Druid is a high performance real-time analytics database."},{heading:"apache-druid",content:"Apache Druid uses Apache ZooKeeper (ZK) for management of current cluster state. The operations that happen over ZK are :"},{heading:"apache-druid",content:"Coordinator leader election"},{heading:"apache-druid",content:'Segment "publishing" protocol from Historical and Realtime'},{heading:"apache-druid",content:"Segment load/drop protocol between Coordinator and Historical"},{heading:"apache-druid",content:"Overlord leader election"},{heading:"apache-druid",content:"Overlord and MiddleManager task management"},{heading:"apache-dubbo",content:"Apache Dubbo is a high-performance, java based open source RPC framework."},{heading:"apache-dubbo",content:"Zookeeper is used for service registration discovery and configuration management in Dubbo ."},{heading:"apache-flink",content:`Apache Flink is a framework and distributed processing engine for stateful computations over unbounded and bounded data streams.
Flink has been designed to run in all common cluster environments, perform computations at in-memory speed and at any scale.`},{heading:"apache-flink",content:`To enable JobManager High Availability you have to set the high-availability mode to zookeeper, configure a ZooKeeper quorum and set up a masters file with all JobManagers hosts and their web UI ports.
Flink leverages ZooKeeper for distributed coordination between all running JobManager instances. ZooKeeper is a separate service from Flink,
which provides highly reliable distributed coordination via leader election and light-weight consistent state storage .`},{heading:"apache-flume",content:`Flume is a distributed, reliable, and available service for efficiently collecting, aggregating, and moving large amounts
of log data. It has a simple and flexible architecture based on streaming data flows. It is robust and fault tolerant
with tunable reliability mechanisms and many failover and recovery mechanisms. It uses a simple extensible data model
that allows for online analytic application.`},{heading:"apache-flume",content:"Flume supports Agent configurations via Zookeeper. This is an experimental feature ."},{heading:"apache-fluo",content:"Apache Fluo is a distributed processing system that lets users make incremental updates to large data sets."},{heading:"apache-fluo",content:"Apache Fluo is built on Apache Accumulo which uses Apache Zookeeper for consensus [31,37]."},{heading:"apache-griffin",content:"Big Data Quality Solution For Batch and Streaming."},{heading:"apache-griffin",content:"Griffin uses Zookeeper for coordination to provide redundancy and high availability of Kafka [31,36]."},{heading:"apache-hadoop",content:`The Apache Hadoop software library is a framework that allows for the distributed processing of large data sets across
clusters of computers using simple programming models. It is designed to scale up from single servers to thousands of machines,
each offering local computation and storage. Rather than rely on hardware to deliver high-availability,
the library itself is designed to detect and handle failures at the application layer, so delivering a highly-available service on top of a cluster of computers, each of which may be prone to failures.`},{heading:"apache-hadoop",content:"The implementation of automatic HDFS failover relies on ZooKeeper for the following things:"},{heading:"apache-hadoop",content:`Failure detection - each of the NameNode machines in the cluster maintains a persistent session in ZooKeeper.
If the machine crashes, the ZooKeeper session will expire, notifying the other NameNode that a failover should be triggered.`},{heading:"apache-hadoop",content:`Active NameNode election - ZooKeeper provides a simple mechanism to exclusively elect a node as active. If the current active NameNode crashes,
another node may take a special exclusive lock in ZooKeeper indicating that it should become the next active.`},{heading:"apache-hadoop",content:`The ZKFailoverController (ZKFC) is a new component which is a ZooKeeper client which also monitors and manages the state of the NameNode.
Each of the machines which runs a NameNode also runs a ZKFC, and that ZKFC is responsible for:`},{heading:"apache-hadoop",content:`Health monitoring - the ZKFC pings its local NameNode on a periodic basis with a health-check command.
So long as the NameNode responds in a timely fashion with a healthy status, the ZKFC considers the node healthy.
If the node has crashed, frozen, or otherwise entered an unhealthy state, the health monitor will mark it as unhealthy.`},{heading:"apache-hadoop",content:`ZooKeeper session management - when the local NameNode is healthy, the ZKFC holds a session open in ZooKeeper.
If the local NameNode is active, it also holds a special “lock” znode. This lock uses ZooKeeper’s support for “ephemeral” nodes;
if the session expires, the lock node will be automatically deleted.`},{heading:"apache-hadoop",content:`ZooKeeper-based election - if the local NameNode is healthy, and the ZKFC sees that no other node currently holds the lock znode,
it will itself try to acquire the lock. If it succeeds, then it has “won the election”, and is responsible for running a failover to make its local NameNode active.
The failover process is similar to the manual failover described above: first, the previous active is fenced if necessary,
and then the local NameNode transitions to active state .`},{heading:"apache-hbase",content:"HBase is the Hadoop database. It's an open-source, distributed, column-oriented store model."},{heading:"apache-hbase",content:`HBase uses ZooKeeper for master election, server lease management, bootstrapping, and coordination between servers.
A distributed Apache HBase installation depends on a running ZooKeeper cluster. All participating nodes and clients
need to be able to access the running ZooKeeper ensemble .`},{heading:"apache-hbase",content:`As you can see, ZooKeeper is a fundamental part of HBase. All operations that require coordination, such as Regions
assignment, Master-Failover, replication, and snapshots, are built on ZooKeeper .`},{heading:"apache-helix",content:"A cluster management framework for partitioned and replicated distributed resources."},{heading:"apache-helix",content:`We need a distributed store to maintain the state of the cluster and a notification system to notify if there is any change in the cluster state.
Helix uses Apache ZooKeeper to achieve this functionality .
Zookeeper provides:`},{heading:"apache-helix",content:"A way to represent PERSISTENT state which remains until its deleted"},{heading:"apache-helix",content:"A way to represent TRANSIENT/EPHEMERAL state which vanishes when the process that created the state dies"},{heading:"apache-helix",content:"A notification mechanism when there is a change in PERSISTENT and EPHEMERAL state"},{heading:"apache-hive",content:`The Apache Hive data warehouse software facilitates reading, writing, and managing large datasets residing in distributed
storage using SQL. Structure can be projected onto data already in storage. A command line tool and JDBC driver are provided to connect users to Hive.`},{heading:"apache-hive",content:"Hive has been using ZooKeeper as distributed lock manager to support concurrency in HiveServer2 [25,26]."},{heading:"apache-ignite",content:`Ignite is a memory-centric distributed database, caching, and processing platform for
transactional, analytical, and streaming workloads delivering in-memory speeds at petabyte scale`},{heading:"apache-ignite",content:`Apache Ignite discovery mechanism goes with a ZooKeeper implementations which allows scaling Ignite clusters to 100s and 1000s of nodes
preserving linear scalability and performance [31,34].​`},{heading:"apache-james-mailbox",content:`The Apache James Mailbox is a library providing a flexible Mailbox storage accessible by mail protocols
(IMAP4, POP3, SMTP,...) and other protocols.`},{heading:"apache-james-mailbox",content:"Uses Zookeeper and Curator Framework for generating distributed unique ID's ."},{heading:"apache-kafka",content:"Kafka is a distributed publish/subscribe messaging system"},{heading:"apache-kafka",content:"Apache Kafka relies on ZooKeeper for the following things:"},{heading:"apache-kafka",content:`Controller election
The controller is one of the most important broking entity in a Kafka ecosystem, and it also has the responsibility
to maintain the leader-follower relationship across all the partitions. If a node by some reason is shutting down,
it’s the controller’s responsibility to tell all the replicas to act as partition leaders in order to fulfill the
duties of the partition leaders on the node that is about to fail. So, whenever a node shuts down, a new controller
can be elected and it can also be made sure that at any given time, there is only one controller and all the follower nodes have agreed on that.`},{heading:"apache-kafka",content:`Configuration Of Topics
The configuration regarding all the topics including the list of existing topics, the number of partitions for each topic,
the location of all the replicas, list of configuration overrides for all topics and which node is the preferred leader, etc.`},{heading:"apache-kafka",content:`Access control lists
Access control lists or ACLs for all the topics are also maintained within Zookeeper.`},{heading:"apache-kafka",content:`Membership of the cluster
Zookeeper also maintains a list of all the brokers that are functioning at any given moment and are a part of the cluster .`},{heading:"apache-kylin",content:`Apache Kylin is an open source Distributed Analytics Engine designed to provide SQL interface and multi-dimensional analysis (OLAP) on Hadoop/Spark supporting extremely large datasets,
original contributed from eBay Inc.`},{heading:"apache-kylin",content:"Apache Kylin leverages Zookeeper for job coordination [31,33]."},{heading:"apache-mesos",content:`Apache Mesos abstracts CPU, memory, storage, and other compute resources away from machines (physical or virtual),
enabling fault-tolerant and elastic distributed systems to easily be built and run effectively.`},{heading:"apache-mesos",content:`Mesos has a high-availability mode that uses multiple Mesos masters: one active master (called the leader or leading master)
and several backups in case it fails. The masters elect the leader, with Apache ZooKeeper both coordinating the election
and handling leader detection by masters, agents, and scheduler drivers .`},{heading:"apache-oozie",content:"Oozie is a workflow scheduler system to manage Apache Hadoop jobs."},{heading:"apache-oozie",content:`the Oozie servers use it for coordinating access to the database and communicating with each other. In order to have full HA,
there should be at least 3 ZooKeeper servers .`},{heading:"apache-pulsar",content:"Apache Pulsar is an open-source distributed pub-sub messaging system originally created at Yahoo and now part of the Apache Software Foundation"},{heading:"apache-pulsar",content:"Pulsar uses Apache Zookeeper for metadata storage, cluster configuration, and coordination. In a Pulsar instance:"},{heading:"apache-pulsar",content:"A configuration store quorum stores configuration for tenants, namespaces, and other entities that need to be globally consistent."},{heading:"apache-pulsar",content:`Each cluster has its own local ZooKeeper ensemble that stores cluster-specific configuration and coordination such as ownership metadata,
broker load reports, BookKeeper ledger metadata, and more .`},{heading:"apache-solr",content:"Solr is the popular, blazing-fast, open source enterprise search platform built on Apache Lucene."},{heading:"apache-solr",content:`In the "Cloud" edition (v4.x and up) of enterprise search engine Apache Solr, ZooKeeper is used for configuration,
leader election and more [12,13].`},{heading:"apache-spark",content:"Apache Spark is a unified analytics engine for large-scale data processing."},{heading:"apache-spark",content:`Utilizing ZooKeeper to provide leader election and some state storage, you can launch multiple Masters in your cluster connected to the same ZooKeeper instance.
One will be elected “leader” and the others will remain in standby mode. If the current leader dies, another Master will be elected,
recover the old Master’s state, and then resume scheduling .`},{heading:"apache-storm",content:`Apache Storm is a free and open source distributed realtime computation system. Apache Storm makes it easy to reliably
process unbounded streams of data, doing for realtime processing what Hadoop did for batch processing.
Apache Storm is simple, can be used with any programming language, and is a lot of fun to use!`},{heading:"apache-storm",content:"Storm uses Zookeeper for coordinating the cluster ."},{heading:"ageto",content:"The AGETO RnD team uses ZooKeeper in a variety of internal as well as external consulting projects ."},{heading:"benipal-technologies",content:"ZooKeeper is used for internal application development with Solr and Hadoop with Hbase ."},{heading:"box",content:"Box uses ZooKeeper for service discovery, service coordination, Solr and Hadoop support, etc ."},{heading:"deepdyve",content:`We do search for research and provide access to high quality content using advanced search technologies Zookeeper is used to
manage server state, control index deployment and a myriad other tasks .`},{heading:"facebook",content:`Facebook uses the Zeus ([17,18]) for configuration management which is a forked version of ZooKeeper, with many scalability
and performance en- hancements in order to work at the Facebook scale.
It runs a consensus protocol among servers distributed across mul- tiple regions for resilience. If the leader fails,
a follower is converted into a new leader.`},{heading:"idium-portal",content:"Idium Portal is a hosted web-publishing system delivered by Norwegian company, Idium AS."},{heading:"idium-portal",content:"ZooKeeper is used for cluster messaging, service bootstrapping, and service coordination ."},{heading:"makara",content:"Using ZooKeeper on 2-node cluster on VMware workstation, Amazon EC2, Zen"},{heading:"makara",content:"Using zkpython"},{heading:"makara",content:"Looking into expanding into 100 node cluster ."},{heading:"midokura",content:"We do virtualized networking for the cloud computing era. We use ZooKeeper for various aspects of our distributed control plane ."},{heading:"pinterest",content:`Pinterest uses the ZooKeeper for Service discovery and dynamic configuration.Like many large scale web sites, Pinterest’s infrastructure consists of servers that communicate with
backend services composed of a number of individual servers for managing load and fault tolerance. Ideally, we’d like the configuration to reflect only the active hosts,
so clients don’t need to deal with bad hosts as often. ZooKeeper provides a well known pattern to solve this problem .`},{heading:"rackspace",content:`The Email & Apps team uses ZooKeeper to coordinate sharding and responsibility changes in a distributed e-mail client
that pulls and indexes data for search. ZooKeeper also provides distributed locking for connections to prevent a cluster from overwhelming servers .`},{heading:"sematext",content:"Uses ZooKeeper in SPM (which includes ZooKeeper monitoring component, too!), Search Analytics, and Logsene ."},{heading:"tubemogul",content:"Uses ZooKeeper for leader election, configuration management, locking, group membership ."},{heading:"twitter",content:`ZooKeeper is used at Twitter as the source of truth for storing critical metadata. It serves as a coordination kernel to
provide distributed coordination services, such as leader election and distributed locking.
Some concrete examples of ZooKeeper in action include [15,16]:`},{heading:"twitter",content:"ZooKeeper is used to store service registry, which is used by Twitter’s naming service for service discovery."},{heading:"twitter",content:`Manhattan (Twitter’s in-house key-value database), Nighthawk (sharded Redis), and Blobstore (in-house photo and video storage),
stores its cluster topology information in ZooKeeper.`},{heading:"twitter",content:"EventBus, Twitter’s pub-sub messaging system, stores critical metadata in ZooKeeper and uses ZooKeeper for leader election."},{heading:"twitter",content:"Mesos, Twitter’s compute platform, uses ZooKeeper for leader election."},{heading:"vastcom",content:"Used internally as a part of sharding services, distributed synchronization of data/index updates, configuration management and failover support ."},{heading:"wealthfront",content:`Wealthfront uses ZooKeeper for service discovery, leader election and distributed locking among its many backend services.
ZK is an essential part of Wealthfront's continuous deployment infrastructure .`},{heading:"yahoo",content:"ZooKeeper is used for a myriad of services inside Yahoo! for doing leader election, configuration management, sharding, locking, group membership etc ."},{heading:"zynga",content:"ZooKeeper at Zynga is used for a variety of services including configuration management, leader election, sharding and more ."},{heading:"zynga",content:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/PoweredBy"},{heading:"zynga",content:"https://www.youtube.com/watch?v=Ew53T6h9oRw"},{heading:"zynga",content:"https://bookkeeper.apache.org/docs/4.7.3/getting-started/concepts/#ledgers"},{heading:"zynga",content:"http://cxf.apache.org/dosgi-discovery-demo-page.html"},{heading:"zynga",content:"https://flume.apache.org/FlumeUserGuide.html"},{heading:"zynga",content:"http://dubbo.apache.org/en-us/blog/dubbo-zk.html"},{heading:"zynga",content:"https://hadoop.apache.org/docs/r2.7.1/hadoop-project-dist/hadoop-hdfs/HDFSHighAvailabilityWithQJM.html"},{heading:"zynga",content:"https://hbase.apache.org/book.html#zookeeper"},{heading:"zynga",content:"https://www.cloudkarafka.com/blog/2018-07-04-cloudkarafka_what_is_zookeeper.html"},{heading:"zynga",content:"http://mesos.apache.org/documentation/latest/high-availability/"},{heading:"zynga",content:"http://incubator.apache.org/projects/s4.html"},{heading:"zynga",content:"https://lucene.apache.org/solr/guide/6_6/using-zookeeper-to-manage-configuration-files.html#UsingZooKeepertoManageConfigurationFiles-StartupBootstrap"},{heading:"zynga",content:"https://lucene.apache.org/solr/guide/6_6/setting-up-an-external-zookeeper-ensemble.html"},{heading:"zynga",content:"https://spark.apache.org/docs/latest/spark-standalone.html#standby-masters-with-zookeeper"},{heading:"zynga",content:"https://blog.twitter.com/engineering/en_us/topics/infrastructure/2018/zookeeper-at-twitter.html"},{heading:"zynga",content:"https://blog.twitter.com/engineering/en_us/topics/infrastructure/2018/dynamic-configuration-at-twitter.html"},{heading:"zynga",content:"TANG, C., KOOBURAT, T., VENKATACHALAM, P.,CHANDER, A., WEN, Z., NARAYANAN, A., DOWELL,P., AND KARL, R. Holistic Configuration Management at Facebook. In Proceedings of the 25th Symposium on Operating System Principles (SOSP’15) (Monterey, CA,USA, Oct. 2015)."},{heading:"zynga",content:"https://www.youtube.com/watch?v=SeZV373gUZc"},{heading:"zynga",content:"https://medium.com/@Pinterest_Engineering/zookeeper-resilience-at-pinterest-adfd8acf2a6b"},{heading:"zynga",content:"https://blog.cloudera.com/what-are-hbase-znodes/"},{heading:"zynga",content:"https://helix.apache.org/Architecture.html"},{heading:"zynga",content:"http://storm.apache.org/releases/current/Setting-up-a-Storm-cluster.html"},{heading:"zynga",content:"https://ci.apache.org/projects/flink/flink-docs-release-1.9/ops/jobmanager_high_availability.html"},{heading:"zynga",content:"https://pulsar.apache.org/docs/en/concepts-architecture-overview/#metadata-store"},{heading:"zynga",content:"https://cwiki.apache.org/confluence/display/Hive/Locking"},{heading:"zynga",content:"ZooKeeperHiveLockManager implementation in the hive code base"},{heading:"zynga",content:"https://druid.apache.org/docs/latest/dependencies/zookeeper.html"},{heading:"zynga",content:"https://mapr.com/blog/apache-drill-architecture-ultimate-guide/"},{heading:"zynga",content:"https://oozie.apache.org/docs/4.1.0/AG_Install.html"},{heading:"zynga",content:"https://docs.spring.io/spring-xd/docs/current/reference/html/"},{heading:"zynga",content:"https://cwiki.apache.org/confluence/display/CURATOR/Powered+By"},{heading:"zynga",content:"https://projects.spring.io/spring-statemachine/"},{heading:"zynga",content:"https://www.tigeranalytics.com/blog/apache-kylin-architecture/"},{heading:"zynga",content:"https://apacheignite.readme.io/docs/cluster-discovery"},{heading:"zynga",content:"http://atlas.apache.org/HighAvailability.html"},{heading:"zynga",content:"http://griffin.apache.org/docs/usecases.html"},{heading:"zynga",content:"https://fluo.apache.org/"},{heading:"zynga",content:"https://spring.io/projects/spring-cloud-zookeeper"}],headings:[{id:"free-software-projects",content:"Free Software Projects"},{id:"adroitlogic-ultraesb",content:"AdroitLogic UltraESB"},{id:"akka",content:"Akka"},{id:"eclipse-communication-framework",content:"Eclipse Communication Framework"},{id:"eclipse-gyrex",content:"Eclipse Gyrex"},{id:"goldenorb",content:"GoldenOrb"},{id:"juju",content:"Juju"},{id:"katta",content:"Katta"},{id:"keptcollections",content:"KeptCollections"},{id:"neo4j",content:"Neo4j"},{id:"norbert",content:"Norbert"},{id:"spring-cloud-zookeeper",content:"spring-cloud-zookeeper"},{id:"spring-statemachine",content:"spring-statemachine"},{id:"spring-xd",content:"spring-xd"},{id:"talend-esb",content:"Talend ESB"},{id:"redis_failover",content:"redis_failover"},{id:"apache-projects",content:"Apache Projects"},{id:"apache-accumulo",content:"Apache Accumulo"},{id:"apache-atlas",content:"Apache Atlas"},{id:"apache-bookkeeper",content:"Apache BookKeeper"},{id:"apache-cxf-dosgi",content:"Apache CXF DOSGi"},{id:"apache-drill",content:"Apache Drill"},{id:"apache-druid",content:"Apache Druid"},{id:"apache-dubbo",content:"Apache Dubbo"},{id:"apache-flink",content:"Apache Flink"},{id:"apache-flume",content:"Apache Flume"},{id:"apache-fluo",content:"Apache Fluo"},{id:"apache-griffin",content:"Apache Griffin"},{id:"apache-hadoop",content:"Apache Hadoop"},{id:"apache-hbase",content:"Apache HBase"},{id:"apache-helix",content:"Apache Helix"},{id:"apache-hive",content:"Apache Hive"},{id:"apache-ignite",content:"Apache Ignite"},{id:"apache-james-mailbox",content:"Apache James Mailbox"},{id:"apache-kafka",content:"Apache Kafka"},{id:"apache-kylin",content:"Apache Kylin"},{id:"apache-mesos",content:"Apache Mesos"},{id:"apache-oozie",content:"Apache Oozie"},{id:"apache-pulsar",content:"Apache Pulsar"},{id:"apache-solr",content:"Apache Solr"},{id:"apache-spark",content:"Apache Spark"},{id:"apache-storm",content:"Apache Storm"},{id:"companies",content:"Companies"},{id:"ageto",content:"AGETO"},{id:"benipal-technologies",content:"Benipal Technologies"},{id:"box",content:"Box"},{id:"deepdyve",content:"Deepdyve"},{id:"facebook",content:"Facebook"},{id:"idium-portal",content:"Idium Portal"},{id:"makara",content:"Makara"},{id:"midokura",content:"Midokura"},{id:"pinterest",content:"Pinterest"},{id:"rackspace",content:"Rackspace"},{id:"sematext",content:"Sematext"},{id:"tubemogul",content:"Tubemogul"},{id:"twitter",content:"Twitter"},{id:"vastcom",content:"Vast.com"},{id:"wealthfront",content:"Wealthfront"},{id:"yahoo",content:"Yahoo!"},{id:"zynga",content:"Zynga"}]};const l=[{depth:2,url:"#free-software-projects",title:e.jsx(e.Fragment,{children:"Free Software Projects"})},{depth:3,url:"#adroitlogic-ultraesb",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://adroitlogic.org/",children:"AdroitLogic UltraESB"})})},{depth:3,url:"#akka",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://akka.io/",children:"Akka"})})},{depth:3,url:"#eclipse-communication-framework",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://www.eclipse.org/ecf",children:"Eclipse Communication Framework"})})},{depth:3,url:"#eclipse-gyrex",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://www.eclipse.org/gyrex",children:"Eclipse Gyrex"})})},{depth:3,url:"#goldenorb",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://www.goldenorbos.org/",children:"GoldenOrb"})})},{depth:3,url:"#juju",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://juju.ubuntu.com/",children:"Juju"})})},{depth:3,url:"#katta",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://katta.sourceforge.net/",children:"Katta"})})},{depth:3,url:"#keptcollections",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://github.com/anthonyu/KeptCollections",children:"KeptCollections"})})},{depth:3,url:"#neo4j",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://neo4j.com/",children:"Neo4j"})})},{depth:3,url:"#norbert",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://sna-projects.com/norbert",children:"Norbert"})})},{depth:3,url:"#spring-cloud-zookeeper",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://spring.io/projects/spring-cloud-zookeeper",children:"spring-cloud-zookeeper"})})},{depth:3,url:"#spring-statemachine",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://projects.spring.io/spring-statemachine/",children:"spring-statemachine"})})},{depth:3,url:"#spring-xd",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://projects.spring.io/spring-xd/",children:"spring-xd"})})},{depth:3,url:"#talend-esb",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://www.talend.com/products-application-integration/application-integration-esb-se.php",children:"Talend ESB"})})},{depth:3,url:"#redis_failover",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://github.com/ryanlecompte/redis_failover",children:"redis_failover"})})},{depth:2,url:"#apache-projects",title:e.jsx(e.Fragment,{children:"Apache Projects"})},{depth:3,url:"#apache-accumulo",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://accumulo.apache.org/",children:"Apache Accumulo"})})},{depth:3,url:"#apache-atlas",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://atlas.apache.org",children:"Apache Atlas"})})},{depth:3,url:"#apache-bookkeeper",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://bookkeeper.apache.org/",children:"Apache BookKeeper"})})},{depth:3,url:"#apache-cxf-dosgi",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://cxf.apache.org/distributed-osgi.html",children:"Apache CXF DOSGi"})})},{depth:3,url:"#apache-drill",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://drill.apache.org/",children:"Apache Drill"})})},{depth:3,url:"#apache-druid",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://druid.apache.org/",children:"Apache Druid"})})},{depth:3,url:"#apache-dubbo",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://dubbo.apache.org",children:"Apache Dubbo"})})},{depth:3,url:"#apache-flink",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://flink.apache.org/",children:"Apache Flink"})})},{depth:3,url:"#apache-flume",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://flume.apache.org/",children:"Apache Flume"})})},{depth:3,url:"#apache-fluo",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://fluo.apache.org/",children:"Apache Fluo"})})},{depth:3,url:"#apache-griffin",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://griffin.apache.org/",children:"Apache Griffin"})})},{depth:3,url:"#apache-hadoop",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://hadoop.apache.org/",children:"Apache Hadoop"})})},{depth:3,url:"#apache-hbase",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://hbase.apache.org/",children:"Apache HBase"})})},{depth:3,url:"#apache-helix",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://helix.apache.org/",children:"Apache Helix"})})},{depth:3,url:"#apache-hive",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://hive.apache.org",children:"Apache Hive"})})},{depth:3,url:"#apache-ignite",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://ignite.apache.org/",children:"Apache Ignite"})})},{depth:3,url:"#apache-james-mailbox",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://james.apache.org/mailbox/",children:"Apache James Mailbox"})})},{depth:3,url:"#apache-kafka",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://kafka.apache.org/",children:"Apache Kafka"})})},{depth:3,url:"#apache-kylin",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://kylin.apache.org/",children:"Apache Kylin"})})},{depth:3,url:"#apache-mesos",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://mesos.apache.org/",children:"Apache Mesos"})})},{depth:3,url:"#apache-oozie",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://oozie.apache.org",children:"Apache Oozie"})})},{depth:3,url:"#apache-pulsar",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://pulsar.apache.org",children:"Apache Pulsar"})})},{depth:3,url:"#apache-solr",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://lucene.apache.org/solr/",children:"Apache Solr"})})},{depth:3,url:"#apache-spark",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://spark.apache.org/",children:"Apache Spark"})})},{depth:3,url:"#apache-storm",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://storm.apache.org",children:"Apache Storm"})})},{depth:2,url:"#companies",title:e.jsx(e.Fragment,{children:"Companies"})},{depth:3,url:"#ageto",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://www.ageto.de/",children:"AGETO"})})},{depth:3,url:"#benipal-technologies",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://www.benipaltechnologies.com/",children:"Benipal Technologies"})})},{depth:3,url:"#box",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://box.net/",children:"Box"})})},{depth:3,url:"#deepdyve",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://www.deepdyve.com/",children:"Deepdyve"})})},{depth:3,url:"#facebook",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://www.facebook.com/",children:"Facebook"})})},{depth:3,url:"#idium-portal",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://www.idium.no/no/idium_portal/",children:"Idium Portal"})})},{depth:3,url:"#makara",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://www.makara.com/",children:"Makara"})})},{depth:3,url:"#midokura",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://www.midokura.com/",children:"Midokura"})})},{depth:3,url:"#pinterest",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://www.pinterest.com/",children:"Pinterest"})})},{depth:3,url:"#rackspace",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://www.rackspace.com/email_hosting",children:"Rackspace"})})},{depth:3,url:"#sematext",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://sematext.com/",children:"Sematext"})})},{depth:3,url:"#tubemogul",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://tubemogul.com/",children:"Tubemogul"})})},{depth:3,url:"#twitter",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"https://twitter.com/",children:"Twitter"})})},{depth:3,url:"#vastcom",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://www.vast.com/",children:"Vast.com"})})},{depth:3,url:"#wealthfront",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://wealthfront.com/",children:"Wealthfront"})})},{depth:3,url:"#yahoo",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://www.yahoo.com/",children:"Yahoo!"})})},{depth:3,url:"#zynga",title:e.jsx(e.Fragment,{children:e.jsx("a",{href:"http://www.zynga.com/",children:"Zynga"})})},{depth:2,url:"#footnote-label",title:e.jsx(e.Fragment,{children:"Footnotes"})}];function n(a){const t={a:"a",h2:"h2",h3:"h3",li:"li",ol:"ol",p:"p",section:"section",strong:"strong",sup:"sup",ul:"ul",...a.components};return e.jsxs(e.Fragment,{children:[e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["Applications and organizations using ZooKeeper include (alphabetically) ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`,e.jsxs(t.li,{children:["If your use case wants to be listed here. Please do not hesitate, submit a pull request or write an email to ",e.jsx(t.strong,{children:e.jsx(t.a,{href:"mailto:dev@zookeeper.apache.org",children:"dev@zookeeper.apache.org"})}),`,
and then, your use case will be included.`]}),`
`,e.jsxs(t.li,{children:["If this documentation has violated your intellectual property rights or you and your company's privacy, write an email to ",e.jsx(t.strong,{children:e.jsx(t.a,{href:"mailto:dev@zookeeper.apache.org",children:"dev@zookeeper.apache.org"})}),`,
we will handle them in a timely manner.`]}),`
`]}),`
`,e.jsx(t.h2,{id:"free-software-projects",children:"Free Software Projects"}),`
`,e.jsx(t.h3,{id:"adroitlogic-ultraesb",children:e.jsx(t.a,{href:"http://adroitlogic.org/",children:"AdroitLogic UltraESB"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`Uses ZooKeeper to implement node coordination, in clustering support. This allows the management of the complete cluster,
or any specific node - from any other node connected via JMX. A Cluster wide command framework developed on top of the
ZooKeeper coordination allows commands that fail on some nodes to be retried etc. We also support the automated graceful
round-robin-restart of a complete cluster of nodes using the same framework `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-2","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"akka",children:e.jsx(t.a,{href:"http://akka.io/",children:"Akka"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`Akka is the platform for the next generation event-driven, scalable and fault-tolerant architectures on the JVM.
Or: Akka is a toolkit and runtime for building highly concurrent, distributed, and fault tolerant event-driven applications on the JVM `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-3","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"eclipse-communication-framework",children:e.jsx(t.a,{href:"http://www.eclipse.org/ecf",children:"Eclipse Communication Framework"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`The Eclipse ECF project provides an implementation of its Abstract Discovery services using Zookeeper. ECF itself
is used in many projects providing base functionality for communication, all based on OSGi `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-4","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"eclipse-gyrex",children:e.jsx(t.a,{href:"http://www.eclipse.org/gyrex",children:"Eclipse Gyrex"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"The Eclipse Gyrex project provides a platform for building your own Java OSGi based clouds."}),`
`,e.jsxs(t.li,{children:[`ZooKeeper is used as the core cloud component for node membership and management, coordination of jobs executing among workers,
a lock service and a simple queue service and a lot more `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-5","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"goldenorb",children:e.jsx(t.a,{href:"http://www.goldenorbos.org/",children:"GoldenOrb"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["massive-scale Graph analysis ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-6","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"juju",children:e.jsx(t.a,{href:"https://juju.ubuntu.com/",children:"Juju"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["Service deployment and orchestration framework, formerly called Ensemble ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-7","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"katta",children:e.jsx(t.a,{href:"http://katta.sourceforge.net/",children:"Katta"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Katta serves distributed Lucene indexes in a grid environment."}),`
`,e.jsxs(t.li,{children:["Zookeeper is used for node, master and index management in the grid ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-8","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"keptcollections",children:e.jsx(t.a,{href:"https://github.com/anthonyu/KeptCollections",children:"KeptCollections"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"KeptCollections is a library of drop-in replacements for the data structures in the Java Collections framework."}),`
`,e.jsxs(t.li,{children:["KeptCollections uses Apache ZooKeeper as a backing store, thus making its data structures distributed and scalable ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-9","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"neo4j",children:e.jsx(t.a,{href:"https://neo4j.com/",children:"Neo4j"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:`Neo4j is a Graph Database. It's a disk based, ACID compliant transactional storage engine for big graphs and fast graph traversals,
using external indices like Lucene/Solr for global searches.`}),`
`,e.jsxs(t.li,{children:[`We use ZooKeeper in the Neo4j High Availability components for write-master election,
read slave coordination and other cool stuff. ZooKeeper is a great and focused project - we like! `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-10","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"norbert",children:e.jsx(t.a,{href:"http://sna-projects.com/norbert",children:"Norbert"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["Partitioned routing and cluster management ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-11","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"spring-cloud-zookeeper",children:e.jsx(t.a,{href:"https://spring.io/projects/spring-cloud-zookeeper",children:"spring-cloud-zookeeper"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`Spring Cloud Zookeeper provides Apache Zookeeper integrations for Spring Boot apps through autoconfiguration
and binding to the Spring Environment and other Spring programming model idioms. With a few simple annotations
you can quickly enable and configure the common patterns inside your application and build large distributed systems with Zookeeper.
The patterns provided include Service Discovery and Distributed Configuration `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-38",id:"user-content-fnref-38","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"2"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"spring-statemachine",children:e.jsx(t.a,{href:"https://projects.spring.io/spring-statemachine/",children:"spring-statemachine"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Spring Statemachine is a framework for application developers to use state machine concepts with Spring applications."}),`
`,e.jsx(t.li,{children:"Spring Statemachine can provide this feature:Distributed state machine based on a Zookeeper [31,32]."}),`
`]}),`
`,e.jsx(t.h3,{id:"spring-xd",children:e.jsx(t.a,{href:"https://projects.spring.io/spring-xd/",children:"spring-xd"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:`Spring XD is a unified, distributed, and extensible system for data ingestion, real time analytics, batch processing, and data export.
The project’s goal is to simplify the development of big data applications.`}),`
`,e.jsx(t.li,{children:`ZooKeeper - Provides all runtime information for the XD cluster. Tracks running containers, in which containers modules
and jobs are deployed, stream definitions, deployment manifests, and the like [30,31].`}),`
`]}),`
`,e.jsx(t.h3,{id:"talend-esb",children:e.jsx(t.a,{href:"http://www.talend.com/products-application-integration/application-integration-esb-se.php",children:"Talend ESB"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Talend ESB is a versatile and flexible, enterprise service bus."}),`
`,e.jsxs(t.li,{children:[`It uses ZooKeeper as endpoint repository of both REST and SOAP Web services.
By using ZooKeeper Talend ESB is able to provide failover and load balancing capabilities in a very light-weight manner `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-12","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"redis_failover",children:e.jsx(t.a,{href:"https://github.com/ryanlecompte/redis_failover",children:"redis_failover"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["Redis Failover is a ZooKeeper-based automatic master/slave failover solution for Ruby ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-13","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h2,{id:"apache-projects",children:"Apache Projects"}),`
`,e.jsx(t.h3,{id:"apache-accumulo",children:e.jsx(t.a,{href:"https://accumulo.apache.org/",children:"Apache Accumulo"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Accumulo is a distributed key/value store that provides expressive, cell-level access labels."}),`
`,e.jsxs(t.li,{children:[`Apache ZooKeeper plays a central role within the Accumulo architecture. Its quorum consistency model supports an overall
Accumulo architecture with no single points of failure. Beyond that, Accumulo leverages ZooKeeper to store and communication
configuration information for users and tables, as well as operational states of processes and tablets `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-2",id:"user-content-fnref-2","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"3"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-atlas",children:e.jsx(t.a,{href:"http://atlas.apache.org",children:"Apache Atlas"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:`Atlas is a scalable and extensible set of core foundational governance services – enabling enterprises to effectively and efficiently meet
their compliance requirements within Hadoop and allows integration with the whole enterprise data ecosystem.`}),`
`,e.jsx(t.li,{children:"Atlas uses Zookeeper for coordination to provide redundancy and high availability of HBase,Kafka [31,35]."}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-bookkeeper",children:e.jsx(t.a,{href:"https://bookkeeper.apache.org/",children:"Apache BookKeeper"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"A scalable, fault-tolerant, and low-latency storage service optimized for real-time workloads."}),`
`,e.jsxs(t.li,{children:[`BookKeeper requires a metadata storage service to store information related to ledgers and available bookies. BookKeeper currently uses
ZooKeeper for this and other tasks `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-3",id:"user-content-fnref-3","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"4"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-cxf-dosgi",children:e.jsx(t.a,{href:"http://cxf.apache.org/distributed-osgi.html",children:"Apache CXF DOSGi"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:`Apache CXF is an open source services framework. CXF helps you build and develop services using frontend programming
APIs, like JAX-WS and JAX-RS. These services can speak a variety of protocols such as SOAP, XML/HTTP, RESTful HTTP,
or CORBA and work over a variety of transports such as HTTP, JMS or JBI.`}),`
`,e.jsxs(t.li,{children:["The Distributed OSGi implementation at Apache CXF uses ZooKeeper for its Discovery functionality ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-4",id:"user-content-fnref-4","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"5"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-drill",children:e.jsx(t.a,{href:"http://drill.apache.org/",children:"Apache Drill"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Schema-free SQL Query Engine for Hadoop, NoSQL and Cloud Storage"}),`
`,e.jsxs(t.li,{children:[`ZooKeeper maintains ephemeral cluster membership information. The Drillbits use ZooKeeper to find other Drillbits in the cluster,
and the client uses ZooKeeper to find Drillbits to submit a query `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-28",id:"user-content-fnref-28","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"6"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-druid",children:e.jsx(t.a,{href:"https://druid.apache.org/",children:"Apache Druid"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Apache Druid is a high performance real-time analytics database."}),`
`,e.jsxs(t.li,{children:["Apache Druid uses Apache ZooKeeper (ZK) for management of current cluster state. The operations that happen over ZK are ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-27",id:"user-content-fnref-27","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"7"})}),":",`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Coordinator leader election"}),`
`,e.jsx(t.li,{children:'Segment "publishing" protocol from Historical and Realtime'}),`
`,e.jsx(t.li,{children:"Segment load/drop protocol between Coordinator and Historical"}),`
`,e.jsx(t.li,{children:"Overlord leader election"}),`
`,e.jsx(t.li,{children:"Overlord and MiddleManager task management"}),`
`]}),`
`]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-dubbo",children:e.jsx(t.a,{href:"http://dubbo.apache.org",children:"Apache Dubbo"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Apache Dubbo is a high-performance, java based open source RPC framework."}),`
`,e.jsxs(t.li,{children:["Zookeeper is used for service registration discovery and configuration management in Dubbo ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-6",id:"user-content-fnref-6","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"8"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-flink",children:e.jsx(t.a,{href:"https://flink.apache.org/",children:"Apache Flink"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:`Apache Flink is a framework and distributed processing engine for stateful computations over unbounded and bounded data streams.
Flink has been designed to run in all common cluster environments, perform computations at in-memory speed and at any scale.`}),`
`,e.jsxs(t.li,{children:[`To enable JobManager High Availability you have to set the high-availability mode to zookeeper, configure a ZooKeeper quorum and set up a masters file with all JobManagers hosts and their web UI ports.
Flink leverages ZooKeeper for distributed coordination between all running JobManager instances. ZooKeeper is a separate service from Flink,
which provides highly reliable distributed coordination via leader election and light-weight consistent state storage `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-23",id:"user-content-fnref-23","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"9"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-flume",children:e.jsx(t.a,{href:"https://flume.apache.org/",children:"Apache Flume"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`
`,e.jsx(t.p,{children:`Flume is a distributed, reliable, and available service for efficiently collecting, aggregating, and moving large amounts
of log data. It has a simple and flexible architecture based on streaming data flows. It is robust and fault tolerant
with tunable reliability mechanisms and many failover and recovery mechanisms. It uses a simple extensible data model
that allows for online analytic application.`}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsxs(t.p,{children:["Flume supports Agent configurations via Zookeeper. This is an experimental feature ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-5",id:"user-content-fnref-5","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"10"})}),"."]}),`
`]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-fluo",children:e.jsx(t.a,{href:"https://fluo.apache.org/",children:"Apache Fluo"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Apache Fluo is a distributed processing system that lets users make incremental updates to large data sets."}),`
`,e.jsx(t.li,{children:"Apache Fluo is built on Apache Accumulo which uses Apache Zookeeper for consensus [31,37]."}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-griffin",children:e.jsx(t.a,{href:"https://griffin.apache.org/",children:"Apache Griffin"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Big Data Quality Solution For Batch and Streaming."}),`
`,e.jsx(t.li,{children:"Griffin uses Zookeeper for coordination to provide redundancy and high availability of Kafka [31,36]."}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-hadoop",children:e.jsx(t.a,{href:"http://hadoop.apache.org/",children:"Apache Hadoop"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`
`,e.jsx(t.p,{children:`The Apache Hadoop software library is a framework that allows for the distributed processing of large data sets across
clusters of computers using simple programming models. It is designed to scale up from single servers to thousands of machines,
each offering local computation and storage. Rather than rely on hardware to deliver high-availability,
the library itself is designed to detect and handle failures at the application layer, so delivering a highly-available service on top of a cluster of computers, each of which may be prone to failures.`}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsx(t.p,{children:"The implementation of automatic HDFS failover relies on ZooKeeper for the following things:"}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Failure detection"}),` - each of the NameNode machines in the cluster maintains a persistent session in ZooKeeper.
If the machine crashes, the ZooKeeper session will expire, notifying the other NameNode that a failover should be triggered.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Active NameNode election"}),` - ZooKeeper provides a simple mechanism to exclusively elect a node as active. If the current active NameNode crashes,
another node may take a special exclusive lock in ZooKeeper indicating that it should become the next active.`]}),`
`]}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsx(t.p,{children:`The ZKFailoverController (ZKFC) is a new component which is a ZooKeeper client which also monitors and manages the state of the NameNode.
Each of the machines which runs a NameNode also runs a ZKFC, and that ZKFC is responsible for:`}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Health monitoring"}),` - the ZKFC pings its local NameNode on a periodic basis with a health-check command.
So long as the NameNode responds in a timely fashion with a healthy status, the ZKFC considers the node healthy.
If the node has crashed, frozen, or otherwise entered an unhealthy state, the health monitor will mark it as unhealthy.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"ZooKeeper session management"}),` - when the local NameNode is healthy, the ZKFC holds a session open in ZooKeeper.
If the local NameNode is active, it also holds a special “lock” znode. This lock uses ZooKeeper’s support for “ephemeral” nodes;
if the session expires, the lock node will be automatically deleted.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"ZooKeeper-based election"}),` - if the local NameNode is healthy, and the ZKFC sees that no other node currently holds the lock znode,
it will itself try to acquire the lock. If it succeeds, then it has “won the election”, and is responsible for running a failover to make its local NameNode active.
The failover process is similar to the manual failover described above: first, the previous active is fenced if necessary,
and then the local NameNode transitions to active state `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-7",id:"user-content-fnref-7","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"11"})}),"."]}),`
`]}),`
`]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-hbase",children:e.jsx(t.a,{href:"https://hbase.apache.org/",children:"Apache HBase"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"HBase is the Hadoop database. It's an open-source, distributed, column-oriented store model."}),`
`,e.jsxs(t.li,{children:[`HBase uses ZooKeeper for master election, server lease management, bootstrapping, and coordination between servers.
A distributed Apache HBase installation depends on a running ZooKeeper cluster. All participating nodes and clients
need to be able to access the running ZooKeeper ensemble `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-8",id:"user-content-fnref-8","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"12"})}),"."]}),`
`,e.jsxs(t.li,{children:[`As you can see, ZooKeeper is a fundamental part of HBase. All operations that require coordination, such as Regions
assignment, Master-Failover, replication, and snapshots, are built on ZooKeeper `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-20",id:"user-content-fnref-20","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"13"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-helix",children:e.jsx(t.a,{href:"http://helix.apache.org/",children:"Apache Helix"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"A cluster management framework for partitioned and replicated distributed resources."}),`
`,e.jsxs(t.li,{children:[`We need a distributed store to maintain the state of the cluster and a notification system to notify if there is any change in the cluster state.
Helix uses Apache ZooKeeper to achieve this functionality `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-21",id:"user-content-fnref-21","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"14"})}),`.
Zookeeper provides:`]}),`
`,e.jsx(t.li,{children:"A way to represent PERSISTENT state which remains until its deleted"}),`
`,e.jsx(t.li,{children:"A way to represent TRANSIENT/EPHEMERAL state which vanishes when the process that created the state dies"}),`
`,e.jsx(t.li,{children:"A notification mechanism when there is a change in PERSISTENT and EPHEMERAL state"}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-hive",children:e.jsx(t.a,{href:"https://hive.apache.org",children:"Apache Hive"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:`The Apache Hive data warehouse software facilitates reading, writing, and managing large datasets residing in distributed
storage using SQL. Structure can be projected onto data already in storage. A command line tool and JDBC driver are provided to connect users to Hive.`}),`
`,e.jsx(t.li,{children:"Hive has been using ZooKeeper as distributed lock manager to support concurrency in HiveServer2 [25,26]."}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-ignite",children:e.jsx(t.a,{href:"https://ignite.apache.org/",children:"Apache Ignite"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:`Ignite is a memory-centric distributed database, caching, and processing platform for
transactional, analytical, and streaming workloads delivering in-memory speeds at petabyte scale`}),`
`,e.jsx(t.li,{children:`Apache Ignite discovery mechanism goes with a ZooKeeper implementations which allows scaling Ignite clusters to 100s and 1000s of nodes
preserving linear scalability and performance [31,34].​`}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-james-mailbox",children:e.jsx(t.a,{href:"http://james.apache.org/mailbox/",children:"Apache James Mailbox"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:`The Apache James Mailbox is a library providing a flexible Mailbox storage accessible by mail protocols
(IMAP4, POP3, SMTP,...) and other protocols.`}),`
`,e.jsxs(t.li,{children:["Uses Zookeeper and Curator Framework for generating distributed unique ID's ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-31",id:"user-content-fnref-31","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"15"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-kafka",children:e.jsx(t.a,{href:"https://kafka.apache.org/",children:"Apache Kafka"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Kafka is a distributed publish/subscribe messaging system"}),`
`,e.jsxs(t.li,{children:["Apache Kafka relies on ZooKeeper for the following things:",`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Controller election"}),`
The controller is one of the most important broking entity in a Kafka ecosystem, and it also has the responsibility
to maintain the leader-follower relationship across all the partitions. If a node by some reason is shutting down,
it’s the controller’s responsibility to tell all the replicas to act as partition leaders in order to fulfill the
duties of the partition leaders on the node that is about to fail. So, whenever a node shuts down, a new controller
can be elected and it can also be made sure that at any given time, there is only one controller and all the follower nodes have agreed on that.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Configuration Of Topics"}),`
The configuration regarding all the topics including the list of existing topics, the number of partitions for each topic,
the location of all the replicas, list of configuration overrides for all topics and which node is the preferred leader, etc.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Access control lists"}),`
Access control lists or ACLs for all the topics are also maintained within Zookeeper.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Membership of the cluster"}),`
Zookeeper also maintains a list of all the brokers that are functioning at any given moment and are a part of the cluster `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-9",id:"user-content-fnref-9","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"16"})}),"."]}),`
`]}),`
`]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-kylin",children:e.jsx(t.a,{href:"http://kylin.apache.org/",children:"Apache Kylin"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:`Apache Kylin is an open source Distributed Analytics Engine designed to provide SQL interface and multi-dimensional analysis (OLAP) on Hadoop/Spark supporting extremely large datasets,
original contributed from eBay Inc.`}),`
`,e.jsx(t.li,{children:"Apache Kylin leverages Zookeeper for job coordination [31,33]."}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-mesos",children:e.jsx(t.a,{href:"http://mesos.apache.org/",children:"Apache Mesos"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:`Apache Mesos abstracts CPU, memory, storage, and other compute resources away from machines (physical or virtual),
enabling fault-tolerant and elastic distributed systems to easily be built and run effectively.`}),`
`,e.jsxs(t.li,{children:[`Mesos has a high-availability mode that uses multiple Mesos masters: one active master (called the leader or leading master)
and several backups in case it fails. The masters elect the leader, with Apache ZooKeeper both coordinating the election
and handling leader detection by masters, agents, and scheduler drivers `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-10",id:"user-content-fnref-10","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"17"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-oozie",children:e.jsx(t.a,{href:"https://oozie.apache.org",children:"Apache Oozie"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Oozie is a workflow scheduler system to manage Apache Hadoop jobs."}),`
`,e.jsxs(t.li,{children:[`the Oozie servers use it for coordinating access to the database and communicating with each other. In order to have full HA,
there should be at least 3 ZooKeeper servers `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-29",id:"user-content-fnref-29","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"18"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-pulsar",children:e.jsx(t.a,{href:"https://pulsar.apache.org",children:"Apache Pulsar"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Apache Pulsar is an open-source distributed pub-sub messaging system originally created at Yahoo and now part of the Apache Software Foundation"}),`
`,e.jsxs(t.li,{children:["Pulsar uses Apache Zookeeper for metadata storage, cluster configuration, and coordination. In a Pulsar instance:",`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"A configuration store quorum stores configuration for tenants, namespaces, and other entities that need to be globally consistent."}),`
`,e.jsxs(t.li,{children:[`Each cluster has its own local ZooKeeper ensemble that stores cluster-specific configuration and coordination such as ownership metadata,
broker load reports, BookKeeper ledger metadata, and more `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-24",id:"user-content-fnref-24","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"19"})}),"."]}),`
`]}),`
`]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-solr",children:e.jsx(t.a,{href:"https://lucene.apache.org/solr/",children:"Apache Solr"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Solr is the popular, blazing-fast, open source enterprise search platform built on Apache Lucene."}),`
`,e.jsx(t.li,{children:`In the "Cloud" edition (v4.x and up) of enterprise search engine Apache Solr, ZooKeeper is used for configuration,
leader election and more [12,13].`}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-spark",children:e.jsx(t.a,{href:"https://spark.apache.org/",children:"Apache Spark"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Apache Spark is a unified analytics engine for large-scale data processing."}),`
`,e.jsxs(t.li,{children:[`Utilizing ZooKeeper to provide leader election and some state storage, you can launch multiple Masters in your cluster connected to the same ZooKeeper instance.
One will be elected “leader” and the others will remain in standby mode. If the current leader dies, another Master will be elected,
recover the old Master’s state, and then resume scheduling `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-14",id:"user-content-fnref-14","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"20"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"apache-storm",children:e.jsx(t.a,{href:"http://storm.apache.org",children:"Apache Storm"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:`Apache Storm is a free and open source distributed realtime computation system. Apache Storm makes it easy to reliably
process unbounded streams of data, doing for realtime processing what Hadoop did for batch processing.
Apache Storm is simple, can be used with any programming language, and is a lot of fun to use!`}),`
`,e.jsxs(t.li,{children:["Storm uses Zookeeper for coordinating the cluster ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-22",id:"user-content-fnref-22","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"21"})}),"."]}),`
`]}),`
`,e.jsx(t.h2,{id:"companies",children:"Companies"}),`
`,e.jsx(t.h3,{id:"ageto",children:e.jsx(t.a,{href:"http://www.ageto.de/",children:"AGETO"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["The AGETO RnD team uses ZooKeeper in a variety of internal as well as external consulting projects ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-14","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"benipal-technologies",children:e.jsx(t.a,{href:"http://www.benipaltechnologies.com/",children:"Benipal Technologies"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["ZooKeeper is used for internal application development with Solr and Hadoop with Hbase ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-15","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"box",children:e.jsx(t.a,{href:"http://box.net/",children:"Box"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["Box uses ZooKeeper for service discovery, service coordination, Solr and Hadoop support, etc ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-16","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"deepdyve",children:e.jsx(t.a,{href:"http://www.deepdyve.com/",children:"Deepdyve"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`We do search for research and provide access to high quality content using advanced search technologies Zookeeper is used to
manage server state, control index deployment and a myriad other tasks `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-17","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"facebook",children:e.jsx(t.a,{href:"https://www.facebook.com/",children:"Facebook"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:`Facebook uses the Zeus ([17,18]) for configuration management which is a forked version of ZooKeeper, with many scalability
and performance en- hancements in order to work at the Facebook scale.
It runs a consensus protocol among servers distributed across mul- tiple regions for resilience. If the leader fails,
a follower is converted into a new leader.`}),`
`]}),`
`,e.jsx(t.h3,{id:"idium-portal",children:e.jsx(t.a,{href:"http://www.idium.no/no/idium_portal/",children:"Idium Portal"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Idium Portal is a hosted web-publishing system delivered by Norwegian company, Idium AS."}),`
`,e.jsxs(t.li,{children:["ZooKeeper is used for cluster messaging, service bootstrapping, and service coordination ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-18","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"makara",children:e.jsx(t.a,{href:"http://www.makara.com/",children:"Makara"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"Using ZooKeeper on 2-node cluster on VMware workstation, Amazon EC2, Zen"}),`
`,e.jsx(t.li,{children:"Using zkpython"}),`
`,e.jsxs(t.li,{children:["Looking into expanding into 100 node cluster ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-19","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"midokura",children:e.jsx(t.a,{href:"http://www.midokura.com/",children:"Midokura"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["We do virtualized networking for the cloud computing era. We use ZooKeeper for various aspects of our distributed control plane ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-20","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"pinterest",children:e.jsx(t.a,{href:"https://www.pinterest.com/",children:"Pinterest"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`Pinterest uses the ZooKeeper for Service discovery and dynamic configuration.Like many large scale web sites, Pinterest’s infrastructure consists of servers that communicate with
backend services composed of a number of individual servers for managing load and fault tolerance. Ideally, we’d like the configuration to reflect only the active hosts,
so clients don’t need to deal with bad hosts as often. ZooKeeper provides a well known pattern to solve this problem `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-19",id:"user-content-fnref-19","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"22"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"rackspace",children:e.jsx(t.a,{href:"http://www.rackspace.com/email_hosting",children:"Rackspace"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`The Email & Apps team uses ZooKeeper to coordinate sharding and responsibility changes in a distributed e-mail client
that pulls and indexes data for search. ZooKeeper also provides distributed locking for connections to prevent a cluster from overwhelming servers `,e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-21","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"sematext",children:e.jsx(t.a,{href:"http://sematext.com/",children:"Sematext"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["Uses ZooKeeper in SPM (which includes ZooKeeper monitoring component, too!), Search Analytics, and Logsene ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-22","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"tubemogul",children:e.jsx(t.a,{href:"http://tubemogul.com/",children:"Tubemogul"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["Uses ZooKeeper for leader election, configuration management, locking, group membership ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-23","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"twitter",children:e.jsx(t.a,{href:"https://twitter.com/",children:"Twitter"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`ZooKeeper is used at Twitter as the source of truth for storing critical metadata. It serves as a coordination kernel to
provide distributed coordination services, such as leader election and distributed locking.
Some concrete examples of ZooKeeper in action include [15,16]:`,`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:"ZooKeeper is used to store service registry, which is used by Twitter’s naming service for service discovery."}),`
`,e.jsx(t.li,{children:`Manhattan (Twitter’s in-house key-value database), Nighthawk (sharded Redis), and Blobstore (in-house photo and video storage),
stores its cluster topology information in ZooKeeper.`}),`
`,e.jsx(t.li,{children:"EventBus, Twitter’s pub-sub messaging system, stores critical metadata in ZooKeeper and uses ZooKeeper for leader election."}),`
`,e.jsx(t.li,{children:"Mesos, Twitter’s compute platform, uses ZooKeeper for leader election."}),`
`]}),`
`]}),`
`]}),`
`,e.jsx(t.h3,{id:"vastcom",children:e.jsx(t.a,{href:"http://www.vast.com/",children:"Vast.com"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["Used internally as a part of sharding services, distributed synchronization of data/index updates, configuration management and failover support ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-24","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"wealthfront",children:e.jsx(t.a,{href:"http://wealthfront.com/",children:"Wealthfront"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`Wealthfront uses ZooKeeper for service discovery, leader election and distributed locking among its many backend services.
ZK is an essential part of Wealthfront's continuous `,e.jsx(t.a,{href:"http://eng.wealthfront.com/2010/05/02/deployment-infrastructure-for-continuous-deployment/",children:"deployment infrastructure"})," ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-25","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"yahoo",children:e.jsx(t.a,{href:"http://www.yahoo.com/",children:"Yahoo!"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["ZooKeeper is used for a myriad of services inside Yahoo! for doing leader election, configuration management, sharding, locking, group membership etc ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-26","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsx(t.h3,{id:"zynga",children:e.jsx(t.a,{href:"http://www.zynga.com/",children:"Zynga"})}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["ZooKeeper at Zynga is used for a variety of services including configuration management, leader election, sharding and more ",e.jsx(t.sup,{children:e.jsx(t.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-27","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),"."]}),`
`]}),`
`,e.jsxs(t.section,{"data-footnotes":!0,className:"footnotes",children:[e.jsx(t.h2,{className:"sr-only",id:"footnote-label",children:"Footnotes"}),`
`,e.jsxs(t.ol,{children:[`
`,e.jsxs(t.li,{id:"user-content-fn-1",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/PoweredBy",children:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/PoweredBy"})," ",e.jsx(t.a,{href:"#user-content-fnref-1","data-footnote-backref":"","aria-label":"Back to reference 1",className:"data-footnote-backref",children:"↩"})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-2","data-footnote-backref":"","aria-label":"Back to reference 1-2",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"2"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-3","data-footnote-backref":"","aria-label":"Back to reference 1-3",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"3"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-4","data-footnote-backref":"","aria-label":"Back to reference 1-4",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"4"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-5","data-footnote-backref":"","aria-label":"Back to reference 1-5",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"5"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-6","data-footnote-backref":"","aria-label":"Back to reference 1-6",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"6"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-7","data-footnote-backref":"","aria-label":"Back to reference 1-7",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"7"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-8","data-footnote-backref":"","aria-label":"Back to reference 1-8",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"8"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-9","data-footnote-backref":"","aria-label":"Back to reference 1-9",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"9"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-10","data-footnote-backref":"","aria-label":"Back to reference 1-10",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"10"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-11","data-footnote-backref":"","aria-label":"Back to reference 1-11",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"11"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-12","data-footnote-backref":"","aria-label":"Back to reference 1-12",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"12"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-13","data-footnote-backref":"","aria-label":"Back to reference 1-13",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"13"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-14","data-footnote-backref":"","aria-label":"Back to reference 1-14",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"14"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-15","data-footnote-backref":"","aria-label":"Back to reference 1-15",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"15"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-16","data-footnote-backref":"","aria-label":"Back to reference 1-16",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"16"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-17","data-footnote-backref":"","aria-label":"Back to reference 1-17",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"17"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-18","data-footnote-backref":"","aria-label":"Back to reference 1-18",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"18"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-19","data-footnote-backref":"","aria-label":"Back to reference 1-19",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"19"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-20","data-footnote-backref":"","aria-label":"Back to reference 1-20",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"20"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-21","data-footnote-backref":"","aria-label":"Back to reference 1-21",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"21"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-22","data-footnote-backref":"","aria-label":"Back to reference 1-22",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"22"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-23","data-footnote-backref":"","aria-label":"Back to reference 1-23",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"23"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-24","data-footnote-backref":"","aria-label":"Back to reference 1-24",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"24"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-25","data-footnote-backref":"","aria-label":"Back to reference 1-25",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"25"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-26","data-footnote-backref":"","aria-label":"Back to reference 1-26",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"26"})]})," ",e.jsxs(t.a,{href:"#user-content-fnref-1-27","data-footnote-backref":"","aria-label":"Back to reference 1-27",className:"data-footnote-backref",children:["↩",e.jsx(t.sup,{children:"27"})]})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-38",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://spring.io/projects/spring-cloud-zookeeper",children:"https://spring.io/projects/spring-cloud-zookeeper"})," ",e.jsx(t.a,{href:"#user-content-fnref-38","data-footnote-backref":"","aria-label":"Back to reference 2",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-2",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://www.youtube.com/watch?v=Ew53T6h9oRw",children:"https://www.youtube.com/watch?v=Ew53T6h9oRw"})," ",e.jsx(t.a,{href:"#user-content-fnref-2","data-footnote-backref":"","aria-label":"Back to reference 3",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-3",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://bookkeeper.apache.org/docs/4.7.3/getting-started/concepts/#ledgers",children:"https://bookkeeper.apache.org/docs/4.7.3/getting-started/concepts/#ledgers"})," ",e.jsx(t.a,{href:"#user-content-fnref-3","data-footnote-backref":"","aria-label":"Back to reference 4",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-4",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"http://cxf.apache.org/dosgi-discovery-demo-page.html",children:"http://cxf.apache.org/dosgi-discovery-demo-page.html"})," ",e.jsx(t.a,{href:"#user-content-fnref-4","data-footnote-backref":"","aria-label":"Back to reference 5",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-28",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://mapr.com/blog/apache-drill-architecture-ultimate-guide/",children:"https://mapr.com/blog/apache-drill-architecture-ultimate-guide/"})," ",e.jsx(t.a,{href:"#user-content-fnref-28","data-footnote-backref":"","aria-label":"Back to reference 6",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-27",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://druid.apache.org/docs/latest/dependencies/zookeeper.html",children:"https://druid.apache.org/docs/latest/dependencies/zookeeper.html"})," ",e.jsx(t.a,{href:"#user-content-fnref-27","data-footnote-backref":"","aria-label":"Back to reference 7",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-6",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"http://dubbo.apache.org/en-us/blog/dubbo-zk.html",children:"http://dubbo.apache.org/en-us/blog/dubbo-zk.html"})," ",e.jsx(t.a,{href:"#user-content-fnref-6","data-footnote-backref":"","aria-label":"Back to reference 8",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-23",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://ci.apache.org/projects/flink/flink-docs-release-1.9/ops/jobmanager_high_availability.html",children:"https://ci.apache.org/projects/flink/flink-docs-release-1.9/ops/jobmanager_high_availability.html"})," ",e.jsx(t.a,{href:"#user-content-fnref-23","data-footnote-backref":"","aria-label":"Back to reference 9",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-5",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://flume.apache.org/FlumeUserGuide.html",children:"https://flume.apache.org/FlumeUserGuide.html"})," ",e.jsx(t.a,{href:"#user-content-fnref-5","data-footnote-backref":"","aria-label":"Back to reference 10",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-7",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://hadoop.apache.org/docs/r2.7.1/hadoop-project-dist/hadoop-hdfs/HDFSHighAvailabilityWithQJM.html",children:"https://hadoop.apache.org/docs/r2.7.1/hadoop-project-dist/hadoop-hdfs/HDFSHighAvailabilityWithQJM.html"})," ",e.jsx(t.a,{href:"#user-content-fnref-7","data-footnote-backref":"","aria-label":"Back to reference 11",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-8",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://hbase.apache.org/book.html#zookeeper",children:"https://hbase.apache.org/book.html#zookeeper"})," ",e.jsx(t.a,{href:"#user-content-fnref-8","data-footnote-backref":"","aria-label":"Back to reference 12",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-20",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://blog.cloudera.com/what-are-hbase-znodes/",children:"https://blog.cloudera.com/what-are-hbase-znodes/"})," ",e.jsx(t.a,{href:"#user-content-fnref-20","data-footnote-backref":"","aria-label":"Back to reference 13",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-21",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://helix.apache.org/Architecture.html",children:"https://helix.apache.org/Architecture.html"})," ",e.jsx(t.a,{href:"#user-content-fnref-21","data-footnote-backref":"","aria-label":"Back to reference 14",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-31",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://cwiki.apache.org/confluence/display/CURATOR/Powered+By",children:"https://cwiki.apache.org/confluence/display/CURATOR/Powered+By"})," ",e.jsx(t.a,{href:"#user-content-fnref-31","data-footnote-backref":"","aria-label":"Back to reference 15",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-9",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://www.cloudkarafka.com/blog/2018-07-04-cloudkarafka_what_is_zookeeper.html",children:"https://www.cloudkarafka.com/blog/2018-07-04-cloudkarafka_what_is_zookeeper.html"})," ",e.jsx(t.a,{href:"#user-content-fnref-9","data-footnote-backref":"","aria-label":"Back to reference 16",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-10",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"http://mesos.apache.org/documentation/latest/high-availability/",children:"http://mesos.apache.org/documentation/latest/high-availability/"})," ",e.jsx(t.a,{href:"#user-content-fnref-10","data-footnote-backref":"","aria-label":"Back to reference 17",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-29",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://oozie.apache.org/docs/4.1.0/AG_Install.html",children:"https://oozie.apache.org/docs/4.1.0/AG_Install.html"})," ",e.jsx(t.a,{href:"#user-content-fnref-29","data-footnote-backref":"","aria-label":"Back to reference 18",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-24",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://pulsar.apache.org/docs/en/concepts-architecture-overview/#metadata-store",children:"https://pulsar.apache.org/docs/en/concepts-architecture-overview/#metadata-store"})," ",e.jsx(t.a,{href:"#user-content-fnref-24","data-footnote-backref":"","aria-label":"Back to reference 19",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-14",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://spark.apache.org/docs/latest/spark-standalone.html#standby-masters-with-zookeeper",children:"https://spark.apache.org/docs/latest/spark-standalone.html#standby-masters-with-zookeeper"})," ",e.jsx(t.a,{href:"#user-content-fnref-14","data-footnote-backref":"","aria-label":"Back to reference 20",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-22",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"http://storm.apache.org/releases/current/Setting-up-a-Storm-cluster.html",children:"http://storm.apache.org/releases/current/Setting-up-a-Storm-cluster.html"})," ",e.jsx(t.a,{href:"#user-content-fnref-22","data-footnote-backref":"","aria-label":"Back to reference 21",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`,e.jsxs(t.li,{id:"user-content-fn-19",children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.a,{href:"https://medium.com/@Pinterest_Engineering/zookeeper-resilience-at-pinterest-adfd8acf2a6b",children:"https://medium.com/@Pinterest_Engineering/zookeeper-resilience-at-pinterest-adfd8acf2a6b"})," ",e.jsx(t.a,{href:"#user-content-fnref-19","data-footnote-backref":"","aria-label":"Back to reference 22",className:"data-footnote-backref",children:"↩"})]}),`
`]}),`
`]}),`
`]})]})}function h(a={}){const{wrapper:t}=a.components||{};return t?e.jsx(t,{...a,children:e.jsx(n,{...a})}):n(a)}export{r as _markdown,h as default,s as extractedReferences,i as frontmatter,c as structuredData,l as toc};
