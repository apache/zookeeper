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

The introduction to Oracle Quorum increases the availability of a cluster of 2 ZooKeeper instances with an unreliable fail
detector as known as the Oracle. The Oracle is designed to grant the permission to the instance which is the only remained instance
in a 2-node configuration when the other instance is identified as faulty by the fail detector, the Oracle.

## The implementation of the Oracle

Every instance shall access to a file which contains either 0 or 1 to indicate whether this instance is authorized by the Oracle.
However, this design can be arbitrary since the fail detector algorithms vary from each other. Therefore, ones can override
the method of _askOracle()_ in _QuorumOracleMaj_ to adapt the preferred way of deciphering the message from the Oracle.

## The deployment contexts

The Oracle is designed to increase the availability of a cluster of 2 ZooKeeper instances; thus, the size of the voting member is **2**.
In other words, the Oracle solves the consensus in the problem domain of _2f_ where _f_ indicates the number of faulty instances in the
cluster.

In the case that the size of the voting members exceeds 2, the expected way to make the Oracle work correctly is to reconfigure the size of the cluster 
when a faulty machine is identified. For example, with a configuration of 5 instances, when a faulty machine breaks the connection with the Leader, it is expected
to have a _reconfig_ client request to the cluster, which makes the cluster to re-form as the configuration of 4 instances. Therefore, once the size of the voting member
equals to 2, the configuration falls into the problem domain which the Oracle is designed to address.

## How to deploy the Oracle in _zoo.cfg_

Regardless of the size of the cluster, the _oraclePath_ requires to be configured at the time of the initialization, which is like 
other static parameters. The below shows the correct way to specify and enable the Oracle.

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
   

## What is different with the Oracle enabled

The _QuorumPeerConfig_ will create an instance of _QuorumOracleMaj_ instead of the default QuorumVerifier, _QuorumMaj_ when it reads
the _zoo.cfg_ contains _oraclePath_. QuorumOracleMaj inheritances from QuorumMaj, and differs from its superclass by overriding the 
method of _containsQuorum()_. QuorumOracleMaj is designed to execute its version of _containsQuorum_ when the Leader loses all of its followers,
and fails to maintain the quorum. In other cases, _QuorumOracleMaj_ shall execute as _QuorumMaj_.


