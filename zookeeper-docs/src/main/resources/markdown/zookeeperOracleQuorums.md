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

# Introduction to Oracle Quorum
The introduction to Oracle Quorum increases the availability of a cluster of 2 ZooKeeper instances with a failure detector as known as the Oracle.
 The Oracle is designed to grant the permission to the instance which is the only remaining instance
in a 2-instance configuration when the other instance is identified as faulty by the fail detector, the Oracle.

## The implementation of the Oracle
Every instance shall access to a file which contains either 0 or 1 to indicate whether this instance is authorized by the Oracle.
However, this design can be changed since the fail detector algorithms vary from each other. Therefore, ones can override the method of _askOracle()_ in _QuorumOracleMaj_ to adapt the preferred way of deciphering the message from the Oracle.

## The deployment contexts
The Oracle is designed to increase the availability of a cluster of 2 ZooKeeper instances; thus, the size of the voting member is **2**.
In other words, the Oracle solves the consensus problem of a possibility of faulty instance in a two-instance ensemble.

In the case that the size of the voting members exceeds 2, the expected way to make the Oracle work correctly is to reconfigure the size of the cluster when a faulty machine is identified.
For example, with a configuration of 5 instances, when a faulty machine breaks the connection with the Leader, it is expected to have a _reconfig_ client request to the cluster, which makes the cluster to re-form as the configuration of 4 instances.
Therefore, once the size of the voting member equals to 2, the configuration falls into the problem domain which the Oracle is designed to address.

## How to deploy the Oracle in _zoo.cfg_
Regardless of the size of the cluster, the _oraclePath_ must be configured at the time of the initialization, which is like other static parameters.
The below shows the correct way to specify and enable the Oracle.

    oraclePath=/to/some/file
    
#### An example of zoo.cfg:

    dataDir=/data
    dataLogDir=/datalog
    tickTime=2000
    initLimit=5
    syncLimit=2
    autopurge.snapRetainCount=3
    autopurge.purgeInterval=0
    maxClientCnxns=60
    standaloneEnabled=true
    admin.enableServer=true
    oraclePath=/chassis/mastership
    server.1=0.0.0.0:2888:3888;2181
    server.2=hw1:2888:3888;2181
    
The QuorumOracleMaj is designed to read the result of a failure detector, which is written on a text file, the oracle file.  
The configuration in the zoo.cfg like the following:
    
    oraclePath=/to/some/file
    
Suppose you have the result of the failure detector written on /some/path/result.txt, and then the correct configuration is the following:

    oraclePath=/some/path/result.txt
    
So, what is the correct content of the provided file? An example file can be created with the following command from the terminal:

    $echo 1 > /some/path/result.txt
    
Any equivalent files are suitable for the current implementation of QuorumOracleMaj.
The number of oracle files should be equal to the number of ZooKeeper instances configured to enable the Oracle.
In other words, each ZooKeeper instance should have its oracle file, and the files shall not be shared; otherwise, the issues in the next section will arise.

## What differs after the deployment of the Oracle enabled
The _QuorumPeerConfig_ will create an instance of _QuorumOracleMaj_ instead of the default QuorumVerifier, _QuorumMaj_ when it reads the _zoo.cfg_ contains _oraclePath_.
QuorumOracleMaj inheritances from QuorumMaj, and differs from its superclass by overriding the method of _containsQuorum()_.
QuorumOracleMaj is designed to execute its version of _containsQuorum_ when the Leader loses all of its followers, and fails to maintain the quorum.
In other cases, _QuorumOracleMaj_ shall execute as _QuorumMaj_.

## What we should pay attention to the Oracle
We consider an asynchronous distributed system which consists of **2** ZooKeeper instances and an Oracle.

### Liveness Issue:
When we consider the oracle satisfies the following property introduced by [CT]:

    Strong Completeness: There is a time after which every process that crashes is permanently suspected by every correct processes

The liveness of the system is ensured by the Oracle.
However, when the introduced oracle fails to maintain this property, the lost of the liveness is expected as the following example,

Suppose we have a Leader and a Follower, which are running in the broadcasting state,
The system will lose its liveness when:

    1. The Leader fails, but the Oracle does not detect the faulty Leader, which means the Oracle will not authorize the Follower to become a new Leader.
    2. When a Follower fails, but the Oracle does not detect the faulty follower, which means the Oracle will authorize the Leader to move system forward.

### Safety Issue:
#### Lost of Progress
The progress can lost when multiple failures occurs in the system at different time as the following example,

Suppose we have a Leader(Ben) and a Follower(John) in the broadcasting state,

    At T1 with zxid(0x1_1): L-Ben fails, and the F-John takes over the system under the authorization from the Oracle.
    At T2 with zxid(0x2_1): The F-John becomes a new Leader, L-John, and starts a new epoch.
    At T3 with zxid(0x2_A): L-John fails
    At T4 with zxid(0x2_A): Ben recovers up and starts its leader election.
    At T5 with zxid(0x3_1): Ben becomes the new leader, L-Ben, under the authorization from the Oracle.

In this case, the system loses its progress after the L-Ben failed.


However, the lost of progress can be prevented by making the Oracle is capable of referring the latest zxid. 
When the Oracle could refer to the latest zxid,

    At T5 with zxid(0x2_A): Ben will not end his leader election because the Oracle would not authorize although John is down.

Nevertheless, we exchange the liveness for the safety.
#### Split Brain Issue
We consider the Oracle satisfies the following desired property introduced by [CT],

    Accuracy: There is a time after which some correct processes is never suspected by any processes
    
Nevertheless, the decisions which the Oracle gives out should be mutual exclusive. 

In other words,

Suppose we have a Leader(Ben) and a Follower(John) in the broadcasting state,

    - At any time, the Oracle will not authorize both Ben and John even though the failure detectors think each other is faulty.
    Or
    - At any time, for any two values in any two Oracle files respectively, the values are not both equal to 1.     

The split brain is expected when the Oracle fails to maintain this property during the leader election phase of 

    1. Start of the system 
    2. A failed instance recovers from failures.
    
## Examples of Concepts for Implementation of a Failure Detector
One should consider that the failure detector's outcome is to authorize the querying ZooKeeper instance whether it has the right to move the system forward without waiting for the faulty instance, which is identified by the failure detector.

### An Implementation of Hardware
Suppose two dedicated pieces of hardware, hw1 and hw2, can host ZooKeeper instances, zk1 and zk2, respectively, and form a cluster.
A hardware device is attached to both of the hardware, and it is capable of determining whether the hardware is power on or not.
So, when hw1 is not power on, the zk1 is undoubtedly faulty. 
Therefore, the hardware device updates the oracle file on hw2 to 1, which indicates that zk1 is faulty and authorizes zk2 to move the system forwards.

### An Implementation of Software
Suppose two dedicated pieces of hardware, hw1 and hw2, can host ZooKeeper instances, zk1 and zk2, respectively, and form a cluster.
One can have two more services, o1 and o2, on hw1 and hw2, respectively. The job of o1 and o2 are detecting the other hardware is alive or not.
For example, o1 can constantly ping hw2 to determine if hw2 is power on or not.
When o1 cannot ping hw2, o1 identifies that hw2 is faulty and then update the oracle file of zk1 to 1, which indicates that zk2 is faulty and authorizes zk1 to move the system forwards.
  
### Use USB devices as Oracle to Maintain Progress
In macOS,10.15.7 (19H2), the external storage devices are mounted under `/Volumes`. 
Thus, we can insert a USB device which contains the required information as the oracle.
When the device is connected, the oracle authorizes the leader to move system forward, which also means the other instance fails.
There are **SIX** steps to reproduce this stimulation.

* Firstly, insert a USB device named `Oracle`, and then we can expect that  `/Volumes/Oracle` is accessible.
* Secondly, we create a file contains `1` under `/Volumes/Oracle` named `mastership`.
Now we can access `/Volumes/Oracle/mastership`, and so does the zookeeper instances to see whether it has the right to move the system forward.
The file can easily be generated by the following command:


    $echo 1 > mastership

* Thirdly, you shall have a `zoo.cfg` like the example below:


    dataDir=/data
    dataLogDir=/datalog
    tickTime=2000
    initLimit=5
    syncLimit=2
    autopurge.snapRetainCount=3
    autopurge.purgeInterval=0
    maxClientCnxns=60
    standaloneEnabled=true
    admin.enableServer=true
    oraclePath=/Volumes/Oracle/mastership
    server.1=0.0.0.0:2888:3888;2181
    server.2=hw1:2888:3888;2181
    
_(NOTE) The split brain issues will not occur because there is only a SINGLE USB device in this stimulation._
_Additionally, `mastership` should not be shared by multiple instances._
_Thus, only one ZooKeeper instance is configured with Oracle._
_For more, please refer to Section Safety Issue._

* Fourthly, start the cluster, and it is expected it forms a quorum normally.
* Fifthly, terminate the instance either without attaching to a USB device or `mastership` contains 0.
There are two scenarios to expect:
  1. A leader failure occurs, and the remained instance finishes the leader election on its own due to the oracle.
  2. The quorum is still maintained due to the oracle.
  
* Lastly, when the USB device is removed, `/Volumes/Oracle/mastership` becomes unavailable.
Therefore, according to the current implementation, whenever the Leader queries the oracle, the oracle throws an exception and return `FALSE`.
Repeat the fifth step, and then it is expected that either the system cannot recover from a leader failure ,or the leader loses the quorum.
In either case, the service is interrupted.

With these steps, we can show and practice how the oracle works with two-instance systems with ease.

##REFERENCE
[CT] Tushar Deepak Chandra and Sam Toueg. 1991. Unreliable failure detectors for asynchronous systems (preliminary version). In <i>Proceedings of the tenth annual ACM symposium on Principles of distributed computing</i> (<i>PODC '91</i>). Association for Computing Machinery, New York, NY, USA, 325–340. DOI:https://doi.org/10.1145/112600.112627