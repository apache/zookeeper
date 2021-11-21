# ZAB 1.0-tla

## Overview
This project is devoted to providing formal specification and verification using TLA+ for the Zookeeper Atomic Broadcast(ZAB) consensus protocol proposed by apache/zookeeper.

We have made a formal [specification](sync-abstract/ZabWithFLE.tla) for Zab using TLA+ toolbox, and we have done a certain scale of model checking to verify the correctness of Zab.

Currently we have implemented phase RECOVERY-SYNC and made a new formal [specification](sync-implementation/ZabWithFLEAndSYNC.tla). This version of spec is closer to engineering implementation.  
	* Note: In SYNCHRONIZATION, we implemented *DIFF* and *TRUNC* this two sync modes. Why we didn't implement *SNAP* is that in spec we will not meet conditions when "minCommittedLog > peerLastZxid", since it is more natural that head of committedLog is just head of history in spec. 

We have also made a formal [specification](sync-abstract/FastLeaderElection.tla) for Fast Leader Election in Zab since ZAB 1.0 depends on FLE to complete the election phase.  
	* Note: There exists some differences between FLE under sync-abstract and FLE under sync-implementation, to adapt to respective tla spec of Zab.

There exist some differences between our specification and engineering implementation, which are not contrary to facts. If you have any question, please let us know.

## Requirements
TLA+ toolbox version 1.7.0

## Run
Create specification [ZabWithFLE.tla](sync-abstract/ZabWithFLE.tla) or [ZabWithFLEAndSYNC.tla](sync-implementation/ZabWithFLEAndSYNC.tla) and run models in the following way.  
We can clearly divide spec into five modules, which are:  
- Phase0. (Fast) Leader Election  
- Phase1. Discovery  
- Phase2. Synchronization  
- Phase3. Broadcast  
- Failure, network dalay that results in going back to election

### Assign constants
After creating a new model and choosing *Temporal formula* with value *SpecZ* in ZabWithFLE.tla(or *Spec* in ZabWithFLEAndSYNC.tla), we first assign most of  constants.  
We need to set CONSTANTS about server states as model value, including *LEADING*, *FOLLOWING*, and *LOOKING*.  
We need to set CONSTANTS about server zabstates as model value, including *ELECTION*, *DISCOVERY*, *SYNCHRONIZATION*, and *BROADCAST*.  
We need to set CONSTANTS about message types as model value, including *FOLLOWERINFO*, *LEADERINFO*, *ACKEPOCH*, *NEWLEADER*, *ACKLD*, *UPTODATE*, *PROPOSAL*, *ACK*, *COMMIT*, *NOTIFICATION*, and *NONE*.  
We need to set CONSTANT *Value* as a symmetrical model value (such as <symmetrical\>{v1,v2}).How to set the value of *Value* has no effect on the correctness, so we choose to set it to a symmetrical model value.    
If you are using ZabWithFLEAndSYNC.tla, we need to additionally set *DIFF* and *TRUNC* as model value.  

### Assign invariants
We remove *'Deadlock'* option.  
We add invariants defined in spec into *'Invariants'* to check whether the model will reach an illogical state, including *ShouldNotBeTriggered*, *Leadership1*, *Leadership2*, *PrefixConsistency*, *Integrity*, *Agreement*, *TotalOrder*, *LocalPrimaryOrder*, *GlobalPriamryOrder*, and *PrimaryIntegrity*.  
Let me describe these invariants here briefly. Except for the first four, all invariants are defined in paper@fpj.  
	-	**ShouldNotBeTriggered**: We consider the reasonable conditions of the internal logic when servers receive messages, and when servers should not receive which types of messages.  
	-	**Leadership1/2**: There is most one leader(prospective leader) in a certain epoch.  
	-	**PrefixConsistency**: Transactions that have been delivered as a prefix in history are the same in any server.  
	-	**Integrity**: If some follower delivers one transaction, some primary must have broadcast it.  
	-	**Agreement**: If some follower *f<sub>1</sub>* delivers transaction *a* and some follower *f<sub>2</sub>* delivers transaction *b*, then *f<sub>2</sub>* delivers *a* or *f<sub>1</sub>* delivers *b*.  
	-	**TotalOrder**: If some server delivers *a* before *b*, then any server that delivers *b* must also deliver *a* and deliver *a* before *b*.  
	-	**LocalPrimaryOrder**: If a primary broadcasts *a* before it broadcasts *b*, then a follower that delivers *b* must also deliver *a* before *b*.  
	-	**GlobalPrimaryOrder**: A server *f* delivers both *a* with epoch *e* and *b* with epoch *e'*, and *e* < *e'*, then *f* must deliver *a* before *b*.  
	-	**PrimaryIntegrity**: If primary *p* broadcasts *a* and some follower *f* delivers *b* such that *b* has epoch smaller than epoch of *p*, then *p* must deliver *b* before it broadcasts *a*.  

What's more, we can add *TypeOK* to check whether there exists some variables illegal.

### Assign additional TLC options
We set number of worker threads as 10(if unavailable on your system, just decrease it).  
We can choose checking mode from *Model-checking mode* and *simulation mode*.  
	-	Model-checking mode: It is a traverse method like BFS. Diameter in results represent the maximum depth when traversing. All intermediate results will be saved as binary files locally and occupy a large space if running time is long.  
	-	Simulation mode: Everytime TLC randomly chooses a path and run through it until reaching termination or reaching maximum length of the trace, and randomly chooses another path. Currently we set *Maximum length of the trace* as 100.   
Here we recomend *Model-checking mode* when number of servers is 2, and *Simulation mode* when number of servers is larger than 2.  

### Assign left constants
Finally we need to assign CONSTANT *Server* as a symmetrical model value,  and we recommend setting *Server* as {s1,s2} or {s1,s2,s3}.   

To compress state space, we need to assign CONSTANT *Parameters* as an array, whose domain contains *MaxTimeoutFailures*, *MaxTransactionNum*, *MaxEpoch*. For example, we can assign it to format like [MaxTimeoutFailures |-> 3, MaxTransactionNum |-> 3, MaxEpoch |-> 4].

We are considering adding more parameters to compress state space, and achieve better traces we want.

## Results
>The machine configuration used in the experiment is 2.40 GHz, 10-core CPU, 64GB memory. The TLC version number is 1.7.0.

### Note
Actually our results are not complete here. We will upload them all afterwards.

We uniformly set CONSTANT *Value* as a symmetrical model value <symmetrical\>{v1,v2}. Because we do not care value of proposals. We pay more attention to zxid of them.   

### Verification results of model checking  
|  Mode  |     TLC model         |    Diameter   |     num of states  | time of checking(hh:mm:ss) |
| ----- | ---------------------- | ------------- | ------------------ | ------------------ |
| BFS   | 2 servers   |    39   |  38,882,771 |  00:07:05|
| BFS | (2 servers,3 timeouts,3 transactions)   |   46|  31,118,722 | 00:07:05  |
| Simulation | 2 servers    |     -   |  7,185,089,855 |  22:45:38 |
| Simulation | (2 servers,3 timeouts,3 transactions)   |   -|  417,180,330 | 01:28:29  |
| Simulation | (3 servers,3 timeouts,3 transactions)   |   -|  333,681,088 | 01:47:26  |
