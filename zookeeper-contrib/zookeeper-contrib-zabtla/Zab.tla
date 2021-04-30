-------------------------------- MODULE Zab --------------------------------
\* This is the formal specification for the Zab consensus algorithm,
\* which means Zookeeper Atomic Broadcast.

\* This work is driven by  Junqueira F P, Reed B C, Serafini M. Zab: High-performance broadcast for primary-backup systems

EXTENDS Integers, FiniteSets, Sequences, Naturals, TLC

\* The set of server identifiers
CONSTANT Server

\* The set of requests that can go into history
CONSTANT Value

\* Server states
\* It is unnecessary to add state ELECTION, we can own it by setting leaderOracle to Null.
CONSTANTS Follower, Leader, ProspectiveLeader

\* Message types
CONSTANTS CEPOCH, NEWEPOCH, ACKE, NEWLEADER, ACKLD, COMMITLD, PROPOSE, ACK, COMMIT

\* Additional Message types used for recovery when restarting
CONSTANTS RECOVERYREQUEST, RECOVERYRESPONSE

\* the maximum round of epoch, currently not used
\* CONSTANT Epoches
----------------------------------------------------------------------------
\* Return the maximum value from the set S
Maximum(S) == IF S = {} THEN -1
                        ELSE CHOOSE n \in S: \A m \in S: n >= m

\* Return the minimum value from the set S
Minimum(S) == IF S = {} THEN -1
                        ELSE CHOOSE n \in S: \A m \in S: n <= m

Quorums == {Q \in SUBSET Server: Cardinality(Q)*2 > Cardinality(Server)}
ASSUME QuorumsAssumption == /\ \A Q \in Quorums: Q \subseteq Server
                            /\ \A Q1, Q2 \in Quorums: Q1 \cap Q2 /= {}                           

None == CHOOSE v: v \notin Value

NullPoint == CHOOSE p: p \notin Server
----------------------------------------------------------------------------
\* The server's state(Follower,Leader,ProspectiveLeader).
VARIABLE state

\* The leader's epoch or the last new epoch proposal the follower acknowledged
\* (namely epoch of the last NEWEPOCH accepted, f.p in paper).
VARIABLE currentEpoch

\* The last new leader proposal the follower acknowledged
\* (namely epoch of the last NEWLEADER accepted, f.a in paper).
VARIABLE leaderEpoch

\* The identifier of the leader for followers.
VARIABLE leaderOracle

\* The history of servers as the sequence of transactions.
VARIABLE history

\* The messages repersenting requests and responses sent from one server to another.
\* msgs[i][j] means the input buffer of server j from server i.
VARIABLE msgs

\* The set of servers which the leader think follow itself (Q in paper).
VARIABLE cluster

\* The set of followers who has successfully sent CEPOCH to pleader in pleader.
VARIABLE cepochRecv

\* The set of followers who has successfully sent ACK-E to pleader in pleader.
VARIABLE ackeRecv

\* The set of followers who has successfully sent ACK-LD to pleader in pleader.
VARIABLE ackldRecv

\* ackIndex[i][j] means leader i has received how many ACK messages from follower j.
\* So ackIndex[i][i] is not used.
VARIABLE ackIndex

\* currentCounter[i] means the count of transactions client requests leader.
VARIABLE currentCounter

\* sendCounter[i] means the count of transactions leader has broadcast.
VARIABLE sendCounter

\* initialHistory[i] means the initial history of leader i in epoch currentEpoch[i].
VARIABLE initialHistory

\* commitIndex[i] means leader/follower i should commit how many proposals and sent COMMIT messages.
\* It should be more formal to add variable applyIndex/deliverIndex to represent the prefix entries of the history
\* that has applied to state machine, but we can tolerate that applyIndex(deliverIndex here) = commitIndex.
\* This does not violate correctness. (commitIndex increases monotonically before restarting)
VARIABLE commitIndex

\* commitIndex[i] means leader i has committed how many proposals and sent COMMIT messages.
VARIABLE committedIndex

\* Hepler matrix for follower to stop sending CEPOCH to pleader in followers.
\* Because CEPOCH is the sole message which follower actively sends to pleader.
VARIABLE cepochSent

\* the maximum epoch in CEPOCH pleader received from followers.
VARIABLE tempMaxEpoch

\* the maximum leaderEpoch and most up-to-date history in ACKE pleader received from followers.
VARIABLE tempMaxLastEpoch

\* Because pleader updates state and broadcasts NEWLEADER when it receives ACKE from a quorum of followers,
\* and initialHistory is determined. But tempInitialHistory may change when receiving other ACKEs after entering into phase2.
\* So it is necessary to split initialHistory with tempInitialHistory.
VARIABLE tempInitialHistory

\* the set of all broadcast messages whose tpye is proposal that any leader has sent, only used in verifying properties.
\* So the variable will only be changed in transition LeaderBroadcast1.
VARIABLE proposalMsgsLog

\* Helper set for server who restarts to collect which servers has responded to it.
VARIABLE recoveryRespRecv

\* the maximum epoch and corresponding leaderOracle in RECOVERYRESPONSE from followers.
VARIABLE recoveryMaxEpoch

VARIABLE recoveryMEOracle

VARIABLE recoverySent

\* Persistent state of a server: history, currentEpoch, leaderEpoch
serverVars == <<state, currentEpoch, leaderEpoch, leaderOracle, history, commitIndex>>
leaderVars == <<cluster, cepochRecv, ackeRecv, ackldRecv, ackIndex, currentCounter, sendCounter, initialHistory, committedIndex>>
tempVars   == <<tempMaxEpoch, tempMaxLastEpoch, tempInitialHistory>>
recoveryVars == <<recoveryRespRecv, recoveryMaxEpoch, recoveryMEOracle, recoverySent>>

vars == <<serverVars, msgs, leaderVars, tempVars, recoveryVars, cepochSent, proposalMsgsLog>>
----------------------------------------------------------------------------
LastZxid(his) == IF Len(his) > 0 THEN <<his[Len(his)].epoch,his[Len(his)].counter>>
                                 ELSE <<-1, -1>>

\* Add a message to msgs - add a message m to msgs[i][j]
Send(i, j, m) == msgs' = [msgs EXCEPT ![i][j] = Append(msgs[i][j], m)]

Send2(i, j, m1, m2) == msgs' = [msgs EXCEPT ![i][j] = Append(Append(msgs[i][j], m1), m2)]

\* Remove a message from msgs - discard head of msgs[i][j]
Discard(i, j) == msgs' = IF msgs[i][j] /= << >> THEN [msgs EXCEPT ![i][j] = Tail(msgs[i][j])]
                                                ELSE msgs

\* Leader/Pleader broadcasts a message to all other servers in Q
Broadcast(i, m) == msgs' = [ii \in Server |-> [ij \in Server |-> IF /\ ii = i 
                                                                    /\ ij # i
                                                                    /\ ij \in cluster[i] THEN Append(msgs[ii][ij], m)
                                                                                         ELSE msgs[ii][ij]]] 

BroadcastToAll(i, m) == msgs' = [ii \in Server |-> [ij \in Server |-> IF /\ ii = i /\ ij # i THEN Append(msgs[ii][ij], m)
                                                                                             ELSE msgs[ii][ij]]]

\* Combination of Send and Discard - discard head of msgs[j][i] and add m into msgs[i][j]
Reply(i, j, m) == msgs' = [msgs EXCEPT ![j][i] = Tail(msgs[j][i]),
                                       ![i][j] = Append(msgs[i][j], m)]

Reply2(i, j, m1, m2) == msgs' = [msgs EXCEPT ![j][i] = Tail(msgs[j][i]),
                                             ![i][j] = Append(Append(msgs[i][j], m1), m2)]

clean(i, j) == msgs' = [msgs EXCEPT ![i][j] = << >>, ![j][i] = << >>]

----------------------------------------------------------------------------
\* Define initial values for all variables 
Init == /\ state              = [s \in Server |-> Follower]
        /\ currentEpoch       = [s \in Server |-> 0]
        /\ leaderEpoch        = [s \in Server |-> 0]
        /\ leaderOracle       = [s \in Server |-> NullPoint]
        /\ history            = [s \in Server |-> << >>]
        /\ msgs               = [i \in Server |-> [j \in Server |-> << >>]]
        /\ cluster            = [i \in Server |-> {}]
        /\ cepochRecv         = [s \in Server |-> {}]
        /\ ackeRecv           = [s \in Server |-> {}]
        /\ ackldRecv          = [s \in Server |-> {}]
        /\ ackIndex           = [i \in Server |-> [j \in Server |-> 0]]
        /\ currentCounter     = [s \in Server |-> 0]
        /\ sendCounter        = [s \in Server |-> 0]
        /\ commitIndex        = [s \in Server |-> 0]
        /\ committedIndex     = [s \in Server |-> 0]
        /\ initialHistory     = [s \in Server |-> << >>]
        /\ cepochSent         = [s \in Server |-> FALSE]
        /\ tempMaxEpoch       = [s \in Server |-> 0]      
        /\ tempMaxLastEpoch   = [s \in Server |-> 0]
        /\ tempInitialHistory = [s \in Server |-> << >>]
        /\ recoveryRespRecv   = [s \in Server |-> {}]
        /\ recoveryMaxEpoch   = [s \in Server |-> 0]
        /\ recoveryMEOracle   = [s \in Server |-> NullPoint]
        /\ recoverySent       = [s \in Server |-> FALSE]
        /\ proposalMsgsLog    = {}

----------------------------------------------------------------------------     
\* A server becomes pleader and a quorum servers knows that.
Election(i, Q) ==
        /\ i \in Q
        /\ state'              = [s \in Server |-> IF s = i THEN ProspectiveLeader
                                                            ELSE IF s \in Q THEN Follower
                                                                            ELSE state[s]]
        /\ cluster'            = [cluster    EXCEPT ![i] = Q] \* cluster is first initialized in election, not phase1.
        /\ cepochRecv'         = [cepochRecv EXCEPT ![i] = {i}]
        /\ ackeRecv'           = [ackeRecv   EXCEPT ![i] = {i}]
        /\ ackldRecv'          = [ackldRecv  EXCEPT ![i] = {i}]
        /\ ackIndex'           = [ii \in Server |-> [ij \in Server |->
                                                    IF ii = i THEN 0
                                                              ELSE ackIndex[ii][ij]]]
        /\ committedIndex'     = [committedIndex     EXCEPT ![i] = 0]
        /\ initialHistory'     = [initialHistory     EXCEPT ![i] = << >>]
        /\ tempMaxEpoch'       = [tempMaxEpoch       EXCEPT ![i] = currentEpoch[i]]
        /\ tempMaxLastEpoch'   = [tempMaxLastEpoch   EXCEPT ![i] = currentEpoch[i]]
        /\ tempInitialHistory' = [tempInitialHistory EXCEPT ![i] = history[i]]
        /\ leaderOracle'       = [s \in Server |-> IF s \in Q THEN i
                                                              ELSE leaderOracle[s]]
        /\ leaderEpoch'        = [s \in Server |-> IF s \in Q THEN currentEpoch[s]
                                                              ELSE leaderEpoch[s]]
        /\ cepochSent'         = [s \in Server |-> IF s \in Q THEN FALSE
                                                              ELSE cepochSent[s]]
        /\ msgs'               = [ii \in Server |-> [ij \in Server |-> 
                                                     IF ii \in Q \/ ij \in Q THEN << >>
                                                                             ELSE msgs[ii][ij]]]

\* The action should be triggered once at the beginning.
\* Because we abstract the part of leader election, we can use global variables in this action.
InitialElection(i, Q) ==
        /\ \A s \in Server: state[i] = Follower /\ leaderOracle[i] = NullPoint
        /\ Election(i, Q)
        /\ UNCHANGED <<currentEpoch, history, commitIndex, currentCounter, sendCounter, recoveryVars, proposalMsgsLog>>

\* The leader finds timeout with another follower.
LeaderTimeout(i, j) ==
        /\ state[i] # Follower
        /\ j /= i
        /\ j \in cluster[i]
        /\ LET newCluster == cluster[i] \ {j}
           IN /\ \/ /\ newCluster \in Quorums
                    /\ cluster' = [cluster EXCEPT ![i] = newCluster]
                    /\ clean(i, j)
                    /\ UNCHANGED<<state, cepochRecv,ackeRecv, ackldRecv, ackIndex, committedIndex, initialHistory,
                                  tempMaxEpoch, tempMaxLastEpoch, tempInitialHistory, leaderOracle, leaderEpoch, cepochSent>>
                 \/ /\ newCluster \notin Quorums
                    /\ \* LET Q == CHOOSE q \in Quorums: i \in q
                       \*     v == CHOOSE s \in Q: TRUE
                       \* IN Election(v, Q)
                       \E Q \in Quorums: /\ i \in Q
                                         /\ \E v \in Q: Election(v, Q)
        /\ UNCHANGED <<currentEpoch, history, commitIndex, currentCounter, sendCounter, recoveryVars, proposalMsgsLog>>
        
\* A follower finds timeout with the leader.
FollowerTimeout(i) ==
        /\ state[i] = Follower
        /\ leaderOracle[i] /= NullPoint
        /\ \E Q \in Quorums: /\ i \in Q
                             /\ \E v \in Q: Election(v, Q)
        /\ UNCHANGED <<currentEpoch, history, commitIndex, currentCounter, sendCounter, recoveryVars, proposalMsgsLog>>

----------------------------------------------------------------------------
\* A server halts and restarts.
\* Like Recovery protocol in View-stamped Replication, we let a server join in cluster
\* by broadcast recovery and wait until receiving responses from a quorum of servers.
Restart(i) ==
        /\ state'        = [state        EXCEPT ![i] = Follower]
        /\ leaderOracle' = [leaderOracle EXCEPT ![i] = NullPoint]
        /\ commitIndex'  = [commitIndex  EXCEPT ![i] = 0]   
        /\ cepochSent'   = [cepochSent   EXCEPT ![i] = FALSE]
        /\ msgs'         = [ii \in Server |-> [ij \in Server |-> IF ij = i THEN << >>
                                                                           ELSE msgs[ii][ij]]]  
        /\ recoverySent' = [recoverySent EXCEPT ![i] = FALSE]     
        /\ UNCHANGED <<currentEpoch, leaderEpoch, history, leaderVars, tempVars, 
                       recoveryRespRecv, recoveryMaxEpoch, recoveryMEOracle, proposalMsgsLog>>

RecoveryAfterRestart(i) ==
        /\ state[i] = Follower
        /\ leaderOracle[i] = NullPoint
        /\ \lnot recoverySent[i]
        /\ recoveryRespRecv' = [recoveryRespRecv EXCEPT ![i] = {}]
        /\ recoveryMaxEpoch' = [recoveryMaxEpoch EXCEPT ![i] = currentEpoch[i]]
        /\ recoveryMEOracle' = [recoveryMEOracle EXCEPT ![i] = NullPoint]
        /\ recoverySent'     = [recoverySent     EXCEPT ![i] = TRUE]
        /\ BroadcastToAll(i, [mtype |-> RECOVERYREQUEST])
        /\ UNCHANGED <<serverVars, leaderVars, tempVars, cepochSent, proposalMsgsLog>>

HandleRecoveryRequest(i, j) ==
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = RECOVERYREQUEST
        /\ Reply(i, j, [mtype   |-> RECOVERYRESPONSE,
                        moracle |-> leaderOracle[i],
                        mepoch  |-> currentEpoch[i]])
        /\ UNCHANGED <<serverVars, leaderVars, tempVars, cepochSent, recoveryVars, proposalMsgsLog>>

HandleRecoveryResponse(i, j) ==
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = RECOVERYRESPONSE
        /\ LET msg    == msgs[j][i][1]
               infoOk == /\ msg.mepoch >= recoveryMaxEpoch[i]
                         /\ msg.moracle /= NullPoint
           IN \/ /\ infoOk
                 /\ recoveryMaxEpoch' = [recoveryMaxEpoch EXCEPT ![i] = msg.mepoch]
                 /\ recoveryMEOracle' = [recoveryMEOracle EXCEPT ![i] = msg.moracle]
              \/ /\ ~infoOk
                 /\ UNCHANGED <<recoveryMaxEpoch, recoveryMEOracle>>
        /\ Discard(j, i)
        /\ recoveryRespRecv' = [recoveryRespRecv EXCEPT ![i] = IF j \in recoveryRespRecv[i] THEN recoveryRespRecv[i]
                                                                                            ELSE recoveryRespRecv[i] \union {j}]
        /\ UNCHANGED <<serverVars, leaderVars, tempVars, cepochSent, recoverySent, proposalMsgsLog>>

FindCluster(i) == 
        /\ state[i] = Follower
        /\ leaderOracle[i] = NullPoint
        /\ recoveryRespRecv[i] \in Quorums
        /\ LET infoOk == /\ recoveryMEOracle[i] /= i
                         /\ recoveryMEOracle[i] /= NullPoint
                         /\ currentEpoch[i] <= recoveryMaxEpoch[i]
           IN \/ /\ ~infoOk
                 /\ recoverySent' = [recoverySent EXCEPT ![i] = FALSE]
                 /\ UNCHANGED <<currentEpoch, leaderOracle, msgs>>
              \/ /\ infoOk
                 /\ currentEpoch' = [currentEpoch EXCEPT ![i] = recoveryMaxEpoch[i]]
                 /\ leaderOracle' = [leaderOracle EXCEPT ![i] = recoveryMEOracle[i]]
                 /\ Send(i, recoveryMEOracle[i], [mtype |-> CEPOCH,
                                                  mepoch|-> recoveryMaxEpoch[i]])
                 /\ UNCHANGED recoverySent
        /\ UNCHANGED <<state, leaderEpoch, history, commitIndex, leaderVars, tempVars, 
                       recoveryRespRecv, recoveryMaxEpoch, recoveryMEOracle, cepochSent, proposalMsgsLog>>
        
----------------------------------------------------------------------------
\* In phase f11, follower sends f.p to pleader via CEPOCH.
FollowerDiscovery1(i) ==
        /\ state[i] = Follower
        /\ leaderOracle[i] /= NullPoint
        /\ \lnot cepochSent[i]
        /\ LET leader == leaderOracle[i]
           IN Send(i, leader, [mtype  |-> CEPOCH,
                               mepoch |-> currentEpoch[i]])
        /\ cepochSent' = [cepochSent EXCEPT ![i] = TRUE]
        /\ UNCHANGED <<serverVars, leaderVars, tempVars, recoveryVars, proposalMsgsLog>>

\* In phase l11, pleader receives CEPOCH from a quorum, and choose a new epoch e'
\* as its own l.p and sends NEWEPOCH to followers.                 
LeaderHandleCEPOCH(i, j) ==
        /\ state[i] = ProspectiveLeader
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = CEPOCH
        /\ \/ \* new message - modify tempMaxEpoch and cepochRecv
              /\ NullPoint \notin cepochRecv[i]
              /\ LET newEpoch == Maximum({tempMaxEpoch[i],msgs[j][i][1].mepoch})
                 IN tempMaxEpoch' = [tempMaxEpoch EXCEPT ![i] = newEpoch]
              /\ cepochRecv' = [cepochRecv EXCEPT ![i] = IF j \in cepochRecv[i] THEN cepochRecv[i]
                                                                                ELSE cepochRecv[i] \union {j}]
              /\ Discard(j, i)
           \/ \* new follower who joins in cluster / follower whose history and commitIndex do not match
              /\ NullPoint \in cepochRecv[i]
              /\ \/ /\ NullPoint \notin ackeRecv[i]
                    /\ Reply(i, j, [mtype |-> NEWEPOCH,
                                    mepoch|-> leaderEpoch[i]])
                 \/ /\ NullPoint \in ackeRecv[i]
                    /\ Reply2(i, j, [mtype |-> NEWEPOCH,
                                     mepoch|-> leaderEpoch[i]],
                                    [mtype           |-> NEWLEADER,
                                     mepoch          |-> currentEpoch[i],
                                     minitialHistory |-> initialHistory[i]])
              /\ UNCHANGED <<cepochRecv, tempMaxEpoch>>
        /\ cluster' = [cluster EXCEPT ![i] = IF j \in cluster[i] THEN cluster[i] ELSE cluster[i] \union {j}]
        /\ UNCHANGED <<serverVars, ackeRecv, ackldRecv, ackIndex, currentCounter, sendCounter, initialHistory,
                       committedIndex, cepochSent, tempMaxLastEpoch, tempInitialHistory, recoveryVars, proposalMsgsLog>>

\* Here I decide to change leader's epoch in l12&l21, otherwise there may exist an old leader and
\* a new leader who share the same expoch. So here I just change leaderEpoch, and use it in handling ACK-E.
LeaderDiscovery1(i) ==
        /\ state[i] = ProspectiveLeader
        /\ cepochRecv[i] \in Quorums
        /\ leaderEpoch' = [leaderEpoch EXCEPT ![i] = tempMaxEpoch[i] + 1]
        /\ cepochRecv'  = [cepochRecv  EXCEPT ![i] = {NullPoint}]
        /\ Broadcast(i, [mtype  |-> NEWEPOCH,
                         mepoch |-> leaderEpoch'[i]])
        /\ UNCHANGED <<state, currentEpoch, leaderOracle, history, cluster, ackeRecv, ackldRecv, ackIndex, currentCounter, sendCounter,
                       initialHistory, commitIndex, committedIndex, cepochSent, tempVars, recoveryVars, proposalMsgsLog>>

\* In phase f12, follower receives NEWEPOCH. If e' > f.p then sends back ACKE,
\* and ACKE contains f.a and hf to help pleader choose a newer history.
FollowerDiscovery2(i, j) ==
        /\ state[i] = Follower
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = NEWEPOCH
        /\ LET msg == msgs[j][i][1]
           IN \/ \* new NEWEPOCH - accept and reply
                 /\ currentEpoch[i] < msg.mepoch 
                 /\ \/ /\ leaderOracle[i] = j
                       /\ currentEpoch' = [currentEpoch EXCEPT ![i] = msg.mepoch]
                       /\ Reply(i, j, [mtype      |-> ACKE,
                                       mepoch     |-> msg.mepoch,
                                       mlastEpoch |-> leaderEpoch[i],
                                       mhf        |-> history[i]])
                    \/ /\ leaderOracle[i] /= j
                       /\ Discard(j ,i)
                       /\ UNCHANGED currentEpoch
              \/ /\ currentEpoch[i] = msg.mepoch
                 /\ \/ /\ leaderOracle[i] = j
                       /\ Reply(i, j, [mtype      |-> ACKE,
                                       mepoch     |-> msg.mepoch,
                                       mlastEpoch |-> leaderEpoch[i],
                                       mhf        |-> history[i]])
                       /\ UNCHANGED currentEpoch
                    \/ \* It may happen when a leader do not update new epoch to all followers in Q, and a new election begins
                       /\ leaderOracle[i] /= j
                       /\ Discard(j, i)
                       /\ UNCHANGED currentEpoch
              \/ \* stale NEWEPOCH - diacard
                 /\ currentEpoch[i] > msg.mepoch
                 /\ Discard(j, i)
                 /\ UNCHANGED currentEpoch
        /\ UNCHANGED<<state, leaderEpoch, leaderOracle, history, leaderVars, 
                      commitIndex, cepochSent, tempVars, recoveryVars, proposalMsgsLog>>

\* In phase l12, pleader receives ACKE from a quorum, 
\* and select the history of one most up-to-date follower to be the initial history.          
LeaderHandleACKE(i, j) ==
        /\ state[i] = ProspectiveLeader
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = ACKE
        /\ LET msg    == msgs[j][i][1]
               infoOk == \/ msg.mlastEpoch > tempMaxLastEpoch[i]
                         \/ /\ msg.mlastEpoch = tempMaxLastEpoch[i]
                            /\ \/ LastZxid(msg.mhf)[1] > LastZxid(tempInitialHistory[i])[1]
                               \/ /\ LastZxid(msg.mhf)[1] = LastZxid(tempInitialHistory[i])[1]
                                  /\ LastZxid(msg.mhf)[2] >= LastZxid(tempInitialHistory[i])[2]
           IN \/ /\ leaderEpoch[i] = msg.mepoch
                 /\ \/ /\ infoOk
                       /\ tempMaxLastEpoch'   = [tempMaxLastEpoch   EXCEPT ![i] = msg.mlastEpoch]
                       /\ tempInitialHistory' = [tempInitialHistory EXCEPT ![i] = msg.mhf]
                    \/ /\ ~infoOk
                       /\ UNCHANGED <<tempMaxLastEpoch, tempInitialHistory>>
                 \* Followers not in Q will not receive NEWEPOCH, so leader will receive ACKE only when the source is in Q.
                 /\ ackeRecv' = [ackeRecv EXCEPT ![i] = IF j \notin ackeRecv[i] THEN ackeRecv[i] \union {j}
                                                                                ELSE ackeRecv[i]]
              \/ /\ leaderEpoch[i] /= msg.mepoch
                 /\ UNCHANGED<<tempMaxLastEpoch, tempInitialHistory, ackeRecv>>
        /\ Discard(j, i)
        /\ UNCHANGED <<serverVars, cluster, cepochRecv, ackldRecv, ackIndex, currentCounter, 
                       sendCounter, initialHistory, committedIndex, cepochSent, tempMaxEpoch, recoveryVars, proposalMsgsLog>>

LeaderDiscovery2Sync1(i) ==
        /\ state[i] = ProspectiveLeader
        /\ ackeRecv[i] \in Quorums
        /\ currentEpoch'   = [currentEpoch   EXCEPT ![i]    = leaderEpoch[i]]
        /\ history'        = [history        EXCEPT ![i]    = tempInitialHistory[i]]
        /\ initialHistory' = [initialHistory EXCEPT ![i]    = tempInitialHistory[i]]
        /\ ackeRecv'       = [ackeRecv       EXCEPT ![i]    = {NullPoint}]
        /\ ackIndex'       = [ackIndex       EXCEPT ![i][i] = Len(tempInitialHistory[i])]
        \* until now, phase1(Discovery) ends
        /\ Broadcast(i, [mtype           |-> NEWLEADER,
                         mepoch          |-> currentEpoch'[i],
                         minitialHistory |-> history'[i]])
        /\ UNCHANGED <<state, leaderEpoch, leaderOracle, commitIndex, cluster, cepochRecv,ackldRecv, 
                       currentCounter, sendCounter, committedIndex, cepochSent, tempVars, recoveryVars, proposalMsgsLog>> 
                       
\* Note1: Delete the change of commitIndex in LeaderDiscovery2Sync1 and FollowerSync1, then we can promise that
\*        commitIndex of every server increases monotonically, except that some server halts and restarts.

\* Note2: Set cepochRecv, ackeRecv, ackldRecv to {NullPoint} in corresponding three actions to
\*        make sure that the prospective leader will not broadcast NEWEPOCH/NEWLEADER/COMMITLD twice.
----------------------------------------------------------------------------
\* In phase f21, follower receives NEWLEADER. The follower updates its epoch and history,
\* and sends back ACK-LD to pleader.
FollowerSync1(i, j) ==
        /\ state[i] = Follower
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = NEWLEADER
        /\ LET msg == msgs[j][i][1]
               replyOk == /\ currentEpoch[i] <= msg.mepoch
                          /\ leaderOracle[i] = j
           IN \/ \* new NEWLEADER - accept and reply
                 /\ replyOk
                 /\ currentEpoch' = [currentEpoch EXCEPT ![i] = msg.mepoch]
                 /\ leaderEpoch'  = [leaderEpoch  EXCEPT ![i] = msg.mepoch]
                 /\ history'      = [history      EXCEPT ![i] = msg.minitialHistory]
                 /\ Reply(i, j, [mtype    |-> ACKLD,
                                 mepoch   |-> msg.mepoch,
                                 mhistory |-> msg.minitialHistory])
              \/ \* stale NEWLEADER - discard
                 /\ ~replyOk
                 /\ Discard(j, i)
                 /\ UNCHANGED <<currentEpoch, leaderEpoch, history>>
        /\ UNCHANGED <<state, commitIndex, leaderOracle, leaderVars, tempVars, cepochSent, recoveryVars, proposalMsgsLog>>
                 
\* In phase l22, pleader receives ACK-LD from a quorum of followers, and sends COMMIT-LD to followers.
LeaderHandleACKLD(i, j) ==
        /\ state[i] = ProspectiveLeader
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = ACKLD
        /\ LET msg == msgs[j][i][1] 
           IN \/ \* new ACK-LD - accept
                 /\ currentEpoch[i] = msg.mepoch
                 /\ ackIndex'  = [ackIndex  EXCEPT ![i][j] = Len(initialHistory[i])]
                 /\ ackldRecv' = [ackldRecv EXCEPT ![i] = IF j \notin ackldRecv[i] THEN ackldRecv[i] \union {j}
                                                                                   ELSE ackldRecv[i]]
              \/ \* stale ACK-LD - discard
                 /\ currentEpoch[i] /= msg.mepoch
                 /\ UNCHANGED <<ackldRecv, ackIndex>>
        /\ Discard(j, i)
        /\ UNCHANGED <<serverVars, cluster, cepochRecv, ackeRecv, currentCounter, 
                       sendCounter, initialHistory, committedIndex, tempVars, cepochSent, recoveryVars, proposalMsgsLog>>

LeaderSync2(i) == 
        /\ state[i] = ProspectiveLeader
        /\ ackldRecv[i] \in Quorums
        /\ commitIndex'    = [commitIndex    EXCEPT ![i] = Len(history[i])]
        /\ committedIndex' = [committedIndex EXCEPT ![i] = Len(history[i])]
        /\ state'          = [state          EXCEPT ![i] = Leader]
        /\ currentCounter' = [currentCounter EXCEPT ![i] = 0]
        /\ sendCounter'    = [sendCounter    EXCEPT ![i] = 0]
        /\ ackldRecv'      = [ackldRecv      EXCEPT ![i] = {NullPoint}]
        /\ Broadcast(i, [mtype  |-> COMMITLD,
                         mepoch |-> currentEpoch[i],
                         mlength|-> Len(history[i])])
        /\ UNCHANGED <<currentEpoch, leaderEpoch, leaderOracle, history, cluster, cepochRecv, 
                       ackeRecv, ackIndex, initialHistory, tempVars, cepochSent, recoveryVars, proposalMsgsLog>>

\* In phase f22, follower receives COMMIT-LD and delivers all unprocessed transaction.
FollowerSync2(i, j) ==
        /\ state[i] = Follower
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = COMMITLD
        /\ LET msg == msgs[j][i][1]
               replyOk == /\ currentEpoch[i] = msg.mepoch
                          /\ leaderOracle[i] = j
           IN \/ \* new COMMIT-LD - commit all transactions in initial history
                 \* Regradless of Restart, it must be true because one will receive NEWLEADER before receiving COMMIT-LD
                 /\ replyOk
                 /\ \/ /\ Len(history[i]) = msg.mlength
                       /\ commitIndex'  = [commitIndex EXCEPT ![i] = Len(history[i])]
                       /\ Discard(j, i)
                    \/ /\ Len(history[i]) # msg.mlength
                       /\ Reply(i, j, [mtype |-> CEPOCH,
                                       mepoch|-> currentEpoch[i]])
                       /\ UNCHANGED commitIndex
              \/ \* > : stale COMMIT-LD - discard
                 \* < : In our implementation, '<' does not exist due to the guarantee of Restart
                 /\ ~replyOk
                 /\ Discard(j, i)
                 /\ UNCHANGED commitIndex
        /\ UNCHANGED <<state, currentEpoch, leaderEpoch, leaderOracle, history, 
                       leaderVars, tempVars, cepochSent, recoveryVars, proposalMsgsLog>>

----------------------------------------------------------------------------
\* In phase l31, leader receives client request and broadcasts PROPOSE.
ClientRequest(i, v) ==
        /\ state[i] = Leader
        /\ currentCounter' = [currentCounter EXCEPT ![i] = currentCounter[i] + 1]
        /\ LET newTransaction == [epoch   |-> currentEpoch[i],
                                  counter |-> currentCounter'[i],
                                  value   |-> v]
           IN /\ history'  = [history  EXCEPT ![i]    = Append(history[i], newTransaction)]
              /\ ackIndex' = [ackIndex EXCEPT ![i][i] = Len(history'[i])] \* necessary, to push commitIndex
        /\ UNCHANGED <<msgs, state, currentEpoch, leaderEpoch, leaderOracle, commitIndex, cluster, cepochRecv,
                       ackeRecv, ackldRecv, sendCounter, initialHistory, committedIndex, tempVars, cepochSent, recoveryVars, proposalMsgsLog>>

LeaderBroadcast1(i) ==
        /\ state[i] = Leader
        /\ sendCounter[i] < currentCounter[i]
        /\ LET toBeSentCounter == sendCounter[i] + 1
               toBeSentIndex   == Len(initialHistory[i]) + toBeSentCounter
               toBeSentEntry   == history[i][toBeSentIndex]
           IN /\ Broadcast(i, [mtype     |-> PROPOSE,
                               mepoch    |-> currentEpoch[i],
                               mproposal |-> toBeSentEntry])
              /\ sendCounter' = [sendCounter EXCEPT ![i] = toBeSentCounter]
              /\ LET m == [msource|->i, mtype|-> PROPOSE, mepoch|-> currentEpoch[i], mproposal|-> toBeSentEntry]
                 IN proposalMsgsLog' = proposalMsgsLog \union {m}
        /\ UNCHANGED <<serverVars,cepochRecv, cluster, ackeRecv, ackldRecv, ackIndex, 
                       currentCounter, initialHistory, committedIndex, tempVars, recoveryVars, cepochSent>>

\* In phase f31, follower accepts proposal and append it to history.
FollowerBroadcast1(i, j) ==
        /\ state[i] = Follower
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = PROPOSE
        /\ LET msg == msgs[j][i][1]
               replyOk == /\ currentEpoch[i] = msg.mepoch
                          /\ leaderOracle[i] = j
           IN \/ \* It should be that \/ msg.mproposal.counter = 1 
                 \*                   \/ msg.mrpoposal.counter = history[Len(history)].counter + 1
                 /\ replyOk
                 /\ history' = [history EXCEPT ![i] = Append(history[i], msg.mproposal)]
                 /\ Reply(i, j, [mtype  |-> ACK,
                                 mepoch |-> currentEpoch[i],
                                 mindex |-> Len(history'[i])])
              \/ \* If happens, /= must be >, namely a stale leader sends it.
                 /\ ~replyOk
                 /\ Discard(j, i)
                 /\ UNCHANGED history
        /\ UNCHANGED <<state, currentEpoch, leaderEpoch, leaderOracle, commitIndex, 
                       leaderVars, tempVars, cepochSent, recoveryVars, proposalMsgsLog>>

\* In phase l32, leader receives ack from a quorum of followers to a certain proposal,
\* and commits the proposal.
LeaderHandleACK(i, j) ==
        /\ state[i] = Leader
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = ACK
        /\ LET msg == msgs[j][i][1]
           IN \/ \* It should be that ackIndex[i][j] + 1 == msg.mindex
                 /\ currentEpoch[i] = msg.mepoch
                 /\ ackIndex' = [ackIndex EXCEPT ![i][j] = Maximum({ackIndex[i][j], msg.mindex})]
              \/ \* If happens, /= must be >, namely a stale follower sends it.
                 /\ currentEpoch[i] /= msg.mepoch
                 /\ UNCHANGED ackIndex
        /\ Discard(j ,i)
        /\ UNCHANGED <<serverVars, cluster, cepochRecv, ackeRecv, ackldRecv,currentCounter, 
                       sendCounter, initialHistory, committedIndex, tempVars, cepochSent, recoveryVars, proposalMsgsLog>>

LeaderAdvanceCommit(i) ==
        /\ state[i] = Leader
        /\ commitIndex[i] < Len(history[i])
        /\ LET Agree(index)   == {i} \cup {k \in (Server\{i}): ackIndex[i][k] >= index}
               agreeIndexes   == {index \in (commitIndex[i] + 1)..Len(history[i]): Agree(index) \in Quorums}
               newCommitIndex == IF agreeIndexes /= {} THEN Maximum(agreeIndexes)
                                                       ELSE commitIndex[i]
           IN commitIndex' = [commitIndex EXCEPT ![i] = newCommitIndex]
        /\ UNCHANGED <<state, currentEpoch, leaderEpoch, leaderOracle, history,
                       msgs, leaderVars, tempVars, cepochSent, recoveryVars, proposalMsgsLog>>

LeaderBroadcast2(i) ==
        /\ state[i] = Leader
        /\ committedIndex[i] < commitIndex[i]
        /\ LET newCommittedIndex == committedIndex[i] + 1
           IN /\ Broadcast(i, [mtype    |-> COMMIT,
                               mepoch   |-> currentEpoch[i],
                               mindex   |-> newCommittedIndex,
                               mcounter |-> history[i][newCommittedIndex].counter])
              /\ committedIndex' = [committedIndex EXCEPT ![i] = committedIndex[i] + 1]
        /\ UNCHANGED <<serverVars, cluster, cepochRecv, ackeRecv, ackldRecv, ackIndex, currentCounter, 
                       sendCounter, initialHistory, tempVars, cepochSent, recoveryVars, proposalMsgsLog>>

\* In phase f32, follower receives COMMIT and commits transaction.
FollowerBroadcast2(i, j) ==
        /\ state[i] = Follower
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = COMMIT
        /\ LET msg == msgs[j][i][1]
               replyOk == /\ currentEpoch[i] = msg.mepoch
                          /\ leaderOracle[i] = j
           IN \/ /\ replyOk
                 /\ LET infoOk == /\ Len(history[i]) >= msg.mindex
                                  /\ \/ /\ msg.mindex > 0
                                        /\ history[i][msg.mindex].epoch = msg.mepoch
                                        /\ history[i][msg.mindex].counter = msg.mcounter
                                     \/ msg.mindex = 0
                    IN \/ \* new COMMIT - commit transaction in history
                          /\ infoOk
                          /\ commitIndex' = [commitIndex EXCEPT ![i] = Maximum({commitIndex[i], msg.mindex})]
                          /\ Discard(j, i)
                       \/ \* It may happen when the server is a new follower who joined in the cluster,
                          \* and it misses the corresponding PROPOSE.
                          /\ ~infoOk
                          /\ Reply(i, j, [mtype |-> CEPOCH,
                                          mepoch|-> currentEpoch[i]])
                          /\ UNCHANGED commitIndex
              \/ \* stale COMMIT - discard
                 /\ ~replyOk
                 /\ Discard(j, i)
                 /\ UNCHANGED commitIndex
        /\ UNCHANGED <<state, currentEpoch, leaderEpoch, history, leaderOracle, 
                       leaderVars, tempVars, cepochSent, recoveryVars, proposalMsgsLog>>

----------------------------------------------------------------------------
\* When one follower receives PROPOSE or COMMIT which misses some entries between
\* its history and the newest entry, the follower send CEPOCH to catch pace.

\* In phase l33, upon receiving CEPOCH, leader l proposes back NEWEPOCH and NEWLEADER.
LeaderHandleCEPOCHinPhase3(i, j) ==
        /\ state[i] = Leader
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = CEPOCH
        /\ LET msg == msgs[j][i][1]
           IN \/ /\ currentEpoch[i] >= msg.mepoch
                 /\ Reply2(i, j, [mtype  |-> NEWEPOCH,
                                  mepoch |-> currentEpoch[i]],
                                 [mtype           |-> NEWLEADER,
                                  mepoch          |-> currentEpoch[i],
                                  minitialHistory |-> history[i]])
              \/ /\ currentEpoch[i] < msg.mepoch
                 /\ UNCHANGED msgs
        /\ UNCHANGED <<serverVars, leaderVars, tempVars, cepochSent, recoveryVars, proposalMsgsLog>>
        
\* In phase l34, upon receiving ack from f of the NEWLEADER, it sends a commit message to f.
\* Leader l also makes Q := Q \union {f}.
LeaderHandleACKLDinPhase3(i, j) ==
        /\ state[i] = Leader
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = ACKLD
        /\ LET msg == msgs[j][i][1]
               aimCommitIndex == Minimum({commitIndex[i], Len(msg.mhistory)})
               aimCommitCounter == IF aimCommitIndex = 0 THEN 0 ELSE history[i][aimCommitIndex].counter
           IN \/ /\ currentEpoch[i] = msg.mepoch
                 /\ ackIndex' = [ackIndex EXCEPT ![i][j] = Len(msg.mhistory)]
                 /\ Reply(i, j, [mtype    |-> COMMIT,
                                 mepoch   |-> currentEpoch[i],
                                 mindex   |-> aimCommitIndex,
                                 mcounter |-> aimCommitCounter])
              \/ /\ currentEpoch[i] /= msg.mepoch
                 /\ Discard(j, i)
                 /\ UNCHANGED ackIndex
        /\ cluster' = [cluster EXCEPT ![i] = IF j \in cluster[i] THEN cluster[i]
                                                                 ELSE cluster[i] \union {j}]
        /\ UNCHANGED <<serverVars, cepochRecv, ackeRecv, ackldRecv, currentCounter, sendCounter, 
                       initialHistory, committedIndex, tempVars, cepochSent, recoveryVars, proposalMsgsLog>>

\* Let me suppose three conditions when one follower sends CEPOCH to leader:
\* 0. Usually, the server becomes follower in election and sends CEPOCH before receiving NEWEPOCH.
\* 1. The follower wants to join the cluster halfway and get the newest history.
\* 2. The follower has received COMMIT, but there exists the gap between its own history and mindex,
\*    which means there are some transactions before mindex miss. Here we choose to send CEPOCH 
\*    again, to receive the newest history from leader.
BecomeFollower(i) ==
        /\ state[i] /= Follower
        /\ \E j \in Server \ {i}: /\ msgs[j][i] /= << >>   
                                  /\ msgs[j][i][1].mtype /= RECOVERYREQUEST
                                  /\ msgs[j][i][1].mtype /= RECOVERYRESPONSE
                                  /\ LET msg == msgs[j][i][1]
                                     IN /\ NullPoint \in cepochRecv[i]
                                        /\ Maximum({currentEpoch[i],leaderEpoch[i]}) < msg.mepoch
                                        /\ \/ msg.mtype = NEWEPOCH
                                           \/ msg.mtype = NEWLEADER
                                           \/ msg.mtype = COMMITLD
                                           \/ msg.mtype = PROPOSE
                                           \/ msg.mtype = COMMIT
                                        /\ state'        = [state        EXCEPT ![i] = Follower]
                                        /\ currentEpoch' = [currentEpoch EXCEPT ![i] = msg.mepoch]
                                        /\ leaderOracle' = [leaderOracle EXCEPT ![i] = j]
                                        /\ Reply(i, j, [mtype |-> CEPOCH,
                                                        mepoch|-> currentEpoch[i]])
                                        \* Here we should not use Discard.
        /\ UNCHANGED <<leaderEpoch, history, commitIndex, leaderVars, tempVars, cepochSent, recoveryVars, proposalMsgsLog>>
                                        
----------------------------------------------------------------------------
DiscardStaleMessage(i) ==
        /\ \E j \in Server \ {i}: /\ msgs[j][i] /= << >>
                                  /\ msgs[j][i][1].mtype /= RECOVERYREQUEST
                                  /\ msgs[j][i][1].mtype /= RECOVERYRESPONSE
                                  /\ LET msg == msgs[j][i][1]
                                     IN \/ /\ state[i] = Follower
                                           /\ \* \/ msg.mepoch < currentEpoch[i] \* Discussed before.
                                              \/ msg.mtype = CEPOCH
                                              \/ msg.mtype = ACKE
                                              \/ msg.mtype = ACKLD
                                              \/ msg.mtype = ACK
                                        \/ /\ state[i] /= Follower
                                           /\ msg.mtype /= CEPOCH
                                           /\ \/ /\ state[i] = ProspectiveLeader
                                                 /\ \/ msg.mtype = ACK
                                                    \/ /\ msg.mepoch <= Maximum({currentEpoch[i],leaderEpoch[i]})
                                                       /\ \/ msg.mtype = NEWEPOCH
                                                          \/ msg.mtype = NEWLEADER
                                                          \/ msg.mtype = COMMITLD
                                                          \/ msg.mtype = PROPOSE
                                                          \/ msg.mtype = COMMIT
                                              \/ /\ state[i] = Leader
                                                 /\ \/ msg.mtype = ACKE
                                                    \/ /\ msg.mepoch <= currentEpoch[i]
                                                       /\ \/ msg.mtype = NEWEPOCH
                                                          \/ msg.mtype = NEWLEADER
                                                          \/ msg.mtype = COMMITLD
                                                          \/ msg.mtype = PROPOSE
                                                          \/ msg.mtype = COMMIT
                                  /\ Discard(j ,i)
        /\ UNCHANGED <<serverVars, leaderVars, tempVars, cepochSent, recoveryVars, proposalMsgsLog>>


----------------------------------------------------------------------------
\* Defines how the variables may transition.
Next ==
        \/ \E i \in Server, Q \in Quorums: InitialElection(i, Q)
        \/ \E i \in Server:      Restart(i)
        \/ \E i \in Server:      RecoveryAfterRestart(i)
        \/ \E i, j \in Server:   HandleRecoveryRequest(i, j)
        \/ \E i, j \in Server:   HandleRecoveryResponse(i, j)
        \/ \E i, j \in Server:   FindCluster(i)
        \/ \E i, j \in Server:   LeaderTimeout(i, j)
        \/ \E i \in Server:      FollowerTimeout(i) 
        \/ \E i \in Server:      FollowerDiscovery1(i)
        \/ \E i, j \in Server:   LeaderHandleCEPOCH(i, j)
        \/ \E i \in Server:      LeaderDiscovery1(i)
        \/ \E i, j \in Server:   FollowerDiscovery2(i, j)
        \/ \E i, j \in Server:   LeaderHandleACKE(i, j)
        \/ \E i \in Server:      LeaderDiscovery2Sync1(i)
        \/ \E i, j \in Server:   FollowerSync1(i, j)
        \/ \E i, j \in Server:   LeaderHandleACKLD(i, j)
        \/ \E i \in Server:      LeaderSync2(i)
        \/ \E i, j \in Server:   FollowerSync2(i, j)
        \/ \E i \in Server, v \in Value: ClientRequest(i, v)
        \/ \E i \in Server:      LeaderBroadcast1(i)
        \/ \E i, j \in Server:   FollowerBroadcast1(i, j)
        \/ \E i, j \in Server:   LeaderHandleACK(i, j)
        \/ \E i \in Server:      LeaderAdvanceCommit(i)
        \/ \E i \in Server:      LeaderBroadcast2(i)
        \/ \E i, j \in Server:   FollowerBroadcast2(i, j)
        \/ \E i, j \in Server:   LeaderHandleCEPOCHinPhase3(i, j)
        \/ \E i, j \in Server:   LeaderHandleACKLDinPhase3(i, j)
        \/ \E i \in Server:      DiscardStaleMessage(i)
        \/ \E i \in Server:      BecomeFollower(i)

Spec == Init /\ [][Next]_vars

----------------------------------------------------------------------------
\* Safety properties of Zab consensus algorithm

\* There is most one leader/prospective leader in a certain epoch.
Leadership == \A i, j \in Server:
                    /\ \/ state[i] = Leader 
                       \/ /\ state[i] = ProspectiveLeader
                          /\ NullPoint \in ackeRecv[i] \* prospective leader determines its epoch after broadcasting NEWLEADER
                    /\ \/ state[j] = Leader 
                       \/ /\ state[j] = ProspectiveLeader
                          /\ NullPoint \in ackeRecv[j]
                    /\ currentEpoch[i] = currentEpoch[j]
                    => i = j
                    
\* Here, delivering means deliver some transaction from history to replica. We assume deliverIndex = commitIndex.
\* So we can assume the set of delivered transactions is the prefix of history with index from 1 to commitIndex.
\* And we can express a transaction by two-tuple <epoch,counter> according to its uniqueness.
equal(entry1, entry2) == /\ entry1.epoch   = entry2.epoch
                         /\ entry1.counter = entry2.counter

precede(entry1, entry2) == \/ entry1.epoch < entry2.epoch 
                           \/ /\ entry1.epoch   = entry2.epoch
                              /\ entry1.counter < entry2.counter

\* PrefixConsistency: The prefix that have been delivered in history in any process is the same.
PrefixConsistency ==  \A i, j \in Server:
                        LET smaller == Minimum({commitIndex[i], commitIndex[j]})
                        IN \/ smaller    = 0
                           \/ /\ smaller > 0
                              /\ \A index \in 1..smaller: equal(history[i][index], history[j][index])

\* Integrity: If some follower delivers one transaction, then some primary has broadcast it.
Integrity == \A i \in Server:
                state[i] = Follower /\ commitIndex[i] > 0
                => \A index \in 1..commitIndex[i]: \E msg \in proposalMsgsLog: 
                    equal(msg.mproposal, history[i][index])

\* Agreement: If some follower f delivers transaction a and some follower f' delivers transaction b,
\*            then f' delivers a or f delivers b.
Agreement == \A i, j \in Server:
                /\ state[i] = Follower /\ commitIndex[i] > 0
                /\ state[j] = Follower /\ commitIndex[j] > 0
                =>
                \A index1 \in 1..commitIndex[i], index2 \in 1..commitIndex[j]:
                    \/ \E indexj \in 1..commitIndex[j]:
                        equal(history[j][indexj], history[i][index1])
                    \/ \E indexi \in 1..commitIndex[i]:
                        equal(history[i][indexi], history[j][index2])

\* Total order: If some follower delivers a before b, then any process that delivers b
\*              must also deliver a and deliver a before b.
TotalOrder == \A i, j \in Server: commitIndex[i] >= 2 /\ commitIndex[j] >= 2
                => \A indexi1 \in 1..(commitIndex[i]-1): \A indexi2 \in (indexi1 + 1)..commitIndex[i]:
                    LET logOk == \E index \in 1..commitIndex[j]: equal(history[i][indexi2], history[j][index])
                    IN \/ ~logOk
                       \/ /\ logOk 
                          /\ \E indexj2 \in 1..commitIndex[j]: 
                                              /\ equal(history[i][indexi2], history[j][indexj2])
                                              /\ \E indexj1 \in 1..(indexj2 - 1): equal(history[i][indexi1], history[j][indexj1])
        

\* Local primary order: If a primary broadcasts a before it broadcasts b, then a follower that
\*                      delivers b must also deliver a before b.
LocalPrimaryOrder == LET mset(i, e) == {msg \in proposalMsgsLog: msg.msource = i /\ msg.mproposal.epoch = e}
                         mentries(i, e) == {msg.mproposal: msg \in mset(i, e)}
                     IN \A i \in Server: \A e \in 1..currentEpoch[i]:
                           /\ Cardinality(mentries(i, e)) >= 2
                           /\ \* LET tsc1 == CHOOSE p \in mentries(i, e): TRUE
                              \*     tsc2 == CHOOSE p \in mentries(i, e): \lnot equal(p, tsc1)
                              \E tsc1 \in mentries(i, e): \E tsc2 \in mentries(i, e):
                                /\ \lnot equal(tsc2, tsc1)
                                /\ LET tscPre  == IF precede(tsc1, tsc2) THEN tsc1 ELSE tsc2
                                       tscNext == IF precede(tsc1, tsc2) THEN tsc2 ELSE tsc1
                                   IN \A j \in Server: /\ commitIndex[j] >= 2
                                                       /\ \E index \in 1..commitIndex[j]: equal(history[j][index], tscNext)
                                    => 
                                      \E index2 \in 1..commitIndex[j]: 
                                            /\ equal(history[j][index2], tscNext)
                                            /\ index2 > 1
                                            /\ \E index1 \in 1..(index2 - 1): equal(history[j][index1], tscPre)

\* Global primary order: A follower f delivers both a with epoch e and b with epoch e', and e < e',
\*                       then f must deliver a before b.
GlobalPrimaryOrder == \A i \in Server: commitIndex[i] >= 2
                         => \A idx1, idx2 \in 1..commitIndex[i]: \/ history[i][idx1].epoch >= history[i][idx2].epoch
                                                                 \/ /\ history[i][idx1].epoch < history[i][idx2].epoch
                                                                    /\ idx1 < idx2
                                       
\* Primary integrity: If primary p broadcasts a and some follower f delivers b such that b has epoch
\*                    smaller than epoch of p, then p must deliver b before it broadcasts a.
PrimaryIntegrity == \A i, j \in Server: /\ state[i] = Leader 
                                        /\ state[j] = Follower /\ commitIndex[j] >= 1
                        => \A index \in 1..commitIndex[j]: \/ history[j][index].epoch >= currentEpoch[i]
                                                           \/ /\ history[j][index].epoch < currentEpoch[i]
                                                              /\ \E idx \in 1..commitIndex[i]: equal(history[i][idx], history[j][index])

=============================================================================
\* Modification History
\* Last modified Fri Apr 30 15:47:56 CST 2021 by Dell
\* Created Sat Dec 05 13:32:08 CST 2020 by Dell


