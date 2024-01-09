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

------------------------------ MODULE ZkV3_7_0 ------------------------------
(* This is the system specification for Zab in apache/zookeeper with version 3.7.0 *)

(* Reference:
   FLE: FastLeaderElection.java, Vote.java, QuorumPeer.java, e.g. in 
        https://github.com/apache/zookeeper.
   ZAB: QuorumPeer.java, Learner.java, Follower.java, LearnerHandler.java,
        Leader.java, e.g. in https://github.com/apache/zookeeper.
        https://cwiki.apache.org/confluence/display/ZOOKEEPER/Zab1.0.
 *)
EXTENDS FastLeaderElection
-----------------------------------------------------------------------------
\* The set of requests that can go into history
\* CONSTANT Value \* Replaced by recorder.nClientRequest
Value == Nat
 
\* Zab states
CONSTANTS ELECTION, DISCOVERY, SYNCHRONIZATION, BROADCAST

\* Sync modes & message types
CONSTANTS DIFF, TRUNC, SNAP

\* Message types
CONSTANTS FOLLOWERINFO, LEADERINFO, ACKEPOCH, NEWLEADER, ACKLD, 
          UPTODATE, PROPOSAL, ACK, COMMIT
(* NOTE: In production, there is no message type ACKLD. Server judges if counter 
         of ACK is 0 to distinguish one ACK represents ACKLD or not. Here we
         divide ACK into ACKLD and ACK, to enhance readability of spec.*)

\* Node status
CONSTANTS ONLINE, OFFLINE

\* [MaxTimeoutFailures, MaxTransactionNum, MaxEpoch, MaxCrashes, MaxPartitions]
CONSTANT Parameters

MAXEPOCH == 10
-----------------------------------------------------------------------------
\* Variables in annotations mean variables defined in FastLeaderElection.
\* Variables that all servers use.
VARIABLES zabState,      \* Current phase of server, in
                         \* {ELECTION, DISCOVERY, SYNCHRONIZATION, BROADCAST}.
          acceptedEpoch, \* Epoch of the last LEADERINFO packet accepted,
                         \* namely f.p in paper.
          lastCommitted, \* Maximum index and zxid known to be committed,
                         \* namely 'lastCommitted' in Leader. Starts from 0,
                         \* and increases monotonically before restarting.
          lastSnapshot,  \* Index and zxid corresponding to latest snapshot
                         \* from data tree.
          initialHistory \* history that server initially has before election.
          \* state,        \* State of server, in {LOOKING, FOLLOWING, LEADING}.
          \* currentEpoch, \* Epoch of the last NEWLEADER packet accepted,
                           \* namely f.a in paper.
          \* lastProcessed,\* Index and zxid of the last processed txn.
          \* history       \* History of servers: sequence of transactions,
                           \* containing: zxid, value, ackSid, epoch.
           \* leader  : [committedRequests + toBeApplied] [outstandingProposals]
           \* follower: [committedRequests] [pendingTxns] 

\* Variables only used for leader.
VARIABLES learners,       \* Set of servers leader connects, 
                          \* namely 'learners' in Leader.
          connecting,     \* Set of learners leader has received 
                          \* FOLLOWERINFO from, namely  
                          \* 'connectingFollowers' in Leader.
                          \* Set of record [sid, connected].
          electing,       \* Set of learners leader has received
                          \* ACKEPOCH from, namely 'electingFollowers'
                          \* in Leader. Set of record 
                          \* [sid, peerLastZxid, inQuorum].
                          \* And peerLastZxid = <<-1,-1>> means has done
                          \* syncFollower with this sid.
                          \* inQuorum = TRUE means in code it is one
                          \* element in 'electingFollowers'.
          ackldRecv,      \* Set of learners leader has received
                          \* ACK of NEWLEADER from, namely
                          \* 'newLeaderProposal' in Leader.
                          \* Set of record [sid, connected].         
          forwarding,     \* Set of learners that are synced with
                          \* leader, namely 'forwardingFollowers'
                          \* in Leader.
          tempMaxEpoch    \* ({Maximum epoch in FOLLOWEINFO} + 1) that 
                          \* leader has received from learners,
                          \* namely 'epoch' in Leader.
          \* leadingVoteSet \* Set of voters that follow leader.

\* Variables only used for follower.
VARIABLES connectInfo, \* Record [sid, syncMode, nlRcv].
                       \* sid: Leader id that follower has connected with.
                       \* syncMode: Sync mode according to reveiced Sync Message.
                       \* nlRcv: If follower has received NEWLEADER.
          packetsSync  \* packets of PROPOSAL and COMMIT from leader,
                       \* namely 'packetsNotCommitted' and
                       \* 'packetsCommitted' in SyncWithLeader
                       \* in Learner.

\* Variables about network channel.
VARIABLE msgs       \* Simulates network channel.
                    \* msgs[i][j] means the input buffer of server j 
                    \* from server i.
         \* electionMsgs \* Network channel in FLE module.

\* Variables about status of cluster network and node presence.
VARIABLES status,    \* Whether the server is online or offline.
          partition  \* network partion.

\* Variables only used in verifying properties.
VARIABLES epochLeader,       \* Set of leaders in every epoch.
          proposalMsgsLog,   \* Set of all broadcast messages.
          violatedInvariants \* Check whether there are conditions 
                             \* contrary to the facts.
\* Variables only used for looking.                             
\*VARIABLE currentVote,   \* Info of current vote, namely 'currentVote'
\*                        \* in QuorumPeer.
\*         logicalClock,  \* Election instance, namely 'logicalClock'
\*                        \* in FastLeaderElection.
\*         receiveVotes,  \* Votes from current FLE round, namely
\*                        \* 'recvset' in FastLeaderElection.
\*         outOfElection, \* Votes from previous and current FLE round,
\*                        \* namely 'outofelection' in FastLeaderElection.
\*         recvQueue,     \* Queue of received notifications or timeout
\*                        \* signals.
\*         waitNotmsg     \* Whether waiting for new not.See line 1050
\*                        \* in FastLeaderElection for details.
\*VARIABLE idTable \* For mapping Server to Integers,
                   \* to compare ids between servers.
    \* Update: we have transformed idTable from variable to function.

\*VARIABLE clientReuqest \* Start from 0, and increases monotonically
                         \* when LeaderProcessRequest performed. To
                         \* avoid existing two requests with same value. 
    \* Update: Remove it to recorder.nClientRequest.

\* Variable used for recording critical data,
\* to constrain state space or update values.
VARIABLE recorder \* Consists: members of Parameters and pc, values.
                  \* Form is record: 
                  \* [pc, nTransaction, maxEpoch, nTimeout, nClientRequest,
                  \*  nPartition, nCrash]

serverVars == <<state, currentEpoch, lastProcessed, zabState, acceptedEpoch,
                history, lastCommitted, lastSnapshot, initialHistory>>       
electionVars == electionVarsL  
leaderVars == <<leadingVoteSet, learners, connecting, electing, 
                 ackldRecv, forwarding, tempMaxEpoch>>                                           
followerVars == <<connectInfo, packetsSync>>                
verifyVars == <<proposalMsgsLog, epochLeader, violatedInvariants>>   
msgVars == <<msgs, electionMsgs>>
envVars == <<status, partition>>                        

vars == <<serverVars, electionVars, leaderVars, followerVars,
          verifyVars, msgVars, envVars, recorder>> 
-----------------------------------------------------------------------------
ServersIncNullPoint == Server \union {NullPoint} 

Zxid ==
    Seq(Nat \union {-1}) 
    
HistoryItem ==
     [ zxid: Zxid,
       value: Value,
       ackSid: SUBSET Server,
       epoch: Nat ]    
    
Proposal ==
    [ source: Server, 
      epoch: Nat,
      zxid: Zxid,
      data: Value ]   

LastItem ==
    [ index: Nat, zxid: Zxid ]

SyncPackets == 
    [ notCommitted: Seq(HistoryItem),
      committed: Seq(Zxid) ]

Message ==
    [ mtype: {FOLLOWERINFO}, mzxid: Zxid ] \union
    [ mtype: {LEADERINFO}, mzxid: Zxid ] \union
    [ mtype: {ACKEPOCH}, mzxid: Zxid, mepoch: Nat \union {-1} ] \union
    [ mtype: {DIFF}, mzxid: Zxid ] \union 
    [ mtype: {TRUNC}, mtruncZxid: Zxid ] \union 
    [ mtype: {SNAP}, msnapZxid: Zxid, msnapshot: Seq(HistoryItem)] \union
    [ mtype: {PROPOSAL}, mzxid: Zxid, mdata: Value ] \union 
    [ mtype: {COMMIT}, mzxid: Zxid ] \union 
    [ mtype: {NEWLEADER}, mzxid: Zxid ] \union 
    [ mtype: {ACKLD}, mzxid: Zxid ] \union 
    [ mtype: {ACK}, mzxid: Zxid ] \union 
    [ mtype: {UPTODATE}, mzxid: Zxid ]
 
ElectionState == {LOOKING, FOLLOWING, LEADING}

ZabState == {ELECTION, DISCOVERY, SYNCHRONIZATION, BROADCAST}

ViolationSet == {"stateInconsistent", "proposalInconsistent", 
                 "commitInconsistent", "ackInconsistent", 
                 "messageIllegal" }

SyncMode == {DIFF, TRUNC, SNAP, NONE}

Connecting == [ sid : Server,
                connected: BOOLEAN ]

AckldRecv  == Connecting

Electing == [ sid: Server,
              peerLastZxid: Zxid,
              inQuorum: BOOLEAN  ]

ConnectInfo == [ sid : ServersIncNullPoint,
                 syncMode: SyncMode,
                 nlRcv: BOOLEAN ]

Vote ==
    [proposedLeader: ServersIncNullPoint,
     proposedZxid: Zxid,
     proposedEpoch: Nat ]
     
ElectionVote ==
    [vote: Vote, round: Nat, state: ElectionState, version: Nat]

ElectionMsg ==
    [ mtype: {NOTIFICATION}, 
      msource: Server, 
      mstate: ElectionState, 
      mround: Nat, 
      mvote: Vote ] \union
    [ mtype: {NONE} ]
            
TypeOK ==
    /\ zabState \in [Server -> ZabState]
    /\ acceptedEpoch \in [Server -> Nat]
    /\ lastCommitted \in [Server -> LastItem]
    /\ lastSnapshot \in [Server -> LastItem]
    /\ learners \in [Server -> SUBSET Server]
    /\ connecting \in [Server -> SUBSET Connecting]
    /\ electing \in [Server -> SUBSET Electing]
    /\ ackldRecv \in [Server -> SUBSET AckldRecv]
    /\ forwarding \in [Server -> SUBSET Server]
    /\ initialHistory \in [Server -> Seq(HistoryItem)] 
    /\ tempMaxEpoch \in [Server -> Nat]
    /\ connectInfo \in [Server -> ConnectInfo]
    /\ packetsSync \in [Server -> SyncPackets]
    /\ status \in [Server -> {ONLINE, OFFLINE}]
    /\ partition \in [Server -> [Server -> BOOLEAN ]]
    /\ proposalMsgsLog \in SUBSET Proposal
    /\ epochLeader \in [1..MAXEPOCH -> SUBSET Server]
    /\ violatedInvariants \in [ViolationSet -> BOOLEAN]
    /\ msgs \in [Server -> [Server -> Seq(Message)]]
    \* Fast Leader Election
    /\ electionMsgs \in [Server -> [Server -> Seq(ElectionMsg)]]
    /\ recvQueue \in [Server -> Seq(ElectionMsg)]
    /\ leadingVoteSet \in [Server -> SUBSET Server]
    /\ receiveVotes \in [Server -> [Server -> ElectionVote]]
    /\ currentVote \in [Server -> Vote]
    /\ outOfElection \in [Server -> [Server -> ElectionVote]]
    /\ lastProcessed \in [Server -> LastItem]
    /\ history \in [Server -> Seq(HistoryItem)]
    /\ state \in [Server -> ElectionState]
    /\ waitNotmsg \in [Server -> BOOLEAN]
    /\ currentEpoch \in [Server -> Nat]
    /\ logicalClock \in [Server -> Nat]
-----------------------------------------------------------------------------
\* Return the maximum value from the set S
Maximum(S) == IF S = {} THEN -1
                        ELSE CHOOSE n \in S: \A m \in S: n >= m

\* Return the minimum value from the set S
Minimum(S) == IF S = {} THEN -1
                        ELSE CHOOSE n \in S: \A m \in S: n <= m

\* Check server state
IsON(s)  == status[s] = ONLINE 
IsOFF(s) == status[s] = OFFLINE

IsLeader(s)   == state[s] = LEADING
IsFollower(s) == state[s] = FOLLOWING
IsLooking(s)  == state[s] = LOOKING

IsMyLearner(i, j) == j \in learners[i]
IsMyLeader(i, j)  == connectInfo[i].sid = j
HasNoLeader(i)    == connectInfo[i].sid = NullPoint
HasLeader(i)      == connectInfo[i].sid /= NullPoint
MyVote(i)         == currentVote[i].proposedLeader 

\* Check if s is a quorum
IsQuorum(s) == s \in Quorums

HasPartitioned(i, j) == /\ partition[i][j] = TRUE 
                        /\ partition[j][i] = TRUE
-----------------------------------------------------------------------------
\* Check zxid state
ToZxid(z) == [epoch |-> z[1], counter |-> z[2]]

TxnZxidEqual(txn, z) == txn.zxid[1] = z[1] /\ txn.zxid[2] = z[2]

TxnEqual(txn1, txn2) == /\ ZxidEqual(txn1.zxid, txn2.zxid)
                        /\ txn1.value = txn2.value

EpochPrecedeInTxn(txn1, txn2) == txn1.zxid[1] < txn2.zxid[1]
-----------------------------------------------------------------------------
\* Actions about recorder
GetParameter(p) == IF p \in DOMAIN Parameters THEN Parameters[p] ELSE 0
GetRecorder(p)  == IF p \in DOMAIN recorder   THEN recorder[p]   ELSE 0

RecorderGetHelper(m) == (m :> recorder[m])
RecorderIncHelper(m) == (m :> recorder[m] + 1)

RecorderIncTimeout == RecorderIncHelper("nTimeout")
RecorderGetTimeout == RecorderGetHelper("nTimeout")
RecorderIncCrash   == RecorderIncHelper("nCrash")
RecorderGetCrash   == RecorderGetHelper("nCrash")

RecorderSetTransactionNum(pc) == ("nTransaction" :> 
                                IF pc[1] = "LeaderProcessRequest" THEN
                                    LET s == CHOOSE i \in Server: 
                                        \A j \in Server: Len(history'[i]) >= Len(history'[j])                       
                                    IN Len(history'[s])
                                ELSE recorder["nTransaction"])
RecorderSetMaxEpoch(pc)       == ("maxEpoch" :> 
                                IF pc[1] = "LeaderProcessFOLLOWERINFO" THEN
                                    LET s == CHOOSE i \in Server:
                                        \A j \in Server: acceptedEpoch'[i] >= acceptedEpoch'[j]
                                    IN acceptedEpoch'[s]
                                ELSE recorder["maxEpoch"])
RecorderSetRequests(pc)       == ("nClientRequest" :>
                                IF pc[1] = "LeaderProcessRequest" THEN
                                    recorder["nClientRequest"] + 1
                                ELSE recorder["nClientRequest"] )
RecorderSetPartition(pc)      == ("nPartition" :> 
                                IF pc[1] = "PartitionStart" THEN recorder["nPartition"] + 1
                                                            ELSE recorder["nPartition"] )  
RecorderSetPc(pc)      == ("pc" :> pc)
RecorderSetFailure(pc) == CASE pc[1] = "Timeout"         -> RecorderIncTimeout @@ RecorderGetCrash
                          []   pc[1] = "LeaderTimeout"   -> RecorderIncTimeout @@ RecorderGetCrash 
                          []   pc[1] = "FollowerTimeout" -> RecorderIncTimeout @@ RecorderGetCrash 
                          []   pc[1] = "PartitionStart"  -> IF IsLooking(pc[2]) 
                                                            THEN RecorderGetTimeout @@ RecorderGetCrash
                                                            ELSE RecorderIncTimeout @@ RecorderGetCrash
                          []   pc[1] = "NodeCrash"       -> IF IsLooking(pc[2]) 
                                                            THEN RecorderGetTimeout @@ RecorderIncCrash 
                                                            ELSE RecorderIncTimeout @@ RecorderIncCrash 
                          []   OTHER                     -> RecorderGetTimeout @@ RecorderGetCrash

UpdateRecorder(pc) == recorder' = RecorderSetPartition(pc)
                                  @@  RecorderSetFailure(pc)  @@ RecorderSetTransactionNum(pc)
                                  @@ RecorderSetMaxEpoch(pc)  @@ RecorderSetPc(pc) 
                                  @@ RecorderSetRequests(pc)  @@ recorder
UnchangeRecorder   == UNCHANGED recorder

CheckParameterHelper(n, p, Comp(_,_)) == IF p \in DOMAIN Parameters 
                                         THEN Comp(n, Parameters[p])
                                         ELSE TRUE
CheckParameterLimit(n, p) == CheckParameterHelper(n, p, LAMBDA i, j: i < j)

CheckTimeout        == CheckParameterLimit(recorder.nTimeout,     "MaxTimeoutFailures")
CheckTransactionNum == CheckParameterLimit(recorder.nTransaction, "MaxTransactionNum")
CheckEpoch          == CheckParameterLimit(recorder.maxEpoch,     "MaxEpoch")
CheckPartition      == /\ CheckTimeout
                       /\ CheckParameterLimit(recorder.nPartition,   "MaxPartitions")
CheckCrash(i)       == /\ \/ IsLooking(i)
                          \/ /\ ~IsLooking(i)
                             /\ CheckTimeout
                       /\ CheckParameterLimit(recorder.nCrash,    "MaxCrashes")

CheckStateConstraints == CheckTimeout /\ CheckTransactionNum /\ CheckEpoch
-----------------------------------------------------------------------------
\* Actions about network
PendingFOLLOWERINFO(i, j) == /\ msgs[j][i] /= << >>
                             /\ msgs[j][i][1].mtype = FOLLOWERINFO
PendingLEADERINFO(i, j)   == /\ msgs[j][i] /= << >>
                             /\ msgs[j][i][1].mtype = LEADERINFO
PendingACKEPOCH(i, j)     == /\ msgs[j][i] /= << >>
                             /\ msgs[j][i][1].mtype = ACKEPOCH
PendingNEWLEADER(i, j)    == /\ msgs[j][i] /= << >>
                             /\ msgs[j][i][1].mtype = NEWLEADER
PendingACKLD(i, j)        == /\ msgs[j][i] /= << >>
                             /\ msgs[j][i][1].mtype = ACKLD
PendingUPTODATE(i, j)     == /\ msgs[j][i] /= << >>
                             /\ msgs[j][i][1].mtype = UPTODATE
PendingPROPOSAL(i, j)     == /\ msgs[j][i] /= << >>
                             /\ msgs[j][i][1].mtype = PROPOSAL
PendingACK(i, j)          == /\ msgs[j][i] /= << >>
                             /\ msgs[j][i][1].mtype = ACK
PendingCOMMIT(i, j)       == /\ msgs[j][i] /= << >>
                             /\ msgs[j][i][1].mtype = COMMIT
\* Add a message to msgs - add a message m to msgs.
Send(i, j, m) == msgs' = [msgs EXCEPT ![i][j] = Append(msgs[i][j], m)]
SendPackets(i, j, ms) == msgs' = [msgs EXCEPT ![i][j] = msgs[i][j] \o ms ]
DiscardAndSendPackets(i, j, ms) == msgs' = [msgs EXCEPT ![j][i] = Tail(msgs[j][i]), 
                                                ![i][j] = msgs[i][j] \o ms ]
\* Remove a message from msgs - discard head of msgs.
Discard(i, j) == msgs' = IF msgs[i][j] /= << >> THEN [msgs EXCEPT ![i][j] = Tail(msgs[i][j])]
                                                ELSE msgs
\* Leader broadcasts a message(PROPOSAL/COMMIT) to all other servers in forwardingFollowers.
Broadcast(i, m) == msgs' = [msgs EXCEPT ![i] = [v \in Server |-> IF /\ v \in forwarding[i]
                                                                    /\ v /= i
                                                                 THEN Append(msgs[i][v], m)
                                                                 ELSE msgs[i][v]]]                                                           
DiscardAndBroadcast(i, j, m) ==
        msgs' = [msgs EXCEPT ![j][i] = Tail(msgs[j][i]),
                             ![i] = [v \in Server |-> IF /\ v \in forwarding[i]
                                                         /\ v /= i
                                                      THEN Append(msgs[i][v], m)
                                                      ELSE msgs[i][v]]]            
\* Leader broadcasts LEADERINFO to all other servers in connectingFollowers.
DiscardAndBroadcastLEADERINFO(i, j, m) ==
        LET new_connecting_quorum == {c \in connecting'[i]: c.connected = TRUE }
            new_sid_connecting == {c.sid: c \in new_connecting_quorum }
        IN 
        msgs' = [msgs EXCEPT ![j][i] = Tail(msgs[j][i]),
                             ![i] = [v \in Server |-> IF /\ v \in new_sid_connecting
                                                         /\ v \in learners[i] 
                                                         /\ v /= i
                                                      THEN Append(msgs[i][v], m)
                                                      ELSE msgs[i][v] ] ]
\* Leader broadcasts UPTODATE to all other servers in newLeaderProposal.
DiscardAndBroadcastUPTODATE(i, j, m) ==
        LET new_ackldRecv_quorum == {a \in ackldRecv'[i]: a.connected = TRUE }
            new_sid_ackldRecv == {a.sid: a \in new_ackldRecv_quorum}
        IN
        msgs' = [msgs EXCEPT ![j][i] = Tail(msgs[j][i]),
                             ![i] = [v \in Server |-> IF /\ v \in new_sid_ackldRecv
                                                         /\ v \in learners[i] 
                                                         /\ v /= i
                                                      THEN Append(msgs[i][v], m)
                                                      ELSE msgs[i][v] ] ]
\* Combination of Send and Discard - discard head of msgs[j][i] and add m into msgs.
Reply(i, j, m) == msgs' = [msgs EXCEPT ![j][i] = Tail(msgs[j][i]),
                                       ![i][j] = Append(msgs[i][j], m)]

\* Shuffle input buffer.
Clean(i, j) == msgs' = [msgs EXCEPT ![j][i] = << >>, ![i][j] = << >>]     
CleanInputBuffer(i) == msgs' = [s \in Server |-> [v \in Server |-> IF v = i THEN << >>
                                                                   ELSE msgs[s][v]]]  
CleanInputBufferInCluster(S) == msgs' = [s \in Server |-> 
                                            [v \in Server |-> IF v \in S THEN << >>
                                                              ELSE msgs[s][v] ] ]                      
-----------------------------------------------------------------------------
\* Define initial values for all variables 
InitServerVars == /\ InitServerVarsL
                  /\ zabState      = [s \in Server |-> ELECTION]
                  /\ acceptedEpoch = [s \in Server |-> 0]
                  /\ lastCommitted = [s \in Server |-> [ index |-> 0,
                                                         zxid  |-> <<0, 0>> ] ]
                  /\ lastSnapshot  = [s \in Server |-> [ index |-> 0,
                                                         zxid  |-> <<0, 0>> ] ]
                  /\ initialHistory = [s \in Server |-> << >>]

InitLeaderVars == /\ InitLeaderVarsL
                  /\ learners         = [s \in Server |-> {}]
                  /\ connecting       = [s \in Server |-> {}]
                  /\ electing         = [s \in Server |-> {}]
                  /\ ackldRecv        = [s \in Server |-> {}]
                  /\ forwarding       = [s \in Server |-> {}]
                  /\ tempMaxEpoch     = [s \in Server |-> 0]

InitElectionVars == InitElectionVarsL

InitFollowerVars == /\ connectInfo = [s \in Server |-> [sid |-> NullPoint,
                                                        syncMode |-> NONE,
                                                        nlRcv |-> FALSE ] ]
                    /\ packetsSync = [s \in Server |->
                                        [ notCommitted |-> << >>,
                                          committed    |-> << >> ] ]

InitVerifyVars == /\ proposalMsgsLog    = {}
                  /\ epochLeader        = [e \in 1..MAXEPOCH |-> {} ]
                  /\ violatedInvariants = [stateInconsistent    |-> FALSE,
                                           proposalInconsistent |-> FALSE,
                                           commitInconsistent   |-> FALSE,
                                           ackInconsistent      |-> FALSE,
                                           messageIllegal       |-> FALSE ]
                   
InitMsgVars == /\ msgs         = [s \in Server |-> [v \in Server |-> << >>] ]
               /\ electionMsgs = [s \in Server |-> [v \in Server |-> << >>] ]

InitEnvVars == /\ status    = [s \in Server |-> ONLINE ]
               /\ partition = [s \in Server |-> [v \in Server |-> FALSE] ]
                
InitRecorder == recorder = [nTimeout       |-> 0,
                            nTransaction   |-> 0,
                            nPartition     |-> 0,
                            maxEpoch       |-> 0,
                            nCrash         |-> 0,
                            pc             |-> <<"Init">>,
                            nClientRequest |-> 0]

Init == /\ InitServerVars
        /\ InitLeaderVars
        /\ InitElectionVars
        /\ InitFollowerVars
        /\ InitVerifyVars
        /\ InitMsgVars
        /\ InitEnvVars
        /\ InitRecorder
-----------------------------------------------------------------------------
ZabTurnToLeading(i) ==
        /\ zabState'       = [zabState   EXCEPT ![i] = DISCOVERY]
        /\ learners'       = [learners   EXCEPT ![i] = {i}]
        /\ connecting'     = [connecting EXCEPT ![i] = { [ sid       |-> i,
                                                           connected |-> TRUE ] }]
        /\ electing'       = [electing   EXCEPT ![i] = { [ sid          |-> i,
                                                           peerLastZxid |-> <<-1,-1>>,
                                                           inQuorum     |-> TRUE ] }]
        /\ ackldRecv'      = [ackldRecv  EXCEPT ![i] = { [ sid       |-> i,
                                                           connected |-> TRUE ] }]
        /\ forwarding'     = [forwarding EXCEPT ![i] = {}]
        /\ initialHistory' = [initialHistory EXCEPT ![i] = history'[i]]
        /\ tempMaxEpoch'   = [tempMaxEpoch   EXCEPT ![i] = acceptedEpoch[i] + 1]

ZabTurnToFollowing(i) ==
        /\ zabState' = [zabState EXCEPT ![i] = DISCOVERY]
        /\ initialHistory' = [initialHistory EXCEPT ![i] = history'[i]]
        /\ packetsSync' = [packetsSync EXCEPT ![i].notCommitted = << >>, 
                                              ![i].committed = << >> ]
          
(* Fast Leader Election *)
FLEReceiveNotmsg(i, j) ==
        /\ IsON(i)
        /\ ReceiveNotmsg(i, j)
        /\ UNCHANGED <<zabState, acceptedEpoch, lastCommitted, learners, connecting, 
                      initialHistory, electing, ackldRecv, forwarding, tempMaxEpoch,
                      lastSnapshot, followerVars, verifyVars, envVars, msgs>>
        /\ UpdateRecorder(<<"FLEReceiveNotmsg", i, j>>)

FLENotmsgTimeout(i) ==
        /\ IsON(i)
        /\ NotmsgTimeout(i)
        /\ UNCHANGED <<zabState, acceptedEpoch, lastCommitted, learners, connecting, 
                       initialHistory, electing, ackldRecv, forwarding, tempMaxEpoch, 
                       lastSnapshot, followerVars, verifyVars, envVars, msgs>>
        /\ UpdateRecorder(<<"FLENotmsgTimeout", i>>)

FLEHandleNotmsg(i) ==
        /\ IsON(i)
        /\ HandleNotmsg(i)
        /\ LET newState == state'[i]
           IN
           \/ /\ newState = LEADING
              /\ ZabTurnToLeading(i)
              /\ UNCHANGED packetsSync
           \/ /\ newState = FOLLOWING
              /\ ZabTurnToFollowing(i)
              /\ UNCHANGED <<learners, connecting, electing, ackldRecv, 
                            forwarding, tempMaxEpoch>>
           \/ /\ newState = LOOKING
              /\ UNCHANGED <<zabState, learners, connecting, electing, ackldRecv,
                             forwarding, tempMaxEpoch, packetsSync, initialHistory>>
        /\ UNCHANGED <<lastCommitted, lastSnapshot, acceptedEpoch,
                       connectInfo, verifyVars, envVars, msgs>>
        /\ UpdateRecorder(<<"FLEHandleNotmsg", i>>)

\* On the premise that ReceiveVotes.HasQuorums = TRUE, 
\* corresponding to logic in FastLeaderElection.
FLEWaitNewNotmsg(i) ==
        /\ IsON(i)
        /\ WaitNewNotmsg(i)
        /\ LET newState == state'[i]
           IN
           \/ /\ newState = LEADING
              /\ ZabTurnToLeading(i)
              /\ UNCHANGED packetsSync
           \/ /\ newState = FOLLOWING
              /\ ZabTurnToFollowing(i)
              /\ UNCHANGED <<learners, connecting, electing, ackldRecv, forwarding,
                             tempMaxEpoch>>
           \/ /\ newState = LOOKING
              /\ PrintT("Note: New state is LOOKING in FLEWaitNewNotmsgEnd," \o 
                    " which should not happen.")
              /\ UNCHANGED <<zabState, learners, connecting, electing, ackldRecv,
                             forwarding, tempMaxEpoch, initialHistory, packetsSync>>
        /\ UNCHANGED <<lastCommitted, lastSnapshot, acceptedEpoch,
                       connectInfo, verifyVars, envVars, msgs>>
        /\ UpdateRecorder(<<"FLEWaitNewNotmsg", i>>)
-----------------------------------------------------------------------------
InitialVotes == [ vote    |-> InitialVote,
                  round   |-> 0,
                  state   |-> LOOKING,
                  version |-> 0 ]

InitialConnectInfo == [sid        |-> NullPoint,
                       syncMode   |-> NONE,
                       nlRcv      |-> FALSE ]

\* Equals to for every server in S, performing action ZabTimeout.
ZabTimeoutInCluster(S) ==
        /\ state' = [s \in Server |-> IF s \in S THEN LOOKING ELSE state[s] ]
        /\ lastProcessed' = [s \in Server |-> IF s \in S THEN InitLastProcessed(s)
                                                         ELSE lastProcessed[s] ]
        /\ logicalClock' = [s \in Server |-> IF s \in S THEN logicalClock[s] + 1 
                                                        ELSE logicalClock[s] ]
        /\ currentVote' = [s \in Server |-> IF s \in S THEN
                                                       [proposedLeader |-> s,
                                                        proposedZxid   |-> lastProcessed'[s].zxid,
                                                        proposedEpoch  |-> currentEpoch[s] ]
                                                       ELSE currentVote[s] ]
        /\ receiveVotes' = [s \in Server |-> IF s \in S THEN [v \in Server |-> InitialVotes]
                                                        ELSE receiveVotes[s] ]
        /\ outOfElection' = [s \in Server |-> IF s \in S THEN [v \in Server |-> InitialVotes]
                                                         ELSE outOfElection[s] ]
        /\ recvQueue' = [s \in Server |-> IF s \in S THEN << [mtype |-> NONE] >> 
                                                     ELSE recvQueue[s] ]
        /\ waitNotmsg' = [s \in Server |-> IF s \in S THEN FALSE ELSE waitNotmsg[s] ]
        /\ leadingVoteSet' = [s \in Server |-> IF s \in S THEN {} ELSE leadingVoteSet[s] ]
        /\ UNCHANGED <<electionMsgs, currentEpoch, history>>
        /\ zabState' = [s \in Server |-> IF s \in S THEN ELECTION ELSE zabState[s] ]
        /\ connectInfo' = [s \in Server |-> IF s \in S THEN InitialConnectInfo
                                                       ELSE connectInfo[s] ]
        /\ CleanInputBufferInCluster(S)

(* Describe how a server transitions from LEADING/FOLLOWING to LOOKING.*)
FollowerShutdown(i) ==
        /\ ZabTimeout(i)
        /\ zabState'    = [zabState    EXCEPT ![i] = ELECTION]
        /\ connectInfo' = [connectInfo EXCEPT ![i] = InitialConnectInfo]

LeaderShutdown(i) ==
        /\ LET cluster == {i} \union learners[i]
           IN ZabTimeoutInCluster(cluster)
        /\ learners'   = [learners   EXCEPT ![i] = {}]
        /\ forwarding' = [forwarding EXCEPT ![i] = {}]

RemoveElecting(set, sid) ==
        LET sid_electing == {s.sid: s \in set }
        IN IF sid \notin sid_electing THEN set
           ELSE LET info == CHOOSE s \in set: s.sid = sid
                    new_info == [ sid          |-> sid,
                                  peerLastZxid |-> <<-1, -1>>,
                                  inQuorum     |-> info.inQuorum ]
                IN (set \ {info}) \union {new_info}
RemoveConnectingOrAckldRecv(set, sid) ==
        LET sid_set == {s.sid: s \in set}
        IN IF sid \notin sid_set THEN set
           ELSE LET info == CHOOSE s \in set: s.sid = sid
                    new_info == [ sid       |-> sid,
                                  connected |-> FALSE ]
                IN (set \ {info}) \union {new_info}

\* See removeLearnerHandler for details.
RemoveLearner(i, j) ==
        /\ learners'   = [learners   EXCEPT ![i] = @ \ {j}] 
        /\ forwarding' = [forwarding EXCEPT ![i] = IF j \in forwarding[i] 
                                                   THEN @ \ {j} ELSE @ ]
        /\ electing'   = [electing   EXCEPT ![i] = RemoveElecting(@, j) ]
        /\ connecting' = [connecting EXCEPT ![i] = RemoveConnectingOrAckldRecv(@, j) ]
        /\ ackldRecv'  = [ackldRecv  EXCEPT ![i] = RemoveConnectingOrAckldRecv(@, j) ]
-----------------------------------------------------------------------------
\* Actions of situation error. 
PartitionStart(i, j) ==
        /\ CheckPartition \* test restrictions of partition
        /\ i /= j
        /\ IsON(i)
        /\ IsON(j)
        /\ \lnot HasPartitioned(i, j)
        /\ \/ /\ IsLeader(i)   /\ IsMyLearner(i, j)
              /\ IsFollower(j) /\ IsMyLeader(j, i)
              /\ LET newLearners == learners[i] \ {j}
                 IN \/ /\ IsQuorum(newLearners)   \* just remove this learner
                       /\ RemoveLearner(i, j)
                       /\ FollowerShutdown(j)
                       /\ Clean(i ,j)
                    \/ /\ ~IsQuorum(newLearners)  \* leader switches to looking
                       /\ LeaderShutdown(i)
                       /\ UNCHANGED <<connecting, electing, ackldRecv>>
           \/ /\ IsLooking(i)
              /\ IsLooking(j)
              /\ IdCompare(i, j)
              /\ UNCHANGED <<varsL, zabState, connectInfo, msgs, learners,
                             forwarding, connecting, electing, ackldRecv>>
        /\ partition' = [partition EXCEPT ![i][j] = TRUE, ![j][i] = TRUE ]
        /\ UNCHANGED <<acceptedEpoch, lastCommitted, lastSnapshot, tempMaxEpoch,
                       initialHistory, verifyVars, packetsSync, status>>
        /\ UpdateRecorder(<<"PartitionStart", i, j>>)

PartitionRecover(i, j) ==
        /\ IsON(i)
        /\ IsON(j)
        /\ IdCompare(i, j)
        /\ HasPartitioned(i, j)
        /\ partition' = [partition EXCEPT ![i][j] = FALSE, ![j][i] = FALSE ]
        /\ UNCHANGED <<serverVars, leaderVars, electionVars, followerVars,
                       verifyVars, msgVars, status>>
        /\ UpdateRecorder(<<"PartitionRecover", i, j>>)

NodeCrash(i) ==
        /\ CheckCrash(i)
        /\ IsON(i)
        /\ status' = [status EXCEPT ![i] = OFFLINE ]
        /\ \/ /\ IsLooking(i)
              /\ UNCHANGED <<varsL, zabState, connectInfo, msgs, learners,
                             forwarding, connecting, electing, ackldRecv>>
           \/ /\ IsFollower(i)
              /\ LET connectedWithLeader == HasLeader(i)
                 IN \/ /\ connectedWithLeader
                       /\ LET leader == connectInfo[i].sid
                              newCluster == learners[leader] \ {i}
                          IN 
                          \/ /\ IsQuorum(newCluster)
                             /\ RemoveLearner(leader, i) 
                             /\ FollowerShutdown(i)
                             /\ Clean(leader, i)
                          \/ /\ ~IsQuorum(newCluster)
                             /\ LeaderShutdown(leader)
                             /\ UNCHANGED <<electing, connecting, ackldRecv>>
                    \/ /\ ~connectedWithLeader
                       /\ FollowerShutdown(i)
                       /\ CleanInputBuffer({i})
                       /\ UNCHANGED <<learners, forwarding, connecting, electing, ackldRecv>>
           \/ /\ IsLeader(i)
              /\ LeaderShutdown(i)
              /\ UNCHANGED <<electing, connecting, ackldRecv>>
        /\ UNCHANGED <<acceptedEpoch, lastCommitted, lastSnapshot, tempMaxEpoch,
                       initialHistory, verifyVars, packetsSync, partition>>
        /\ UpdateRecorder(<<"NodeCrash", i>>)

NodeStart(i) ==
        /\ IsOFF(i)
        /\ status' = [status EXCEPT ![i] = ONLINE ]
        /\ lastProcessed' = [lastProcessed  EXCEPT ![i] = InitLastProcessed(i)]
        /\ lastCommitted' = [lastCommitted  EXCEPT ![i] = lastSnapshot[i]]
        /\ UNCHANGED <<state, currentEpoch, zabState, acceptedEpoch, history, 
                       lastSnapshot, initialHistory, leaderVars, electionVars, 
                       followerVars, verifyVars, msgVars, partition>>
        /\ UpdateRecorder(<<"NodeStart", i>>)
-----------------------------------------------------------------------------
(* Establish connection between leader and follower, containing actions like 
   addLearnerHandler, findLeader, connectToLeader.*)
ConnectAndFollowerSendFOLLOWERINFO(i, j) ==
        /\ IsON(i)     /\ IsON(j)
        /\ IsLeader(i) /\ \lnot IsMyLearner(i, j)
        /\ IsFollower(j) /\ HasNoLeader(j) /\ MyVote(j) = i
        /\ learners'   = [learners   EXCEPT ![i] = learners[i] \union {j}] 
        /\ connectInfo' = [connectInfo EXCEPT ![j].sid = i]
        /\ Send(j, i, [ mtype |-> FOLLOWERINFO,
                        mzxid |-> <<acceptedEpoch[j], 0>> ])  
        /\ UNCHANGED <<serverVars, electionVars, leadingVoteSet, connecting, 
                       electing, ackldRecv, forwarding, tempMaxEpoch,
                       verifyVars, envVars, electionMsgs, packetsSync>>
        /\ UpdateRecorder(<<"ConnectAndFollowerSendFOLLOWERINFO", i, j>>)

\* waitingForNewEpoch in Leader
WaitingForNewEpoch(i, set) == (i \in set /\ IsQuorum(set)) = FALSE
WaitingForNewEpochTurnToFalse(i, set) == /\ i \in set
                                         /\ IsQuorum(set) 

\* There may exists some follower in connecting but shuts down and
\* connects again. So when leader broadcasts LEADERINFO, the
\* follower will receive LEADERINFO, and receive it again after
\* sending FOLLOWERINFO. So connected makes sure each follower
\* will only receive LEADERINFO at most once before timeout. 
UpdateConnectingOrAckldRecv(oldSet, sid) ==
        LET sid_set == {s.sid: s \in oldSet}
        IN IF sid \in sid_set
           THEN LET old_info == CHOOSE info \in oldSet: info.sid = sid
                    follower_info == [ sid       |-> sid,
                                       connected |-> TRUE ]
                IN (oldSet \ {old_info} ) \union {follower_info}
           ELSE LET follower_info == [ sid       |-> sid,
                                       connected |-> TRUE ]
                IN oldSet \union {follower_info}

(* Leader waits for receiving FOLLOWERINFO from a quorum including itself,
   and chooses a new epoch e' as its own epoch and broadcasts LEADERINFO.
   See getEpochToPropose in Leader for details. *)
LeaderProcessFOLLOWERINFO(i, j) ==
        /\ CheckEpoch  \* test restrictions of max epoch
        /\ IsON(i)
        /\ IsLeader(i)
        /\ PendingFOLLOWERINFO(i, j)
        /\ LET msg == msgs[j][i][1]
               infoOk == IsMyLearner(i, j)
               lastAcceptedEpoch == msg.mzxid[1]
               sid_connecting == {c.sid: c \in connecting[i]}
           IN 
           /\ infoOk
           /\ \/ \* 1. has not broadcast LEADERINFO 
                 /\ WaitingForNewEpoch(i, sid_connecting)
                 /\ \/ /\ zabState[i] = DISCOVERY
                       /\ UNCHANGED violatedInvariants
                    \/ /\ zabState[i] /= DISCOVERY
                       /\ PrintT("Exception: waitingFotNewEpoch true," \o
                          " while zabState not DISCOVERY.")
                       /\ violatedInvariants' = [violatedInvariants EXCEPT !.stateInconsistent = TRUE]
                 /\ tempMaxEpoch' = [tempMaxEpoch EXCEPT ![i] = IF lastAcceptedEpoch >= tempMaxEpoch[i] 
                                                                THEN lastAcceptedEpoch + 1
                                                                ELSE @]
                 /\ connecting'   = [connecting   EXCEPT ![i] = UpdateConnectingOrAckldRecv(@, j) ]
                 /\ LET new_sid_connecting == {c.sid: c \in connecting'[i]}
                    IN
                    \/ /\ WaitingForNewEpochTurnToFalse(i, new_sid_connecting)
                       /\ acceptedEpoch' = [acceptedEpoch EXCEPT ![i] = tempMaxEpoch'[i]]
                       /\ LET newLeaderZxid == <<acceptedEpoch'[i], 0>>
                              m == [ mtype |-> LEADERINFO,
                                     mzxid |-> newLeaderZxid ]
                          IN DiscardAndBroadcastLEADERINFO(i, j, m)
                    \/ /\ ~WaitingForNewEpochTurnToFalse(i, new_sid_connecting)
                       /\ Discard(j, i)
                       /\ UNCHANGED acceptedEpoch
              \/  \* 2. has broadcast LEADERINFO 
                 /\ ~WaitingForNewEpoch(i, sid_connecting)
                 /\ Reply(i, j, [ mtype |-> LEADERINFO,
                                  mzxid |-> <<acceptedEpoch[i], 0>> ] )
                 /\ UNCHANGED <<tempMaxEpoch, connecting, acceptedEpoch, violatedInvariants>>
        /\ UNCHANGED <<state, currentEpoch, lastProcessed, zabState, history, lastCommitted, 
                       followerVars, electionVars, initialHistory, leadingVoteSet, learners, 
                       electing, ackldRecv, forwarding, proposalMsgsLog, epochLeader, 
                       lastSnapshot, electionMsgs, envVars>>
        /\ UpdateRecorder(<<"LeaderProcessFOLLOWERINFO", i, j>>)
        
(* Follower receives LEADERINFO. If newEpoch >= acceptedEpoch, then follower 
   updates acceptedEpoch and sends ACKEPOCH back, containing currentEpoch and
   lastProcessedZxid. After this, zabState turns to SYNC. 
   See registerWithLeader in Learner for details.*)
FollowerProcessLEADERINFO(i, j) ==
        /\ IsON(i)
        /\ IsFollower(i)
        /\ PendingLEADERINFO(i, j)
        /\ LET msg      == msgs[j][i][1]
               newEpoch == msg.mzxid[1]
               infoOk   == IsMyLeader(i, j)
               epochOk  == newEpoch >= acceptedEpoch[i]
               stateOk  == zabState[i] = DISCOVERY
           IN /\ infoOk
              /\ \/ \* 1. Normal case
                    /\ epochOk   
                    /\ \/ /\ stateOk
                          /\ \/ /\ newEpoch > acceptedEpoch[i]
                                /\ acceptedEpoch' = [acceptedEpoch EXCEPT ![i] = newEpoch]
                                /\ LET epochBytes == currentEpoch[i]
                                       m == [ mtype  |-> ACKEPOCH,
                                              mzxid  |-> lastProcessed[i].zxid, 
                                              mepoch |-> epochBytes ] 
                                   IN Reply(i, j, m)
                             \/ /\ newEpoch = acceptedEpoch[i]
                                /\ LET m == [ mtype  |-> ACKEPOCH,
                                              mzxid  |-> lastProcessed[i].zxid,
                                              mepoch |-> -1 ]
                                   IN Reply(i, j, m)
                                /\ UNCHANGED acceptedEpoch
                          /\ zabState' = [zabState EXCEPT ![i] = SYNCHRONIZATION]
                          /\ UNCHANGED violatedInvariants
                       \/ /\ ~stateOk
                          /\ PrintT("Exception: Follower receives LEADERINFO," \o
                             " whileZabState not DISCOVERY.")
                          /\ violatedInvariants' = [violatedInvariants EXCEPT !.stateInconsistent = TRUE]
                          /\ Discard(j, i)
                          /\ UNCHANGED <<acceptedEpoch, zabState>>
                    /\ UNCHANGED <<varsL, connectInfo, learners, forwarding, electing,
                                   connecting, ackldRecv>>
                 \/ \* 2. Abnormal case - go back to election
                    /\ ~epochOk 
                    /\ FollowerShutdown(i)
                    /\ Clean(i, connectInfo[i].sid)
                    /\ RemoveLearner(connectInfo[i].sid, i)
                    /\ UNCHANGED <<acceptedEpoch, violatedInvariants>>
        /\ UNCHANGED <<history, lastCommitted, tempMaxEpoch, initialHistory, lastSnapshot,
                       proposalMsgsLog, epochLeader, packetsSync, envVars>>
        /\ UpdateRecorder(<<"FollowerProcessLEADERINFO", i, j>>)
-----------------------------------------------------------------------------    
RECURSIVE UpdateAckSidHelper(_,_,_,_)
UpdateAckSidHelper(his, cur, end, target) ==
        IF cur > end THEN his
        ELSE LET curTxn == [ zxid   |-> his[1].zxid,
                             value  |-> his[1].value,
                             ackSid |-> IF target \in his[1].ackSid THEN his[1].ackSid
                                        ELSE his[1].ackSid \union {target},
                             epoch  |-> his[1].epoch ]
             IN <<curTxn>> \o UpdateAckSidHelper(Tail(his), cur + 1, end, target)

\* There originally existed one bug in LeaderProcessACK when 
\* monotonicallyInc = FALSE, and it is we did not add ackSid of 
\* history in SYNC. So we update ackSid in syncFollower.
UpdateAckSid(his, lastSeenIndex, target) ==
        IF Len(his) = 0 \/ lastSeenIndex = 0 THEN his
        ELSE UpdateAckSidHelper(his, 1, Minimum( { Len(his), lastSeenIndex} ), target)

\* return -1: this zxid appears at least twice; Len(his) + 1: does not exist;
\* 1 ~ Len(his): exists and appears just once.
RECURSIVE ZxidToIndexHepler(_,_,_,_)
ZxidToIndexHepler(his, zxid, cur, appeared) == 
        IF cur > Len(his) THEN cur  
        ELSE IF TxnZxidEqual(his[cur], zxid) 
             THEN CASE appeared = TRUE -> -1
                  []   OTHER           -> Minimum( { cur, 
                            ZxidToIndexHepler(his, zxid, cur + 1, TRUE) } ) 
             ELSE ZxidToIndexHepler(his, zxid, cur + 1, appeared)

ZxidToIndex(his, zxid) == IF ZxidEqual( zxid, <<0, 0>> ) THEN 0
                          ELSE IF Len(his) = 0 THEN 1
                               ELSE LET len == Len(his) IN
                                    IF \E idx \in 1..len: TxnZxidEqual(his[idx], zxid)
                                    THEN ZxidToIndexHepler(his, zxid, 1, FALSE)
                                    ELSE len + 1

\* Find index idx which meets: 
\* history[idx].zxid <= zxid < history[idx + 1].zxid
RECURSIVE IndexOfZxidHelper(_,_,_,_)
IndexOfZxidHelper(his, zxid, cur, end) ==
        IF cur > end THEN end
        ELSE IF ZxidCompare(his[cur].zxid, zxid) THEN cur - 1
             ELSE IndexOfZxidHelper(his, zxid, cur + 1, end)

IndexOfZxid(his, zxid) == IF Len(his) = 0 THEN 0
                          ELSE LET idx == ZxidToIndex(his, zxid)
                                   len == Len(his)
                               IN 
                               IF idx <= len THEN idx
                               ELSE IndexOfZxidHelper(his, zxid, 1, len)

RECURSIVE queuePackets(_,_,_,_,_)
queuePackets(queue, his, cur, committed, end) == 
        IF cur > end THEN queue
        ELSE CASE cur > committed ->
                LET m_proposal == [ mtype |-> PROPOSAL, 
                                    mzxid |-> his[cur].zxid,
                                    mdata |-> his[cur].value ]
                IN queuePackets(Append(queue, m_proposal), his, cur + 1, committed, end)
             []   cur <= committed ->
                LET m_proposal == [ mtype |-> PROPOSAL, 
                                    mzxid |-> his[cur].zxid,
                                    mdata |-> his[cur].value ]
                    m_commit   == [ mtype |-> COMMIT,
                                    mzxid |-> his[cur].zxid ]
                    newQueue   == queue \o <<m_proposal, m_commit>>
                IN queuePackets(newQueue, his, cur + 1, committed, end)

RECURSIVE setPacketsForChecking(_,_,_,_,_,_)
setPacketsForChecking(set, src, ep, his, cur, end) ==
        IF cur > end THEN set
        ELSE LET m_proposal == [ source |-> src,
                                 epoch  |-> ep,
                                 zxid   |-> his[cur].zxid,
                                 data   |-> his[cur].value ]
             IN setPacketsForChecking((set \union {m_proposal}), src, ep, his, cur + 1, end)

\* Func lead() calls zk.loadData(), which will call takeSnapshot().
LastSnapshot(i) == IF zabState[i] = BROADCAST THEN lastSnapshot[i]
                   ELSE CASE IsLeader(i) -> 
                            LET lastIndex == Len(history[i])
                            IN IF lastIndex = 0 THEN [ index |-> 0,
                                                       zxid  |-> <<0, 0>> ]
                               ELSE [ index |-> lastIndex,
                                      zxid  |-> history[i][lastIndex].zxid ]
                        []   OTHER -> lastSnapshot[i]

\* To compress state space, 
\* 1. we merge sending SNAP and outputing snapshot buffer into sending SNAP, and
\* 2. substitute sub sequence of history for snapshot of data tree.
SerializeSnapshot(i, idx) == IF idx <= 0 THEN << >>
                             ELSE SubSeq(history[i], 1, idx)

(* See queueCommittedProposals in LearnerHandler and startForwarding in Leader
   for details. For proposals in committedLog and toBeApplied, send <PROPOSAL,
   COMMIT>. For proposals in outstandingProposals, send PROPOSAL only. *)
SendSyncMsgs(i, j, lastSeenZxid, lastSeenIndex, mode, needRemoveHead) ==
        /\ LET lastCommittedIndex == IF zabState[i] = BROADCAST 
                                     THEN lastCommitted[i].index
                                     ELSE Len(history[i])
               lastProposedIndex  == Len(history[i])
               queue_origin == IF lastSeenIndex >= lastProposedIndex 
                               THEN << >>
                               ELSE queuePackets(<< >>, history[i], 
                                    lastSeenIndex + 1, lastCommittedIndex,
                                    lastProposedIndex)
               set_forChecking == IF lastSeenIndex >= lastProposedIndex 
                                  THEN {}
                                  ELSE setPacketsForChecking( { }, i, 
                                        acceptedEpoch[i], history[i],
                                        lastSeenIndex + 1, lastProposedIndex)
               m_trunc == [ mtype |-> TRUNC, mtruncZxid |-> lastSeenZxid ]
               m_diff  == [ mtype |-> DIFF,  mzxid |-> lastSeenZxid ]
               m_snap  == [ mtype |-> SNAP,  msnapZxid |-> lastSeenZxid,
                                             msnapshot |-> SerializeSnapshot(i, lastSeenIndex) ]
               newLeaderZxid == <<acceptedEpoch[i], 0>>
               m_newleader == [ mtype |-> NEWLEADER,
                                mzxid |-> newLeaderZxid ]
               queue_toSend == CASE mode = TRUNC -> (<<m_trunc>> \o queue_origin) \o <<m_newleader>>
                               []   mode = DIFF  -> (<<m_diff>>  \o queue_origin) \o <<m_newleader>>
                               []   mode = SNAP  -> (<<m_snap>>  \o queue_origin) \o <<m_newleader>>
           IN /\ \/ /\ needRemoveHead
                    /\ DiscardAndSendPackets(i, j, queue_toSend)
                 \/ /\ ~needRemoveHead
                    /\ SendPackets(i, j, queue_toSend)
              /\ proposalMsgsLog' = proposalMsgsLog \union set_forChecking
        /\ forwarding' = [forwarding EXCEPT ![i] = @ \union {j} ]
        /\ \/ /\ mode = TRUNC \/ mode = DIFF 
              /\ history' = [history EXCEPT ![i] = UpdateAckSid(@, lastSeenIndex, j) ]
           \/ /\ mode = SNAP
              /\ UNCHANGED history \* txns before minCommitted don't need to be committed again

(* Leader syncs with follower by sending DIFF/TRUNC/SNAP/PROPOSAL/COMMIT/NEWLEADER.
   See syncFollower in LearnerHandler for details. *)
SyncFollower(i, j, peerLastZxid, needRemoveHead) ==
        LET \* IsPeerNewEpochZxid == peerLastZxid[2] = 0
            lastProcessedZxid == lastProcessed[i].zxid
            minCommittedIdx   == lastSnapshot[i].index + 1
            maxCommittedIdx   == IF zabState[i] = BROADCAST THEN lastCommitted[i].index
                                 ELSE Len(history[i])
            committedLogEmpty == minCommittedIdx > maxCommittedIdx
            minCommittedLog   == IF committedLogEmpty THEN lastProcessedZxid
                                 ELSE history[i][minCommittedIdx].zxid
            maxCommittedLog   == IF committedLogEmpty THEN lastProcessedZxid
                                 ELSE IF maxCommittedIdx = 0 THEN << 0, 0>>
                                      ELSE history[i][maxCommittedIdx].zxid

            \* Hypothesis: 1. minCommittedLog : txn with index of lastSnapshot + 1
            \*             2. maxCommittedLog : LastCommitted, to compress state space.
            \*             3. merge queueCommittedProposals,startForwarding and 
            \*                sending NEWLEADER into SendSyncMsgs.

        IN \/ \* case1. peerLastZxid = lastProcessedZxid,
              \*        sned DIFF & StartForwarding(lastProcessedZxid)
              /\ ZxidEqual(peerLastZxid, lastProcessedZxid)
              /\ SendSyncMsgs(i, j, peerLastZxid, lastProcessed[i].index, 
                                    DIFF, needRemoveHead)
           \/ /\ ~ZxidEqual(peerLastZxid, lastProcessedZxid)
              /\ \/ \* case2. peerLastZxid > maxCommittedLog,
                    \*        send TRUNC(maxCommittedLog) & StartForwarding
                    /\ ZxidCompare(peerLastZxid, maxCommittedLog)
                    /\ SendSyncMsgs(i, j, maxCommittedLog, maxCommittedIdx, 
                                          TRUNC, needRemoveHead)
                 \/ \* case3. minCommittedLog <= peerLastZxid <= maxCommittedLog
                    /\ ~ZxidCompare(peerLastZxid, maxCommittedLog)
                    /\ ~ZxidCompare(minCommittedLog, peerLastZxid)
                    /\ LET lastSeenIndex == ZxidToIndex(history[i], peerLastZxid)
                           exist == /\ lastSeenIndex >= minCommittedIdx
                                    /\ lastSeenIndex <= Len(history[i])
                           lastIndex == IF exist THEN lastSeenIndex
                                        ELSE IndexOfZxid(history[i], peerLastZxid)
                           \* Maximum zxid that < peerLastZxid
                           lastZxid  == IF exist THEN peerLastZxid
                                        ELSE IF lastIndex = 0 THEN <<0, 0>>
                                             ELSE history[i][lastIndex].zxid
                       IN 
                       \/ \* case 3.1. peerLastZxid exists in committedLog,
                          \*           DIFF + queueCommittedProposals(peerLastZxid + 1)
                          \*                + StartForwarding
                          /\ exist
                          /\ SendSyncMsgs(i, j, peerLastZxid, lastSeenIndex, 
                                                DIFF, needRemoveHead)
                       \/ \* case 3.2. peerLastZxid does not exist in committedLog,
                          \*           TRUNC(lastZxid) + queueCommittedProposals(lastZxid + 1)
                          \*                           + StartForwarding
                          /\ ~exist
                          /\ SendSyncMsgs(i, j, lastZxid, lastIndex, 
                                                TRUNC, needRemoveHead)
                 \/ \* case4. peerLastZxid < minCommittedLog,
                    \*        send SNAP(lastProcessed) + StartForwarding
                    /\ ZxidCompare(minCommittedLog, peerLastZxid)
                    /\ SendSyncMsgs(i, j, lastProcessedZxid, maxCommittedIdx,
                                          SNAP, needRemoveHead)

\* compare state summary of two servers
IsMoreRecentThan(ss1, ss2) == \/ ss1.currentEpoch > ss2.currentEpoch
                              \/ /\ ss1.currentEpoch = ss2.currentEpoch
                                 /\ ZxidCompare(ss1.lastZxid, ss2.lastZxid)

\* electionFinished in Leader
ElectionFinished(i, set) == /\ i \in set
                            /\ IsQuorum(set)

\* There may exist some follower shuts down and connects again, while
\* it has sent ACKEPOCH or updated currentEpoch last time. This means
\* sid of this follower has existed in elecingFollower but its info 
\* is old. So we need to make sure each sid in electingFollower is 
\* unique and latest(newest).
UpdateElecting(oldSet, sid, peerLastZxid, inQuorum) ==
        LET sid_electing == {s.sid: s \in oldSet }
        IN IF sid \in sid_electing 
           THEN LET old_info == CHOOSE info \in oldSet : info.sid = sid
                    follower_info == 
                             [ sid          |-> sid,
                               peerLastZxid |-> peerLastZxid,
                               inQuorum     |-> (inQuorum \/ old_info.inQuorum) ]
                IN (oldSet \ {old_info} ) \union {follower_info}
           ELSE LET follower_info == 
                             [ sid          |-> sid,
                               peerLastZxid |-> peerLastZxid,
                               inQuorum     |-> inQuorum ]
                IN oldSet \union {follower_info}

LeaderTurnToSynchronization(i) ==
        /\ currentEpoch' = [currentEpoch EXCEPT ![i] = acceptedEpoch[i]]
        /\ zabState'     = [zabState     EXCEPT ![i] = SYNCHRONIZATION]

(* Leader waits for receiving ACKEPOPCH from a quorum, and check whether it has most recent
   state summary from them. After this, leader's zabState turns to SYNCHRONIZATION.
   See waitForEpochAck in Leader for details. *)
LeaderProcessACKEPOCH(i, j) ==
        /\ IsON(i)
        /\ IsLeader(i)
        /\ PendingACKEPOCH(i, j)
        /\ LET msg == msgs[j][i][1]
               infoOk == IsMyLearner(i, j)           
               leaderStateSummary   == [ currentEpoch |-> currentEpoch[i], 
                                         lastZxid     |-> lastProcessed[i].zxid ]
               followerStateSummary == [ currentEpoch |-> msg.mepoch,  
                                         lastZxid     |-> msg.mzxid ]
               logOk == \* whether follower is no more up-to-date than leader
                        ~IsMoreRecentThan(followerStateSummary, leaderStateSummary)
               electing_quorum == {e \in electing[i]: e.inQuorum = TRUE }
               sid_electing == {s.sid: s \in electing_quorum }
           IN /\ infoOk
              /\ \/ \* electionFinished = true, jump ouf of waitForEpochAck. 
                    \* Different from code, here we still need to record info
                    \* into electing, to help us perform syncFollower afterwards.
                    \* Since electing already meets quorum, it does not break
                    \* consistency between code and spec.
                    /\ ElectionFinished(i, sid_electing)
                    /\ electing' = [electing EXCEPT ![i] = UpdateElecting(@, j, msg.mzxid, FALSE) ]
                    /\ Discard(j, i)
                    /\ UNCHANGED <<varsL, zabState, forwarding, connectInfo, 
                                   learners, epochLeader, violatedInvariants>>
                 \/ /\ ~ElectionFinished(i, sid_electing)
                    /\ \/ /\ zabState[i] = DISCOVERY
                          /\ UNCHANGED violatedInvariants
                       \/ /\ zabState[i] /= DISCOVERY
                          /\ PrintT("Exception: electionFinished false," \o
                             " while zabState not DISCOVERY.")
                          /\ violatedInvariants' = [violatedInvariants EXCEPT 
                                                    !.stateInconsistent = TRUE]
                    /\ \/ /\ followerStateSummary.currentEpoch = -1
                          /\ electing' = [electing EXCEPT ![i] = UpdateElecting(@, j, 
                                                                msg.mzxid, FALSE) ]
                          /\ Discard(j, i)
                          /\ UNCHANGED <<varsL, zabState, forwarding, connectInfo, 
                                         learners, epochLeader>>
                       \/ /\ followerStateSummary.currentEpoch > -1
                          /\ \/ \* normal follower 
                                /\ logOk
                                /\ electing' = [electing EXCEPT ![i] = 
                                            UpdateElecting(@, j, msg.mzxid, TRUE) ]
                                /\ LET new_electing_quorum == {e \in electing'[i]: e.inQuorum = TRUE }
                                       new_sid_electing == {s.sid: s \in new_electing_quorum }
                                   IN 
                                   \/ \* electionFinished = true, jump out of waitForEpochAck,
                                      \* update currentEpoch and zabState.
                                      /\ ElectionFinished(i, new_sid_electing) 
                                      /\ LeaderTurnToSynchronization(i)
                                      /\ LET newLeaderEpoch == acceptedEpoch[i]
                                         IN epochLeader' = [epochLeader EXCEPT ![newLeaderEpoch]
                                                = @ \union {i} ] \* for checking invariants
                                   \/ \* there still exists electionFinished = false.
                                      /\ ~ElectionFinished(i, new_sid_electing)
                                      /\ UNCHANGED <<currentEpoch, zabState, epochLeader>>
                                /\ Discard(j, i)
                                /\ UNCHANGED <<state, lastProcessed, electionVars, leadingVoteSet,
                                               electionMsgs, connectInfo, learners, history, forwarding>>
                             \/ \* Exists follower more recent than leader
                                /\ ~logOk 
                                /\ LeaderShutdown(i)
                                /\ UNCHANGED <<electing, epochLeader>>
        /\ UNCHANGED <<acceptedEpoch, lastCommitted, lastSnapshot, connecting, ackldRecv,
                       tempMaxEpoch, initialHistory, packetsSync, proposalMsgsLog, envVars>>
        /\ UpdateRecorder(<<"LeaderProcessACKEPOCH", i, j>>)

\* Strip syncFollower from LeaderProcessACKEPOCH.
\* Only when electionFinished = true and there exists some
\* learnerHandler has not perform syncFollower, this 
\* action will be called.
LeaderSyncFollower(i, j) ==
        /\ IsON(i)
        /\ IsLeader(i)
        /\ LET electing_quorum == {e \in electing[i]: e.inQuorum = TRUE }
               electionFinished == ElectionFinished(i, {s.sid: s \in electing_quorum } )
               toSync == {s \in electing[i] : /\ ~ZxidEqual( s.peerLastZxid, <<-1, -1>>)
                                              /\ s.sid \in learners[i] }
               canSync == toSync /= {}
           IN
           /\ electionFinished
           /\ canSync
           /\ \E s \in toSync: s.sid = j
           /\ LET chosen == CHOOSE s \in toSync: s.sid = j
                  newChosen == [ sid          |-> chosen.sid,
                                 peerLastZxid |-> <<-1, -1>>, \* <<-1,-1>> means has handled.
                                 inQuorum     |-> chosen.inQuorum ] 
              IN /\ SyncFollower(i, chosen.sid, chosen.peerLastZxid, FALSE)
                 /\ electing' = [electing EXCEPT ![i] = (@ \ {chosen}) \union {newChosen} ]
        /\ UNCHANGED <<state, currentEpoch, lastProcessed, zabState, acceptedEpoch, 
                    lastCommitted, initialHistory, electionVars, leadingVoteSet,
                    learners, connecting, ackldRecv, tempMaxEpoch, followerVars, 
                    lastSnapshot, epochLeader, violatedInvariants, electionMsgs, envVars>>
        /\ UpdateRecorder(<<"LeaderSyncFollower", i, j>>)

TruncateLog(his, index) == IF index <= 0 THEN << >>
                           ELSE SubSeq(his, 1, index)

(* Follower receives DIFF/TRUNC, and then may receives PROPOSAL,COMMIT,NEWLEADER,
   and UPTODATE. See syncWithLeader in Learner for details. *)
FollowerProcessSyncMessage(i, j) ==
        /\ IsON(i)
        /\ IsFollower(i)
        /\ msgs[j][i] /= << >>
        /\ \/ msgs[j][i][1].mtype = DIFF 
           \/ msgs[j][i][1].mtype = TRUNC
           \/ msgs[j][i][1].mtype = SNAP
        /\ LET msg == msgs[j][i][1]
               infoOk == IsMyLeader(i, j)
               stateOk == zabState[i] = SYNCHRONIZATION
           IN /\ infoOk
              /\ \/ \* Follower should receive packets in SYNC.
                    /\ ~stateOk
                    /\ PrintT("Exception: Follower receives DIFF/TRUNC/SNAP," \o
                             " whileZabState not SYNCHRONIZATION.")
                    /\ violatedInvariants' = [violatedInvariants EXCEPT !.stateInconsistent = TRUE]
                    /\ UNCHANGED <<history, initialHistory, lastProcessed, lastCommitted, connectInfo>>
                 \/ /\ stateOk
                    /\ \/ /\ msg.mtype = DIFF
                          /\ connectInfo' = [connectInfo EXCEPT ![i].syncMode = DIFF]         
                          /\ UNCHANGED <<history, initialHistory, lastProcessed, lastCommitted,
                                    violatedInvariants>>
                       \/ /\ msg.mtype = TRUNC
                          /\ connectInfo' = [connectInfo EXCEPT ![i].syncMode = TRUNC]
                          /\ LET truncZxid  == msg.mtruncZxid
                                 truncIndex == ZxidToIndex(history[i], truncZxid)
                                 truncOk    == /\ truncIndex >= lastCommitted[i].index
                                               /\ truncIndex <= Len(history[i])
                             IN
                             \/ /\ ~truncOk
                                /\ PrintT("Exception: TRUNC error.")
                                /\ violatedInvariants' = [violatedInvariants EXCEPT 
                                                !.proposalInconsistent = TRUE]
                                /\ UNCHANGED <<history, initialHistory, lastProcessed, lastCommitted>>
                             \/ /\ truncOk
                                /\ history' = [history EXCEPT 
                                                    ![i] = TruncateLog(history[i], truncIndex)]
                                /\ initialHistory' = [initialHistory EXCEPT ![i] = history'[i]]
                                /\ lastProcessed' = [lastProcessed EXCEPT 
                                                    ![i] = [ index |-> truncIndex,
                                                             zxid  |-> truncZxid] ]
                                /\ lastCommitted' = [lastCommitted EXCEPT 
                                                    ![i] = [ index |-> truncIndex,
                                                             zxid  |-> truncZxid] ]
                                /\ UNCHANGED violatedInvariants
                       \/ /\ msg.mtype = SNAP
                          /\ connectInfo' = [connectInfo EXCEPT ![i].syncMode = SNAP]
                          /\ history' = [history EXCEPT ![i] = msg.msnapshot]
                          /\ initialHistory' = [initialHistory EXCEPT ![i] = history'[i]]
                          /\ lastProcessed' = [lastProcessed EXCEPT 
                                                    ![i] = [ index |-> Len(history'[i]),
                                                             zxid  |-> msg.msnapZxid] ]
                          /\ lastCommitted' = [lastCommitted EXCEPT 
                                                    ![i] = [ index |-> Len(history'[i]),
                                                             zxid  |-> msg.msnapZxid] ]
                          /\ UNCHANGED violatedInvariants
        /\ Discard(j, i)
        /\ UNCHANGED <<state, currentEpoch, zabState, acceptedEpoch, electionVars,
                       leaderVars, tempMaxEpoch, packetsSync, lastSnapshot,
                       proposalMsgsLog, epochLeader, electionMsgs, envVars>>
        /\ UpdateRecorder(<<"FollowerProcessSyncMessage", i, j>>)

\* See variable snapshotNeeded in Learner for details.
SnapshotNeeded(i) == \/ connectInfo[i].syncMode = TRUNC
                     \/ connectInfo[i].syncMode = SNAP

\* See variable writeToTxnLog in Learner for details.
WriteToTxnLog(i) == IF \/ connectInfo[i].syncMode = DIFF
                       \/ connectInfo[i].nlRcv = TRUE
                    THEN TRUE ELSE FALSE

\* See lastProposed in Leader for details.
LastProposed(i) == IF Len(history[i]) = 0 THEN [ index |-> 0, 
                                                 zxid  |-> <<0, 0>> ]
                   ELSE
                   LET lastIndex == Len(history[i])
                       entry     == history[i][lastIndex]
                   IN [ index |-> lastIndex,
                        zxid  |-> entry.zxid ]

\* See lastQueued in Learner for details.
LastQueued(i) == IF ~IsFollower(i) \/ zabState[i] /= SYNCHRONIZATION 
                 THEN LastProposed(i)
                 ELSE \* condition: IsFollower(i) /\ zabState = SYNCHRONIZATION
                      LET packetsInSync == packetsSync[i].notCommitted
                          lenSync  == Len(packetsInSync)
                          totalLen == Len(history[i]) + lenSync
                      IN IF lenSync = 0 THEN LastProposed(i)
                         ELSE [ index |-> totalLen,
                                zxid  |-> packetsInSync[lenSync].zxid ]

IsNextZxid(curZxid, nextZxid) ==
            \/ \* first PROPOSAL in this epoch
               /\ nextZxid[2] = 1
               /\ curZxid[1] < nextZxid[1]
            \/ \* not first PROPOSAL in this epoch
               /\ nextZxid[2] > 1
               /\ curZxid[1] = nextZxid[1]
               /\ curZxid[2] + 1 = nextZxid[2]

FollowerProcessPROPOSALInSync(i, j) ==
        /\ IsON(i)
        /\ IsFollower(i)
        /\ PendingPROPOSAL(i, j)
        /\ zabState[i] = SYNCHRONIZATION
        /\ LET msg == msgs[j][i][1]
               infoOk == IsMyLeader(i, j)
               isNext == IsNextZxid(LastQueued(i).zxid, msg.mzxid)
               newTxn == [ zxid   |-> msg.mzxid,
                           value  |-> msg.mdata,
                           ackSid |-> {},    \* follower do not consider ackSid
                           epoch  |-> acceptedEpoch[i] ] \* epoch of this round
           IN /\ infoOk
              /\ \/ /\ isNext
                    /\ packetsSync' = [packetsSync EXCEPT ![i].notCommitted 
                                = Append(packetsSync[i].notCommitted, newTxn) ]
                 \/ /\ ~isNext
                    /\ PrintT("Warn: Follower receives PROPOSAL," \o
                        " while zxid != lastQueued + 1.")
                    /\ UNCHANGED packetsSync
        \* logRequest -> SyncRequestProcessor -> SendAckRequestProcessor -> reply ack
        \* So here we do not need to send ack to leader.
        /\ Discard(j, i)
        /\ UNCHANGED <<serverVars, electionVars, leaderVars, connectInfo,
                       verifyVars, electionMsgs, envVars>>
        /\ UpdateRecorder(<<"FollowerProcessPROPOSALInSync", i, j>>)

RECURSIVE IndexOfFirstTxnWithEpoch(_,_,_,_)
IndexOfFirstTxnWithEpoch(his, epoch, cur, end) == 
            IF cur > end THEN cur 
            ELSE IF his[cur].epoch = epoch THEN cur
                 ELSE IndexOfFirstTxnWithEpoch(his, epoch, cur + 1, end)

LastCommitted(i) == IF zabState[i] = BROADCAST THEN lastCommitted[i]
                    ELSE CASE IsLeader(i)   -> 
                            LET lastInitialIndex == Len(initialHistory[i])
                            IN IF lastInitialIndex = 0 THEN [ index |-> 0,
                                                              zxid  |-> <<0, 0>> ]
                               ELSE [ index |-> lastInitialIndex,
                                      zxid  |-> history[i][lastInitialIndex].zxid ]
                         []   IsFollower(i) ->
                            LET completeHis == history[i] \o packetsSync[i].notCommitted
                                packetsCommitted == packetsSync[i].committed
                                lenCommitted == Len(packetsCommitted)
                            IN IF lenCommitted = 0 \* return last one in history
                               THEN LET lastIndex == Len(history[i])
                                        lastInitialIndex == Len(initialHistory[i])
                                    IN IF lastIndex = lastInitialIndex
                                       THEN IF lastIndex = 0
                                            THEN [ index |-> 0,
                                                   zxid  |-> <<0, 0>> ]
                                            ELSE [ index |-> lastIndex ,
                                                   zxid  |-> history[lastIndex].zxid ]
                                       ELSE IF lastInitialIndex < lastCommitted[i].index
                                            THEN lastCommitted[i]
                                            ELSE IF lastInitialIndex = 0
                                                 THEN [ index |-> 0,
                                                        zxid  |-> <<0, 0>> ]
                                                 ELSE [ index |-> lastInitialIndex,
                                                        zxid  |-> history[lastInitialIndex].zxid ]
                               ELSE                \* return tail of packetsCommitted
                                    LET committedIndex == ZxidToIndex(completeHis, 
                                                     packetsCommitted[lenCommitted] )
                                    IN [ index |-> committedIndex, 
                                         zxid  |-> packetsCommitted[lenCommitted] ]
                         []   OTHER -> lastCommitted[i]

TxnWithIndex(i, idx) == IF ~IsFollower(i) \/ zabState[i] /= SYNCHRONIZATION 
                        THEN history[i][idx]
                        ELSE LET completeHis == history[i] \o packetsSync[i].notCommitted
                             IN completeHis[idx]

(* To simplify specification, we assume snapshotNeeded = false and 
   writeToTxnLog = true. So here we just call packetsCommitted.add. *)
FollowerProcessCOMMITInSync(i, j) ==
        /\ IsON(i)
        /\ IsFollower(i)
        /\ PendingCOMMIT(i, j)
        /\ zabState[i] = SYNCHRONIZATION
        /\ LET msg == msgs[j][i][1]
               infoOk == IsMyLeader(i, j)
               committedIndex == LastCommitted(i).index + 1
               exist == /\ committedIndex <= LastQueued(i).index
                        /\ IsNextZxid(LastCommitted(i).zxid, msg.mzxid)
               match == ZxidEqual(msg.mzxid, TxnWithIndex(i, committedIndex).zxid )
           IN /\ infoOk 
              /\ \/ /\ exist
                    /\ \/ /\ match
                          /\ LET writeToTxnLog == WriteToTxnLog(i)
                             IN
                             \/ /\ ~writeToTxnLog \* zk.processTxn() & packetsNotCommitted.remove()
                                /\ LET committedTxn == packetsSync[i].notCommitted[1]
                                   IN 
                                   /\ history' = [ history EXCEPT ![i] 
                                               = Append(@, committedTxn)]
                                   /\ lastCommitted' = [ lastCommitted EXCEPT ![i]
                                                     = [index |-> Len(history'[i]),
                                                        zxid  |-> committedTxn.zxid ] ]
                                   /\ lastProcessed' = [ lastProcessed EXCEPT ![i]
                                                     = lastCommitted'[i] ]
                                   /\ packetsSync' = [ packetsSync EXCEPT ![i].notCommitted
                                                   = Tail(@) ]
                             \/ /\ writeToTxnLog  \* packetsCommitted.add()
                                /\ packetsSync' = [ packetsSync EXCEPT ![i].committed
                                                = Append(packetsSync[i].committed, msg.mzxid) ]
                                /\ UNCHANGED <<history, lastCommitted, lastProcessed>>
                          /\ UNCHANGED violatedInvariants
                       \/ /\ ~match
                          /\ PrintT("Warn: Follower receives COMMIT," \o
                               " but zxid not the next committed zxid in COMMIT.")
                          /\ violatedInvariants' = [violatedInvariants EXCEPT 
                                    !.commitInconsistent = TRUE ]
                          /\ UNCHANGED <<history, lastCommitted, lastProcessed, packetsSync>>
                 \/ /\ ~exist
                    /\ PrintT("Warn: Follower receives COMMIT," \o
                         " but no packets with its zxid exists.")
                    /\ violatedInvariants' = [violatedInvariants EXCEPT 
                                !.commitInconsistent = TRUE ]
                    /\ UNCHANGED <<history, lastCommitted, lastProcessed, packetsSync>>
        /\ Discard(j, i)
        /\ UNCHANGED <<state, currentEpoch, zabState, acceptedEpoch,
                       lastSnapshot, initialHistory, electionVars, leaderVars,
                       connectInfo, epochLeader, proposalMsgsLog, electionMsgs, envVars>>
        /\ UpdateRecorder(<<"FollowerProcessCOMMITInSync", i, j>>)

\* Assuming that everytime committing two txns, node takes snapshot.
ShouldSnapshot(i) == lastCommitted[i].index - lastSnapshot[i].index >= 2

(* There are mainly three places where calling takeSnapshot():
   1. zk.loadData() in lead() when node becomes leader;
   2. syncRequestProcessor.run() tells when to snapshot;
   3. node processing NEWLEADER in learner.syncWithLeader();
 *)
 TakeSnapshot(i) == LET snapOk == lastSnapshot[i].index <= lastCommitted[i].index
                    IN \/ /\ snapOk
                          /\ lastSnapshot' = [ lastSnapshot EXCEPT ![i] = lastCommitted[i] ]
                          /\ UNCHANGED violatedInvariants
                       \/ /\ ~snapOk
                          /\ PrintT("Exception: index of snapshot greater than" \o
                                    "index of committed.")
                          /\ violatedInvariants' = [violatedInvariants EXCEPT 
                                                    !.commitInconsistent = TRUE ]
                          /\ UNCHANGED lastSnapshot

RECURSIVE ACKInBatches(_,_)
ACKInBatches(queue, packets) ==
        IF packets = << >> THEN queue
        ELSE LET head == packets[1]
                 newPackets == Tail(packets)
                 m_ack == [ mtype |-> ACK,
                            mzxid |-> head.zxid ]
             IN ACKInBatches( Append(queue, m_ack), newPackets )

(* Update currentEpoch, and logRequest every packets in
   packetsNotCommitted and clear it. As syncProcessor will 
   be called in logRequest, we have to reply acks here. *)
FollowerProcessNEWLEADER(i, j) ==
        /\ IsON(i)
        /\ IsFollower(i)
        /\ PendingNEWLEADER(i, j)
        /\ LET msg == msgs[j][i][1]
               infoOk == IsMyLeader(i, j)
               packetsInSync == packetsSync[i].notCommitted
               m_ackld == [ mtype |-> ACKLD,
                            mzxid |-> msg.mzxid ]
               ms_ack  == ACKInBatches( << >>, packetsInSync )
               queue_toSend == <<m_ackld>> \o ms_ack \* send ACK-NEWLEADER first.
           IN /\ infoOk
              /\ currentEpoch' = [currentEpoch EXCEPT ![i] = acceptedEpoch[i] ]
              /\ history'      = [history      EXCEPT ![i] = @ \o packetsInSync ]
              /\ packetsSync'  = [packetsSync  EXCEPT ![i].notCommitted = << >> ]
              /\ connectInfo'  = [connectInfo  EXCEPT ![i].nlRcv = TRUE,
                                                      ![i].syncMode = NONE ]
              /\ \/ /\ SnapshotNeeded(i)
                    /\ TakeSnapshot(i)
                 \/ /\ ~SnapshotNeeded(i)
                    /\ UNCHANGED <<lastSnapshot, violatedInvariants>>
              /\ DiscardAndSendPackets(i, j, queue_toSend)
        /\ UNCHANGED <<state, lastProcessed, zabState, acceptedEpoch, lastCommitted, 
                       electionVars, leaderVars, initialHistory,
                       proposalMsgsLog, epochLeader, electionMsgs, envVars>>
        /\ UpdateRecorder(<<"FollowerProcessNEWLEADER", i, j>>)

\* quorumFormed in Leader
QuorumFormed(i, set) == i \in set /\ IsQuorum(set)

UpdateElectionVote(i, epoch) == UpdateProposal(i, currentVote[i].proposedLeader,
                                    currentVote[i].proposedZxid, epoch)

\* See startZkServer in Leader for details.
StartZkServer(i) ==
        LET latest == LastProposed(i)
        IN /\ lastCommitted' = [lastCommitted EXCEPT ![i] = latest]
           /\ lastProcessed' = [lastProcessed EXCEPT ![i] = latest]
           /\ lastSnapshot'  = [lastSnapshot  EXCEPT ![i] = latest]
           /\ UpdateElectionVote(i, acceptedEpoch[i])

LeaderTurnToBroadcast(i) ==
        /\ StartZkServer(i)
        /\ zabState' = [zabState EXCEPT ![i] = BROADCAST]

(* Leader waits for receiving quorum of ACK whose lower bits of zxid is 0, and
   broadcasts UPTODATE. See waitForNewLeaderAck for details.  *)
LeaderProcessACKLD(i, j) ==
        /\ IsON(i)
        /\ IsLeader(i)
        /\ PendingACKLD(i, j)
        /\ LET msg    == msgs[j][i][1]
               infoOk == IsMyLearner(i, j)
               match  == ZxidEqual(msg.mzxid, <<acceptedEpoch[i], 0>>)
               currentZxid == <<acceptedEpoch[i], 0>>
               m_uptodate == [ mtype |-> UPTODATE,
                               mzxid |-> currentZxid ] \* not important
               sid_ackldRecv == {a.sid: a \in ackldRecv[i]}
           IN /\ infoOk
              /\ \/ \* just reply UPTODATE.
                    /\ QuorumFormed(i, sid_ackldRecv)
                    /\ Reply(i, j, m_uptodate)
                    /\ UNCHANGED <<ackldRecv, zabState, lastCommitted, lastProcessed,
                                   lastSnapshot, currentVote, violatedInvariants>>
                 \/ /\ ~QuorumFormed(i, sid_ackldRecv)
                    /\ \/ /\ match
                          /\ ackldRecv' = [ackldRecv EXCEPT ![i] = UpdateConnectingOrAckldRecv(@, j) ]
                          /\ LET new_sid_ackldRecv == {a.sid: a \in ackldRecv'[i]}
                             IN
                             \/ \* jump out of waitForNewLeaderAck, and do startZkServer,
                                \* setZabState, and reply UPTODATE.
                                /\ QuorumFormed(i, new_sid_ackldRecv)
                                /\ LeaderTurnToBroadcast(i)
                                /\ DiscardAndBroadcastUPTODATE(i, j, m_uptodate)
                             \/ \* still wait in waitForNewLeaderAck.
                                /\ ~QuorumFormed(i, new_sid_ackldRecv)
                                /\ Discard(j, i)
                                /\ UNCHANGED <<zabState, lastCommitted, lastProcessed,
                                               lastSnapshot, currentVote>>
                          /\ UNCHANGED violatedInvariants
                       \/ /\ ~match
                          /\ PrintT("Exception: NEWLEADER ACK is from a different epoch. ")
                          /\ violatedInvariants' = [violatedInvariants EXCEPT 
                                        !.ackInconsistent = TRUE]
                          /\ Discard(j, i)
                          /\ UNCHANGED <<ackldRecv, zabState, lastCommitted, 
                                         lastSnapshot, lastProcessed, currentVote>>
        /\ UNCHANGED <<state, currentEpoch, acceptedEpoch, history, logicalClock, receiveVotes, 
                    outOfElection, recvQueue, waitNotmsg, leadingVoteSet, learners, connecting, 
                    electing, forwarding, tempMaxEpoch, initialHistory, followerVars, 
                    proposalMsgsLog, epochLeader, electionMsgs ,envVars>>
        /\ UpdateRecorder(<<"LeaderProcessACKLD", i, j>>)

TxnsWithPreviousEpoch(i) ==
            LET completeHis == IF ~IsFollower(i) \/ zabState[i] /= SYNCHRONIZATION 
                               THEN history[i] 
                               ELSE history[i] \o packetsSync[i].notCommitted
                end   == Len(completeHis)
                first == IndexOfFirstTxnWithEpoch(completeHis, acceptedEpoch[i], 1, end)
            IN IF first > end THEN completeHis
               ELSE SubSeq(completeHis, 1, first - 1)

TxnsRcvWithCurEpoch(i) ==
            LET completeHis == IF ~IsFollower(i) \/ zabState[i] /= SYNCHRONIZATION 
                               THEN history[i] 
                               ELSE history[i] \o packetsSync[i].notCommitted
                end   == Len(completeHis)
                first == IndexOfFirstTxnWithEpoch(completeHis, acceptedEpoch[i], 1, end)
            IN IF first > end THEN << >>
               ELSE SubSeq(completeHis, first, end) \* completeHis[first : end]

\* Txns received in current epoch but not committed.
\* See pendingTxns in FollowerZooKeeper for details.
PendingTxns(i) == IF ~IsFollower(i) \/ zabState[i] /= SYNCHRONIZATION 
                  THEN SubSeq(history[i], lastCommitted[i].index + 1, Len(history[i]))
                  ELSE LET packetsCommitted == packetsSync[i].committed
                           completeHis == history[i] \o packetsSync[i].notCommitted
                       IN IF Len(packetsCommitted) = 0 
                          THEN SubSeq(completeHis, Len(history[i]) + 1, Len(completeHis))
                          ELSE SubSeq(completeHis, LastCommitted(i).index + 1, Len(completeHis))

CommittedTxns(i) == IF ~IsFollower(i) \/ zabState[i] /= SYNCHRONIZATION 
                    THEN SubSeq(history[i], 1, lastCommitted[i].index)
                    ELSE LET packetsCommitted == packetsSync[i].committed
                             completeHis == history[i] \o packetsSync[i].notCommitted
                         IN IF Len(packetsCommitted) = 0 THEN history[i]
                            ELSE SubSeq( completeHis, 1, LastCommitted(i).index )

\* Each zxid of packetsCommitted equals to zxid of 
\* corresponding txn in txns.
RECURSIVE TxnsAndCommittedMatch(_,_)
TxnsAndCommittedMatch(txns, packetsCommitted) ==
        LET len1 == Len(txns)
            len2 == Len(packetsCommitted)
        IN IF len2 = 0 THEN TRUE 
           ELSE IF len1 < len2 THEN FALSE 
                ELSE /\ ZxidEqual(txns[len1].zxid, packetsCommitted[len2])
                     /\ TxnsAndCommittedMatch( SubSeq(txns, 1, len1 - 1), 
                                               SubSeq(packetsCommitted, 1, len2 - 1) )

FollowerLogRequestInBatches(i, leader, ms_ack, packetsNotCommitted) ==
        /\ history' = [history EXCEPT ![i] = @ \o packetsNotCommitted ]
        /\ DiscardAndSendPackets(i, leader, ms_ack)

\* Since commit will call commitProcessor.commit, which will finally 
\* update lastProcessed, we update it here atomically.
FollowerCommitInBatches(i) ==
        LET committedTxns == CommittedTxns(i)
            packetsCommitted == packetsSync[i].committed
            match == TxnsAndCommittedMatch(committedTxns, packetsCommitted)
        IN 
        \/ /\ match 
           /\ lastCommitted' = [lastCommitted EXCEPT ![i] = LastCommitted(i)]
           /\ lastProcessed' = [lastProcessed EXCEPT ![i] = lastCommitted'[i]]
           /\ UNCHANGED violatedInvariants
        \/ /\ ~match
           /\ PrintT("Warn: Committing zxid withou see txn. /" \o 
                "Committing zxid != pending txn zxid.")
           /\ violatedInvariants' = [violatedInvariants EXCEPT 
                        !.commitInconsistent = TRUE ]
           /\ UNCHANGED <<lastCommitted, lastProcessed>>

(* Follower jump out of outerLoop here, and log the stuff that came in
   between snapshot and uptodate, which means calling logRequest and 
   commit to clear packetsNotCommitted and packetsCommitted. *)
FollowerProcessUPTODATE(i, j) ==
        /\ IsON(i)
        /\ IsFollower(i)
        /\ PendingUPTODATE(i, j)
        /\ LET msg == msgs[j][i][1]
               infoOk == IsMyLeader(i, j)
               packetsNotCommitted == packetsSync[i].notCommitted
               ms_ack == ACKInBatches(<< >>, packetsNotCommitted)
           IN /\ infoOk
              \* Here we ignore ack of UPTODATE.
              /\ UpdateElectionVote(i, acceptedEpoch[i])
              /\ FollowerLogRequestInBatches(i, j, ms_ack, packetsNotCommitted)
              /\ FollowerCommitInBatches(i)
              /\ packetsSync' = [packetsSync EXCEPT ![i].notCommitted = << >>,
                                                    ![i].committed = << >> ]
              /\ zabState' = [zabState EXCEPT ![i] = BROADCAST ]
        /\ UNCHANGED <<state, currentEpoch, acceptedEpoch, logicalClock, lastSnapshot,
                receiveVotes, outOfElection, recvQueue, waitNotmsg, leaderVars, envVars,
                initialHistory, connectInfo, epochLeader, proposalMsgsLog, electionMsgs>>
        /\ UpdateRecorder(<<"FollowerProcessUPTODATE", i, j>>)
-----------------------------------------------------------------------------
IncZxid(s, zxid) == IF currentEpoch[s] = zxid[1] THEN <<zxid[1], zxid[2] + 1>>
                    ELSE <<currentEpoch[s], 1>>

(* Leader receives client propose and broadcasts PROPOSAL. See processRequest
   in ProposalRequestProcessor and propose in Leader for details. Since 
   prosalProcessor.processRequest -> syncProcessor.processRequest ->
   ackProcessor.processRequest -> leader.processAck, we initially set 
   txn.ackSid = {i}, assuming we have done leader.processAck.
   Note: In production, any server in traffic can receive requests and 
         forward it to leader if necessary. We choose to let leader be
         the sole one who can receive write requests, to simplify spec 
         and keep correctness at the same time.
*)
LeaderProcessRequest(i) ==
        /\ CheckTransactionNum \* test restrictions of transaction num
        /\ IsON(i)
        /\ IsLeader(i)
        /\ zabState[i] = BROADCAST
        /\ LET inBroadcast == {s \in forwarding[i]: zabState[s] = BROADCAST}
           IN IsQuorum(inBroadcast)
        /\ LET request_value == GetRecorder("nClientRequest") \* unique value
               newTxn == [ zxid   |-> IncZxid(i, LastProposed(i).zxid),
                           value  |-> request_value, 
                           ackSid |-> {i}, \* assume we have done leader.processAck
                           epoch  |-> acceptedEpoch[i] ]
               m_proposal == [ mtype |-> PROPOSAL,
                               mzxid |-> newTxn.zxid,
                               mdata |-> request_value ]
               m_proposal_for_checking == [ source |-> i,
                                            epoch  |-> acceptedEpoch[i],
                                            zxid   |-> newTxn.zxid,
                                            data   |-> request_value ]
           IN /\ history' = [history EXCEPT ![i] = Append(@, newTxn) ]
              /\ \/ /\ ShouldSnapshot(i)
                    /\ TakeSnapshot(i)
                 \/ /\ ~ShouldSnapshot(i)
                    /\ UNCHANGED <<lastSnapshot, violatedInvariants>>
              /\ Broadcast(i, m_proposal)
              /\ proposalMsgsLog' = proposalMsgsLog \union {m_proposal_for_checking}
        /\ UNCHANGED <<state, currentEpoch, lastProcessed, zabState, acceptedEpoch,
                       lastCommitted, electionVars, leaderVars, followerVars,
                       initialHistory, epochLeader, electionMsgs, envVars>>
        /\ UpdateRecorder(<<"LeaderProcessRequest", i>>)

(* Follower processes PROPOSAL in BROADCAST. See processPacket
   in Follower for details. *)
FollowerProcessPROPOSAL(i, j) ==
        /\ IsON(i)
        /\ IsFollower(i)
        /\ PendingPROPOSAL(i, j)
        /\ zabState[i] = BROADCAST
        /\ LET msg == msgs[j][i][1]
               infoOk == IsMyLeader(i, j)
               isNext == IsNextZxid( LastQueued(i).zxid, msg.mzxid)
               newTxn == [ zxid   |-> msg.mzxid,
                           value  |-> msg.mdata,
                           ackSid |-> {},
                           epoch  |-> acceptedEpoch[i] ]
               m_ack  == [ mtype |-> ACK,
                           mzxid |-> msg.mzxid ]
          IN /\ infoOk
             /\ \/ /\ isNext
                   /\ history' = [history EXCEPT ![i] = Append(@, newTxn)]
                   /\ \/ /\ ShouldSnapshot(i)
                         /\ TakeSnapshot(i)
                      \/ /\ ~ShouldSnapshot(i)
                         /\ UNCHANGED <<lastSnapshot, violatedInvariants>>
                   /\ Reply(i, j, m_ack)
                \/ /\ ~isNext
                   /\ PrintT("Exception: Follower receives PROPOSAL, while" \o 
                        " the transaction is not the next.")
                   /\ violatedInvariants' = [violatedInvariants EXCEPT 
                                !.proposalInconsistent = TRUE]
                   /\ UNCHANGED <<history, lastSnapshot, msgs>>
        /\ UNCHANGED <<state, currentEpoch, lastProcessed, zabState, acceptedEpoch,
                 lastCommitted, electionVars, leaderVars, followerVars, initialHistory,
                 epochLeader, proposalMsgsLog, electionMsgs, envVars>>
        /\ UpdateRecorder(<<"FollowerProcessPROPOSAL", i, j>>)

\* See outstandingProposals in Leader
OutstandingProposals(i) == IF zabState[i] /= BROADCAST THEN << >>
                           ELSE SubSeq( history[i], lastCommitted[i].index + 1,
                                 Len(history[i]) )

LastAckIndexFromFollower(i, j) == 
        LET set_index == {idx \in 1..Len(history[i]): j \in history[i][idx].ackSid }
        IN Maximum(set_index)

\* See commit in Leader for details.
LeaderCommit(s, follower, index, zxid) ==
        /\ lastCommitted' = [lastCommitted EXCEPT ![s] = [ index |-> index,
                                                           zxid  |-> zxid ] ]
        /\ LET m_commit == [ mtype |-> COMMIT,
                             mzxid |-> zxid ]
           IN DiscardAndBroadcast(s, follower, m_commit)

\* Try to commit one operation, called by LeaderProcessAck.
\* See tryToCommit in Leader for details.
\* commitProcessor.commit -> processWrite -> toBeApplied.processRequest
\* -> finalProcessor.processRequest, finally processTxn will be implemented
\* and lastProcessed will be updated. So we update it here.
LeaderTryToCommit(s, index, zxid, newTxn, follower) ==
        LET allTxnsBeforeCommitted == lastCommitted[s].index >= index - 1
                    \* Only when all proposals before zxid has been committed,
                    \* this proposal can be permitted to be committed.
            hasAllQuorums == IsQuorum(newTxn.ackSid)
                    \* In order to be committed, a proposal must be accepted
                    \* by a quorum.
            ordered == lastCommitted[s].index + 1 = index
                    \* Commit proposals in order.
        IN \/ /\ \* Current conditions do not satisfy committing the proposal.
                 \/ ~allTxnsBeforeCommitted
                 \/ ~hasAllQuorums
              /\ Discard(follower, s)
              /\ UNCHANGED <<violatedInvariants, lastCommitted, lastProcessed>>
           \/ /\ allTxnsBeforeCommitted
              /\ hasAllQuorums
              /\ \/ /\ ~ordered
                    /\ PrintT("Warn: Committing zxid " \o ToString(zxid) \o " not first.")
                    /\ violatedInvariants' = [violatedInvariants EXCEPT 
                            !.commitInconsistent = TRUE]
                 \/ /\ ordered
                    /\ UNCHANGED violatedInvariants
              /\ LeaderCommit(s, follower, index, zxid)
              /\ lastProcessed' = [lastProcessed EXCEPT ![s] = [ index |-> index,
                                                                 zxid  |-> zxid ] ]

(* Leader Keeps a count of acks for a particular proposal, and try to
   commit the proposal. See case Leader.ACK in LearnerHandler,
   processRequest in AckRequestProcessor, and processAck in Leader for
   details. *)
LeaderProcessACK(i, j) ==
        /\ IsON(i)
        /\ IsLeader(i)
        /\ PendingACK(i, j)
        /\ LET msg == msgs[j][i][1]
               infoOk == IsMyLearner(i, j)
               outstanding == LastCommitted(i).index < LastProposed(i).index
                        \* outstandingProposals not null
               hasCommitted == ~ZxidCompare( msg.mzxid, LastCommitted(i).zxid)
                        \* namely, lastCommitted >= zxid
               index == ZxidToIndex(history[i], msg.mzxid)
               exist == index >= 1 /\ index <= LastProposed(i).index
                        \* the proposal exists in history
               ackIndex == LastAckIndexFromFollower(i, j)
               monotonicallyInc == \/ ackIndex = -1
                                   \/ ackIndex + 1 = index
                        \* TCP makes everytime ackIndex should just increase by 1
           IN /\ infoOk 
              /\ \/ /\ exist
                    /\ monotonicallyInc
                    /\ LET txn == history[i][index]
                           txnAfterAddAck == [ zxid   |-> txn.zxid,
                                               value  |-> txn.value,
                                               ackSid |-> txn.ackSid \union {j} ,
                                               epoch  |-> txn.epoch ]   
                       IN \* p.addAck(sid)
                       /\ history' = [history EXCEPT ![i][index] = txnAfterAddAck ] 
                       /\ \/ /\ \* Note: outstanding is 0. 
                                \* / proposal has already been committed.
                                \/ ~outstanding
                                \/ hasCommitted
                             /\ Discard(j, i)
                             /\ UNCHANGED <<violatedInvariants, lastCommitted, lastProcessed>>
                          \/ /\ outstanding
                             /\ ~hasCommitted
                             /\ LeaderTryToCommit(i, index, msg.mzxid, txnAfterAddAck, j)
                 \/ /\ \/ ~exist
                       \/ ~monotonicallyInc
                    /\ PrintT("Exception: No such zxid. " \o 
                           " / ackIndex doesn't inc monotonically.")
                    /\ violatedInvariants' = [violatedInvariants 
                            EXCEPT !.ackInconsistent = TRUE]
                    /\ Discard(j, i)
                    /\ UNCHANGED <<history, lastCommitted, lastProcessed>>
        /\ UNCHANGED <<state, currentEpoch, zabState, acceptedEpoch, electionVars,
                    leaderVars, initialHistory, followerVars, proposalMsgsLog, epochLeader, 
                    lastSnapshot, electionMsgs, envVars>>
        /\ UpdateRecorder(<<"LeaderProcessACK", i, j>>)

(* Follower processes COMMIT in BROADCAST. See processPacket
   in Follower for details. *)
FollowerProcessCOMMIT(i, j) ==
        /\ IsON(i)
        /\ IsFollower(i)
        /\ PendingCOMMIT(i, j)
        /\ zabState[i] = BROADCAST
        /\ LET msg == msgs[j][i][1]
               infoOk == IsMyLeader(i, j)
               pendingTxns == PendingTxns(i)
               noPending == Len(pendingTxns) = 0
           IN
           /\ infoOk 
           /\ \/ /\ noPending
                 /\ PrintT("Warn: Committing zxid without seeing txn.")
                 /\ UNCHANGED <<lastCommitted, lastProcessed, violatedInvariants>>
              \/ /\ ~noPending
                 /\ LET firstElementZxid == pendingTxns[1].zxid
                        match == ZxidEqual(firstElementZxid, msg.mzxid)
                    IN 
                    \/ /\ ~match
                       /\ PrintT("Exception: Committing zxid not equals" \o
                                " next pending txn zxid.")
                       /\ violatedInvariants' = [violatedInvariants EXCEPT 
                               !.commitInconsistent = TRUE]
                       /\ UNCHANGED <<lastCommitted, lastProcessed>>
                    \/ /\ match
                       /\ lastCommitted' = [lastCommitted EXCEPT 
                                ![i] = [ index |-> lastCommitted[i].index + 1,
                                         zxid  |-> firstElementZxid ] ]
                       /\ lastProcessed' = [lastProcessed EXCEPT 
                                ![i] = [ index |-> lastCommitted[i].index + 1,
                                         zxid  |-> firstElementZxid ] ]
                       /\ UNCHANGED violatedInvariants
        /\ Discard(j, i)
        /\ UNCHANGED <<state, currentEpoch, zabState, acceptedEpoch, history,
                    electionVars, leaderVars, initialHistory, followerVars,
                    lastSnapshot, proposalMsgsLog, epochLeader, electionMsgs, envVars>>
        /\ UpdateRecorder(<<"FollowerProcessCOMMIT", i, j>>)
-----------------------------------------------------------------------------
(* Used to discard some messages which should not exist in network channel.
   This action should not be triggered. *)
FilterNonexistentMessage(i) ==
        /\ \E j \in Server \ {i}: /\ msgs[j][i] /= << >>
                                  /\ LET msg == msgs[j][i][1]
                                     IN 
                                        \/ /\ IsLeader(i)
                                           /\ LET infoOk == IsMyLearner(i, j)
                                              IN
                                              \/ msg.mtype = LEADERINFO
                                              \/ msg.mtype = NEWLEADER
                                              \/ msg.mtype = UPTODATE
                                              \/ msg.mtype = PROPOSAL
                                              \/ msg.mtype = COMMIT
                                              \/ /\ ~infoOk
                                                 /\ \/ msg.mtype = FOLLOWERINFO
                                                    \/ msg.mtype = ACKEPOCH
                                                    \/ msg.mtype = ACKLD
                                                    \/ msg.mtype = ACK
                                        \/ /\ IsFollower(i)
                                           /\ LET infoOk == IsMyLeader(i, j) 
                                              IN
                                              \/ msg.mtype = FOLLOWERINFO
                                              \/ msg.mtype = ACKEPOCH
                                              \/ msg.mtype = ACKLD
                                              \/ msg.mtype = ACK
                                              \/ /\ ~infoOk
                                                 /\ \/ msg.mtype = LEADERINFO
                                                    \/ msg.mtype = NEWLEADER
                                                    \/ msg.mtype = UPTODATE
                                                    \/ msg.mtype = PROPOSAL
                                                    \/ msg.mtype = COMMIT   
                                        \/ IsLooking(i)
                                  /\ Discard(j, i)
        /\ violatedInvariants' = [violatedInvariants EXCEPT !.messageIllegal = TRUE]
        /\ UNCHANGED <<serverVars, electionVars, leaderVars, envVars,
                       followerVars, proposalMsgsLog, epochLeader, electionMsgs>>
        /\ UnchangeRecorder        
-----------------------------------------------------------------------------
\* Defines how the variables may transition.
Next == 
        (* FLE module *)
            \/ \E i, j \in Server: FLEReceiveNotmsg(i, j)
            \/ \E i \in Server:    FLENotmsgTimeout(i)
            \/ \E i \in Server:    FLEHandleNotmsg(i)
            \/ \E i \in Server:    FLEWaitNewNotmsg(i)
        (* situation errors like failure, network partition *)
            \/ \E i, j \in Server: PartitionStart(i, j)
            \/ \E i, j \in Server: PartitionRecover(i, j)
            \/ \E i \in Server:    NodeCrash(i)
            \/ \E i \in Server:    NodeStart(i)
        (* Zab module - Discovery and Synchronization part *)
            \/ \E i, j \in Server: ConnectAndFollowerSendFOLLOWERINFO(i, j)
            \/ \E i, j \in Server: LeaderProcessFOLLOWERINFO(i, j)
            \/ \E i, j \in Server: FollowerProcessLEADERINFO(i, j)
            \/ \E i, j \in Server: LeaderProcessACKEPOCH(i, j)
            \/ \E i, j \in Server: LeaderSyncFollower(i, j)
            \/ \E i, j \in Server: FollowerProcessSyncMessage(i, j)
            \/ \E i, j \in Server: FollowerProcessPROPOSALInSync(i, j)
            \/ \E i, j \in Server: FollowerProcessCOMMITInSync(i, j)
            \/ \E i, j \in Server: FollowerProcessNEWLEADER(i, j)
            \/ \E i, j \in Server: LeaderProcessACKLD(i, j)
            \/ \E i, j \in Server: FollowerProcessUPTODATE(i, j)
        (* Zab module - Broadcast part *)
            \/ \E i \in Server:    LeaderProcessRequest(i)
            \/ \E i, j \in Server: FollowerProcessPROPOSAL(i, j)
            \/ \E i, j \in Server: LeaderProcessACK(i, j)
            \/ \E i, j \in Server: FollowerProcessCOMMIT(i, j)
        (* An action used to judge whether there are redundant messages in network *)
            \/ \E i \in Server:    FilterNonexistentMessage(i)

Spec == Init /\ [][Next]_vars
-----------------------------------------------------------------------------
\* Define safety properties of Zab 1.0 protocol.

CheckDuringAction == \A p \in DOMAIN violatedInvariants: violatedInvariants[p] = FALSE

\* There is most one established leader for a certain epoch.
Leadership1 == \A i, j \in Server:
                   /\ IsLeader(i) /\ zabState[i] \in {SYNCHRONIZATION, BROADCAST}
                   /\ IsLeader(j) /\ zabState[j] \in {SYNCHRONIZATION, BROADCAST}
                   /\ acceptedEpoch[i] = acceptedEpoch[j]
                  => i = j

Leadership2 == \A epoch \in 1..MAXEPOCH: Cardinality(epochLeader[epoch]) <= 1

\* PrefixConsistency: The prefix that have been committed 
\* in history in any process is the same.
PrefixConsistency == \A i, j \in Server:
                        LET smaller == Minimum({lastCommitted[i].index, lastCommitted[j].index})
                        IN \/ smaller = 0
                           \/ /\ smaller > 0
                              /\ \A index \in 1..smaller:
                                   TxnEqual(history[i][index], history[j][index])

\* Integrity: If some follower delivers one transaction, then some primary has broadcast it.
Integrity == \A i \in Server:
                /\ IsFollower(i)
                /\ lastCommitted[i].index > 0
                => \A idx \in 1..lastCommitted[i].index: \E proposal \in proposalMsgsLog:
                    LET txn_proposal == [ zxid  |-> proposal.zxid,
                                          value |-> proposal.data ]
                    IN  TxnEqual(history[i][idx], txn_proposal)

\* Agreement: If some follower f delivers transaction a and some follower f' delivers transaction b,
\*            then f' delivers a or f delivers b.
Agreement == \A i, j \in Server:
                /\ IsFollower(i) /\ lastCommitted[i].index > 0
                /\ IsFollower(j) /\ lastCommitted[j].index > 0
                =>
                \A idx1 \in 1..lastCommitted[i].index, idx2 \in 1..lastCommitted[j].index :
                    \/ \E idx_j \in 1..lastCommitted[j].index:
                        TxnEqual(history[j][idx_j], history[i][idx1])
                    \/ \E idx_i \in 1..lastCommitted[i].index:
                        TxnEqual(history[i][idx_i], history[j][idx2])

\* Total order: If some follower delivers a before b, then any process that delivers b
\*              must also deliver a and deliver a before b.
TotalOrder == \A i, j \in Server: 
                LET committed1 == lastCommitted[i].index 
                    committed2 == lastCommitted[j].index  
                IN committed1 >= 2 /\ committed2 >= 2
                    => \A idx_i1 \in 1..(committed1 - 1) : \A idx_i2 \in (idx_i1 + 1)..committed1 :
                    LET logOk == \E idx \in 1..committed2 :
                                     TxnEqual(history[i][idx_i2], history[j][idx])
                    IN \/ ~logOk 
                       \/ /\ logOk 
                          /\ \E idx_j2 \in 1..committed2 : 
                                /\ TxnEqual(history[i][idx_i2], history[j][idx_j2])
                                /\ \E idx_j1 \in 1..(idx_j2 - 1):
                                       TxnEqual(history[i][idx_i1], history[j][idx_j1])

\* Local primary order: If a primary broadcasts a before it broadcasts b, then a follower that
\*                      delivers b must also deliver a before b.
LocalPrimaryOrder == LET p_set(i, e) == {p \in proposalMsgsLog: /\ p.source = i 
                                                                /\ p.epoch  = e }
                         txn_set(i, e) == { [ zxid  |-> p.zxid, 
                                              value |-> p.data ] : p \in p_set(i, e) }
                     IN \A i \in Server: \A e \in 1..currentEpoch[i]:
                         \/ Cardinality(txn_set(i, e)) < 2
                         \/ /\ Cardinality(txn_set(i, e)) >= 2
                            /\ \E txn1, txn2 \in txn_set(i, e):
                             \/ TxnEqual(txn1, txn2)
                             \/ /\ ~TxnEqual(txn1, txn2)
                                /\ LET TxnPre  == IF ZxidCompare(txn1.zxid, txn2.zxid) THEN txn2 ELSE txn1
                                       TxnNext == IF ZxidCompare(txn1.zxid, txn2.zxid) THEN txn1 ELSE txn2
                                   IN \A j \in Server: /\ lastCommitted[j].index >= 2
                                                       /\ \E idx \in 1..lastCommitted[j].index: 
                                                            TxnEqual(history[j][idx], TxnNext)
                                        => \E idx2 \in 1..lastCommitted[j].index: 
                                            /\ TxnEqual(history[j][idx2], TxnNext)
                                            /\ idx2 > 1
                                            /\ \E idx1 \in 1..(idx2 - 1): 
                                                TxnEqual(history[j][idx1], TxnPre)

\* Global primary order: A follower f delivers both a with epoch e and b with epoch e', and e < e',
\*                       then f must deliver a before b.
GlobalPrimaryOrder == \A i \in Server: lastCommitted[i].index >= 2
                         => \A idx1, idx2 \in 1..lastCommitted[i].index:
                                \/ ~EpochPrecedeInTxn(history[i][idx1], history[i][idx2])
                                \/ /\ EpochPrecedeInTxn(history[i][idx1], history[i][idx2])
                                   /\ idx1 < idx2

\* Primary integrity: If primary p broadcasts a and some follower f delivers b such that b has epoch
\*                    smaller than epoch of p, then p must deliver b before it broadcasts a.
PrimaryIntegrity == \A i, j \in Server: /\ IsLeader(i)   /\ IsMyLearner(i, j)
                                        /\ IsFollower(j) /\ IsMyLeader(j, i)
                                        /\ zabState[i] = BROADCAST
                                        /\ zabState[j] = BROADCAST
                                        /\ lastCommitted[j].index >= 1
                        => \A idx_j \in 1..lastCommitted[j].index:
                                \/ history[j][idx_j].zxid[1] >= currentEpoch[i]
                                \/ /\ history[j][idx_j].zxid[1] < currentEpoch[i]
                                   /\ \E idx_i \in 1..lastCommitted[i].index: 
                                        TxnEqual(history[i][idx_i], history[j][idx_j])
=============================================================================
\* Modification History
\* Last modified Tue Jan 17 21:21:28 CST 2023 by huangbinyu
\* Last modified Mon Nov 22 22:25:23 CST 2021 by Dell
\* Created Sat Oct 23 16:05:04 CST 2021 by Dell
