<!--
Copyright 2002-2019 The Apache Software Foundation

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

- Applications and organizations using ZooKeeper include (alphabetically)[1].
- If your use case wants to be listed here. Please do not hesitate, submit a pull request or write an email to **dev@zookeeper.apache.org**,
  and then, your use case will be included.


## Free Software Projects

### [AdroitLogic UltraESB](http://adroitlogic.org/)
  - Uses ZooKeeper to implement node coordination, in clustering support. This allows the management of the complete cluster,
  or any specific node - from any other node connected via JMX. A Cluster wide command framework developed on top of the
  ZooKeeper coordination allows commands that fail on some nodes to be retried etc. We also support the automated graceful
  round-robin-restart of a complete cluster of nodes using the same framework.

### [Akka](http://akka.io/)
  - Akka is the platform for the next generation event-driven, scalable and fault-tolerant architectures on the JVM.
  Or: Akka is a toolkit and runtime for building highly concurrent, distributed, and fault tolerant event-driven applications on the JVM.

### [Eclipse Communication Framework](http://www.eclipse.org/ecf) 
  - The Eclipse ECF project provides an implementation of its Abstract Discovery services using Zookeeper. ECF itself
  is used in many projects providing base functionallity for communication, all based on OSGi.

### [Eclipse Gyrex](http://www.eclipse.org/gyrex)
  - The Eclipse Gyrex project provides a platform for building your own Java OSGi based clouds. 
  - ZooKeeper is used as the core cloud component for node membership and management, coordination of jobs executing among workers,
  a lock service and a simple queue service and a lot more.

### [GoldenOrb](http://www.goldenorbos.org/)
  - massive-scale Graph analysis

### [Juju](https://juju.ubuntu.com/)
  - Service deployment and orchestration framework, formerly called Ensemble.

### [Katta](http://katta.sourceforge.net/)
  - Katta serves distributed Lucene indexes in a grid environment.
  - Zookeeper is used for node, master and index management in the grid.

### [KeptCollections](https://github.com/anthonyu/KeptCollections)
  - KeptCollections is a library of drop-in replacements for the data structures in the Java Collections framework.
  - KeptCollections uses Apache ZooKeeper as a backing store, thus making its data structures distributed and scalable.

### [Neo4j](https://neo4j.com/)
  - Neo4j is a Graph Database. It's a disk based, ACID compliant transactional storage engine for big graphs and fast graph traversals,
    using external indicies like Lucene/Solr for global searches.
  - We use ZooKeeper in the Neo4j High Availability components for write-master election,
    read slave coordination and other cool stuff. ZooKeeper is a great and focused project - we like!

### [Norbert](http://sna-projects.com/norbert)
  - Partitioned routing and cluster management.

### [OpenStack Nova](http://www.openstack.org/)
  - OpenStack  is an open source software stack for the creation and management of private and public clouds. It is designed to manage pools of compute,
    storage, and networking resources in data centers, allowing the management of these resources through a consolidated dashboard and flexible APIs.
  - Nova is the software component in OpenStack, which is responsible for managing the compute resources, where virtual machines (VMs) are hosted in a cloud computing environment. It is also known as the OpenStack Compute Service. OpenStack
    Nova provides a cloud computing fabric controller, supporting a wide variety of virtualization technologies such as KVM, Xen, VMware, and many more. In addition to its native API, it also includes compatibility with Amazon EC2 and S3 APIs.
    Nova depends on up-to-date information about the availability of the various compute nodes and services that run on them, for its proper operation. For example, the virtual machine placement operation requires to know the currently available compute nodes and their current state.
    Nova uses ZooKeeper to implement an efficient membership service, which monitors the availability of registered services. This is done through the ZooKeeper ServiceGroup Driver, which works by using ZooKeeper's ephemeral znodes. Each service registers by creating an ephemeral znode on startup. Now, when the service dies, ZooKeeper will automatically delete the corresponding ephemeral znode. The removal of this znode can be used to trigger the corresponding recovery logic.
    For example, when a compute node crashes, the nova-compute service that is running in that node also dies. This causes the session with ZooKeeper service to expire, and as a result, ZooKeeper deletes the ephemeral znode created by the nova-compute service. If the cloud controller keeps a watch on this node-deletion event, it will come to know about the compute node crash and can trigger a migration procedure to evacuate all the VMs that are running in the failed compute node to other nodes. This way, high availability of the VMs can be ensured in real time.
  - ZooKeeper is also being considered for the following use cases in OpenStack Nova:
    - Storing the configuration metadata (nova.conf)
    - Maintaining high availability of services and automatic failover using
    leader election

### [spring-cloud-zookeeper](https://spring.io/projects/spring-cloud-zookeeper)
  - Spring Cloud Zookeeper provides Apache Zookeeper integrations for Spring Boot apps through autoconfiguration
    and binding to the Spring Environment and other Spring programming model idioms. With a few simple annotations
    you can quickly enable and configure the common patterns inside your application and build large distributed systems with Zookeeper.
    The patterns provided include Service Discovery and Distributed Configuration.

### [Talend ESB](http://www.talend.com/products-application-integration/application-integration-esb-se.php)
  - Talend ESB is a versatile and flexible, enterprise service bus.
  - It uses ZooKeeper as endpoint repository of both REST and SOAP Web services.
    By using ZooKeeper Talend ESB is able to provide failover and load balancing capabilities in a very light-weight manner

### [redis_failover](https://github.com/ryanlecompte/redis_failover)
  - Redis Failover is a ZooKeeper-based automatic master/slave failover solution for Ruby.


## Apache Projects

### [Apache Accumulo](https://accumulo.apache.org/)
  - Accumulo is a distributed key/value store that provides expressive, cell-level access labels.
  - Apache ZooKeeper plays a central role within the Accumulo architecture. Its quorum consistency model supports an overall
    Accumulo architecture with no single points of failure. Beyond that, Accumulo leverages ZooKeeper to store and communication 
    configuration information for users and tables, as well as operational states of processes and tablets.[2]

### [Apache BookKeeper](https://bookkeeper.apache.org/)
  - A scalable, fault-tolerant, and low-latency storage service optimized for real-time workloads.
  - BookKeeper requires a metadata storage service to store information related to ledgers and available bookies. BookKeeper currently uses
    ZooKeeper for this and other tasks[3].

### [Apache CXF DOSGi](http://cxf.apache.org/distributed-osgi.html)
  - Apache CXF is an open source services framework. CXF helps you build and develop services using frontend programming
    APIs, like JAX-WS and JAX-RS. These services can speak a variety of protocols such as SOAP, XML/HTTP, RESTful HTTP,
    or CORBA and work over a variety of transports such as HTTP, JMS or JBI.
  - The Distributed OSGi implementation at Apache CXF uses ZooKeeper for its Discovery functionality.[4]

### [Apache Dubbo](http://dubbo.apache.org)
  - Apache Dubbo is a high-performance, java based open source RPC framework.
  - Zookeeper is used for service registration discovery and configuration management in Dubbo.[6]

### [Apache Flink](https://flink.apache.org/)
  - Apache Flink is a framework and distributed processing engine for stateful computations over unbounded and bounded data streams.
    Flink has been designed to run in all common cluster environments, perform computations at in-memory speed and at any scale.
  - To enable JobManager High Availability you have to set the high-availability mode to zookeeper, configure a ZooKeeper quorum and set up a masters file with all JobManagers hosts and their web UI ports.
    Flink leverages ZooKeeper for distributed coordination between all running JobManager instances. ZooKeeper is a separate service from Flink,
    which provides highly reliable distributed coordination via leader election and light-weight consistent state storage[23].

### [Apache Flume](https://flume.apache.org/)
  - Flume is a distributed, reliable, and available service for efficiently collecting, aggregating, and moving large amounts
    of log data. It has a simple and flexible architecture based on streaming data flows. It is robust and fault tolerant
    with tunable reliability mechanisms and many failover and recovery mechanisms. It uses a simple extensible data model
    that allows for online analytic application.
  - Flume supports Agent configurations via Zookeeper. This is an experimental feature.[5]

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
      and then the local NameNode transitions to active state.[7]

### [Apache HBase](https://hbase.apache.org/)
  - HBase is the Hadoop database. It's an open-source, distributed, column-oriented store model.
  - HBase uses ZooKeeper for master election, server lease management, bootstrapping, and coordination between servers.
    A distributed Apache HBase installation depends on a running ZooKeeper cluster. All participating nodes and clients
    need to be able to access the running ZooKeeper ensemble.[8]
  - As you can see, ZooKeeper is a fundamental part of HBase. All operations that require coordination, such as Regions
    assignment, Master-Failover, replication, and snapshots, are built on ZooKeeper[20].

### [Apache Helix](http://helix.apache.org/)
  - Apache Helix is a cluster management framework for partitioned and replicated distributed resources.
  - It provides a generic way of automatically managing the resources in a cluster. Helix acts as a decision subsystem for the cluster, and is responsible for the following tasks and many more:
    - Automating the reassignment of resources to the nodes
    - Handling node failure detection and recovery
    - Dynamic cluster reconfiguration (node and resource addition/deletion)
    - Scheduling of maintenance tasks (backups, index rebuilds, and so on)
    - Maintaining load balancing and flow control in the cluster
    In order to store the current cluster state, Helix needs a distributed and highly available configuration or cluster metadata store, for which it uses ZooKeeper.
  - ZooKeeper provides Helix with the following capabilities:
    - This framework represents the PERSISTENT state, which remains until it's removed
    - This framework also represents the TRANSIENT/EPHEMERAL state, which goes away when the process that created the state leaves the cluster
    - This framework notifies the subsystem when there is a change in the PERSISTENT and EPHEMERAL state of the cluster
  - Helix also allows simple lookups of task assignments through the configuration store built on top of ZooKeeper. Through this, clients can look up where the tasks are currently assigned.
    This way, Helix can also provide a service discovery registry.[21]

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
    Zookeeper also maintains a list of all the brokers that are functioning at any given moment and are a part of the cluster.[9]

### [Apache Mesos](http://mesos.apache.org/)
  - Apache Mesos abstracts CPU, memory, storage, and other compute resources away from machines (physical or virtual),
    enabling fault-tolerant and elastic distributed systems to easily be built and run effectively.
  - Mesos has a high-availability mode that uses multiple Mesos masters: one active master (called the leader or leading master)
    and several backups in case it fails. The masters elect the leader, with Apache ZooKeeper both coordinating the election
    and handling leader detection by masters, agents, and scheduler drivers.[10]

### [Apache Pulsar](https://pulsar.apache.org)
  - Apache Pulsar is an open-source distributed pub-sub messaging system originally created at Yahoo and now part of the Apache Software Foundation
  - Pulsar uses Apache Zookeeper for metadata storage, cluster configuration, and coordination. In a Pulsar instance:
    - A configuration store quorum stores configuration for tenants, namespaces, and other entities that need to be globally consistent.
    - Each cluster has its own local ZooKeeper ensemble that stores cluster-specific configuration and coordination such as ownership metadata,
      broker load reports, BookKeeper ledger metadata, and more[24].

### [Apache S4](https://github.com/apache/incubator-retired-s4)
  - S4(**retired** on 2014-06-19[11]) is a general-purpose, distributed, scalable, partially fault-tolerant, pluggable platform that allows programmers
    to easily develop applications for processing continuous unbounded streams of data.

### [Apache Solr](https://lucene.apache.org/solr/)
  - Solr is the popular, blazing-fast, open source enterprise search platform built on Apache Lucene.
  - In the "Cloud" edition (v4.x and up) of enterprise search engine Apache Solr, ZooKeeper is used for configuration,
    leader election and more[12,13]

### [Apache Spark](https://spark.apache.org/)
  - Apache Spark is a unified analytics engine for large-scale data processing.
  - Utilizing ZooKeeper to provide leader election and some state storage, you can launch multiple Masters in your cluster connected to the same ZooKeeper instance.
    One will be elected “leader” and the others will remain in standby mode. If the current leader dies, another Master will be elected,
    recover the old Master’s state, and then resume scheduling[14]


## Companies

### [101tec](http://101tec.com/)
 - we do consulting in the area of enterprise distributed systems.
 - We use zookeeper to manage a system build out of hadoop, katta, oracle batch jobs and a web component.

### [AGETO](http://www.ageto.de/)
 - The AGETO RnD team uses ZooKeeper in a variety of internal as well as external consulting projects.

### [Benipal Technologies](http://www.benipaltechnologies.com/)
 - ZooKeeper is used for internal application development with Solr and Hadoop with Hbase.

### [Box](http://box.net/)
 - Box uses ZooKeeper for service discovery, service coordination, Solr and Hadoop support, etc.

### [Deepdyve](http://www.deepdyve.com/)
 - We do search for research and provide access to high quality content using advanced search technologies Zookeeper is used to
   manage server state, control index deployment and a myriad other tasks.

### [eBay](https://www.ebay.com/)
 - eBay uses ZooKeeper to develop a job type limiter. The job limiter limits the execution of the same type of jobs from running simultaneously beyond a specified number in the grid.
   For each job type, the type limiter keeps track of the running count and the limit. When the running count hits the limit, spawning of a new job
   is not allowed until a job of that type finishes or terminates. This job type limiter is used for jobs that use third-party services using APIs to update the maximum bid for keyword ads.
   Usually, the API call capacity is limited. Even if the grid has enough capacity to run hundreds of jobs, the job limiter system built with ZooKeeper allows
   only a predetermined number (for example, N) of concurrent jobs against a partner API. The type limiter ensures that the (N+1)th job waits until one of the N running jobs has completed[21,22].

### [Facebook](https://www.facebook.com/)
 - Facebook uses ZooKeeper in its messaging platform and many other use cases. Facebook's messaging system is powered by a system called cell system.
   The entire messaging system (e-mail, SMS, Facebook Chat, and the Facebook Inbox) is divided into cells. Cells are composed of a cluster of servers and services,
   and each cell serves only a fraction of Facebook users. The use of cells has the following advantages:
   - Incremental scaling with minuscule failures
   - Easy upgrading of hardware and software
   - Failures affect only the cell boundary, which affects limited users
   - Hosting cells in distributed data centers avoids disasters
   - Each cell comprises a cluster of servers and is coordinated by a ZooKeeper ensemble. The servers that host a particular application register themselves with ZooKeeper.
     The mapping between users and servers is then done using the consistent hashing algorithm. Basically, the nodes are arranged in a ring,
     and each node is responsible for a specified range of the users depending on the hash values.
   - If a node fails, another node in the neighboring position in the ring takes over the load for those users affected by the failure.
     This allows for an even distribution of the load and also enables easy addition and removal of nodes into and from the cluster in the cell[21].
 - Facebook uses the Zeus([17,18]) for configuration management which is a forked version of ZooKeeper, with many scalability
   and performance en- hancements in order to work at the Facebook scale.
   It runs a consensus protocol among servers distributed across mul- tiple regions for resilience. If the leader fails,
   a follower is converted into a new leader.

### [Idium Portal](http://www.idium.no/no/idium_portal/)
 - Idium Portal is a hosted web-publishing system delivered by Norwegian company, Idium AS.
 - ZooKeeper is used for cluster messaging, service bootstrapping, and service coordination.

### [Makara](http://www.makara.com/)
 - Using ZooKeeper on 2-node cluster on VMware workstation, Amazon EC2, Zen
 - Using zkpython
 - Looking into expanding into 100 node cluster

### [Midokura](http://www.midokura.com/)
 - We do virtualized networking for the cloud computing era. We use ZooKeeper for various aspects of our distributed control plane.

### [Netflix](https://www.netflix.com)
 - Netflix([21]) is an avid ZooKeeper user in its distributed platform, which led them to develop Curator and Exhibitor to enhance the functionalities of ZooKeeper.
   A few of the use cases of ZooKeeper/Curator at Netflix are as follows:
    - Ensuring the generation of unique values in various sequence ID generators
    - Cassandra backups
    - Implementing the TrackID service
    - Performing leader election with ZooKeeper for various distributed tasks
    - Implementing a distributed semaphore for concurrent jobs
    - Distributed caching

### [Nutanix](http://www.nutanix.com/)
  - Nutanix develops a hyper-converged storage and compute solution, which leverages local components of a host machine, such as storage capacity (disks) and compute (CPUs),
    to create a distributed platform for virtualization. This solution is known as the Nutanix Virtual Computing Platform. It supports industry-standard hypervisor ESXi, KVM, and Hyper-V.
    The platform is bundled in the appliance form factor with two nodes or four nodes. A VM in the platform known as the Nutanix Controller VM works as the decision subsystem, which manages the platform.
  - The Nutanix platform uses Apache ZooKeeper as a Cluster configuration manager. The configuration data that pertains to the platform, such as hostnames, IP addresses,
    and the cluster state, is stored in a ZooKeeper ensemble. ZooKeeper is also used to query the state of the various services that run in the platform[21].

### [Pinterest](https://www.pinterest.com/)
 - Pinterest uses the ZooKeeper for Service discovery and dynamic configuration.Like many large scale web sites, Pinterest’s infrastructure consists of servers that communicate with
   backend services composed of a number of individual servers for managing load and fault tolerance. Ideally, we’d like the configuration to reflect only the active hosts,
   so clients don’t need to deal with bad hosts as often. ZooKeeper provides a well known pattern to solve this problem[19].

### [Rackspace](http://www.rackspace.com/email_hosting)
 - The Email & Apps team uses ZooKeeper to coordinate sharding and responsibility changes in a distributed e-mail client
   that pulls and indexes data for search. ZooKeeper also provides distributed locking for connections to prevent a cluster from overwhelming servers.

### [Sematext](http://sematext.com/)
 - Uses ZooKeeper in SPM (which includes ZooKeeper monitoring component, too!), Search Analytics, and Logsene.

### [Tubemogul](http://tubemogul.com/)
 - Uses ZooKeeper for leader election, configuration management, locking, group membership

### [Twitter](https://twitter.com/)
 - ZooKeeper is used at Twitter as the source of truth for storing critical metadata. It serves as a coordination kernel to
   provide distributed coordination services, such as leader election and distributed locking.
   Some concrete examples of ZooKeeper in action include[15,16]:
   - ZooKeeper is used to store service registry, which is used by Twitter’s naming service for service discovery.
   - Manhattan, Twitter’s in-house key-value database, stores its cluster topology information in ZooKeeper.
   - EventBus, Twitter’s pub-sub messaging system, stores critical metadata in ZooKeeper and uses ZooKeeper for leader election.
   - Mesos, Twitter’s compute platform, uses ZooKeeper for leader election.
 
### [Vast.com](http://www.vast.com/)
 - Used internally as a part of sharding services, distributed synchronization of data/index updates, configuration management and failover support

### [VMware vSphere Storage Appliance](https://www.vmware.com/products/vsphere-storage-appliance.html)
 - VMware vSphere Storage Appliance (VSA) is a software-storage appliance. The VMware VSA comes in a cluster configuration of two or three nodes,
   known as the VSA Storage Cluster. A virtual machine instance inside each of the VMware ESXiTM host in the VSA Storage
   Cluster claims all the available local directed attached storage space and presents it as one mirrored volume of all the ESXi hosts in the VMware vCenter Server datacenter.
   It uses the NFS protocol to export the volume.
 - VSA uses ZooKeeper as the base clustering library for the following primitives:
    - As a cluster membership model to detect VSA failures in the cluster
    - As a distributed metadata storage system to store the cluster states
    - As a leader elector to select a master VSA that performs metadata operations

### [Wealthfront](http://wealthfront.com/)
 - Wealthfront uses ZooKeeper for service discovery, leader election and distributed locking among its many backend services.
   ZK is an essential part of Wealthfront's continuous [deployment infrastructure](http://eng.wealthfront.com/2010/05/02/deployment-infrastructure-for-continuous-deployment/).

### [Yahoo!](http://www.yahoo.com/)
 - ZooKeeper is used for a myriad of services inside Yahoo! for doing leader election, configuration management, sharding, locking, group membership etc[1].
 - ZooKeeper was originally developed by Yahoo! before it became an Apache project. It is used for a multitude of services inside Yahoo! to perform leader election, configuration management, 
   naming system, sharding, distributed locking, group membership, and so on. Yahoo! is a big Hadoop shop that performs analysis in its massive datasets,
   and ZooKeeper is used for various distributed coordination and synchronization tasks[21].

### [Zynga](http://www.zynga.com/)
 - ZooKeeper at Zynga is used for a variety of services including configuration management, leader election, sharding and more...[1]
 - Zynga uses ZooKeeper for configuration management of their hosted games. ZooKeeper allows Zynga to update the configuration files for a plethora of online games,
   which are used across the world by millions of users in a very short span of time. The games are served from Zynga's multiple data centers.
   With ZooKeeper, the configuration's system updates thousands of configuration files in a very small span of time.
   The configurations are validated by validation systems against the business logic to ensure that configurations are updated correctly and services are properly configured to the updated data.
   In the absence of ZooKeeper, the configuration update at the same time in a short time interval would be a real nightmare.
   Again, failure to sync these configuration files within the available time span would have caused severe service disruption[21].

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
- [21] Saurav Haloi(2015). *Apache ZooKeeper essentials*. Packt Publishing.
- [22] *Grid Computing with Fault-Tolerant Actors and ZooKeeper*. https://tech.ebayinc.com/engineering/grid-computing-with-fault-tolerant-actors-and-zookeeper/#.VGh-jMk_9kU
- [23] https://ci.apache.org/projects/flink/flink-docs-release-1.9/ops/jobmanager_high_availability.html
- [24] https://pulsar.apache.org/docs/en/concepts-architecture-overview/#metadata-store