# Zab-tla

## Overview
This project is devoted to providing formal specification and verification using TLA+ for the Zookeeper Atomic Broadcast(Zab) consensus protocol proposed in *Junqueira F P, Reed B C, Serafini M. Zab: High-performance broadcast for primary-backup systems[C]//2011 IEEE/IFIP 41st International Conference on Dependable Systems & Networks (DSN). IEEE, 2011: 245-256*.  

Currently we are devoted to writing spec for ZAB 1.0, which represents new version of ZAB.  

We have made a formal [specification](Zab.tla) for Zab using TLA+ toolbox, and we have done a certain scale of model checking to verify the correctness of Zab.

Due to the simplification of Zab algorithm description in the paper, some details in specification were modified and added. If you have any question, please let us know.

You can find this document in chinese in [doc-in-chinsese](doc-in-chinese/README.md).


## Requirements
TLA+ toolbox version 1.7.0

## Run
Create specification [experiment/ZabWithQTest.tla](experiment/ZabWithQTest.tla) or [experiment/ZabWithQTest2.tla](experiment/ZabWithQTest2.tla) and run models in the following way.  
These two spec files are equivalent. Through ZabWithQTest2.tla, we can understand spec better, and understand how we verify correctness of ZAB by extending Zab Module. Through ZabWithQTest2.tla, we can clearly divide spec into five modules, which are:  
- Phase0. Leader Election  
- Phase1. Discovery  
- Phase2. Synchronization  
- Phase3. Broadcast  
- Recovery Module  
### Assign constants
After creating a new model and choosing *Temporal formula* with value *Spec*(*SpecT* in ZabWithQTest2.tla), we first assign most of  constants.  
We need to set CONSTANTS about server states as model value, including *Follower*, *Leader*, and *ProspectiveLeader*.  
We need to set CONSTANTS about message types as model value, including *CEPOCH*, *NEWEPOCH*, *ACKE*, *NEWLEADER*, *ACKLD*, *COMMITLD*, *PROPOSE*, *ACK*, *COMMIT*, *RECOVERYREQUEST*, and *RECOVERYRESPONSE*.  
We need to set CONSTANT *Value* as a symmetrical model value (such as <symmetrical\>{v1,v2}) to compress state space.

### Assign invariants
We remove *'Deadlock'* option.  
We add invariants defined in spec into *'Invariants'* to check whether the model will reach an illogical state, including *Leadership*, *PrefixConsistency*, *Integrity*, *Agreement*, *TotalOrder*, *LocalPrimaryOrder*, *GlobalPriamryOrder*, and *PrimaryIntegrity*.  
Let me describe these invariants here briefly. Except for the first two, all invariants are defined in paper.  
	-	**Lerdership**: There is most one leader(prospective leader) in a certain epoch.  
	-	**PrefixConsistency**: Transactions that have been delivered as a prefix in history are the same in any server.  
	-	**Integrity**: If some follower delivers one transaction, some primary must have broadcast it.  
	-	**Agreement**: If some follower *f<sub>1</sub>* delivers transaction *a* and some follower *f<sub>2</sub>* delivers transaction *b*, then *f<sub>2</sub>* delivers *a* or *f<sub>1</sub>* delivers *b*.  
	-	**TotalOrder**: If some server delivers *a* before *b*, then any server that delivers *b* must also deliver *a* and deliver *a* before *b*.  
	-	**LocalPrimaryOrder**: If a primary broadcasts *a* before it broadcasts *b*, then a follower that delivers *b* must also deliver *a* before *b*.  
	-	**GlobalPrimaryOrder**: A server *f* delivers both *a* with epoch *e* and *b* with epoch *e'*, and *e* < *e'*, then *f* must deliver *a* before *b*.  
	-	**PrimaryIntegrity**: If primary *p* broadcasts *a* and some follower *f* delivers *b* such that *b* has epoch smaller than epoch of *p*, then *p* must deliver *b* before it broadcasts *a*.  

### Assign additional TLC options
We set number of worker threads as 10(if unavailable on your system, just decrease it).  
We can choose checking mode from *Model-checking mode* and *simulation mode*.  
	-	Model-checking mode: It is a traverse method like BFS. Diameter in results represent the maximum depth when traversing. All intermediate results will be saved as binary files locally and occupy a large space if running time is long.  
	-	Simulation mode: Everytime TLC randomly chooses a path and run through it until reaching termination or reaching maximum length of the trace, and randomly chooses another path. Currently we set *Maximum length of the trace* as 100.  

### Assign left constants
Finally we need to assign CONSTANTS *Server* as a symmetrical model value, and assign  *MaxTotalRestartNum*, *MaxElectionNum*, and *MaxTransactionNum* as ordinary assignment. Their meanings are obvious, and we recommend setting *Server* as {s1,s2} or {s1,s2,s3}, setting *MaxTotalRestartNum*, *MaxElectionNum*, and *MaxTransactionNum* between 1 and 3.


### Results
You can find our [result](experiment/README.md) of verification using model checking of TLA+.



## Abstraction in specification
>The Zab protocol in paper dose not focus on leader election, so we abstract the process of leader election in spec. Our spec can simulate non-Byzantion faults. In addition, what we pay attention to is consistency of system state, and we abstract or omit some parts in actual implementation, such as replying results to client.

### Abstraction to Election
Except for the action *Election*, all actions perform on a specific server to reflect the feature of distributed. Since the paper does not pay attention to the process of selecting a leader, we abstract this process and it can be reflected in action *Election*. *Election* and actions which call it are the only actions that are abstracted in the whole specification.

### Abstraction to communication medium
Communication in Zab is based on TCP channels, so there is no packet loss, redundancy, or disorder in message delivery. We use module *Sequence* in TLA+ to simulate channel meeting the property of receiving messages in order. So there is a certain difference between our communication channel and end-to-end TCP channel.    
We believe it can simulate message delay when a server does not perform the action of receiving messages. And it can simulate a process failuere when a server does not perform any action.

### Abstraction and omission to actions unrelated to system state
What we care about is consistecy of the state in the system. We do not care about details like client's request to the system or the system's reply to client, or server delivering transactions to replica. Therefore, we simplify the process of client requesting, and omit reply to client. We assume that each committed transaction will be delivered to replica immediately, so we can treat variable history[i][1..commitIndex] as the transaction sequence that server *i* delivers to the corresponding replica.

## Differences from paper
>This section describes difference between the protocol in paper and our specification.

### Issue 1 Line: 196(line number in Zab.tla), Action: Election
In *Step l.1.1* and *Step l.2.2* in paper, a prospective leader will not perform the next action until receiving a quorum of followers. It obviously affects availability. We think the leader itself should be a member of the quorum. So, when we reset variables *cepochRecv*, *ackeRecv* and *ackldRecv* in the action *Election*, we initialize these sets with adding leader ID to the sets.  
In addition, according to *Step l.1.1* in paper, we know that the prospective leader determines its *cluster*(*Q* in paper) is based on information from *CEPOCH* received. So, Q is a set not satisfying the property of quorum in the initial stage of Phase 1(*Discovery*), which may trigger action *LeaderTimeout* to perform a new round of election. For this reason, we initialize *Q* in action *Election*, so that *Q* maintains the property of quorum anytime in this round.

### Issue 2 Line: 419, Action: LeaderHandleACKE; Line: 444, Action: LeaderDiscovery2Sync1
In *Step l.1.2* in paper, the prospective leader selects best information to update after receiving *ACK-E* from each follower in *Q*. We think this condition is relatively harsh. In specification, the prospective leader will select information to update and broadcast *NEWLEADER* after receiving a quorum of followers. Whether you choose the method in paper or the method in specification, it does not trigger a threat to the correctness of the algorithm.

### Issue 3 Line: 470, Action: FollowerSync1
In *Step f.2.1* in paper, in general, since each follower in Q will receive *NEWEPOCH* before receiving *NEWLEADER*, the *currentEpoch[i]* of server *i* is equal to the epoch in *NEWLEADER*. In some extreme cases, the *currentEpoch[i]* may be larger than the epoch in *NEWLEADER*. In these cases, comparing that server *i* perform a new round of election, we discard such messages whose epoch is smaller than local epoch.

## Addition in specification
> This section describes parts that are added in our specification compared with the protocol in paper.

### Issue 4 Line: 261, Action: Restart, RecoveryAfterRestart, HandleRecoveryRequest, HandleRecoveryResponse, FindCluster
*Step l.3.3* and *Step l.3.4* in paper describe the process of the leader replying when receiving *CEPOCH*, and adding it to *Q* after receiving *ACK-LD*. These steps describe how leader lets a new member join the cluster, but the paper lacks the process of how a certain server finds a leader and sends *CEPOCH* to it. Here we imitate the recovery mechanism of View-Stamped Replication. The specific process is:  
1.	After a server restarts(*Restart*), it will send a message with type *RECOVERYREQUEST* to all other servers(*RecoveryAfterRestart*).  
2.	Servers reply its *leaderOracle* and *currentEpoch* when receiving *RECOVERYREQUEST*(*HandleRecoveryRequest*).  
3.	When the server receives reply from a quorum, is selects data from reply with the biggest epoch and non-empty oracle to update. Then it may find the leader, and send *CEPOCH* to the leader to try to achieve state consistency(*HandleRecoveryResponse*,*FindCluster*).  

It is common when the leader the server finds is not the latest leader. In this case, it will not receive ack of *CEPOCH* and search new leader again.

### Issue 5 Line: 340, Action: LeaderHandleCEPOCH
*Step l.3.3* and *Step l.3.4* in paper only describe the process when a leader receives *CEPOCH* and *ACK-LD*, and do not describe case when a prospective leader receives *CEPOCH* and *ACK-LD* from servers not belonging to *Q*. In specification, when the prospective leader receives *CEPOCH* from server *i*, if server *i* does not belong to *Q*, it adds *i* to *Q*(comparing that leader adds *i* to *Q* when receiving *ACK-LD*). The prospective leader will then judge whether it has broadcast *NEWEPOCH* and *NEWLEADER* and send them to *i* if not. Here we do not need to determine whether the prospective leader has broadcast *COMMIT-LD*, because its *state* will change to *Leader* after *COMMIT-LD* has been broadcast.

### Issue 6 Line: 579, Action: FollowerBroadcast1
In fact, it is not enough for followers to judge whether one transaction is legal when receiving *COMMIT*. When receiving *PROPOSE*, followers should also make a judgment on the message instead of directly adding the transaction to *history* and replying *ACK*. This may lead to an error change in *ackIndex* and consequently lead to a n error change in *commitIndex*. The following is a situation where the follower does not make a judgment when receiving *PROPOSE*, and thus makes an error.
![pic wrong commitIndex](doc-in-chinese/picture/pic_double_same_transaction.PNG)  
Therefore, we judge when the follower receive *PROPOSE*. If the transaction does not meet the conditions for adding to *history*, followers will reply *CEPOCH* to request an update message.

### Issue 7 Line: 651, Action: FollowerBroadcast2
We can consider that when a server is in *Q* from election phase, it will receive messages from the leader in order. So the committed transaction in *COMMIT* it receives must exist in its local history. But for servers that join in *Q* after election phase, this property may not always be satisfied.  
We consider such situation(as the picture shows). After a certain node *j* finds leader *i*, it sends *CEPOCH* to *i*, and next *i* and *j* interact messages normally. After receiving *ACK-LD*, *i* adds *j* to *Q*. But after *i* sends *NEWLEADER*, i received a client request to update history and broadcast a message with type *PROPOSE*, which is shielded for *j*. After *j* joins *Q*, *j* receives *COMMIT* of the request, but the committed transaction can not be found in its history.  
![pic recovery](doc-in-chinese/picture/pic_recovery.PNG)  
For this situation, our solution is that when the transaction in *COMMIT* received by a follower is not locally available, *CEPOCH* is transmitted to the leader to seek state consistency. That is, a server will send *CEPOCH* to leader when it is in phase 1(*Discovery*), or wants to join some cluster, or finds missing transactions.