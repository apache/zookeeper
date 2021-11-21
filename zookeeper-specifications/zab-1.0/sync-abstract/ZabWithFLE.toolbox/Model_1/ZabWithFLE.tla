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
\* The set of requests that can go into history
CONSTANT Value
 
\* Zab states
CONSTANTS ELECTION, DISCOVERY, SYNCHRONIZATION, BROADCAST

\* Message types
CONSTANTS FOLLOWERINFO, LEADERINFO, ACKEPOCH, NEWLEADER, ACKLD, UPTODATE, PROPOSAL, ACK, COMMIT
(* NOTE: Additional message types used for recovery in synchronization(TRUNC/DIFF/SNAP) 
         are not needed since we abstract this part.(see action RECOVERYSYNC) *)
(* NOTE: In production, there is no message type ACKLD. Server judges if counter of ACK is 0 to
         distinguish one ACK represents ACKLD or not. Here we divide ACK into ACKLD and ACK, to
         enhance readability of spec.*)
(* TODO: Consider in the future replacing the magic atomic synchronization RECOVERYSYNC
         with DIFF based message passing. *)

\* [MaxTimeoutFailures, MaxTransactionNum, MaxEpoch]
CONSTANT Parameters
(* TODO: Here we can add more constraints to decrease space.*)

MAXEPOCH == 10
-----------------------------------------------------------------------------
\* Variables that all servers need to use.
VARIABLES zabState,      \* The current phase of server,
                         \* in {ELECTION, DISCOVERY, SYNCHRONIZATION, BROADCAST}.

          acceptedEpoch, \* The epoch number of the last LEADERINFO packet accepted,
                         \* namely f.p in paper.

          history,       \* The history of servers as the sequence of transactions.

          commitIndex    \* Maximum index known to be committed.
                         \* Starts from 0, and increases monotonically before restarting.
                         \* Equals to 'lastCommitted' in code.
(* These transactions whose index \le commitIndex[i] can be applied to state machine immediately.
   So if we have a variable applyIndex, we can suppose that applyIndex[i] = commitIndex[i] when verifying properties.
   But in phase SYNC, follower will apply all queued proposals to state machine when receiving NEWLEADER.
   But follower only serves traffic after receiving UPTODATE, so sequential consistency is not violated.
   
   So when we verify properties, we still suppose applyIndex[i] = commitIndex[i], because this is an engineering detail.
*)
\* Variables only used when state = LEADING.
VARIABLES learners,   \* The set of servers which leader i think are connected wich i.

          cepochRecv, \* The set of followers who has successfully sent FOLLOWERINFO to leader.
                      \* Equals to 'connectingFollowers' in code.

          ackeRecv,   \* The set of followers who has successfully sent ACK-E to leader.
                      \* Equals to 'electingFollowers' in code.

          ackldRecv,  \* The set of followers who has successfully sent ACK-LD to leader in leader.
                      \* Equals to 'newLeaderProposal' in code.
                   
          forwarding, \* The set of servers which leader i should broadcast PROPOSAL and COMMIT to.
                      \* Equals to 'forwardingFollowers' in code.

          ackIndex,   \* [i][j]: The latest index that leader i has received from follower j via ACK.

          currentCounter  \* [i]: The count of transactions that clients have requested leader i.

          \* sendCounter,     \* [i]: The count of transactions that leader i has broadcast via PROPOSAL.
          \* committedIndex,  \* [i]: The maximum index of trasactions 
                            \* that leader i has broadcast in COMMIT.
          \* committedCounter \* [i][j]: The latest counter of transaction 
                           \* that leader i has confirmed that follower j has committed.

\* Variables only used when state = LEADING & zabState != BROADCAST.
VARIABLES initialHistory, \* [i]: The initial history of leader i in epoch acceptedEpoch[i].

          tempMaxEpoch    \* the maximum epoch in CEPOCH the prospective leader received from followers.

\* Variables only used when state = FOLLOWING.
VARIABLES cepochSent, \* Express whether follower has sent FOLLOWERINFO to leader.

          leaderAddr, \* Express whether follower i has connected or lost connection.
                      \* [i]: The leader id of follower i.
                      
          synced      \* Express whether follower has completed sync with leader.

\* Variables about network channel.
VARIABLE msgs       \* Simulates network channel.
                    \* msgs[i][j] means the input buffer of server j from server i.

\* Variables only used in verifying properties.
VARIABLES epochLeader,     \* The set of leaders in every epoch.

          proposalMsgsLog, \* The set of all broadcast messages.

          inherentViolated \* Check whether there are conditions contrary to the facts.

\* Variable used for recording data to constrain state space.
VARIABLE recorder \* Consists: members of Parameters and pc.

serverVarsZ == <<state, currentEpoch, lastZxid, zabState, acceptedEpoch, history, commitIndex>>         \* 7 variables

electionVarsZ == electionVars  \* 6 variables

leaderVarsZ == <<leadingVoteSet, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, 
                 ackIndex, currentCounter>>                          \* 8 variables

tempVarsZ == <<initialHistory, tempMaxEpoch>>                       \* 2 variables
  
followerVarsZ == <<cepochSent, leaderAddr, synced>>                 \* 3 variables

verifyVarsZ == <<proposalMsgsLog, epochLeader, inherentViolated>>   \* 3 variables

msgVarsZ == <<msgs, electionMsgs>>                                  \* 2 variables

vars == <<serverVarsZ, electionVarsZ, leaderVarsZ, tempVarsZ, followerVarsZ, verifyVarsZ, msgVarsZ, idTable, recorder>>  \* 33 variables
-----------------------------------------------------------------------------
ServersIncNullPoint == Server \union {NullPoint} 

Zxid ==
    Seq(Nat) 
    \* \union [epoch: Nat, counter: Nat]
    
HistoryItem ==
     [epoch: Nat,
      counter: Nat,
      value: Value]    
    
Proposal ==
    [msource: Server, mtype: {"RECOVERYSYNC"}, mepoch: Nat, mproposals: Seq(HistoryItem)] \union
    [msource: Server, mtype: {PROPOSAL}, mepoch: Nat, mproposal: HistoryItem]   

Message ==
    [mtype: {FOLLOWERINFO}, mepoch: Nat] \union
    [mtype: {NEWLEADER}, mepoch: Nat, mlastZxid: Zxid] \union
    [mtype: {ACKLD}, mepoch: Nat] \union
    [mtype: {LEADERINFO}, mepoch: Nat] \union
    [mtype: {ACKEPOCH}, mepoch: Nat, mlastEpoch: Nat, mlastZxid: Zxid] \union
    [mtype: {UPTODATE}, mepoch: Nat, mcommit: Nat] \union
    [mtype: {PROPOSAL}, mepoch: Nat, mproposal: HistoryItem] \union
    [mtype: {ACK}, mepoch: Nat, mzxid: Zxid] \union
    [mtype: {COMMIT}, mepoch: Nat, mzxid: Zxid]
    
ElectionState == {LOOKING, FOLLOWING, LEADING}

Vote ==
    [proposedLeader: ServersIncNullPoint,
     proposedZxid: Zxid,
     proposedEpoch: Nat]
     
ElectionVote ==
    [vote: Vote, round: Nat, state: ElectionState, version: Nat]

ElectionMsg ==
    [mtype: {NOTIFICATION}, msource: Server, mstate: ElectionState, mround: Nat, mvote: Vote] \union
    [mtype: {NONE}]
            
TypeOK ==
    /\ zabState \in [Server -> {ELECTION, DISCOVERY, SYNCHRONIZATION, BROADCAST}]
    /\ acceptedEpoch \in [Server -> Nat]
    /\ history \in [Server -> Seq(HistoryItem)] 
    /\ commitIndex \in [Server -> Nat]
    /\ learners \in [Server -> SUBSET ServersIncNullPoint]
    /\ cepochRecv \in [Server -> SUBSET ServersIncNullPoint]
    /\ ackeRecv \in [Server -> SUBSET ServersIncNullPoint]
    /\ ackldRecv \in [Server -> SUBSET ServersIncNullPoint]
    /\ ackIndex \in [Server -> [Server -> Nat]]
    /\ currentCounter \in [Server -> Nat]
    \* /\ sendCounter \in [Server -> Nat]
    \* /\ committedIndex \in [Server -> Nat]
    \* /\ committedCounter \in [Server -> [Server -> Nat]]
    /\ forwarding \in [Server -> SUBSET ServersIncNullPoint]
    /\ initialHistory \in [Server -> Seq(HistoryItem)] 
    /\ tempMaxEpoch \in [Server -> Nat]
    /\ cepochSent \in [Server -> BOOLEAN]
    /\ leaderAddr \in [Server -> ServersIncNullPoint]
    /\ synced \in [Server -> BOOLEAN]
    /\ proposalMsgsLog \in SUBSET Proposal
    /\ epochLeader \in [1..MAXEPOCH -> SUBSET ServersIncNullPoint]
    /\ inherentViolated \in BOOLEAN
    /\ forwarding \in [Server -> SUBSET Server]
    /\ msgs \in [Server -> [Server -> Seq(Message)]]
    \* Fast Leader Election
    /\ electionMsgs \in [Server -> [Server -> Seq(ElectionMsg)]]
    /\ recvQueue \in [Server -> Seq(ElectionMsg)]
    /\ leadingVoteSet \in [Server -> SUBSET Server]
    /\ receiveVotes \in [Server -> [Server -> ElectionVote]]
    /\ currentVote \in [Server -> Vote]
    /\ outOfElection \in [Server -> [Server -> ElectionVote]]
    /\ lastZxid \in [Server -> Zxid]
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
IsLeader(s)   == state[s] = LEADING
IsFollower(s) == state[s] = FOLLOWING
IsLooking(s)  == state[s] = LOOKING

IsMyLearner(i, j) == j \in learners[i]
IsMyLeader(i, j)  == leaderAddr[i] = j
HasNoLeader(i)    == leaderAddr[i] = NullPoint
HasLeader(i)      == leaderAddr[i] /= NullPoint

\* Check if s is a quorum
IsQuorum(s) == s \in Quorums

\* Check zxid state
ToZxid(z) == [epoch |-> z[1], counter |-> z[2]]

PZxidEqual(p, z) == p.epoch = z[1] /\ p.counter = z[2]

TransactionEqual(t1, t2) == /\ t1.epoch   = t2.epoch
                            /\ t1.counter = t2.counter
                            
TransactionPrecede(t1, t2) == \/ t1.epoch < t2.epoch
                              \/ /\ t1.epoch   = t2.epoch
                                 /\ t1.counter < t2.counter
-----------------------------------------------------------------------------
\* Actions about recorder
GetParameter(p) == IF p \in DOMAIN Parameters THEN Parameters[p] ELSE 0

RecorderGetHelper(m) == (m :> recorder[m])
RecorderIncHelper(m) == (m :> recorder[m] + 1)

RecorderIncTimeout == RecorderIncHelper("nTimeout")
RecorderGetTimeout == RecorderGetHelper("nTimeout")
RecorderSetTransactionNum(pc) == ("nTransaction" :> 
                                IF pc[1] = "ClientRequestAndLeaderBroadcastProposal" THEN
                                    LET s == CHOOSE i \in Server: 
                                        \A j \in Server: Len(history'[i]) >= Len(history'[j])                       
                                    IN Len(history'[s])
                                ELSE recorder["nTransaction"])
RecorderSetMaxEpoch(pc)       == ("maxEpoch" :> 
                                IF pc[1] = "LeaderBroadcastLEADERINFO" THEN
                                    LET s == CHOOSE i \in Server:
                                        \A j \in Server: acceptedEpoch'[i] >= acceptedEpoch'[j]
                                    IN acceptedEpoch'[s]
                                ELSE recorder["maxEpoch"])
RecorderSetPc(pc)      == ("pc" :> pc)
RecorderSetFailure(pc) == CASE pc[1] = "Timeout"         -> RecorderIncTimeout
                          []   pc[1] = "LeaderTimeout"   -> RecorderIncTimeout
                          []   pc[1] = "FollowerTimeout" -> RecorderIncTimeout
                          []   OTHER                     -> RecorderGetTimeout

UpdateRecorder(pc) == recorder' = RecorderSetFailure(pc)      @@ RecorderSetTransactionNum(pc)
                                  @@ RecorderSetMaxEpoch(pc)  @@ RecorderSetPc(pc) @@ recorder
UnchangeRecorder   == UNCHANGED recorder

CheckParameterHelper(n, p, Comp(_,_)) == IF p \in DOMAIN Parameters 
                                         THEN Comp(n, Parameters[p])
                                         ELSE TRUE
CheckParameterLimit(n, p) == CheckParameterHelper(n, p, LAMBDA i, j: i < j)

CheckTimeout        == CheckParameterLimit(recorder.nTimeout,     "MaxTimeoutFailures")
CheckTransactionNum == CheckParameterLimit(recorder.nTransaction, "MaxTransactionNum")
CheckEpoch          == CheckParameterLimit(recorder.maxEpoch,     "MaxEpoch")

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
BroadcastLEADERINFO(i, m) == msgs' = [msgs EXCEPT ![i] = [v \in Server |-> IF /\ v \in cepochRecv[i]
                                                                              /\ v \in learners[i]
                                                                              /\ v /= i THEN Append(msgs[i][v], m)
                                                                                        ELSE msgs[i][v]]]
\* Leader broadcasts UPTODATE to all other servers in newLeaderProposal.
BroadcastUPTODATE(i, m) == msgs' = [msgs EXCEPT ![i] = [v \in Server |-> IF /\ v \in ackldRecv[i]
                                                                            /\ v \in learners[i]
                                                                            /\ v /= i THEN Append(msgs[i][v], m)
                                                                                      ELSE msgs[i][v]]]

\* Combination of Send and Discard - discard head of msgs[j][i] and add m into msgs.
Reply(i, j, m) == msgs' = [msgs EXCEPT ![j][i] = Tail(msgs[j][i]),
                                       ![i][j] = Append(msgs[i][j], m)]

\* Shuffle the input buffer from server j(i) in server i(j).
Clean(i, j) == msgs' = [msgs EXCEPT ![j][i] = << >>, ![i][j] = << >>]                                 
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
                   /\ forwarding       = [s \in Server |-> {}]
                   \* /\ sendCounter      = [s \in Server |-> 0]
                   \* /\ committedIndex   = [s \in Server |-> 0]

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
                
InitRecorder == recorder = [nTimeout     |-> 0,
                            nTransaction |-> 0,
                            maxEpoch     |-> 0,
                            pc           |-> <<"InitZ">>]

InitZ == /\ InitServerVarsZ
         /\ InitLeaderVarsZ
         /\ InitElectionVarsZ
         /\ InitTempVarsZ
         /\ InitFollowerVarsZ
         /\ InitVerifyVarsZ
         /\ InitMsgVarsZ
         /\ idTable = InitializeIdTable(Server)
         /\ InitRecorder
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
        /\ commitIndex'      = [commitIndex      EXCEPT ![i] = 0]
        \* /\ sendCounter'      = [sendCounter      EXCEPT ![i] = 0]
        \* /\ committedIndex'   = [committedIndex   EXCEPT ![i] = 0]
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
        /\ UNCHANGED <<zabState, acceptedEpoch, history, commitIndex, learners, cepochRecv, ackeRecv, ackldRecv,
                       forwarding, ackIndex, currentCounter, tempVarsZ, followerVarsZ, verifyVarsZ, msgs>>
        /\ UpdateRecorder(<<"FLEReceiveNotmsg", i, j>>)

FLENotmsgTimeout(i) ==
        /\ NotmsgTimeout(i)
        /\ UNCHANGED <<zabState, acceptedEpoch, history, commitIndex,learners, cepochRecv, ackeRecv, ackldRecv, 
                       forwarding, ackIndex, currentCounter, tempVarsZ, followerVarsZ, verifyVarsZ, msgs>>
        /\ UpdateRecorder(<<"FLENotmsgTimeout", i>>)

FLEHandleNotmsg(i) ==
        /\ HandleNotmsg(i)
        /\ LET newState == state'[i]
           IN
           \/ /\ newState = LEADING
              /\ ZabTurnToLeading(i)
              /\ UNCHANGED <<cepochSent, synced>>
           \/ /\ newState = FOLLOWING
              /\ ZabTurnToFollowing(i)
              /\ UNCHANGED <<learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, currentCounter, tempVarsZ>>
           \/ /\ newState = LOOKING
              /\ UNCHANGED <<zabState, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, 
                             currentCounter, commitIndex, tempVarsZ, cepochSent, synced>>
        /\ UNCHANGED <<acceptedEpoch, history, leaderAddr, verifyVarsZ, msgs>>
        /\ UpdateRecorder(<<"FLEHandleNotmsg", i>>)

\* On the premise that ReceiveVotes.HasQuorums = TRUE, corresponding to logic in line 1050-1055 in LFE.java.
FLEWaitNewNotmsg(i) ==
        /\ WaitNewNotmsg(i)
        /\ UNCHANGED <<zabState, acceptedEpoch, history, commitIndex,learners, cepochRecv, ackeRecv, ackldRecv,
                       forwarding, ackIndex, currentCounter, tempVarsZ, followerVarsZ, verifyVarsZ, msgs>>
        /\ UpdateRecorder(<<"FLEWaitNewNotmsg", i>>)

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
              /\ UNCHANGED <<learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, currentCounter, tempVarsZ>>
           \/ /\ newState = LOOKING
              /\ PrintT("Note: New state is LOOKING in FLEWaitNewNotmsgEnd, which should not happen.")
              /\ UNCHANGED <<zabState, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, 
                             currentCounter, commitIndex, tempVarsZ, cepochSent, synced>>
        /\ UNCHANGED <<acceptedEpoch, history, leaderAddr, verifyVarsZ, msgs>>
        /\ UpdateRecorder(<<"FLEWaitNewNotmsgEnd", i>>)          
-----------------------------------------------------------------------------
(* Describe how a server transitions from LEADING/FOLLOWING to LOOKING.*)
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
        /\ msgs'       = [s \in Server |-> [v \in Server |-> IF v \in learners[i] \/ s \in learners[i] 
                                                             THEN << >> ELSE msgs[s][v]]]

RemoverLearner(i, j) ==
        /\ learners'   = [learners   EXCEPT ![i] = learners[i] \ {j}] 
        /\ forwarding' = [forwarding EXCEPT ![i] = IF j \in forwarding[i] THEN forwarding[i] \ {j} ELSE forwarding[i]]
        /\ cepochRecv' = [cepochRecv EXCEPT ![i] = IF j \in cepochRecv[i] THEN cepochRecv[i] \ {j} ELSE cepochRecv[i]]
        /\ ackeRecv'   = [ackeRecv   EXCEPT ![i] = IF j \in ackeRecv[i]   THEN ackeRecv[i]   \ {j} ELSE ackeRecv[i]]
        /\ ackldRecv'  = [ackldRecv  EXCEPT ![i] = IF j \in ackldRecv[i]  THEN ackldRecv[i]  \ {j} ELSE ackldRecv[i]]
        /\ ackIndex'   = [ackIndex   EXCEPT ![i][j] = 0]

FollowerTimeout(i) ==
        /\ CheckTimeout \* test restrictions of timeout 1
        /\ IsFollower(i)
        /\ HasNoLeader(i)
        /\ FollowerShutdown(i)
        /\ msgs' = [s \in Server |-> [v \in Server |-> IF v = i THEN << >> ELSE msgs[s][v]]]
        /\ UNCHANGED <<acceptedEpoch, history, commitIndex, learners, cepochRecv, ackeRecv, ackldRecv, 
                       forwarding, ackIndex, currentCounter, tempVarsZ, cepochSent, synced, verifyVarsZ>>
        /\ UpdateRecorder(<<"FollowerTimeout", i>>)
        
LeaderTimeout(i) ==
        /\ CheckTimeout \* test restrictions of timeout 2
        /\ IsLeader(i)
        /\ \lnot IsQuorum(learners[i])
        /\ LeaderShutdown(i)
        /\ UNCHANGED <<acceptedEpoch, history, commitIndex, cepochRecv, ackeRecv, ackldRecv, ackIndex,
                       currentCounter, tempVarsZ, cepochSent, synced, verifyVarsZ>>
        /\ UpdateRecorder(<<"LeaderTimeout", i>>)  
-----------------------------------------------------------------------------
(* Establish connection between leader i and follower j. 
   It means i creates a learnerHandler for communicating with j, and j finds i's address.*)
EstablishConnection(i, j) ==
        /\ IsLeader(i)   
        /\ IsFollower(j)
        /\ \lnot IsMyLearner(i, j)
        /\ HasNoLeader(i)
        /\ currentVote[j].proposedLeader = i
        /\ learners'   = [learners   EXCEPT ![i] = learners[i] \union {j}] \* Leader:   'addLearnerHandler(peer)'
        /\ leaderAddr' = [leaderAddr EXCEPT ![j] = i]                      \* Follower: 'connectToLeader(addr, hostname)'
        /\ UNCHANGED <<serverVarsZ, electionVarsZ, leadingVoteSet, cepochRecv, ackeRecv, ackldRecv, forwarding,
                       ackIndex,  currentCounter, tempVarsZ, cepochSent, synced, verifyVarsZ, msgVarsZ, idTable>>
        /\ UpdateRecorder(<<"EstablishConnection", i, j>>)
        
(* The leader i finds timeout and TCP connection between i and j closes.*)       
Timeout(i, j) ==
        /\ CheckTimeout \* test restrictions of timeout 3
        /\ IsLeader(i) 
        /\ IsFollower(j)
        /\ IsMyLearner(i, j)
        /\ IsMyLeader(j, i)
        (* The action of leader i.(corresponding to function 'removeLearnerHandler(peer)'.) *)
        /\ RemoverLearner(i, j)
        (* The action of follower j. *)
        /\ FollowerShutdown(j)
        (* Clean channel between i and j.*)
        /\ Clean(i, j)
        /\ UNCHANGED <<acceptedEpoch, history, commitIndex, currentCounter,
                       tempVarsZ, cepochSent, synced, verifyVarsZ>>
        /\ UpdateRecorder(<<"Timeout", i, j>>)
-----------------------------------------------------------------------------
\* Follower sends f.p to leader via FOLLOWERINFO(CEPOCH). 
FollowerSendFOLLOWERINFO(i) ==
        /\ IsFollower(i)
        /\ zabState[i] = DISCOVERY
        /\ HasLeader(i)
        /\ \lnot cepochSent[i]
        /\ Send(i, leaderAddr[i], [mtype  |-> FOLLOWERINFO,
                                   mepoch |-> acceptedEpoch[i]])
        /\ cepochSent' = [cepochSent EXCEPT ![i] = TRUE]
        /\ UNCHANGED <<serverVarsZ, leaderVarsZ, electionVarsZ, tempVarsZ, leaderAddr, synced, 
                       verifyVarsZ, electionMsgs, idTable>>
        /\ UpdateRecorder(<<"FollowerSendFOLLOWERINFO", i>>)
        
(* Leader waits for receiving FOLLOWERINFO from a quorum,
   and then chooses a new epoch e' as its own epoch and broadcasts LEADERINFO. *)
LeaderHandleFOLLOWERINFO(i, j) ==
        /\ IsLeader(i)
        /\ PendingFOLLOWERINFO(i, j)
        /\ LET msg == msgs[j][i][1]
           IN \/ \* 1. has not broadcast LEADERINFO - modify tempMaxEpoch
                 /\ NullPoint \notin cepochRecv[i]  
                 /\ LET newEpoch == Maximum({tempMaxEpoch[i], msg.mepoch})
                    IN tempMaxEpoch' = [tempMaxEpoch EXCEPT ![i] = newEpoch]
                 /\ Discard(j, i)
              \/  \* 2. has broadcast LEADERINFO - no need to handle the msg, just send LEADERINFO to corresponding server
                 /\ NullPoint \in cepochRecv[i]     
                 /\ Reply(i, j, [mtype  |-> LEADERINFO,
                                 mepoch |-> acceptedEpoch[i]])
                 /\ UNCHANGED tempMaxEpoch
        /\ cepochRecv' = [cepochRecv EXCEPT ![i] = IF j \in cepochRecv[i] THEN cepochRecv[i]
                                                                          ELSE cepochRecv[i] \union {j}]
        /\ UNCHANGED <<serverVarsZ, followerVarsZ, electionVarsZ, initialHistory, leadingVoteSet, learners, 
                       ackeRecv, ackldRecv, forwarding, ackIndex, currentCounter, verifyVarsZ, electionMsgs, idTable>>
        /\ UpdateRecorder(<<"LeaderHandleFOLLOWERINFO", i, j>>)
        
LeaderBroadcastLEADERINFO(i) ==
        /\ CheckEpoch  \* test restrictions of max epoch
        /\ IsLeader(i)
        /\ zabState[i] = DISCOVERY
        /\ IsQuorum(cepochRecv[i])
        /\ acceptedEpoch' = [acceptedEpoch EXCEPT ![i] = tempMaxEpoch[i] + 1]
        /\ cepochRecv'    = [cepochRecv    EXCEPT ![i] = cepochRecv[i] \union {NullPoint}]
        /\ BroadcastLEADERINFO(i, [mtype  |-> LEADERINFO,
                                   mepoch |-> acceptedEpoch'[i]])
        /\ UNCHANGED <<state, currentEpoch, lastZxid, zabState, history, commitIndex, electionVarsZ, 
                       leadingVoteSet, learners, ackeRecv, ackldRecv, forwarding, ackIndex, currentCounter,
                       tempVarsZ, followerVarsZ, verifyVarsZ, electionMsgs, idTable>>
        /\ UpdateRecorder(<<"LeaderBroadcastLEADERINFO", i>>)

(* In phase f12, follower receives NEWEPOCH. If e' > f.p, then follower sends ACK-E back,
   and ACK-E contains f.a and lastZxid to let leader judge whether it is the latest.
   After handling NEWEPOCH, follower's zabState turns to SYNCHRONIZATION. *)
FollowerHandleLEADERINFO(i, j) ==
        /\ IsFollower(i)
        /\ PendingLEADERINFO(i, j)
        /\ LET msg     == msgs[j][i][1]
               infoOk  == IsMyLeader(i, j)
               epochOk == /\ infoOk
                          /\ msg.mepoch >= acceptedEpoch[i]
               correct == /\ epochOk
                          /\ zabState[i] = DISCOVERY
           IN /\ infoOk
              /\ \/ \* 1. Normal case
                    /\ epochOk   
                    /\ \/ /\ correct
                          /\ acceptedEpoch' = [acceptedEpoch EXCEPT ![i] = msg.mepoch]
                          /\ Reply(i, j, [mtype      |-> ACKEPOCH,
                                          mepoch     |-> msg.mepoch,
                                          mlastEpoch |-> currentEpoch[i],
                                          mlastZxid  |-> lastZxid[i]])
                          /\ cepochSent' = [cepochSent EXCEPT ![i] = TRUE]
                          /\ UNCHANGED inherentViolated
                       \/ /\ ~correct
                          /\ PrintT("Exception: Follower receives LEADERINFO while its ZabState is not DISCOVERY.")
                          /\ inherentViolated' = TRUE
                          /\ Discard(j, i)
                          /\ UNCHANGED <<acceptedEpoch, cepochSent>>
                    /\ zabState' = [zabState EXCEPT ![i] = IF zabState[i] = DISCOVERY THEN SYNCHRONIZATION
                                                                                      ELSE zabState[i]]
                    /\ UNCHANGED <<varsL, leaderAddr>>
                 \/ \* 2. Abnormal case - go back to election
                    /\ ~epochOk 
                    /\ FollowerShutdown(i)
                    /\ Clean(i, j)
                    /\ UNCHANGED <<acceptedEpoch, cepochSent, inherentViolated>>
        /\ UNCHANGED <<history, commitIndex, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, ackIndex, 
                       currentCounter, tempVarsZ, synced, proposalMsgsLog, epochLeader>>
        /\ UpdateRecorder(<<"FollowerHandleLEADERINFO", i, j>>)
        
\* Abstraction of actions making follower synced with leader before leader sending NEWLEADER.
SubRECOVERYSYNC(i, j) ==
        LET canSync == /\ IsLeader(i)   /\ zabState[i] /= DISCOVERY      /\ IsMyLearner(i, j)
                       /\ IsFollower(j) /\ zabState[j] = SYNCHRONIZATION /\ IsMyLeader(j, i)   
                       /\ synced[j] = FALSE
        IN
        \/ /\ canSync
           /\ history'     = [history     EXCEPT ![j] = history[i]]
           /\ lastZxid'    = [lastZxid    EXCEPT ![j] = lastZxid[i]]
           /\ UpdateProposal(j, leaderAddr[j], lastZxid'[j], currentEpoch[j])
           /\ commitIndex' = [commitIndex EXCEPT ![j] = commitIndex[i]]
           /\ synced'      = [synced      EXCEPT ![j] = TRUE]
           /\ forwarding'  = [forwarding  EXCEPT ![i] = forwarding[i] \union {j}]    \* j will join traffic, and receive PROPOSAL and COMMIT.
           /\ ackIndex'    = [ackIndex    EXCEPT ![i][j] = Len(history[i])]
           /\ LET ms == [msource|->i, mtype|->"RECOVERYSYNC", mepoch|->acceptedEpoch[i], mproposals|->history[i]]
              IN proposalMsgsLog' = IF ms \in proposalMsgsLog THEN proposalMsgsLog
                                                              ELSE proposalMsgsLog \union {ms}
           /\ UNCHANGED inherentViolated
        \/ /\ ~canSync
           /\ PrintT("Exception: Leader wants to sync with follower while the condition doesn't allow.")
           /\ inherentViolated' = TRUE
           /\ UNCHANGED <<history, lastZxid, currentVote, commitIndex, synced, forwarding, ackIndex, proposalMsgsLog>>
        
\* Leader waits for receiving ACKEPOPCH from a quorum, and check whether it has 
\* the latest history and epoch from them. If so, leader's zabState turns to SYNCHRONIZATION. 
LeaderHandleACKEPOCH(i, j) ==
        /\ IsLeader(i)
        /\ PendingACKEPOCH(i, j)
        /\ LET msg == msgs[j][i][1]
               infoOk == /\ IsMyLearner(i, j)           
                         /\ acceptedEpoch[i] = msg.mepoch
               logOk  == \* logOk represents whether leader is more up-to-date than follower
                         /\ infoOk
                         /\ \/ currentEpoch[i] > msg.mlastEpoch
                            \/ /\ currentEpoch[i] = msg.mlastEpoch
                               /\ \/ lastZxid[i][1] > msg.mlastZxid[1]
                                  \/ /\ lastZxid[i][1] = msg.mlastZxid[1]
                                     /\ lastZxid[i][2] >= msg.mlastZxid[2]
               replyOk == /\ infoOk
                          /\ NullPoint \in ackeRecv[i]
           IN /\ infoOk
              /\ \/ /\ replyOk
                    /\ SubRECOVERYSYNC(i, j)
                    /\ ackeRecv' = [ackeRecv EXCEPT ![i] = IF j \notin ackeRecv[i] THEN ackeRecv[i] \union {j}
                                                                                   ELSE ackeRecv[i]]
                    /\ Reply(i, j, [mtype     |-> NEWLEADER,
                                    mepoch    |-> acceptedEpoch[i],
                                    mlastZxid |-> lastZxid[i]])
                    /\ UNCHANGED <<state, currentEpoch,logicalClock, receiveVotes, outOfElection, recvQueue, waitNotmsg, 
                                   leadingVoteSet, electionMsgs, idTable, zabState, leaderAddr, learners>>
                 \/ /\ ~replyOk
                    /\ \/ \* normal case
                          /\ logOk
                          /\ ackeRecv' = [ackeRecv EXCEPT ![i] = IF j \notin ackeRecv[i] THEN ackeRecv[i] \union {j}
                                                                                         ELSE ackeRecv[i]]
                          /\ Discard(j, i)
                          /\ UNCHANGED <<varsL, zabState, leaderAddr, learners, forwarding>>
                       \/ \* go back to election since there exists follower more up-to-date than leader
                          /\ ~logOk
                          /\ LeaderShutdown(i)
                          /\ UNCHANGED ackeRecv
                    /\ UNCHANGED <<history, commitIndex, synced, forwarding, ackIndex, proposalMsgsLog, inherentViolated>>
        /\ UNCHANGED <<acceptedEpoch, cepochRecv, ackldRecv, currentCounter,
                       tempVarsZ, cepochSent, epochLeader>>
        /\ UpdateRecorder(<<"LeaderHandleACKEPOCH", i, j>>)

(* Note: Since we abstract the process 'syncFollower' for leader and 'syncWithLeader' for follower,
         we cannot discribe how transactions being committed. One way we choose is let commitIndex 
         be changed when leader receives quorum of ACKEPOCH and truns to SYNCHRONIZATION.
         It is actually different from code which uses DIFF/TRUNC/SNAP syncMode to complete sync.
*)                                   
LeaderTransitionToSynchronization(i) ==
        /\ IsLeader(i)
        /\ zabState[i] = DISCOVERY
        /\ IsQuorum(ackeRecv[i])
        /\ zabState'       = [zabState       EXCEPT ![i] = SYNCHRONIZATION]
        /\ currentEpoch'   = [currentEpoch   EXCEPT ![i] = acceptedEpoch[i]]
        /\ commitIndex'    = [commitIndex    EXCEPT ![i] = Len(history[i])]
        /\ initialHistory' = [initialHistory EXCEPT ![i] = history[i]]
        /\ ackeRecv'       = [ackeRecv       EXCEPT ![i] = ackeRecv[i] \union {NullPoint}]
        /\ ackIndex'       = [ackIndex       EXCEPT ![i][i] = Len(history[i])]
        /\ UpdateProposal(i, i, lastZxid[i], currentEpoch'[i])
        /\ LET epoch == acceptedEpoch[i]
           IN epochLeader' = [epochLeader EXCEPT ![epoch] = epochLeader[epoch] \union {i}]
        /\ UNCHANGED <<state, lastZxid, acceptedEpoch, history, logicalClock, receiveVotes, outOfElection, 
                       recvQueue, waitNotmsg, leadingVoteSet, learners, cepochRecv, ackldRecv, forwarding, currentCounter,
                       tempMaxEpoch, followerVarsZ, proposalMsgsLog, inherentViolated, msgVarsZ, idTable>>
        /\ UpdateRecorder(<<"LeaderTransitionToSynchronization", i>>)
       
(*       
   Note: Set cepochRecv, ackeRecv, ackldRecv to {NullPoint} in corresponding three actions to
         make sure that the prospective leader will not broadcast NEWEPOCH/NEWLEADER/COMMITLD twice.
*)
-----------------------------------------------------------------------------
RECOVERYSYNC(i, j) ==
        LET canSync == /\ IsLeader(i)   /\ zabState[i] /= DISCOVERY      /\ IsMyLearner(i, j)  /\ j \in ackeRecv[i]
                       /\ IsFollower(j) /\ zabState[j] = SYNCHRONIZATION /\ IsMyLeader(j, i)   /\ synced[j] = FALSE
        IN
        /\ canSync
        /\ SubRECOVERYSYNC(i, j)
        /\ Send(i, j, [mtype     |-> NEWLEADER,
                       mepoch    |-> acceptedEpoch[i],
                       mlastZxid |-> lastZxid[i]])
        /\ UNCHANGED <<state, zabState, acceptedEpoch, currentEpoch, logicalClock, receiveVotes, outOfElection, recvQueue, waitNotmsg, 
                       leadingVoteSet, learners, cepochRecv, ackeRecv, ackldRecv, currentCounter,
                       tempVarsZ, cepochSent, leaderAddr, epochLeader, electionMsgs, idTable>>
        /\ UpdateRecorder(<<"RECOVERYSYNC", i, j>>)

(* Follower receives NEWLEADER. The follower updates its epoch and history,
   and sends back ACK-LD to leader. *)
FollowerHandleNEWLEADER(i, j) ==
        /\ IsFollower(i)
        /\ PendingNEWLEADER(i, j)
        /\ LET msg     == msgs[j][i][1]
               infoOk  == /\ IsMyLeader(i, j)
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
                    /\ PrintT("Exception: Follower receives NEWLEADER while it has not completed sync with leader.")
                    /\ inherentViolated' = TRUE
                    /\ Discard(j, i)
        /\ UNCHANGED <<state, lastZxid, zabState, acceptedEpoch, history, commitIndex, logicalClock, receiveVotes, 
                       outOfElection, recvQueue, waitNotmsg, leaderVarsZ, tempVarsZ, followerVarsZ, proposalMsgsLog, 
                       epochLeader, electionMsgs, idTable>>
        /\ UpdateRecorder(<<"FollowerHandleNEWLEADER", i, j>>)
        
\* Leader receives ACK-LD from a quorum of followers, and sends COMMIT-LD(UPTODATE) to followers.
LeaderHandleACKLD(i, j) ==
        /\ IsLeader(i)
        /\ PendingACKLD(i, j)
        /\ LET msg     == msgs[j][i][1]
               infoOk  == /\ acceptedEpoch[i] = msg.mepoch
                          /\ IsMyLearner(i, j)
               replyOk == /\ infoOk
                          /\ NullPoint \in ackldRecv[i]
           IN /\ infoOk
              /\ \/ \* leader has broadcast UPTODATE - just reply
                    /\ replyOk
                    /\ Reply(i, j, [mtype   |-> UPTODATE,
                                    mepoch  |-> acceptedEpoch[i],
                                    mcommit |-> commitIndex[i]])
                 \/ \* leader still waits for a quorum's ACKLD to broadcast UPTODATE
                    /\ ~replyOk
                    /\ Discard(j, i)
              /\ ackldRecv' = [ackldRecv EXCEPT ![i] = IF j \notin ackldRecv[i] THEN ackldRecv[i] \union {j}
                                                                                ELSE ackldRecv[i]]
        /\ UNCHANGED <<serverVarsZ, electionVarsZ, leadingVoteSet, learners, cepochRecv, ackeRecv, forwarding, 
                       ackIndex, currentCounter,tempVarsZ, followerVarsZ, verifyVarsZ, electionMsgs, idTable>>
        /\ UpdateRecorder(<<"LeaderHandleACKLD", i, j>>)
        
LeaderTransitionToBroadcast(i) ==
        /\ IsLeader(i)
        /\ zabState[i] = SYNCHRONIZATION
        /\ IsQuorum(ackldRecv[i])
        /\ zabState'       = [zabState       EXCEPT ![i] = BROADCAST]
        /\ currentCounter' = [currentCounter EXCEPT ![i] = 0]
        \* /\ sendCounter'    = [sendCounter    EXCEPT ![i] = 0]
        \* /\ committedIndex' = [committedIndex EXCEPT ![i] = Len(history[i])]
        /\ ackldRecv'      = [ackldRecv      EXCEPT ![i] = ackldRecv[i] \union {NullPoint}]
        /\ BroadcastUPTODATE(i, [mtype   |-> UPTODATE,
                                 mepoch  |-> acceptedEpoch[i],
                                 mcommit |-> Len(history[i])]) \* In actual UPTODATE doesn't carry this info
        /\ UNCHANGED <<state, currentEpoch, lastZxid, acceptedEpoch, history, commitIndex, electionVarsZ, leadingVoteSet, 
                       learners, cepochRecv, ackeRecv, forwarding, ackIndex, tempVarsZ, followerVarsZ, 
                       verifyVarsZ, electionMsgs, idTable>>
        /\ UpdateRecorder(<<"LeaderTransitionToBroadcast", i>>)
        
FollowerHandleUPTODATE(i, j) ==
        /\ IsFollower(i)
        /\ PendingUPTODATE(i, j)
        /\ LET msg == msgs[j][i][1]
               infoOk == /\ IsMyLeader(i, j)
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
                    /\ PrintT("Exception: Follower receives UPTODATE while its ZabState is not SYNCHRONIZATION.")
                    /\ inherentViolated' = TRUE
                    /\ UNCHANGED <<commitIndex, zabState>>
        /\ Discard(j, i)
        /\ UNCHANGED <<state, currentEpoch, lastZxid, acceptedEpoch, history, electionVarsZ, leaderVarsZ, tempVarsZ, followerVarsZ,
                       proposalMsgsLog, epochLeader, electionMsgs, idTable>>
        /\ UpdateRecorder(<<"FollowerHandleUPTODATE", i, j>>)
-----------------------------------------------------------------------------
\* Leader receives client request and broadcasts PROPOSAL.
(* Note1: In production, any server in traffic can receive requests and forward it to leader if necessary.
          We choose to let leader be the sole one who can receive requests, 
          to simplify spec and keep correctness at the same time.
   Note2: To compress state space, we now choose to merge action client request and 
          action leader broadcasts proposal into one action.
*)
ClientRequestAndLeaderBroadcastProposal(i, v) ==
        /\ CheckTransactionNum \* test restrictions of transaction num
        /\ IsLeader(i)
        /\ zabState[i] = BROADCAST
        /\ currentCounter' = [currentCounter EXCEPT ![i] = currentCounter[i] + 1]
        /\ LET newTransaction == [epoch   |-> acceptedEpoch[i],
                                  counter |-> currentCounter'[i],
                                  value   |-> v]
           IN /\ history'  = [history  EXCEPT ![i] = Append(history[i], newTransaction)]
              /\ lastZxid' = [lastZxid EXCEPT ![i] = <<acceptedEpoch[i], currentCounter'[i]>>]
              /\ ackIndex' = [ackIndex EXCEPT ![i][i] = Len(history'[i])]
              /\ UpdateProposal(i, i, lastZxid'[i], currentEpoch[i])
              /\ Broadcast(i, [mtype     |-> PROPOSAL,
                               mepoch    |-> acceptedEpoch[i],
                               mproposal |-> newTransaction])
              /\ LET m == [msource|-> i, mepoch|-> acceptedEpoch[i], mtype|-> PROPOSAL, mproposal|-> newTransaction]
                 IN proposalMsgsLog' = proposalMsgsLog \union {m}
        /\ UNCHANGED <<state, currentEpoch, zabState, acceptedEpoch, commitIndex, logicalClock, receiveVotes, outOfElection, 
                       recvQueue, waitNotmsg, leadingVoteSet, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, 
                       tempVarsZ, followerVarsZ, epochLeader, inherentViolated,
                       electionMsgs, idTable>>
        /\ UpdateRecorder(<<"ClientRequestAndLeaderBroadcastProposal", i, v>>)

\* Follower accepts proposal and append it to history.
FollowerHandlePROPOSAL(i, j) ==
        /\ IsFollower(i)
        /\ PendingPROPOSAL(i, j)
        /\ LET msg    == msgs[j][i][1]
               infoOk == /\ IsMyLeader(i, j)
                         /\ acceptedEpoch[i] = msg.mepoch
               correct == /\ infoOk
                          /\ zabState[i] /= DISCOVERY
                          /\ synced[i]
               logOk  == \* the first PROPOSAL in this epoch
                         \/ /\ msg.mproposal.counter = 1 
                            /\ \/ Len(history[i]) = 0
                               \/ /\ Len(history[i]) > 0
                                  /\ history[i][Len(history[i])].epoch < msg.mepoch
                         \* not the first PROPOSAL in this epoch
                         \/ /\ msg.mproposal.counter > 1 
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
                          /\ PrintT("Exception: Follower receives PROPOSAL while the transaction is not the next.")
                          /\ inherentViolated' = TRUE
                          /\ Discard(j, i)
                          /\ UNCHANGED <<history, lastZxid, currentVote>>
                 \/ /\ ~correct
                    /\ PrintT("Exception: Follower receives PROPOSAL while it has not completed sync with leader.")
                    /\ inherentViolated' = TRUE
                    /\ Discard(j, i)
                    /\ UNCHANGED <<history, lastZxid, currentVote>>
        /\ UNCHANGED <<state, currentEpoch, zabState, acceptedEpoch, commitIndex, logicalClock, receiveVotes, 
                       outOfElection, recvQueue, waitNotmsg, leaderVarsZ, tempVarsZ, followerVarsZ, proposalMsgsLog, 
                       epochLeader, electionMsgs, idTable>>
        /\ UpdateRecorder(<<"FollowerHandlePROPOSAL", i, j>>)
                               
\* Create a commit packet and send it to all the members of the quorum.
LeaderCommit(s, source, index, zxid) ==
        /\ commitIndex' = [commitIndex EXCEPT ![s] = index]
        /\ DiscardAndBroadcast(s, source, [mtype  |-> COMMIT,
                                           mepoch |-> acceptedEpoch[s],
                                           mzxid  |-> <<zxid.epoch, zxid.counter>>])
            
\* return true if committed, otherwise false.
LeaderTryToCommit(s, zxid, follower) ==
        LET pindex                      == Len(initialHistory[s]) + zxid.counter
            \* Only when all proposals before zxid all committed, this proposal can be permitted to be committed.
            allProposalsBeforeCommitted == \/ zxid.counter = 1     
                                           \/ /\ zxid.counter > 1  
                                              /\ commitIndex[s] >= pindex - 1 
            \* In order to be committed, a proposal must be accepted by a quorum.
            agreeSet                    == {s} \union {k \in (Server \ {s}): ackIndex'[s][k] >= pindex}
            hasAllQuorums               == IsQuorum(agreeSet)
            \* Commit proposals in order.
            ordered                     == commitIndex[s] + 1 = pindex
        IN \/ /\ \/ ~allProposalsBeforeCommitted
                 \/ ~hasAllQuorums
              /\ Discard(follower, s)
              /\ UNCHANGED <<inherentViolated, commitIndex>>
           \/ /\ allProposalsBeforeCommitted
              /\ hasAllQuorums
              /\ \/ /\ ~ordered
                    /\ PrintT("Exception: Commiting zxid " \o zxid \o "not first.")
                    /\ inherentViolated' = TRUE
                 \/ /\ ordered
                    /\ UNCHANGED inherentViolated
              /\ LeaderCommit(s, follower, pindex, zxid)

\* Keep a count of acks that are received by the leader for a particular
\* proposal, and commit the proposal.
LeaderProcessACK(i, j) ==
        /\ IsLeader(i)
        /\ PendingACK(i, j)
        /\ LET msg == msgs[j][i][1]
               infoOk == /\ j \in forwarding[i]
                         /\ acceptedEpoch[i] = msg.mepoch
               correct == /\ infoOk
                          /\ zabState[i] = BROADCAST
                          /\ currentCounter[i] >= msg.mzxid[2]
               noOutstanding == commitIndex[i] = Len(history[i])
                            \* outstandingProposals: proposals in history[commitIndex + 1: Len(history)]
               hasCommitted  == commitIndex[i] >= Len(initialHistory[i]) + msg.mzxid[2]
                            \* namely, lastCommitted >= zxid
               logOk == /\ infoOk
                        /\ ackIndex[i][j] + 1 = Len(initialHistory[i]) + msg.mzxid[2]
                            \* everytime, ackIndex should just increase by 1
           IN /\ infoOk
              /\ \/ /\ correct
                    /\ logOk
                    /\ ackIndex' = [ackIndex EXCEPT ![i][j] = ackIndex[i][j] + 1]
                    /\ \/ /\ \* Note: outstanding is 0 / proposal has already been committed.
                             \/ noOutstanding
                             \/ hasCommitted
                          /\ Discard(j, i)
                          /\ UNCHANGED <<commitIndex, inherentViolated>>
                       \/ /\ ~noOutstanding
                          /\ ~hasCommitted
                          /\ LeaderTryToCommit(i, ToZxid(msg.mzxid), j)
                 \/ /\ \/ ~correct
                       \/ ~logOk
                    /\ PrintT("Exception: ackIndex doesn't increase monotonically.")
                    /\ inherentViolated' = TRUE
                    /\ Discard(j, i)
                    /\ UNCHANGED <<ackIndex, commitIndex>>
        /\ UNCHANGED <<state, currentEpoch, lastZxid, zabState, acceptedEpoch, history, electionVarsZ, 
                       leadingVoteSet, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, currentCounter,
                       tempVarsZ, followerVarsZ, proposalMsgsLog, epochLeader, electionMsgs, idTable>>
        /\ UpdateRecorder(<<"LeaderProcessACK", i, j>>)

\* Follower receives COMMIT and commits transaction.
FollowerHandleCOMMIT(i, j) ==
        /\ IsFollower(i)
        /\ PendingCOMMIT(i, j)
        /\ LET msg    == msgs[j][i][1]
               infoOk == /\ IsMyLeader(i, j)
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
                                /\ PrintT("Note: Follower receives COMMIT while the index is not the next commit index.")
                                /\ inherentViolated' = TRUE
                                /\ UNCHANGED commitIndex
                       \/ /\ ~logOk
                          /\ PrintT("Exception: Follower receives COMMIT while the transaction has not been saved before.")
                          /\ inherentViolated' = TRUE
                          /\ UNCHANGED commitIndex
                 \/ /\ ~correct
                    /\ PrintT("Exception: Follower receives COMMIT while it has not completed sync with leader.")
                    /\ inherentViolated' = TRUE
                    /\ UNCHANGED commitIndex
        /\ Discard(j, i)
        /\ UNCHANGED <<state, currentEpoch, lastZxid, zabState, acceptedEpoch, history, electionVarsZ, leaderVarsZ, 
                       tempVarsZ, followerVarsZ, proposalMsgsLog, epochLeader, electionMsgs, idTable>>
        /\ UpdateRecorder(<<"FollowerHandleCOMMIT", i, j>>)
-----------------------------------------------------------------------------
(* Used to discard some messages which should not exist in actual.
   This action should not be triggered. *)
FilterNonexistentMessage(i) ==
        /\ \E j \in Server \ {i}: /\ msgs[j][i] /= << >>
                                  /\ LET msg == msgs[j][i][1]
                                     IN 
                                        \/ /\ IsLeader(i)
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
                                        \/ /\ IsFollower(i)
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
                                        \/ IsLooking(i)
                                  /\ Discard(j, i)
        /\ inherentViolated' = TRUE
        /\ UnchangeRecorder
        /\ UNCHANGED <<serverVarsZ, electionVarsZ, leaderVarsZ, tempVarsZ, followerVarsZ, proposalMsgsLog, 
                       epochLeader, electionMsgs, idTable>>
        
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
            \/ \E i \in Server:    FollowerTimeout(i)
            \/ \E i \in Server:    LeaderTimeout(i)
            \/ \E i, j \in Server: Timeout(i, j)
        (* Zab module - Discovery and Synchronization part *)
            \/ \E i, j \in Server: EstablishConnection(i, j)
            \/ \E i \in Server:    FollowerSendFOLLOWERINFO(i)
            \/ \E i, j \in Server: LeaderHandleFOLLOWERINFO(i, j)
            \/ \E i \in Server:    LeaderBroadcastLEADERINFO(i) 
            \/ \E i, j \in Server: FollowerHandleLEADERINFO(i, j)
            \/ \E i, j \in Server: LeaderHandleACKEPOCH(i, j)
            \/ \E i \in Server:    LeaderTransitionToSynchronization(i)
            \/ \E i, j \in Server: RECOVERYSYNC(i, j)
            \/ \E i, j \in Server: FollowerHandleNEWLEADER(i, j)
            \/ \E i, j \in Server: LeaderHandleACKLD(i, j)
            \/ \E i \in Server:    LeaderTransitionToBroadcast(i)
            \/ \E i, j \in Server: FollowerHandleUPTODATE(i, j)
        (* Zab module - Broadcast part *)
            \/ \E i \in Server, v \in Value: ClientRequestAndLeaderBroadcastProposal(i, v)
            \* \/ \E i \in Server, v \in Value: ClientRequest(i, v)
            \* \/ \E i \in Server:    LeaderBroadcastProposal(i) 
            \/ \E i, j \in Server: FollowerHandlePROPOSAL(i, j)
            \/ \E i, j \in Server: LeaderProcessACK(i, j)
            \* \/ \E i \in Server:    LeaderAdvanceCommit(i)
            \* \/ \E i \in Server:    LeaderBroadcastCommit(i)
            \/ \E i, j \in Server: FollowerHandleCOMMIT(i, j)
        (* An action used to judge whether there are redundant messages in network *)
            \/ \E i \in Server:    FilterNonexistentMessage(i)

SpecZ == InitZ /\ [][NextZ]_vars

-----------------------------------------------------------------------------
\* Define safety properties of Zab 1.0 protocol.

ShouldNotBeTriggered == inherentViolated = FALSE

\* There is most one established leader for a certain epoch.
Leadership1 == \A i, j \in Server:
                   /\ IsLeader(i) /\ zabState[i] \in {SYNCHRONIZATION, BROADCAST}
                   /\ IsLeader(j) /\ zabState[j] \in {SYNCHRONIZATION, BROADCAST}
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
                /\ IsFollower(i)
                /\ commitIndex[i] > 0
                => \A index \in 1..commitIndex[i]: \E msg \in proposalMsgsLog:
                    \/ /\ msg.mtype = PROPOSAL
                       /\ TransactionEqual(msg.mproposal, history[i][index])
                    \/ /\ msg.mtype = "RECOVERYSYNC"
                       /\ \E tindex \in 1..Len(msg.mproposals): TransactionEqual(msg.mproposals[tindex], history[i][index])

\* Agreement: If some follower f delivers transaction a and some follower f' delivers transaction b,
\*            then f' delivers a or f delivers b.
Agreement == \A i, j \in Server:
                /\ IsFollower(i) /\ commitIndex[i] > 0
                /\ IsFollower(j) /\ commitIndex[j] > 0
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
PrimaryIntegrity == \A i, j \in Server: /\ IsLeader(i)
                                        /\ IsFollower(j)
                                        /\ commitIndex[j] >= 1
                        => \A index \in 1..commitIndex[j]: \/ history[j][index].epoch >= currentEpoch[i]
                                                           \/ /\ history[j][index].epoch < currentEpoch[i]
                                                              /\ \E idx \in 1..commitIndex[i]: TransactionEqual(history[i][idx], history[j][index])
(*
LeaderBroadcastProposal(i) ==
        /\ IsLeader(i)
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
        /\ UpdateRecorder(<<"LeaderBroadcastProposal", i>>)
        /\ UNCHANGED <<serverVarsZ, electionVarsZ, leadingVoteSet, learners, cepochRecv, ackeRecv, ackldRecv, forwarding, 
                       ackIndex, currentCounter, committedCounter, tempVarsZ, followerVarsZ, epochLeader, 
                       inherentViolated, electionMsgs, idTable>>
                       
LeaderAdvanceCommit(i) ==
        /\ IsLeader(i)
        /\ zabState[i] = BROADCAST
        /\ commitIndex[i] < Len(history[i])
        /\ LET Agree(index)   == {i} \union {k \in (Server\{i}): ackIndex[i][k] >= index}
               agreeIndexes   == {index \in (commitIndex[i] + 1)..Len(history[i]): Agree(index) \in Quorums}
               newCommitIndex == IF agreeIndexes /= {} THEN Maximum(agreeIndexes)
                                                       ELSE commitIndex[i]
           IN commitIndex' = [commitIndex EXCEPT ![i] = newCommitIndex]
        /\ UpdateRecorder(<<"LeaderAdvanceCommit", i>>)
        /\ UNCHANGED <<state, currentEpoch, lastZxid, zabState, acceptedEpoch, history, electionVarsZ, leaderVarsZ, 
                       tempVarsZ, followerVarsZ, verifyVarsZ, msgVarsZ, idTable>>
*)                                                             
=============================================================================
\* Modification History
\* Last modified Wed Oct 20 20:56:37 CST 2021 by Dell
\* Created Tue Jun 29 22:13:02 CST 2021 by Dell
