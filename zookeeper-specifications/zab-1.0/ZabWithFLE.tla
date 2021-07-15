(*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *)
----------------------------- MODULE ZabWithFLE -----------------------------
(* This is the formal specification for the Zab consensus algorithm,
   which means Zookeeper Atomic Broadcast.*)

(* Reference:
   FLE: FastLeaderElection.java, Vote.java, QuorumPeer.java in https://github.com/apache/zookeeper.
   ZAB: QuorumPeer.java, Learner.java, Follower.java, LearnerHandler.java, Leader.java 
        in https://github.com/apache/zookeeper.
        https://cwiki.apache.org/confluence/display/ZOOKEEPER/Zab1.0.
 *)
EXTENDS FastLeaderElection

-----------------------------------------------------------------------------
(* Defined in FastLeaderElection.tla:
\* The set of server identifiers
CONSTANT Server

\* Server states
CONSTANTS LOOKING, FOLLOWING, LEADING

\* Message types
CONSTANTS NOTIFICATION

\* Timeout signal
CONSTANT NONE
*)
\* The set of requests that can go into history
CONSTANT Value

\* Zab states
CONSTANTS ELECTION, DISCOVERY, SYNCHRONIZATION, BROADCAST

\* Message types
CONSTANTS FOLLOWERINFO, LEADERINFO, ACKEPOCH, NEWLEADER, ACKLD, UPTODATE, PROPOSAL, ACK, COMMIT
(* Additional message types used for recovery in synchronization(TRUNC/DIFF/SNAP) are not needed
   since we abstract this part.(see action RECOVERYSYNC) *)
   
-----------------------------------------------------------------------------
(* Defined in FastLeaderElection.tla: Quorums, NullPoint *)
\* Return the maximum value from the set S
Maximum(S) == IF S = {} THEN -1
                        ELSE CHOOSE n \in S: \A m \in S: n >= m

\* Return the minimum value from the set S
Minimum(S) == IF S = {} THEN -1
                        ELSE CHOOSE n \in S: \A m \in S: n <= m

MAXEPOCH == 10

-----------------------------------------------------------------------------
(* Defined in FastLeaderElection.tla:
   serverVars: <<state, currentEpoch, lastZxid>>,
   electionVars: <<currentVote, logicalClock, receiveVotes, outOfElection, recvQueue, waitNotmsg>>,
   leaderVars: <<leadingVoteSet>>,
   electionMsgs,
   idTable *)
\* The current phase of server(ELECTION,DISCOVERY,SYNCHRONIZATION,BROADCAST)
VARIABLE zabState

\* The epoch number of the last NEWEPOCH(LEADERINFO) packet accepted
\* namely f.p in paper, and currentEpoch in Zab.tla.
VARIABLE acceptedEpoch

\* The history of servers as the sequence of transactions.
VARIABLE history

\* commitIndex[i]: The maximum index of transactions that have been saved in a quorum of servers
\*                 in the perspective of server i.(increases monotonically before restarting)
VARIABLE commitIndex

(* These transactions whose index \le commitIndex[i] can be applied to state machine immediately.
   So if we have a variable applyIndex, we can suppose that applyIndex[i] = commitIndex[i] when verifying properties.
   But in phase SYNC, follower will apply all queued proposals to state machine when receiving NEWLEADER.
   But follower only serves traffic after receiving UPTODATE, so sequential consistency is not violated.
   
   So when we verify properties, we still suppose applyIndex[i] = commitIndex[i], because this is an engineering detail.*)

\* learners[i]: The set of servers which leader i think are connected wich i.
VARIABLE learners

\* The messages representing requests and responses sent from one server to another.
\* msgs[i][j] means the input buffer of server j from server i.
VARIABLE msgs

\* The set of followers who has successfully sent CEPOCH(FOLLOWERINFO) to leader.(equals to connectingFollowers in code)
VARIABLE cepochRecv

\* The set of followers who has successfully sent ACK-E to leader.(equals to electingFollowers in code)
VARIABLE ackeRecv

\* The set of followers who has successfully sent ACK-LD to leader in leader.(equals to newLeaderProposal in code)
VARIABLE ackldRecv

\* The set of servers which leader i broadcasts PROPOSAL and COMMIT to.(equals to forwardingFollowers in code)
VARIABLE forwarding

\* ackIndex[i][j]: The latest index that leader i has received from follower j via ACK.
VARIABLE ackIndex

\* currentCounter[i]: The count of transactions that clients request leader i.
VARIABLE currentCounter

\* sendCounter[i]: The count of transactions that leader i has broadcast in PROPOSAL.
VARIABLE sendCounter

\* committedIndex[i]: The maximum index of trasactions that leader i has broadcast in COMMIT.
VARIABLE committedIndex

\* committedCounter[i][j]: The latest counter of transaction that leader i has confirmed that follower j has committed.
VARIABLE committedCounter

\* initialHistory[i]: The initial history if leader i in epoch acceptedEpoch[i].
VARIABLE initialHistory

\* the maximum epoch in CEPOCH the prospective leader received from followers.
VARIABLE tempMaxEpoch

\* cepochSent[i] = TRUE means follower i has sent CEPOCH(FOLLOWERINFO) to leader.
VARIABLE cepochSent

\* leaderAddr[i]: The leader id of follower i. We use leaderAddr to express whether follower i has connected or lost connection.
VARIABLE leaderAddr

\* synced[i] = TRUE: follower i has completed sync with leader.
VARIABLE synced

\* The set of leaders in every epoch, only used in verifying properties.
VARIABLE epochLeader

\* The set of all broadcast messages, only used in verifying properties.
VARIABLE proposalMsgsLog

\* A variable used to check whether there are conditions contrary to the facts.
VARIABLE inherentViolated

serverVarsZ == <<state, currentEpoch, lastZxid, zabState, acceptedEpoch, history, commitIndex>>         \* 7 variables

electionVarsZ == electionVars  \* 6 variables

leaderVarsZ == <<leadingVoteSet, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, 
                 ackIndex, currentCounter, sendCounter, committedIndex, committedCounter>>              \* 11 variables

tempVarsZ == <<initialHistory, tempMaxEpoch>>     \* 2 variables
  
followerVarsZ == <<cepochSent, leaderAddr, synced>>                 \* 3 variables

verifyVarsZ == <<proposalMsgsLog, epochLeader, inherentViolated>>   \* 3 variables

msgVarsZ == <<msgs, electionMsgs>>                \* 2 variables

vars == <<serverVarsZ, electionVarsZ, leaderVarsZ, tempVarsZ, followerVarsZ, verifyVarsZ, msgVarsZ, idTable>>  \* 35 variables
-----------------------------------------------------------------------------
\* Add a message to msgs - add a message m to msgs[i][j].
Send(i, j, m) == msgs' = [msgs EXCEPT ![i][j] = Append(msgs[i][j], m)]

\* Remove a message from msgs - discard head of msgs[i][j].
Discard(i, j) == msgs' = IF msgs[i][j] /= << >> THEN [msgs EXCEPT ![i][j] = Tail(msgs[i][j])]
                                                ELSE msgs

\* Leader broadcasts a message(PROPOSAL/COMMIT) to all other servers in forwardingFollowers.
Broadcast(i, m) == msgs' = [msgs EXCEPT ![i] = [v \in Server |-> IF /\ v \in forwarding[i]
                                                                    /\ v /= i
                                                                    /\ \/ /\ m.mtype = PROPOSAL
                                                                          /\ ackIndex[i][v] < Len(initialHistory[i]) + m.mproposal.counter
                                                                       \/ /\ m.mtype = COMMIT
                                                                          /\ committedCounter[i][v] < m.mzxid[2]
                                                                  THEN Append(msgs[i][v], m)
                                                                  ELSE msgs[i][v]]]

BroadcastLEADERINFO(i, m) == msgs' = [msgs EXCEPT ![i] = [v \in Server |-> IF /\ v \in cepochRecv[i]
                                                                              /\ v \in learners[i]
                                                                              /\ v /= i THEN Append(msgs[i][v], m)
                                                                                        ELSE msgs[i][v]]]

BroadcastUPTODATE(i, m) == msgs' = [msgs EXCEPT ![i] = [v \in Server |-> IF /\ v \in ackldRecv[i]
                                                                            /\ v \in learners[i]
                                                                            /\ v /= i THEN Append(msgs[i][v], m)
                                                                                      ELSE msgs[i][v]]]

\* Combination of Send and Discard - discard head of msgs[j][i] and add m into msgs[i][j].
Reply(i, j, m) == msgs' = [msgs EXCEPT ![j][i] = Tail(msgs[j][i]),
                                       ![i][j] = Append(msgs[i][j], m)]

\* shuffle the input buffer from server j(i) in server i(j).
Clean(i, j) == msgs' = [msgs EXCEPT ![j][i] = << >>, ![i][j] = << >>]

-----------------------------------------------------------------------------
PZxidEqual(p, z) == p.epoch = z[1] /\ p.counter = z[2]

TransactionEqual(t1, t2) == /\ t1.epoch   = t2.epoch
                            /\ t1.counter = t2.counter
                            
TransactionPrecede(t1, t2) == \/ t1.epoch < t2.epoch
                              \/ /\ t1.epoch   = t2.epoch
                                 /\ t1.counter < t2.counter
                                 
-----------------------------------------------------------------------------
\* Define initial values for all variables 
InitServerVarsZ == /\ InitServerVars
                   /\ zabState      = [s \in Server |-> ELECTION]
                   /\ acceptedEpoch = [s \in Server |-> 0]
                   /\ history       = [s \in Server |-> << >>]
                   /\ commitIndex   = [s \in Server |-> 0]

InitLeaderVarsZ == /\ InitLeaderVars
                   /\ learners         = [s \in Server |-> {}]
                   /\ cepochRecv       = [s \in Server |-> {}]
                   /\ ackeRecv         = [s \in Server |-> {}]
                   /\ ackldRecv        = [s \in Server |-> {}]
                   /\ ackIndex         = [s \in Server |-> [v \in Server |-> 0]]
                   /\ currentCounter   = [s \in Server |-> 0]
                   /\ sendCounter      = [s \in Server |-> 0]
                   /\ committedIndex   = [s \in Server |-> 0]
                   /\ committedCounter = [s \in Server |-> [v \in Server |-> 0]]
                   /\ forwarding       = [s \in Server |-> {}]

InitElectionVarsZ == InitElectionVars

InitTempVarsZ == /\ initialHistory = [s \in Server |-> << >>]
                 /\ tempMaxEpoch   = [s \in Server |-> 0]

InitFollowerVarsZ == /\ cepochSent = [s \in Server |-> FALSE]
                     /\ leaderAddr = [s \in Server |-> NullPoint]
                     /\ synced     = [s \in Server |-> FALSE]

InitVerifyVarsZ == /\ proposalMsgsLog  = {}
                   /\ epochLeader      = [i \in 1..MAXEPOCH |-> {}]
                   /\ inherentViolated = FALSE
                   
InitMsgVarsZ == /\ msgs         = [s \in Server |-> [v \in Server |-> << >>]]
                /\ electionMsgs = [s \in Server |-> [v \in Server |-> << >>]]

InitZ == /\ InitServerVarsZ
         /\ InitLeaderVarsZ
         /\ InitElectionVarsZ
         /\ InitTempVarsZ
         /\ InitFollowerVarsZ
         /\ InitVerifyVarsZ
         /\ InitMsgVarsZ
         /\ idTable = InitializeIdTable(Server)
         
-----------------------------------------------------------------------------
ZabTurnToLeading(i) ==
        /\ zabState'       = [zabState   EXCEPT ![i] = DISCOVERY]
        /\ learners'       = [learners   EXCEPT ![i] = {i}]
        /\ cepochRecv'     = [cepochRecv EXCEPT ![i] = {i}]
        /\ ackeRecv'       = [ackeRecv   EXCEPT ![i] = {i}]
        /\ ackldRecv'      = [ackldRecv  EXCEPT ![i] = {i}]
        /\ forwarding'     = [forwarding EXCEPT ![i] = {}]
        /\ ackIndex'       = [ackIndex   EXCEPT ![i] = [v \in Server |-> IF v = i THEN Len(history[i])
                                                                                  ELSE 0]]
        /\ currentCounter'   = [currentCounter   EXCEPT ![i] = 0]
        /\ sendCounter'      = [sendCounter      EXCEPT ![i] = 0]
        /\ commitIndex'      = [commitIndex      EXCEPT ![i] = 0]
        /\ committedIndex'   = [committedIndex   EXCEPT ![i] = 0]
        /\ committedCounter' = [committedCounter EXCEPT ![i] = [v \in Server |-> IF v = i THEN Len(history[i])
                                                                                          ELSE 0]]
        /\ initialHistory' = [initialHistory EXCEPT ![i] = history[i]]
        /\ tempMaxEpoch'   = [tempMaxEpoch   EXCEPT ![i] = acceptedEpoch[i]]

ZabTurnToFollowing(i) ==
        /\ zabState'    = [zabState   EXCEPT ![i] = DISCOVERY]
        /\ cepochSent'  = [cepochSent EXCEPT ![i] = FALSE]
        /\ synced'      = [synced     EXCEPT ![i] = FALSE]
        /\ commitIndex' = [commitIndex      EXCEPT ![i] = 0]
        
          
(* Fast Leader Election *)
FLEReceiveNotmsg(i, j) ==
        /\ ReceiveNotmsg(i, j)
        /\ UNCHANGED <<zabState, acceptedEpoch, history, commitIndex,learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, currentCounter,
                       sendCounter, committedIndex, committedCounter, tempVarsZ, followerVarsZ, verifyVarsZ, msgs>>

FLENotmsgTimeout(i) ==
        /\ NotmsgTimeout(i)
        /\ UNCHANGED <<zabState, acceptedEpoch, history, commitIndex,learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, currentCounter,
                       sendCounter, committedIndex, committedCounter, tempVarsZ, followerVarsZ, verifyVarsZ, msgs>>

FLEHandleNotmsg(i) ==
        /\ HandleNotmsg(i)
        /\ LET newState == state'[i]
           IN
           \/ /\ newState = LEADING
              /\ ZabTurnToLeading(i)
              /\ UNCHANGED <<cepochSent, synced>>
           \/ /\ newState = FOLLOWING
              /\ ZabTurnToFollowing(i)
              /\ UNCHANGED <<learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, currentCounter, sendCounter, committedIndex, committedCounter, tempVarsZ>>
           \/ /\ newState = LOOKING
              /\ UNCHANGED <<zabState, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, currentCounter, sendCounter, commitIndex,
                             committedIndex, committedCounter, tempVarsZ, cepochSent, synced>>
        /\ UNCHANGED <<acceptedEpoch, history, leaderAddr, verifyVarsZ, msgs>>

\* On the premise that ReceiveVotes.HasQuorums = TRUE, corresponding to logic in line 1050-1055 in LFE.java.
FLEWaitNewNotmsg(i) ==
        /\ WaitNewNotmsg(i)
        /\ UNCHANGED <<zabState, acceptedEpoch, history, commitIndex,learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, currentCounter,
                       sendCounter, committedIndex, committedCounter, tempVarsZ, followerVarsZ, verifyVarsZ, msgs>>

\* On the premise that ReceiveVotes.HasQuorums = TRUE, corresponding to logic in line 1061-1066 in LFE.java.
FLEWaitNewNotmsgEnd(i) ==
        /\ WaitNewNotmsgEnd(i)
        /\ LET newState == state'[i]
           IN
           \/ /\ newState = LEADING
              /\ ZabTurnToLeading(i)
              /\ UNCHANGED <<cepochSent, synced>>
           \/ /\ newState = FOLLOWING
              /\ ZabTurnToFollowing(i)
              /\ UNCHANGED <<learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, currentCounter, sendCounter, committedIndex, committedCounter, tempVarsZ>>
           \/ /\ newState = LOOKING
              /\ PrintT("New state is LOOKING in FLEWaitNewNotmsgEnd, which should not happen.")
              /\ UNCHANGED <<zabState, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, currentCounter, sendCounter, commitIndex,
                             committedIndex, committedCounter, tempVarsZ, cepochSent, synced>>
        /\ UNCHANGED <<acceptedEpoch, history, leaderAddr, verifyVarsZ, msgs>>
              
-----------------------------------------------------------------------------
(* A sub-action describing how a server transitions from LEADING/FOLLOWING to LOOKING.
   Initially I call it 'ZabTimeoutZ', but it will be called not only when timeout, 
   but also when finding a low epoch from leader.*)
FollowerShutdown(i) ==
        /\ ZabTimeout(i)
        /\ zabState'   = [zabState   EXCEPT ![i] = ELECTION]
        /\ leaderAddr' = [leaderAddr EXCEPT ![i] = NullPoint]

LeaderShutdown(i) ==
        /\ ZabTimeout(i)
        /\ zabState'   = [zabState   EXCEPT ![i] = ELECTION]
        /\ leaderAddr' = [s \in Server |-> IF s \in learners[i] THEN NullPoint ELSE leaderAddr[s]]
        /\ learners'   = [learners   EXCEPT ![i] = {}]
        /\ forwarding' = [forwarding EXCEPT ![i] = {}]
        /\ msgs'       = [s \in Server |-> [v \in Server |-> IF v \in learners[i] \/ s \in learners[i] THEN << >> ELSE msgs[s][v]]]

FollowerTimout(i) ==
        /\ state[i]      = FOLLOWING
        /\ leaderAddr[i] = NullPoint
        /\ FollowerShutdown(i)
        /\ msgs' = [s \in Server |-> [v \in Server |-> IF v = i THEN << >> ELSE msgs[s][v]]]
        /\ UNCHANGED <<acceptedEpoch, history, commitIndex, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, 
                       currentCounter, sendCounter, committedIndex, committedCounter, tempVarsZ, cepochSent, synced, verifyVarsZ>>

LeaderTimeout(i) ==
        /\ state[i] = LEADING
        /\ learners[i] \notin Quorums
        /\ LeaderShutdown(i)
        /\ UNCHANGED <<acceptedEpoch, history, commitIndex, cepochRecv, ackeRecv, ackldRecv, ackIndex, currentCounter, sendCounter, committedIndex, committedCounter,
                       tempVarsZ, cepochSent, synced, verifyVarsZ>>
    
-----------------------------------------------------------------------------
(* Establish connection between leader i and follower j. 
   It means i creates a learnerHandler for communicating with j, and j finds i's address.*)
EstablishConnection(i, j) ==
        /\ state[i] = LEADING   /\ state[j] = FOLLOWING
        /\ j \notin learners[i] /\ leaderAddr[j] = NullPoint
        /\ currentVote[j].proposedLeader = i
        /\ learners'   = [learners   EXCEPT ![i] = learners[i] \union {j}] \* Leader:   'addLearnerHandler(peer)'
        /\ leaderAddr' = [leaderAddr EXCEPT ![j] = i]                      \* Follower: 'connectToLeader(addr, hostname)'
        /\ UNCHANGED <<serverVarsZ, electionVarsZ, leadingVoteSet, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, 
                       currentCounter, sendCounter, committedIndex, committedCounter, tempVarsZ, cepochSent, synced, verifyVarsZ, msgVarsZ, idTable>>
        
(* The leader i finds timeout and TCP connection between i and j closes.*)       
Timeout(i, j) ==
        /\ state[i] = LEADING /\ state[j] = FOLLOWING
        /\ j \in learners[i]  /\ leaderAddr[j] = i
        (* The action of leader i.(corresponding to function 'removeLearnerHandler(peer)'.) *)
        /\ learners'   = [learners   EXCEPT ![i] = learners[i] \ {j}] 
        /\ forwarding' = [forwarding EXCEPT ![i] = IF j \in forwarding[i] THEN forwarding[i] \ {j} ELSE forwarding[i]]
        /\ cepochRecv' = [cepochRecv EXCEPT ![i] = IF j \in cepochRecv[i] THEN cepochRecv[i] \ {j} ELSE cepochRecv[i]]
        (* The action of follower j. *)
        /\ FollowerShutdown(j)
        (* Clean input buffer.*)
        /\ Clean(i, j)
        /\ UNCHANGED <<acceptedEpoch, history, commitIndex, ackeRecv, ackldRecv, ackIndex, currentCounter, sendCounter, committedIndex, committedCounter,
                       tempVarsZ, cepochSent, synced, verifyVarsZ>>
-----------------------------------------------------------------------------
\* In phase f11, follower sends f.p to leader via FOLLOWERINFO(CEPOCH).
FollowerSendFOLLOWERINFO(i) ==
        /\ state[i]    = FOLLOWING
        /\ zabState[i] = DISCOVERY
        /\ leaderAddr[i] /= NullPoint
        /\ \lnot cepochSent[i]
        /\ Send(i, leaderAddr[i], [mtype  |-> FOLLOWERINFO,
                                   mepoch |-> acceptedEpoch[i]])
        /\ cepochSent' = [cepochSent EXCEPT ![i] = TRUE]
        /\ UNCHANGED <<serverVarsZ, leaderVarsZ, electionVarsZ, tempVarsZ, leaderAddr, synced, verifyVarsZ, electionMsgs, idTable>>
        
(* In phase l11, leader waits for receiving FOLLOWERINFO from a quorum,
   and then chooses a new epoch e' as its own epoch and broadcasts LEADERINFO. *)
LeaderHandleFOLLOWERINFO(i, j) ==
        /\ state[i] = LEADING
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = FOLLOWERINFO
        /\ LET msg == msgs[j][i][1]
           IN \/ /\ NullPoint \notin cepochRecv[i]   \* 1. has not broadcast LEADERINFO - modify tempMaxEpoch
                 /\ LET newEpoch == Maximum({tempMaxEpoch[i], msg.mepoch})
                    IN tempMaxEpoch' = [tempMaxEpoch EXCEPT ![i] = newEpoch]
                 /\ Discard(j, i)
              \/ /\ NullPoint \in cepochRecv[i]      \* 2. has broadcast LEADERINFO - no need to handle the msg, just send LEADERINFO to corresponding server
                 /\ Reply(i, j, [mtype  |-> LEADERINFO,
                                 mepoch |-> acceptedEpoch[i]])
                 /\ UNCHANGED tempMaxEpoch
        /\ cepochRecv' = [cepochRecv EXCEPT ![i] = IF j \in cepochRecv[i] THEN cepochRecv[i]
                                                                          ELSE cepochRecv[i] \union {j}]
        /\ UNCHANGED <<serverVarsZ, followerVarsZ, electionVarsZ, initialHistory, leadingVoteSet, learners, ackeRecv, ackldRecv, 
                       forwarding, ackIndex, currentCounter, sendCounter, committedIndex, committedCounter, verifyVarsZ, electionMsgs, idTable>>

LeaderDiscovery1(i) ==
        /\ state[i]    = LEADING
        /\ zabState[i] = DISCOVERY
        /\ cepochRecv[i] \in Quorums
        /\ acceptedEpoch' = [acceptedEpoch EXCEPT ![i] = tempMaxEpoch[i] + 1]
        /\ cepochRecv'    = [cepochRecv    EXCEPT ![i] = cepochRecv[i] \union {NullPoint}]
        /\ BroadcastLEADERINFO(i, [mtype  |-> LEADERINFO,
                                   mepoch |-> acceptedEpoch'[i]])
        /\ UNCHANGED <<state, currentEpoch, lastZxid, zabState, history, commitIndex, electionVarsZ, leadingVoteSet, learners, ackeRecv, ackldRecv, 
                       forwarding, ackIndex, currentCounter, sendCounter, committedIndex, committedCounter, 
                       tempVarsZ, followerVarsZ, verifyVarsZ, electionMsgs, idTable>>

(* In phase f12, follower receives NEWEPOCH. If e' > f.p, then follower sends ACK-E back,
   and ACK-E contains f.a and lastZxid to let leader judge whether it is the latest.
   After handling NEWEPOCH, follower's zabState turns to SYNCHRONIZATION. *)
FollowerHandleLEADERINFO(i, j) ==
        /\ state[i] = FOLLOWING
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = LEADERINFO
        /\ LET msg     == msgs[j][i][1]
               infoOk  == j = leaderAddr[i]
               epochOk == /\ infoOk
                          /\ msg.mepoch >= acceptedEpoch[i]
               correct == /\ epochOk
                          /\ zabState[i] = DISCOVERY
           IN /\ infoOk
              /\ \/ /\ epochOk                      \* 1. Normal case
                    /\ \/ /\ correct
                          /\ acceptedEpoch' = [acceptedEpoch EXCEPT ![i] = msg.mepoch]
                          /\ Reply(i, j, [mtype      |-> ACKEPOCH,
                                          mepoch     |-> msg.mepoch,
                                          mlastEpoch |-> currentEpoch[i],
                                          mlastZxid  |-> lastZxid[i]])
                          /\ cepochSent' = [cepochSent EXCEPT ![i] = TRUE]
                          /\ UNCHANGED inherentViolated
                       \/ /\ ~correct
                          /\ PrintT("Exception: Condition correct is false in FollowerHandleLEADERINFO(" \o ToString(i) \o ", " \o ToString(j) \o ").")
                          /\ inherentViolated' = TRUE
                          /\ Discard(j, i)
                          /\ UNCHANGED <<acceptedEpoch, cepochSent>>
                    /\ zabState' = [zabState EXCEPT ![i] = IF zabState[i] = DISCOVERY THEN SYNCHRONIZATION
                                                                                      ELSE zabState[i]]
                    /\ UNCHANGED <<varsL, leaderAddr>>
                 \/ /\ ~epochOk                    \* 2. Abnormal case - go back to election
                    /\ FollowerShutdown(i)
                    /\ Clean(i, j)
                    /\ UNCHANGED <<acceptedEpoch, cepochSent, inherentViolated>>
        /\ UNCHANGED <<history, commitIndex, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, currentCounter, sendCounter, committedIndex,
                       committedCounter, tempVarsZ, synced, proposalMsgsLog, epochLeader>>

\* Abstraction of actions making follower synced with leader before leader sending NEWLEADER.
subRECOVERYSYNC(i, j) ==
        LET canSync == /\ state[i] = LEADING   /\ zabState[i] /= DISCOVERY      /\ j \in learners[i]  /\ j \in ackeRecv[i]
                       /\ state[j] = FOLLOWING /\ zabState[j] = SYNCHRONIZATION /\ leaderAddr[j] = i  /\ synced[j] = FALSE
        IN
        \/ /\ canSync
           /\ history'     = [history     EXCEPT ![j] = history[i]]
           /\ lastZxid'    = [lastZxid    EXCEPT ![j] = lastZxid[i]]
           /\ UpdateProposal(j, leaderAddr[j], lastZxid'[j], currentEpoch[j])
           /\ commitIndex' = [commitIndex EXCEPT ![j] = commitIndex[i]]
           /\ synced'      = [synced      EXCEPT ![j] = TRUE]
           /\ forwarding'  = [forwarding  EXCEPT ![i] = forwarding[i] \union {j}]    \* j will receive PROPOSAL and COMMIT
           /\ ackIndex'    = [ackIndex    EXCEPT ![i][j] = Len(history[i])]
           /\ committedCounter' = [committedCounter EXCEPT ![i][j] = Maximum({commitIndex[i] - Len(initialHistory[i]), 0})]
           /\ LET ms == [msource|->i, mtype|->"RECOVERYSYNC", mepoch|->acceptedEpoch[i], mproposals|->history[i]]
              IN proposalMsgsLog' = IF ms \in proposalMsgsLog THEN proposalMsgsLog
                                                              ELSE proposalMsgsLog \union {ms}
           /\ Reply(i, j, [mtype     |-> NEWLEADER,
                           mepoch    |-> acceptedEpoch[i],
                           mlastZxid |-> lastZxid[i]])
        \/ /\ ~canSync
           /\ Discard(j, i)
           /\ UNCHANGED <<history, lastZxid, currentVote, commitIndex, synced, forwarding, ackIndex, committedCounter, proposalMsgsLog>>
        
(* In phase l12, leader waits for receiving ACKEPOPCH from a quorum,
   and check whether it has the latest history and epoch from them.
   If so, leader's zabState turns to SYNCHRONIZATION. *)
LeaderHandleACKEPOCH(i, j) ==
        /\ state[i] = LEADING
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = ACKEPOCH
        /\ LET msg == msgs[j][i][1]
               infoOk == /\ j \in learners[i]            
                         /\ acceptedEpoch[i] = msg.mepoch
               logOk  == /\ infoOk                          \* logOk = TRUE means leader is more up-to-date than follower j
                         /\ \/ currentEpoch[i] > msg.mlastEpoch
                            \/ /\ currentEpoch[i] = msg.mlastEpoch
                               /\ \/ lastZxid[i][1] > msg.mlastZxid[1]
                                  \/ /\ lastZxid[i][1] = msg.mlastZxid[1]
                                     /\ lastZxid[i][2] >= msg.mlastZxid[2]
               replyOk == /\ infoOk
                          /\ NullPoint \in ackeRecv[i]
           IN /\ infoOk
              /\ \/ /\ replyOk
                    /\ subRECOVERYSYNC(i, j)
                    /\ ackeRecv' = [ackeRecv EXCEPT ![i] = IF j \notin ackeRecv[i] THEN ackeRecv[i] \union {j}
                                                                                   ELSE ackeRecv[i]]
                    /\ UNCHANGED <<state, currentEpoch,logicalClock, receiveVotes, outOfElection, recvQueue, waitNotmsg, leadingVoteSet, electionMsgs, idTable,
                                   zabState, leaderAddr, learners>>
                 \/ /\ ~replyOk
                    /\ \/ /\ logOk
                          /\ ackeRecv' = [ackeRecv EXCEPT ![i] = IF j \notin ackeRecv[i] THEN ackeRecv[i] \union {j}
                                                                                         ELSE ackeRecv[i]]
                          /\ Discard(j, i)
                          /\ UNCHANGED <<varsL, zabState, leaderAddr, learners, forwarding>>
                       \/ /\ ~logOk            \* go back to election
                          /\ LeaderShutdown(i)
                          /\ UNCHANGED ackeRecv
                    /\ UNCHANGED <<history, commitIndex, synced, forwarding, ackIndex, committedCounter, proposalMsgsLog>>
        /\ UNCHANGED <<acceptedEpoch, cepochRecv, ackldRecv,currentCounter, sendCounter, committedIndex, tempVarsZ, cepochSent, epochLeader, inherentViolated>>
                          
LeaderDiscovery2(i) ==
        /\ state[i] = LEADING
        /\ zabState[i] = DISCOVERY
        /\ ackeRecv[i] \in Quorums
        /\ zabState'       = [zabState       EXCEPT ![i] = SYNCHRONIZATION]
        /\ currentEpoch'   = [currentEpoch   EXCEPT ![i] = acceptedEpoch[i]]
        /\ initialHistory' = [initialHistory EXCEPT ![i] = history[i]]
        /\ ackeRecv'       = [ackeRecv       EXCEPT ![i] = ackeRecv[i] \union {NullPoint}]
        /\ ackIndex'       = [ackIndex       EXCEPT ![i][i] = Len(history[i])]
        /\ UpdateProposal(i, i, lastZxid[i], currentEpoch'[i])
        /\ LET epoch == acceptedEpoch[i]
           IN epochLeader' = [epochLeader EXCEPT ![epoch] = epochLeader[epoch] \union {i}]
        /\ UNCHANGED <<state, lastZxid, acceptedEpoch, history, commitIndex, logicalClock, receiveVotes, outOfElection, recvQueue, waitNotmsg,
                       leadingVoteSet, learners, cepochRecv, ackldRecv, forwarding, currentCounter, sendCounter, committedIndex, committedCounter,
                       tempMaxEpoch, followerVarsZ, proposalMsgsLog, inherentViolated, msgVarsZ, idTable>>

(* Note:  Set cepochRecv, ackeRecv, ackldRecv to {NullPoint} in corresponding three actions to
          make sure that the prospective leader will not broadcast NEWEPOCH/NEWLEADER/COMMITLD twice.*)
-----------------------------------------------------------------------------
RECOVERYSYNC(i, j) ==
        /\ state[i] = LEADING   /\ zabState[i] /= DISCOVERY      /\ j \in learners[i] /\ j \in ackeRecv[i]
        /\ state[j] = FOLLOWING /\ zabState[j] = SYNCHRONIZATION /\ leaderAddr[j] = i /\ synced[j] = FALSE
        \* /\ acceptedEpoch[i] = acceptedEpoch[j] \* This condition is unnecessary.
        /\ history'     = [history     EXCEPT ![j] = history[i]]
        /\ lastZxid'    = [lastZxid    EXCEPT ![j] = lastZxid[i]]
        /\ UpdateProposal(j, leaderAddr[j], lastZxid'[j], currentEpoch[j])
        /\ commitIndex' = [commitIndex EXCEPT ![j] = commitIndex[i]]
        /\ synced'      = [synced      EXCEPT ![j] = TRUE]
        /\ forwarding'  = [forwarding  EXCEPT ![i] = forwarding[i] \union {j}]
        /\ ackIndex'    = [ackIndex    EXCEPT ![i][j] = Len(history[i])]
        /\ committedCounter' = [committedCounter EXCEPT ![i][j] = Maximum({commitIndex[i] - Len(initialHistory[i]), 0})]
        /\ LET ms == [msource|->i, mtype|->"RECOVERYSYNC", mepoch|->acceptedEpoch[i], mproposals|->history[i]]
           IN proposalMsgsLog' = IF ms \in proposalMsgsLog THEN proposalMsgsLog
                                                           ELSE proposalMsgsLog \union {ms}
        /\ Send(i, j, [mtype     |-> NEWLEADER,
                       mepoch    |-> acceptedEpoch[i],
                       mlastZxid |-> lastZxid[i]])
        /\ UNCHANGED <<state, zabState, acceptedEpoch, currentEpoch, logicalClock, receiveVotes, outOfElection, recvQueue, waitNotmsg, 
                       leadingVoteSet, learners, cepochRecv, ackeRecv, ackldRecv, currentCounter, sendCounter, committedIndex, 
                       tempVarsZ, cepochSent, leaderAddr, epochLeader, inherentViolated, electionMsgs, idTable>>

(* In phase f21, follower receives NEWLEADER. The follower updates its epoch and history,
   and sends back ACK-LD to leader. *)
FollowerHandleNEWLEADER(i, j) ==
        /\ state[i] = FOLLOWING
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = NEWLEADER
        /\ LET msg     == msgs[j][i][1]
               infoOk  == /\ leaderAddr[i] = j
                          /\ acceptedEpoch[i] = msg.mepoch
               correct == /\ infoOk
                          /\ zabState[i] = SYNCHRONIZATION
                          /\ synced[i]
                          /\ ZxidEqual(lastZxid[i], msg.mlastZxid)
           IN /\ infoOk
              /\ currentEpoch' = [currentEpoch EXCEPT ![i] = msg.mepoch]
              /\ UpdateProposal(i, j, lastZxid[i], currentEpoch'[i])
              /\ \/ /\ correct
                    /\ Reply(i, j, [mtype  |-> ACKLD,
                                    mepoch |-> msg.mepoch])
                    /\ UNCHANGED inherentViolated
                 \/ /\ ~correct
                    /\ PrintT("Exception: Condition correct is false in FollowerHandleNEWLEADER(" \o ToString(i) \o ", " \o ToString(j) \o ").")
                    /\ inherentViolated' = TRUE
                    /\ Discard(j, i)
        /\ UNCHANGED <<state, lastZxid, zabState, acceptedEpoch, history, commitIndex, logicalClock, receiveVotes, outOfElection, recvQueue, waitNotmsg,
                       leaderVarsZ, tempVarsZ, followerVarsZ, proposalMsgsLog, epochLeader, electionMsgs, idTable>>

\* In phase l22, leader receives ACK-LD from a quorum of followers, and sends COMMIT-LD(UPTODATE) to followers.
LeaderHandleACKLD(i, j) ==
        /\ state[i] = LEADING
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = ACKLD
        /\ LET msg     == msgs[j][i][1]
               infoOk  == /\ acceptedEpoch[i] = msg.mepoch
                          /\ j \in learners[i]
               replyOk == /\ infoOk
                          /\ NullPoint \in ackldRecv[i]
           IN /\ infoOk
              /\ \/ /\ replyOk
                    /\ Reply(i, j, [mtype   |-> UPTODATE,
                                    mepoch  |-> acceptedEpoch[i],
                                    mcommit |-> commitIndex[i]])
                    /\ committedCounter' = [committedCounter EXCEPT ![i][j] = Maximum({commitIndex[i] - Len(initialHistory[i]), committedCounter[i][j]})]
                 \/ /\ ~replyOk
                    /\ Discard(j, i)
                    /\ UNCHANGED committedCounter
              /\ ackldRecv' = [ackldRecv EXCEPT ![i] = IF j \notin ackldRecv[i] THEN ackldRecv[i] \union {j}
                                                                                ELSE ackldRecv[i]]
        /\ UNCHANGED <<serverVarsZ, electionVarsZ, leadingVoteSet, learners, cepochRecv, ackeRecv, forwarding, 
                       ackIndex, currentCounter, sendCounter, committedIndex, tempVarsZ, followerVarsZ, verifyVarsZ, electionMsgs, idTable>>

LeaderSync2(i) ==
        /\ state[i] = LEADING
        /\ zabState[i] = SYNCHRONIZATION
        /\ ackldRecv[i] \in Quorums
        /\ commitIndex'    = [commitIndex    EXCEPT ![i] = Len(history[i])]
        /\ committedIndex' = [committedIndex EXCEPT ![i] = Len(history[i])]
        /\ zabState'       = [zabState       EXCEPT ![i] = BROADCAST]
        /\ currentCounter' = [currentCounter EXCEPT ![i] = 0]
        /\ sendCounter'    = [sendCounter    EXCEPT ![i] = 0]
        /\ ackldRecv'      = [ackldRecv      EXCEPT ![i] = ackldRecv[i] \union {NullPoint}]
        /\ BroadcastUPTODATE(i, [mtype   |-> UPTODATE,
                                 mepoch  |-> acceptedEpoch[i],
                                 mcommit |-> Len(history[i])]) \* In actual UPTODATE doesn't carry this info
        /\ UNCHANGED <<state, currentEpoch, lastZxid, acceptedEpoch, history, electionVarsZ, leadingVoteSet, learners, cepochRecv, ackeRecv, forwarding, ackIndex, 
                       committedCounter, tempVarsZ, followerVarsZ, verifyVarsZ, electionMsgs, idTable>>

FollowerHandleUPTODATE(i, j) ==
        /\ state[i] = FOLLOWING
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = UPTODATE
        /\ LET msg == msgs[j][i][1]
               infoOk == /\ leaderAddr[i] = j
                         /\ acceptedEpoch[i] = msg.mepoch
               correct == /\ infoOk
                          /\ zabState[i] = SYNCHRONIZATION
                          /\ currentEpoch[i] = msg.mepoch
           IN /\ infoOk
              /\ \/ /\ correct
                    /\ commitIndex' = [commitIndex EXCEPT ![i] = Maximum({commitIndex[i], msg.mcommit})] \* May have received COMMIT and have a bigger commit index.
                    /\ zabState'    = [zabState    EXCEPT ![i] = BROADCAST]
                    /\ UNCHANGED inherentViolated
                 \/ /\ ~correct
                    /\ PrintT("Exception: Condition correct is false in FollowerHandleUPTODATE(" \o ToString(i) \o ", " \o ToString(j) \o ").")
                    /\ inherentViolated' = TRUE
                    /\ UNCHANGED <<commitIndex, zabState>>
        /\ Discard(j, i)
        /\ UNCHANGED <<state, currentEpoch, lastZxid, acceptedEpoch, history, electionVarsZ, leaderVarsZ, tempVarsZ, followerVarsZ,
                       proposalMsgsLog, epochLeader, electionMsgs, idTable>>

-----------------------------------------------------------------------------
(* In phase l31, leader receives client request and broadcasts PROPOSAL.
   Note: In production, any server in traffic can receive requests and forward it to leader if necessary.
   We choose to let leader be the sole one who can receive requests, to simplify spec and keep correctness at the same time. *)
ClientRequest(i, v) ==
        /\ state[i] = LEADING
        /\ zabState[i] = BROADCAST
        /\ currentCounter' = [currentCounter EXCEPT ![i] = currentCounter[i] + 1]
        /\ LET newTransaction == [epoch   |-> acceptedEpoch[i],
                                  counter |-> currentCounter'[i],
                                  value   |-> v]
           IN /\ history'  = [history  EXCEPT ![i] = Append(history[i], newTransaction)]
              /\ lastZxid' = [lastZxid EXCEPT ![i] = <<acceptedEpoch[i], currentCounter'[i]>>]
              /\ ackIndex' = [ackIndex EXCEPT ![i][i] = Len(history'[i])]
              /\ UpdateProposal(i, i, lastZxid'[i], currentEpoch[i])
        /\ UNCHANGED <<state, currentEpoch, zabState, acceptedEpoch, commitIndex, logicalClock, receiveVotes, outOfElection, recvQueue, waitNotmsg,
                       leadingVoteSet, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, sendCounter, committedIndex, committedCounter,
                       tempVarsZ, followerVarsZ, verifyVarsZ, msgVarsZ, idTable>>

LeaderBroadcast1(i) ==
        /\ state[i] = LEADING
        /\ zabState[i] = BROADCAST
        /\ sendCounter[i] < currentCounter[i]
        /\ LET toBeSentCounter == sendCounter[i] + 1
               toBeSentIndex   == Len(initialHistory[i]) + toBeSentCounter
               toBeSentEntry   == history[i][toBeSentIndex]
           IN /\ Broadcast(i, [mtype     |-> PROPOSAL,
                               mepoch    |-> acceptedEpoch[i],
                               mproposal |-> toBeSentEntry])
              /\ sendCounter' = [sendCounter EXCEPT ![i] = toBeSentCounter]
              /\ LET m == [msource|-> i, mepoch|-> acceptedEpoch[i], mtype|-> PROPOSAL, mproposal|-> toBeSentEntry]
                 IN proposalMsgsLog' = proposalMsgsLog \union {m}
        /\ UNCHANGED <<serverVarsZ, electionVarsZ, leadingVoteSet, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, currentCounter, 
                       committedIndex, committedCounter, tempVarsZ, followerVarsZ, epochLeader, inherentViolated, electionMsgs, idTable>>

\* In phase f31, follower accepts proposal and append it to history.
FollowerHandlePROPOSAL(i, j) ==
        /\ state[i] = FOLLOWING
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = PROPOSAL
        /\ LET msg    == msgs[j][i][1]
               infoOk == /\ leaderAddr[i] = j
                         /\ acceptedEpoch[i] = msg.mepoch
               correct == /\ infoOk
                          /\ zabState[i] /= DISCOVERY
                          /\ synced[i]
               logOk  == \/ /\ msg.mproposal.counter = 1     \* the first PROPOSAL in this epoch
                            /\ \/ Len(history[i]) = 0
                               \/ /\ Len(history[i]) > 0
                                  /\ history[i][Len(history[i])].epoch < msg.mepoch
                         \/ /\ msg.mproposal.counter > 1      \* not the first PROPOSAL in this epoch
                            /\ Len(history[i]) > 0
                            /\ history[i][Len(history[i])].epoch = msg.mepoch
                            /\ history[i][Len(history[i])].counter = msg.mproposal.counter - 1
           IN /\ infoOk
              /\ \/ /\ correct
                    /\ \/ /\ logOk
                          /\ history'  = [history  EXCEPT ![i] = Append(history[i], msg.mproposal)]
                          /\ lastZxid' = [lastZxid EXCEPT ![i] = <<msg.mepoch, msg.mproposal.counter>>]
                          /\ UpdateProposal(i, j, lastZxid'[i], currentEpoch[i])
                          /\ Reply(i, j, [mtype  |-> ACK,
                                          mepoch |-> acceptedEpoch[i],
                                          mzxid  |-> <<msg.mepoch, msg.mproposal.counter>>])
                          /\ UNCHANGED inherentViolated
                       \/ /\ ~logOk
                          /\ PrintT("Exception: Condition logOk is false in FollowerHandlePROPOSAL(" \o ToString(i) \o ", " \o ToString(j) \o ").")
                          /\ inherentViolated' = TRUE
                          /\ Discard(j, i)
                          /\ UNCHANGED <<history, lastZxid, currentVote>>
                 \/ /\ ~correct
                    /\ PrintT("Exception: Condition correct is false in FollowerHandlePROPOSAL(" \o ToString(i) \o ", " \o ToString(j) \o ").")
                    /\ inherentViolated' = TRUE
                    /\ Discard(j, i)
                    /\ UNCHANGED <<history, lastZxid, currentVote>>
        /\ UNCHANGED <<state, currentEpoch, zabState, acceptedEpoch, commitIndex, logicalClock, receiveVotes, outOfElection, recvQueue, waitNotmsg,
                       leaderVarsZ, tempVarsZ, followerVarsZ, proposalMsgsLog, epochLeader, electionMsgs, idTable>>

\* In phase l32, leader receives ack from a quorum of followers to a certain proposal, and commits the proposal.
LeaderHandleACK(i, j) ==
        /\ state[i] = LEADING
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = ACK
        /\ LET msg == msgs[j][i][1]
               infoOk == /\ j \in forwarding[i]
                         /\ acceptedEpoch[i] = msg.mepoch
               correct == /\ infoOk
                          /\ zabState[i] = BROADCAST
                          /\ sendCounter[i] >= msg.mzxid[2]
               logOk == /\ infoOk
                        /\ ackIndex[i][j] + 1 = Len(initialHistory[i]) + msg.mzxid[2]
           IN /\ infoOk
              /\ \/ /\ correct
                    /\ \/ /\ logOk
                          /\ ackIndex' = [ackIndex EXCEPT ![i][j] = ackIndex[i][j] + 1]
                       \/ /\ ~logOk
                          /\ PrintT("Note: redundant ACK.")
                          /\ UNCHANGED ackIndex
                    /\ UNCHANGED inherentViolated
                 \/ /\ ~correct
                    /\ PrintT("Exception: Condition correct is false in FollowerHandleACK(" \o ToString(i) \o ", " \o ToString(j) \o ").")
                    /\ inherentViolated' = TRUE
                    /\ UNCHANGED ackIndex
        /\ Discard(j, i)
        /\ UNCHANGED <<serverVarsZ, electionVarsZ, leadingVoteSet, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, currentCounter, sendCounter, 
                       committedIndex, committedCounter, tempVarsZ, followerVarsZ, proposalMsgsLog, epochLeader, electionMsgs, idTable>>

LeaderAdvanceCommit(i) ==
        /\ state[i] = LEADING
        /\ zabState[i] = BROADCAST
        /\ commitIndex[i] < Len(history[i])
        /\ LET Agree(index)   == {i} \union {k \in (Server\{i}): ackIndex[i][k] >= index}
               agreeIndexes   == {index \in (commitIndex[i] + 1)..Len(history[i]): Agree(index) \in Quorums}
               newCommitIndex == IF agreeIndexes /= {} THEN Maximum(agreeIndexes)
                                                       ELSE commitIndex[i]
           IN commitIndex' = [commitIndex EXCEPT ![i] = newCommitIndex]
        /\ UNCHANGED <<state, currentEpoch, lastZxid, zabState, acceptedEpoch, history, electionVarsZ, leaderVarsZ, tempVarsZ, followerVarsZ,
                       verifyVarsZ, msgVarsZ, idTable>>

LeaderBroadcast2(i) ==
        /\ state[i] = LEADING
        /\ zabState[i] = BROADCAST
        /\ committedIndex[i] < commitIndex[i]
        /\ Len(initialHistory[i]) + sendCounter[i] > committedIndex[i]
        /\ LET newCommittedIndex == committedIndex[i] + 1
           IN /\ Broadcast(i, [mtype  |-> COMMIT,
                               mepoch |-> acceptedEpoch[i],
                               mzxid  |-> <<history[i][newCommittedIndex].epoch, history[i][newCommittedIndex].counter>>])
              /\ committedIndex' = [committedIndex EXCEPT ![i] = committedIndex[i] + 1]
              /\ committedCounter' = [committedCounter EXCEPT ![i] = [v \in Server|-> IF /\ v \in forwarding[i]
                                                                                         /\ committedCounter[i][v] < history[i][newCommittedIndex].counter 
                                                                                      THEN history[i][newCommittedIndex].counter
                                                                                      ELSE committedCounter[i][v]]]
        /\ UNCHANGED <<serverVarsZ, electionVarsZ, leadingVoteSet, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, currentCounter, 
                       sendCounter, tempVarsZ, followerVarsZ, verifyVarsZ, electionMsgs, idTable>>

\* In phase f32, follower receives COMMIT and commits transaction.
FollowerHandleCOMMIT(i, j) ==
        /\ state[i] = FOLLOWING
        /\ msgs[j][i] /= << >>
        /\ msgs[j][i][1].mtype = COMMIT
        /\ LET msg    == msgs[j][i][1]
               infoOk == /\ leaderAddr[i] = j
                         /\ acceptedEpoch[i] = msg.mepoch
               correct == /\ infoOk
                          /\ zabState[i] /= DISCOVERY
                          /\ synced[i]
               mindex == IF Len(history[i]) = 0 THEN -1
                         ELSE IF \E idx \in 1..Len(history[i]): PZxidEqual(history[i][idx], msg.mzxid)
                              THEN CHOOSE idx \in 1..Len(history[i]): PZxidEqual(history[i][idx], msg.mzxid)
                              ELSE -1
               logOk  == mindex > 0
               latest == commitIndex[i] + 1 = mindex
           IN /\ infoOk
              /\ \/ /\ correct
                    /\ \/ /\ logOk
                          /\ \/ /\ latest
                                /\ commitIndex' = [commitIndex EXCEPT ![i] = commitIndex[i] + 1]
                                /\ UNCHANGED inherentViolated
                             \/ /\ ~latest
                                /\ PrintT("Note: Condition latest is false in FollowerHandleCOMMIT(" \o ToString(i) \o ", " \o ToString(j) \o ").")
                                /\ inherentViolated' = TRUE
                                /\ UNCHANGED commitIndex
                       \/ /\ ~logOk
                          /\ PrintT("Exception: Condition logOk is false in FollowerHandleCOMMIT(" \o ToString(i) \o ", " \o ToString(j) \o ").")
                          /\ inherentViolated' = TRUE
                          /\ UNCHANGED commitIndex
                 \/ /\ ~correct
                    /\ PrintT("Exception: Condition correct is false in FollowerHandleCOMMIT(" \o ToString(i) \o ", " \o ToString(j) \o ").")
                    /\ inherentViolated' = TRUE
                    /\ UNCHANGED commitIndex
        /\ Discard(j, i)
        /\ UNCHANGED <<state, currentEpoch, lastZxid, zabState, acceptedEpoch, history, electionVarsZ, leaderVarsZ, tempVarsZ, followerVarsZ,
                       proposalMsgsLog, epochLeader, electionMsgs, idTable>>

-----------------------------------------------------------------------------
(* Used to discard some messages which should not exist in actual.
   This action should not be triggered. *)
FilterNonexistentMessage(i) ==
        /\ \E j \in Server \ {i}: /\ msgs[j][i] /= << >>
                                  /\ LET msg == msgs[j][i][1]
                                     IN 
                                        \/ /\ state[i] = LEADING
                                           /\ LET infoOk == /\ j \in learners[i]
                                                            /\ acceptedEpoch[i] = msg.mepoch
                                              IN
                                              \/ msg.mtype = LEADERINFO
                                              \/ msg.mtype = NEWLEADER
                                              \/ msg.mtype = UPTODATE
                                              \/ msg.mtype = PROPOSAL
                                              \/ msg.mtype = COMMIT
                                              \/ /\ j \notin learners[i]
                                                 /\ msg.mtype = FOLLOWERINFO
                                              \/ /\ ~infoOk
                                                 /\ \/ msg.mtype = ACKEPOCH
                                                    \/ msg.mtype = ACKLD
                                                    \/ msg.mtype = ACK
                                        \/ /\ state[i] = FOLLOWING
                                           /\ LET infoOk == /\ j = leaderAddr[i]
                                                            /\ acceptedEpoch[i] = msg.mepoch
                                              IN
                                              \/ msg.mtype = FOLLOWERINFO
                                              \/ msg.mtype = ACKEPOCH
                                              \/ msg.mtype = ACKLD
                                              \/ msg.mtype = ACK
                                              \/ /\ j /= leaderAddr[i]
                                                 /\ msg.mtype = LEADERINFO
                                              \/ /\ ~infoOk
                                                 /\ \/ msg.mtype = NEWLEADER
                                                    \/ msg.mtype = UPTODATE
                                                    \/ msg.mtype = PROPOSAL
                                                    \/ msg.mtype = COMMIT   
                                        \/ state[i] = LOOKING
                                  /\ Discard(j, i)
        /\ inherentViolated' = TRUE
        /\ UNCHANGED <<serverVarsZ, electionVarsZ, leaderVarsZ, tempVarsZ, followerVarsZ, proposalMsgsLog, epochLeader, electionMsgs, idTable>>
        
-----------------------------------------------------------------------------
\* Defines how the variables may transition.
NextZ ==
        (* FLE modlue *)
        \/ \E i, j \in Server: FLEReceiveNotmsg(i, j)
        \/ \E i \in Server:    FLENotmsgTimeout(i)
        \/ \E i \in Server:    FLEHandleNotmsg(i)
        \/ \E i \in Server:    FLEWaitNewNotmsg(i)
        \/ \E i \in Server:    FLEWaitNewNotmsgEnd(i)
        (* Some conditions like failure, network delay *)
        \/ \E i \in Server:    FollowerTimout(i)
        \/ \E i \in Server:    LeaderTimeout(i)
        \/ \E i, j \in Server: Timeout(i, j)
        (* Zab module - Discovery and Synchronization part *)
        \/ \E i, j \in Server: EstablishConnection(i, j)
        \/ \E i \in Server:    FollowerSendFOLLOWERINFO(i)
        \/ \E i, j \in Server: LeaderHandleFOLLOWERINFO(i, j)
        \/ \E i \in Server:    LeaderDiscovery1(i) 
        \/ \E i, j \in Server: FollowerHandleLEADERINFO(i, j)
        \/ \E i, j \in Server: LeaderHandleACKEPOCH(i, j)
        \/ \E i \in Server:    LeaderDiscovery2(i)
        \/ \E i, j \in Server: RECOVERYSYNC(i, j)
        \/ \E i, j \in Server: FollowerHandleNEWLEADER(i, j)
        \/ \E i, j \in Server: LeaderHandleACKLD(i, j)
        \/ \E i \in Server:    LeaderSync2(i)
        \/ \E i, j \in Server: FollowerHandleUPTODATE(i, j)
        (* Zab module - Broadcast part *)
        \/ \E i \in Server, v \in Value: ClientRequest(i, v)
        \/ \E i \in Server:    LeaderBroadcast1(i) 
        \/ \E i, j \in Server: FollowerHandlePROPOSAL(i, j)
        \/ \E i, j \in Server: LeaderHandleACK(i, j)
        \/ \E i \in Server:    LeaderAdvanceCommit(i)
        \/ \E i \in Server:    LeaderBroadcast2(i)
        \/ \E i, j \in Server: FollowerHandleCOMMIT(i, j)
        (* An action used to judge whether there are redundant messages in network *)
        \/ \E i \in Server:    FilterNonexistentMessage(i)

SpecZ == InitZ /\ [][NextZ]_vars

-----------------------------------------------------------------------------
\* Define safety properties of Zab 1.0 protocol.

ShouldNotBeTriggered == inherentViolated = FALSE

\* There is most one established leader for a certain epoch.
Leadership1 == \A i, j \in Server:
                   /\ state[i] = LEADING /\ zabState[i] \in {SYNCHRONIZATION, BROADCAST}
                   /\ state[j] = LEADING /\ zabState[j] \in {SYNCHRONIZATION, BROADCAST}
                   /\ acceptedEpoch[i] = acceptedEpoch[j]
                  => i = j

Leadership2 == \A epoch \in 1..MAXEPOCH: Cardinality(epochLeader[epoch]) < 2

\* PrefixConsistency: The prefix that have been committed in history in any process is the same.
PrefixConsistency == \A i, j \in Server:
                        LET smaller == Minimum({commitIndex[i], commitIndex[j]})
                        IN \/ smaller = 0
                           \/ /\ smaller > 0
                              /\ \A index \in 1..smaller: TransactionEqual(history[i][index], history[j][index])

\* Integrity: If some follower delivers one transaction, then some primary has broadcast it.
Integrity == \A i \in Server:
                /\ state[i] = FOLLOWING 
                /\ commitIndex[i] > 0
                => \A index \in 1..commitIndex[i]: \E msg \in proposalMsgsLog:
                    \/ /\ msg.mtype = PROPOSAL
                       /\ TransactionEqual(msg.mproposal, history[i][index])
                    \/ /\ msg.mtype = "RECOVERYSYNC"
                       /\ \E tindex \in 1..Len(msg.mproposals): TransactionEqual(msg.mproposals[tindex], history[i][index])

\* Agreement: If some follower f delivers transaction a and some follower f' delivers transaction b,
\*            then f' delivers a or f delivers b.
Agreement == \A i, j \in Server:
                /\ state[i] = FOLLOWING /\ commitIndex[i] > 0
                /\ state[j] = FOLLOWING /\ commitIndex[j] > 0
                =>
                \A index1 \in 1..commitIndex[i], index2 \in 1..commitIndex[j]:
                    \/ \E indexj \in 1..commitIndex[j]:
                        TransactionEqual(history[j][indexj], history[i][index1])
                    \/ \E indexi \in 1..commitIndex[i]:
                        TransactionEqual(history[i][indexi], history[j][index2])

\* Total order: If some follower delivers a before b, then any process that delivers b
\*              must also deliver a and deliver a before b.
TotalOrder == \A i, j \in Server: commitIndex[i] >= 2 /\ commitIndex[j] >= 2
                => \A indexi1 \in 1..(commitIndex[i] - 1): \A indexi2 \in (indexi1 + 1)..commitIndex[i]:
                    LET logOk == \E index \in 1..commitIndex[j]: TransactionEqual(history[i][indexi2], history[j][index])
                    IN \/ ~logOk
                       \/ /\ logOk 
                          /\ \E indexj2 \in 1..commitIndex[j]: 
                                              /\ TransactionEqual(history[i][indexi2], history[j][indexj2])
                                              /\ \E indexj1 \in 1..(indexj2 - 1): TransactionEqual(history[i][indexi1], history[j][indexj1])

\* Local primary order: If a primary broadcasts a before it broadcasts b, then a follower that
\*                      delivers b must also deliver a before b.
LocalPrimaryOrder == LET mset(i, e) == {msg \in proposalMsgsLog: /\ msg.mtype = PROPOSAL
                                                                 /\ msg.msource = i 
                                                                 /\ msg.mepoch = e}
                         mentries(i, e) == {msg.mproposal: msg \in mset(i, e)}
                     IN \A i \in Server: \A e \in 1..currentEpoch[i]:
                         \/ Cardinality(mentries(i, e)) < 2
                         \/ /\ Cardinality(mentries(i, e)) >= 2
                            /\ \E tsc1, tsc2 \in mentries(i, e):
                             \/ TransactionEqual(tsc1, tsc2)
                             \/ /\ \lnot TransactionEqual(tsc1, tsc2)
                                /\ LET tscPre  == IF TransactionPrecede(tsc1, tsc2) THEN tsc1 ELSE tsc2
                                       tscNext == IF TransactionPrecede(tsc1, tsc2) THEN tsc2 ELSE tsc1
                                   IN \A j \in Server: /\ commitIndex[j] >= 2
                                                       /\ \E index \in 1..commitIndex[j]: TransactionEqual(history[j][index], tscNext)
                                    => \E index2 \in 1..commitIndex[j]: 
                                            /\ TransactionEqual(history[j][index2], tscNext)
                                            /\ index2 > 1
                                            /\ \E index1 \in 1..(index2 - 1): TransactionEqual(history[j][index1], tscPre)

\* Global primary order: A follower f delivers both a with epoch e and b with epoch e', and e < e',
\*                       then f must deliver a before b.
GlobalPrimaryOrder == \A i \in Server: commitIndex[i] >= 2
                         => \A idx1, idx2 \in 1..commitIndex[i]: \/ history[i][idx1].epoch >= history[i][idx2].epoch
                                                                 \/ /\ history[i][idx1].epoch < history[i][idx2].epoch
                                                                    /\ idx1 < idx2

\* Primary integrity: If primary p broadcasts a and some follower f delivers b such that b has epoch
\*                    smaller than epoch of p, then p must deliver b before it broadcasts a.
PrimaryIntegrity == \A i, j \in Server: /\ state[i] = LEADING
                                        /\ state[j] = FOLLOWING
                                        /\ commitIndex[j] >= 1
                        => \A index \in 1..commitIndex[j]: \/ history[j][index].epoch >= currentEpoch[i]
                                                           \/ /\ history[j][index].epoch < currentEpoch[i]
                                                              /\ \E idx \in 1..commitIndex[i]: TransactionEqual(history[i][idx], history[j][index])
                                                              
=============================================================================
\* Modification History
\* Last modified Thu Jul 15 14:14:09 CST 2021 by Dell
\* Created Tue Jun 29 22:13:02 CST 2021 by Dell
