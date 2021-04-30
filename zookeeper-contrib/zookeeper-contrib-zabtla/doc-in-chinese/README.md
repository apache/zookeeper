# Zab的TLA+规约 文档

## 概述
-	本实验是由论文*Junqueira F P, Reed B C, Serafini M. Zab: High-performance broadcast for primary-backup systems[C]//2011 IEEE/IFIP 41st International Conference on Dependable Systems & Networks (DSN). IEEE, 2011: 245-256.*启发。本实验根据该论文描述的Zab协议进行了Zab的TLA+规约。
-	我们对Zab使用TLA+工具做了形式化规约，并在此基础上做了一定量的模型检验来验证Zab的正确性。
-	由于论文中对Zab算法描述的精简和细节上的省略，在进行规约时协议中的一些细节由本实验作者进行修改和增加。如有疑问，欢迎指出。

## 支撑平台
TLA+ toolbox 版本1.7.0

## 运行
创建规约并以正常方式建模并运行模型。  
例如，如果你想测试一个规模为3台服务器、执行2轮、被提交事务数量为2的模型，你可以创建test/ZabWithQTest.tla的规约并将*Server*设置为对称的模型值{s1,s2,s3}。

## 规约中的抽象
>论文中的Zab协议不关注选主过程，我们对选主过程进行抽象；且规约中能够模拟系统中的非拜占庭错误；此外，我们关注的是系统内状态的一致，从而抽象或省略了如向客户端回复结果等一些实际实现中的处理。

### Note 1
除了*Election*动作外，其余所有动作都是作用于某一具体服务器上的，这体现了"distributed"这一特性。因为论文不注重选主过程，所以在规约中我们抽象了选主过程，将其在*Election*动作中体现。*Election*动作及调用了*Election*的动作是规约中仅有的被抽象的动作。

### Note 2
Zab使用的通信信道是TCP信道，所以消息传递不会出现丢包、冗余、乱序的情况，我们用*Sequence*模块来模拟满足按序接收消息的性质。我们认为某个服务器不执行接收消息的动作可以模拟消息延迟，某个服务器不执行任何动作可以模拟单机失败。

### Note 3
我们关心的是系统内状态的一致，不关心客户端(client)向系统请求执行和系统向客户端回复结果等的细节，以及各个服务器向副本(复制状态机)提交事务(deliver transaction)的细节。因此我们粗化了*ClientRequest*，省略了向客户端的回复。我们假设每一个可提交的事务会被立即提交到副本中，故可用变量*history[i][1..commitIndex]*来模拟节点*i*向副本提交的事务序列。

## 差异
>该部分描述的是规约与论文的协议之间的差异。

### Issue 1 Line: 196(对应于Zab.tla中的行号), Action: Election
在论文*Step l.1.1，Stepl.2.2*中，当准领导者(prospective leader)接收到一个多数派(quorum)的跟随者(followers)的消息才会作出下一个动作，这显然降低了系统可用性，我们应该把领导者自身也算入到该多数派中。考虑这样一种情况，系统内有3台服务器，其中有一台宕机，那么剩余两台服务器组成的集群中，准领导者接收到的某一消息最多只会来自于另一台，从而导致无法做出下一个动作，整个系统无法推进，这显然是违背了共识协议的高可用性的。于是，我们在动作*Election*中对变量*cepochRecv*，*ackeRecv*和*ackldRecv*重置时就将领导者节点ID加入到集合中。  
此外，准领导者是根据论文*Step l.1.1*中的*CEPOCH*的接收信息来确定它的集群*Q*。那么在Phase1(*Discovery*)初始阶段，*Q*是一个不满足多数派性质的集合(set)，这可能会触发动作*LeaderTimeout*来进行一轮新的选主(leader election)。故我们在动作*Election*中先确定了*Q*，使其在这轮中始终满足多数派性质。


### Issue 2 Line: 417, Action: LeaderHandleACKE; Line: 442, Action: LeaderDiscovery2Sync1
在论文*Step l.1.2*中，准领导者接收到*Q*中每一个跟随者的关于*NEWEPOCH*的回复后在此选出最佳的数据进行更新。我们认为这个条件比较苛刻，在规约中当准领导者接收到一个多数派的跟随者后就会选出最佳的数据进行更新。无论是选择论文中的做法，还是本实验中的做法，对算法的正确性不构成威胁。

### Issue 3 Line: 465, Action: FollowerSync1
在论文*Step f.2.1*中，由于在一般情况下，*Q*中的每个跟随者会在接收到*NEWLEADER*之前先接收到*NEWEPOCH*，故节点*i*的*currentEpoch[i]*与*NEWLEADER*中的epoch是相等的。在某些极端情况下，会有节点*i*的*currentEpoch[i]*比*NEWLEADER*中的epoch大。我们选择丢弃这样的比自己的epoch小的消息，论文中选择的是进行新一轮的选主。


## 添加
>该部分描述的是规约中相比与论文协议进行扩展增加的部分。

### Issue 4 Line: 261, Action: Restart, RecoveryAfterRestart, HandleRecoveryRequest, HandleRecoveryResponse, FindCluster
论文*Step l.3.3*和*Stepl.3.4*描述了领导者接收到*CEPOCH*进行回复，并在接收到*ACK-LD*后将其加入集群*Q*的过程。但是文中缺少了某个节点是如何找到领导者并给领导者发送*CEPOCH*的。这里我们给出了自己的想法，我们模仿*View-Stamped Replication*的恢复机制。具体过程为:  
1.	某一个节点重启(*Restart*)后，它会向其他节点发送*RECOVERYREQUEST*类型的消息(*RecoveryAfterRestart*)。	  
2.	其他节点接收到该消息时将本地的*leaderOracle*和*currentEpoch*返回(*HandleRecoveryRequest*)  。
3.	当该节点收到多数派的回复时，选取回复中epoch最大且oracle非空的数据进行更新，我们认为此时它找到了领导者，并向其发送*CEPOCH*请求同步(*HandleRecoveryResponse*,*FindCluster*)。  

当然，该重启恢复的节点找到的集群不一定的最新的集群，在这种情况下它会因为没得到CEPOCH的回复而重新进行寻找。

### Issue 5 Line: 340, Action: LeaderHandleCEPOCH
论文*Step l.3.3*和*Stepl.3.4*仅描述了领导者接收到*CEPOCH*和*ACK-LD*时的处理，而没有描述在Phase1、Phase2阶段的准领导者接收到来自不属于*Q*的节点的*CEPOCH*和*ACK-LD*的处理，我们考虑了这种情况下的处理方式。当准领导者接收到来自节点*i*的*CEPOCH*，若*i*不属于*Q*，则先将*i*加入*Q*(在Phase3阶段的领导者是接收到*ACK-LD*时将*i*加入到*Q*)。随后准领导者会判断自己是否已经广播过*NEWEPOCH*和*NEWLEADER*，来确保新加入*Q*的成员不会错失消息。这里我们不需要判断准领导者是否广播过*COMMIT-LD*，因为广播了*COMMIT-LD*后状态会转为*Leader*。

### Issue 6 Line: 636, Action: FollowerBroadcast2
我们考虑，当一个节点从选主阶段开始就在集群中，它顺序接收来自领导者的消息，那么它每次收到的*COMMIT*中允许被提交的事务一定存在于本地的*history*中。但是对于后加入集群的节点，这样的性质不一定一直被满足。  
我们考虑这样的情况，某一节点*j*找到领导者*i*后，向*i*发送*CEPOCH*以加入集群，*i*与*j*随后正常交互，在收到*ACK-LD*后领导者将*j*加入集群*Q*中。但在领导者*i*发送*NEWLEADER*后，*i*收到某个客户端请求,修改了*history*并广播一个*PROPOSE*类型的消息，这对*j*来说是屏蔽的，因为此时*j*还没有加入集群*Q*。但是在*j*加入集群后，*j*收到了该请求的*COMMIT*，但该可被提交的事务不能在它的*history*中被找到。流程如下图所示。
![pic recovery](picture/pic_recovery.PNG)  
因此，我们所做出的假设是，当某一跟随者收到的*COMMIT*中的事务是本地没有的时，对领导者重发*CEPOCH*来寻求状态一致。即某节点在Phase1 *Discovery*阶段，或在重启后想要加入集群时，或由于缺失部分事务而试图寻求状态一致时，都会向领导者发送*CEPOCH*。