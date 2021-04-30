# Zab-tla

## Overview
This project is devoted to providing formal specification and verification using TLA+ for the Zookeeper Atomic Broadcast(Zab) consensus protocol proposed in *Junqueira F P, Reed B C, Serafini M. Zab: High-performance broadcast for primary-backup systems[C]//2011 IEEE/IFIP 41st International Conference on Dependable Systems & Networks (DSN). IEEE, 2011: 245-256*.  

We have made a formal specification for Zab using TLA+ toolbox, and we have done a certain scale of model checking to verify the correctness of Zab.

Due to the simplification of Zab algorithm description in the paper, some details in specification were modified and added. If you have any question, please let us know.

You can find this document in chinese in [doc-in-chinsese](doc-in-chinese/README.md).

## Requirements
TLA+ toolbox version 1.7.0

## Run
Create specification and run models in the usual way.  
For example, if you want to check model with 3 servers, 2 rounds and 2 delivered transactions, you can create spec [experiment/ZabWithQTest.tla](experiment/ZabWithQTest.tla) and set *Server* as symmetrical model value {s1,s2,s3}.  

You can find our [result](experiment/README.md) of verification using model checking of TLA+.

## Abstraction in specification
>The Zab protocol in paper dose not focus on leader election, so we abstract the process of leader election in spec. Our spec can simulate non-Byzantion faults. In addition, what we pay attention to is consistency of system state, and we abstract or omit some parts in actual implementation, such as replying results to client.

### Note 1
Except for the action *Election*, all actions perform on a specific server to reflect the feature of distributed. Since the paper does not pay attention to the process of selecting a leader, we abstract this process and it can be reflected in action *Election*. *Election* and actions which call it are the only actions that are abstracted in the whole specification.

### Note 2
Communication in Zab is based on TCP channels, so there is no packet loss, redundancy, or disorder in message delivery. We use module *Sequence* in TLA+ to simulate the property of receiving messages in order.  
We believe it can simulate message delay when a server does not perform the action of receiving messages. And it can simulate a process failuere when a server does not perform any action.

### Note 3
What we care about is consistecy of the state in the system. We do not care about details like client's request to the system or the system's reply to client, or server delivering transactions to replica. Therefore, we simplify the process of client requesting, and omit reply to client. We assume that each committed transaction will be delivered to replica immediately, so we can treat variable history[i][1..commitIndex] as the transaction sequence that server *i* delivers to the corresponding replica.

## Differences from paper
>This section describes difference between the protocol in paper and our specification.

### Issue 1 Line: 196(line number in Zab.tla), Action: Election
In *Step l.1.1* and *Step l.2.2* in paper, a prospective leader will not perform the next action until receiving a quorum of followers. It obviously affects availability. We think the leader itself should be a member of the quorum. So, when we reset variables *cepochRecv*, *ackeRecv* and *ackldRecv* in the action *Election*, we initialize these sets with adding leader ID to the sets.  
In addition, according to *Step l.1.1* in paper, we know that the prospective leader determines its *cluster*(*Q* in paper) is based on information from *CEPOCH* received. So, Q is a set not satisfying the property of quorum in the initial stage of Phase 1(*Discovery*), which may trigger action *LeaderTimeout* to perform a new round of election. For this reason, we initialize *Q* in action *Election*, so that *Q* maintains the property of quorum anytime in this round.

### Issue 2 Line: 417, Action: LeaderHandleACKE; Line: 442, Action: LeaderDiscovery2Sync1
In *Step l.1.2* in paper, the prospective leader selects best information to update after receiving *ACK-E* from each follower in *Q*. We think this condition is relatively harsh. In specification, the prospective leader will select information to update and broadcast *NEWLEADER* after receiving a quorum of followers. Whether you choose the method in paper or the method in specification, it does not trigger a threat to the correctness of the algorithm.

### Issue 3 Line: 465, Action: FollowerSync1
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

### Issue 6 Line: 636, Action: FollowerBroadcast2
We can consider that when a server is in *Q* from election phase, it will receive messages from the leader in order. So the committed transaction in *COMMIT* it receives must exist in its local history. But for servers that join in *Q* after election phase, this property may not always be satisfied.  
We consider such situation(as the picture shows). After a certain node *j* finds leader *i*, it sends *CEPOCH* to *i*, and next *i* and *j* interact messages normally. After receiving *ACK-LD*, *i* adds *j* to *Q*. But after *i* sends *NEWLEADER*, i received a client request to update history and broadcast a message with type *PROPOSE*, which is shielded for *j*. After *j* joins *Q*, *j* receives *COMMIT* of the request, but the committed transaction can not be found in its history.  
![pic recovery](doc-in-chinese/picture/pic_recovery.PNG)  
For this situation, our solution is that when the transaction in *COMMIT* received by a follower is not locally available, *CEPOCH* is transmitted to the leader to seek state consistency. That is, a server will send *CEPOCH* to leader when it is in phase 1(*Discovery*), or wants to join some cluster, or finds missing transactions.