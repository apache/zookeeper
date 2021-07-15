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
------------------------- MODULE FastLeaderElection -------------------------
\* This is the formal specification for Fast Leader Election in Zab protocol.
(* Reference:
   FastLeaderElection.java, Vote.java, QuorumPeer.java in https://github.com/apache/zookeeper.
   Medeiros A. ZooKeeper¡¯s atomic broadcast protocol: Theory and practice[J]. Aalto University School of Science, 2012.
*)
EXTENDS Integers, FiniteSets, Sequences, Naturals, TLC

-----------------------------------------------------------------------------
\* The set of server identifiers
CONSTANT Server

\* Server states
CONSTANTS LOOKING, FOLLOWING, LEADING

\* Message types
CONSTANTS NOTIFICATION

\* Timeout signal
CONSTANT NONE
-----------------------------------------------------------------------------
Quorums == {Q \in SUBSET Server: Cardinality(Q)*2 > Cardinality(Server)}

NullPoint == CHOOSE p: p \notin Server

-----------------------------------------------------------------------------
\* Server's state(LOOKING, FOLLOWING, LEADING).
VARIABLE state

\* The epoch number of the last NEWLEADER packet accepted, used for comparing.
VARIABLE currentEpoch

\* The zxid of the last transaction in history.
VARIABLE lastZxid

\* currentVote[i]: The server who i thinks is the current leader(id,zxid,peerEpoch,...).
VARIABLE currentVote

\* Election instance.(logicalClock in code)
VARIABLE logicalClock

\* The votes from the current leader election are stored in ReceiveVotes.
VARIABLE receiveVotes

(* The votes from previous leader elections, as well as the votes from the current leader election are
   stored in outofelection. Note that notifications in a LOOKING state are not stored in outofelection.
   Only FOLLOWING or LEADING notifications are stored in outofelection.  *)
VARIABLE outOfElection

\* recvQueue[i]: The queue of received notifications or timeout signals in server i.
VARIABLE recvQueue

\* A veriable to wait for new notifications, corresponding to line 1050 in FastLeaderElection.java.
VARIABLE waitNotmsg

\* leadingVoteSet[i]: The set of voters that follow i.
VARIABLE leadingVoteSet

(* The messages about election sent from one server to another.
   electionMsgs[i][j] means the input buffer of server j from server i. *)
VARIABLE electionMsgs

\* Set used for mapping Server to Integers, to compare ids from different servers.
VARIABLE idTable

serverVars == <<state, currentEpoch, lastZxid>>

electionVars == <<currentVote, logicalClock, receiveVotes, outOfElection, recvQueue, waitNotmsg>>

leaderVars == <<leadingVoteSet>>

varsL == <<serverVars, electionVars, leaderVars, electionMsgs, idTable>>
-----------------------------------------------------------------------------
\* Processing of electionMsgs
BroadcastNotmsg(i, m) == electionMsgs' = [electionMsgs EXCEPT ![i] = [v \in Server |-> IF v /= i
                                                                                       THEN Append(electionMsgs[i][v], m)
                                                                                       ELSE electionMsgs[i][v]]]

DiscardNotmsg(i, j) == electionMsgs' = [electionMsgs EXCEPT ![i][j] = IF electionMsgs[i][j] /= << >>
                                                                      THEN Tail(electionMsgs[i][j])
                                                                      ELSE << >>]

ReplyNotmsg(i, j, m) == electionMsgs' = [electionMsgs EXCEPT ![i][j] = Append(electionMsgs[i][j], m),
                                                             ![j][i] = Tail(electionMsgs[j][i])]
                                               
-----------------------------------------------------------------------------
\* Processing of recvQueue
RECURSIVE RemoveNone(_)
RemoveNone(seq) == CASE seq =  << >> -> << >>
                   []   seq /= << >> -> IF Head(seq).mtype = NONE THEN RemoveNone(Tail(seq))
                                                                  ELSE <<Head(seq)>> \o RemoveNone(Tail(seq)) 

\* Processing of idTable and order comparing
RECURSIVE InitializeIdTable(_)
InitializeIdTable(Remaining) == IF Remaining = {} THEN {}
                                ELSE LET chosen == CHOOSE i \in Remaining: TRUE
                                         re     == Remaining \ {chosen}
                                     IN {<<chosen, Cardinality(Remaining)>>} \union InitializeIdTable(re)
                                     
\* FALSE: id1 < id2; TRUE: id1 > id2
IdCompare(id1,id2) == LET item1 == CHOOSE item \in idTable: item[1] = id1
                          item2 == CHOOSE item \in idTable: item[1] = id2
                      IN item1[2] > item2[2]

\* FALSE: zxid1 <= zxid2; TRUE: zxid1 > zxid2
ZxidCompare(zxid1, zxid2) == \/ zxid1[1] > zxid2[1]
                             \/ /\ zxid1[1] = zxid2[1]
                                /\ zxid1[2] > zxid2[2]

ZxidEqual(zxid1, zxid2) == zxid1[1] = zxid2[1] /\ zxid1[2] = zxid2[2]

\* FALSE: vote1 <= vote2; TRUE: vote1 > vote2
TotalOrderPredicate(vote1, vote2) == \/ vote1.proposedEpoch > vote2.proposedEpoch
                                     \/ /\ vote1.proposedEpoch = vote2.proposedEpoch
                                        /\ \/ ZxidCompare(vote1.proposedZxid, vote2.proposedZxid)
                                           \/ /\ ZxidEqual(vote1.proposedZxid, vote2.proposedZxid)
                                              /\ IdCompare(vote1.proposedLeader, vote2.proposedLeader)

VoteEqual(vote1, round1, vote2, round2) == /\ vote1.proposedLeader = vote2.proposedLeader
                                           /\ ZxidEqual(vote1.proposedZxid, vote2.proposedZxid)
                                           /\ vote1.proposedEpoch  = vote2.proposedEpoch
                                           /\ round1 = round2

-----------------------------------------------------------------------------
\* Processing of currentVote
InitialVote == [proposedLeader |-> NullPoint,
                proposedZxid   |-> <<0, 0>>,
                proposedEpoch  |-> 0]

SelfVote(i) == [proposedLeader |-> i,
                proposedZxid   |-> lastZxid[i],
                proposedEpoch  |-> currentEpoch[i]]

UpdateProposal(i, nid, nzxid, nepoch) == currentVote' = [currentVote EXCEPT ![i].proposedLeader = nid, \* no need to record state in LOOKING
                                                                            ![i].proposedZxid   = nzxid,
                                                                            ![i].proposedEpoch  = nepoch]  
                                                                            
-----------------------------------------------------------------------------
\* Processing of receiveVotes and outOfElection
RvClear(i) == receiveVotes'  = [receiveVotes  EXCEPT ![i] = [v \in Server |-> [vote    |-> InitialVote,
                                                                               round   |-> 0,
                                                                               state   |-> LOOKING,
                                                                               version |-> 0]]]

RvPut(i, id, mvote, mround, mstate) == receiveVotes' = CASE receiveVotes[i][id].round < mround -> [receiveVotes EXCEPT ![i][id].vote    = mvote,
                                                                                                                       ![i][id].round   = mround,
                                                                                                                       ![i][id].state   = mstate,
                                                                                                                       ![i][id].version = 1]
                                                       []   receiveVotes[i][id].round = mround -> [receiveVotes EXCEPT ![i][id].vote    = mvote,
                                                                                                                       ![i][id].state   = mstate,
                                                                                                                       ![i][id].version = @ + 1]
                                                       []   receiveVotes[i][id].round > mround -> receiveVotes

Put(i, id, rcvset, mvote, mround, mstate) == CASE rcvset[id].round < mround -> [rcvset EXCEPT ![id].vote    = mvote,
                                                                                              ![id].round   = mround,
                                                                                              ![id].state   = mstate,
                                                                                              ![id].version = 1]
                                             []   rcvset[id].round = mround -> [rcvset EXCEPT ![id].vote    = mvote,
                                                                                              ![id].state   = mstate,
                                                                                              ![id].version = @ + 1]
                                             []   rcvset[id].round > mround -> rcvset

RvClearAndPut(i, id, vote, round) == receiveVotes' = LET oneVote == [vote    |-> vote, 
                                                                     round   |-> round, 
                                                                     state   |-> LOOKING,
                                                                     version |-> 1]
                                                     IN [receiveVotes EXCEPT ![i] = [v \in Server |-> IF v = id THEN oneVote
                                                                                                                ELSE [vote    |-> InitialVote,
                                                                                                                      round   |-> 0,
                                                                                                                      state   |-> LOOKING,
                                                                                                                      version |-> 0]]]                     

VoteSet(i, msource, rcvset, thisvote, thisround) == {msource} \union {s \in (Server \ {msource}): VoteEqual(rcvset[s].vote, 
                                                                                                            rcvset[s].round,
                                                                                                            thisvote,
                                                                                                            thisround)}

HasQuorums(i, msource, rcvset, thisvote, thisround) == LET Q == VoteSet(i, msource, rcvset, thisvote, thisround)
                                                       IN IF Q \in Quorums THEN TRUE ELSE FALSE

CheckLeader(i, votes, thisleader, thisround) == IF thisleader = i THEN (IF thisround = logicalClock[i] THEN TRUE ELSE FALSE)
                                                ELSE (IF votes[thisleader].vote.proposedLeader = NullPoint THEN FALSE
                                                      ELSE (IF votes[thisleader].state = LEADING THEN TRUE 
                                                                                                 ELSE FALSE))

OoeClear(i) == outOfElection' = [outOfElection EXCEPT ![i] = [v \in Server |-> [vote    |-> InitialVote,
                                                                                round   |-> 0,
                                                                                state   |-> LOOKING,
                                                                                version |-> 0]]]  

OoePut(i, id, mvote, mround, mstate) == outOfElection' = CASE outOfElection[i][id].round < mround -> [outOfElection EXCEPT ![i][id].vote    = mvote,
                                                                                                                           ![i][id].round   = mround,
                                                                                                                           ![i][id].state   = mstate,
                                                                                                                           ![i][id].version = 1]
                                                         []   outOfElection[i][id].round = mround -> [outOfElection EXCEPT ![i][id].vote    = mvote,
                                                                                                                           ![i][id].state   = mstate,
                                                                                                                           ![i][id].version = @ + 1]
                                                         []   outOfElection[i][id].round > mround -> outOfElection
                                                                                                                             
-----------------------------------------------------------------------------    
InitServerVars == /\ state        = [s \in Server |-> LOOKING]
                  /\ currentEpoch = [s \in Server |-> 0]
                  /\ lastZxid     = [s \in Server |-> <<0, 0>>]

InitElectionVars == /\ currentVote   = [s \in Server |-> SelfVote(s)]
                    /\ logicalClock  = [s \in Server |-> 0]
                    /\ receiveVotes  = [s \in Server |-> [v \in Server |-> [vote    |-> InitialVote,
                                                                            round   |-> 0,
                                                                            state   |-> LOOKING,
                                                                            version |-> 0]]]
                    /\ outOfElection = [s \in Server |-> [v \in Server |-> [vote    |-> InitialVote,
                                                                            round   |-> 0,
                                                                            state   |-> LOOKING,
                                                                            version |-> 0]]]
                    /\ recvQueue     = [s \in Server |-> << >>]
                    /\ waitNotmsg    = [s \in Server |-> FALSE]

InitLeaderVars == /\ leadingVoteSet = [s \in Server |-> {}]

Init == /\ InitServerVars
        /\ InitElectionVars
        /\ InitLeaderVars
        /\ electionMsgs = [s \in Server |-> [v \in Server |-> << >>]]
        /\ idTable = InitializeIdTable(Server)
        
-----------------------------------------------------------------------------
(* The beginning part of FLE's main function lookForLeader() *)
ZabTimeout(i) ==
        /\ state[i] \in {LEADING, FOLLOWING}
        /\ state'          = [state          EXCEPT ![i] = LOOKING]
        /\ logicalClock'   = [logicalClock   EXCEPT ![i] = logicalClock[i] + 1]
        /\ currentVote'    = [currentVote    EXCEPT ![i] = [proposedLeader |-> i,
                                                            proposedZxid   |-> lastZxid[i],
                                                            proposedEpoch  |-> currentEpoch[i]]]
        /\ receiveVotes'   = [receiveVotes   EXCEPT ![i] = [v \in Server |-> [vote    |-> InitialVote,
                                                                              round   |-> 0,
                                                                              state   |-> LOOKING,
                                                                              version |-> 0]]]
        /\ outOfElection'  = [outOfElection  EXCEPT ![i] = [v \in Server |-> [vote    |-> InitialVote,
                                                                              round   |-> 0,
                                                                              state   |-> LOOKING,
                                                                              version |-> 0]]]
        /\ recvQueue'      = [recvQueue      EXCEPT ![i] = << >>]  
        /\ waitNotmsg'     = [waitNotmsg     EXCEPT ![i] = FALSE]
        /\ leadingVoteSet' = [leadingVoteSet EXCEPT ![i] = {}]
        /\ BroadcastNotmsg(i, [mtype   |-> NOTIFICATION,
                               msource |-> i,
                               mstate  |-> LOOKING,
                               mround  |-> logicalClock'[i],
                               mvote   |-> currentVote'[i]])
        /\ UNCHANGED <<currentEpoch, lastZxid, idTable>>
        
(* Abstraction of WorkerReceiver.run() *)
ReceiveNotmsg(i, j) ==
        /\ electionMsgs[j][i] /= << >>
        /\ LET notmsg == electionMsgs[j][i][1]
               toSend == [mtype   |-> NOTIFICATION,
                          msource |-> i,
                          mstate  |-> state[i],
                          mround  |-> logicalClock[i],
                          mvote   |-> currentVote[i]]
           IN \/ /\ state[i] = LOOKING
                 /\ recvQueue' = [recvQueue EXCEPT ![i] = Append(RemoveNone(recvQueue[i]), notmsg)]
                 /\ LET replyOk == /\ notmsg.mstate = LOOKING
                                   /\ notmsg.mround < logicalClock[i]
                    IN 
                    \/ /\ replyOk
                       /\ ReplyNotmsg(i, j, toSend)
                    \/ /\ ~replyOk
                       /\ DiscardNotmsg(j, i)
              \/ /\ state[i] \in {LEADING, FOLLOWING}
                 /\ \/ \* Only reply when sender's state is LOOKING
                       /\ notmsg.mstate = LOOKING
                       /\ ReplyNotmsg(i, j, toSend)
                    \/ \* sender's state and mine are both not LOOKING, just discard
                       /\ notmsg.mstate /= LOOKING
                       /\ DiscardNotmsg(j, i)
                 /\ UNCHANGED recvQueue
        /\ UNCHANGED <<serverVars, currentVote, logicalClock, receiveVotes, outOfElection, waitNotmsg, leaderVars, idTable>>
        
NotmsgTimeout(i) == 
        /\ state[i] = LOOKING
        /\ \A j \in Server: electionMsgs[j][i] = << >>
        /\ recvQueue[i] = << >>
        /\ recvQueue' = [recvQueue EXCEPT ![i] = Append(recvQueue[i], [mtype |-> NONE])]
        /\ UNCHANGED <<serverVars, currentVote, logicalClock, receiveVotes, outOfElection, waitNotmsg, leaderVars, electionMsgs, idTable>>

-----------------------------------------------------------------------------
\* Sub-action in HandleNotmsg
ReceivedFollowingAndLeadingNotification(i, n) ==
        LET newVotes    == Put(i, n.msource, receiveVotes[i], n.mvote, n.mround, n.mstate)
            voteSet1    == VoteSet(i, n.msource, newVotes, n.mvote, n.mround)
            hasQuorums1 == voteSet1 \in Quorums
            check1      == CheckLeader(i, newVotes, n.mvote.proposedLeader, n.mround)
            leaveOk1    == /\ n.mround = logicalClock[i]
                           /\ hasQuorums1
                           /\ check1    \* state and leadingVoteSet cannot be changed twice in the first '/\' and second '/\'.
        IN
        /\ \/ /\ n.mround = logicalClock[i]
              /\ receiveVotes' = [receiveVotes EXCEPT ![i] = newVotes]
           \/ /\ n.mround /= logicalClock[i]
              /\ UNCHANGED receiveVotes
        /\ \/ /\ leaveOk1
              \* /\ PrintT("leave with condition 1")
              /\ state' = [state EXCEPT ![i] = IF n.mvote.proposedLeader = i THEN LEADING ELSE FOLLOWING]
              /\ leadingVoteSet' = [leadingVoteSet EXCEPT ![i] = IF n.mvote.proposedLeader = i THEN voteSet1 ELSE @]
              /\ UpdateProposal(i, n.mvote.proposedLeader, n.mvote.proposedZxid, n.mvote.proposedEpoch)
              /\ UNCHANGED <<logicalClock, outOfElection>>
           \/ /\ ~leaveOk1
              /\ outOfElection' = [outOfElection EXCEPT ![i] = Put(i, n.msource, outOfElection[i], n.mvote,n.mround, n.mstate)]
              /\ LET voteSet2    == VoteSet(i, n.msource, outOfElection'[i], n.mvote, n.mround)
                     hasQuorums2 == voteSet2 \in Quorums
                     check2      == CheckLeader(i, outOfElection'[i], n.mvote.proposedLeader, n.mround)
                     leaveOk2    == /\ hasQuorums2
                                    /\ check2
                 IN
                 \/ /\ leaveOk2
                    \* /\ PrintT("leave with condition 2")
                    /\ logicalClock' = [logicalClock EXCEPT ![i] = n.mround]
                    /\ state' = [state EXCEPT ![i] = IF n.mvote.proposedLeader = i THEN LEADING ELSE FOLLOWING]
                    /\ leadingVoteSet' = [leadingVoteSet EXCEPT ![i] = IF n.mvote.proposedLeader = i THEN voteSet2 ELSE @]
                    /\ UpdateProposal(i, n.mvote.proposedLeader, n.mvote.proposedZxid, n.mvote.proposedEpoch)
                 \/ /\ ~leaveOk2
                    /\ LET leaveOk3 == /\ n.mstate = LEADING
                                       /\ n.mround = logicalClock[i]
                       IN
                       \/ /\ leaveOk3
                          \* /\ PrintT("leave with condition 3")
                          /\ state' = [state EXCEPT ![i] = IF n.mvote.proposedLeader = i THEN LEADING ELSE FOLLOWING]
                          /\ UpdateProposal(i, n.mvote.proposedLeader, n.mvote.proposedZxid, n.mvote.proposedEpoch)
                       \/ /\ ~leaveOk3
                          /\ UNCHANGED <<state, currentVote>>
                    /\ UNCHANGED <<logicalClock, leadingVoteSet>>

(* Main part of lookForLeader() *)
HandleNotmsg(i) ==
        /\ state[i] = LOOKING
        /\ \lnot waitNotmsg[i]
        /\ recvQueue[i] /= << >>
        /\ LET n         == recvQueue[i][1]
               rawToSend == [mtype   |-> NOTIFICATION,
                             msource |-> i,
                             mstate  |-> LOOKING,
                             mround  |-> logicalClock[i],
                             mvote   |-> currentVote[i]]
           IN \/ /\ n.mtype = NONE
                 /\ BroadcastNotmsg(i, rawToSend)
                 /\ UNCHANGED <<logicalClock, currentVote, receiveVotes, waitNotmsg, outOfElection, state, leadingVoteSet>>
              \/ /\ n.mtype = NOTIFICATION
                 /\ \/ /\ n.mstate = LOOKING
                       /\ \/ \* n.round >= my round, then update data and receiveVotes.
                             /\ n.mround >= logicalClock[i]
                             /\ \/ \* n.round > my round, update round and decide new proposed leader.
                                   /\ n.mround > logicalClock[i]
                                   /\ logicalClock' = [logicalClock EXCEPT ![i] = n.mround] \* There should be RvClear, we will handle it in the following.
                                   /\ LET selfinfo == [proposedLeader |-> i,
                                                       proposedZxid   |-> lastZxid[i],
                                                       proposedEpoch  |-> currentEpoch[i]]
                                          peerOk   == TotalOrderPredicate(n.mvote, selfinfo)
                                      IN \/ /\ peerOk
                                            /\ UpdateProposal(i, n.mvote.proposedLeader, n.mvote.proposedZxid, n.mvote.proposedEpoch)
                                         \/ /\ ~peerOk
                                            /\ UpdateProposal(i, i, lastZxid[i], currentEpoch[i])
                                   /\ BroadcastNotmsg(i, [mtype   |-> NOTIFICATION,
                                                          msource |-> i,
                                                          mstate  |-> LOOKING,
                                                          mround  |-> n.mround,
                                                          mvote   |-> currentVote'[i]])
                                \/ \* n.round = my round & n.vote > my vote
                                   /\ n.mround = logicalClock[i]
                                   /\ LET peerOk == TotalOrderPredicate(n.mvote, currentVote[i])
                                      IN \/ /\ peerOk
                                            /\ UpdateProposal(i, n.mvote.proposedLeader, n.mvote.proposedZxid, n.mvote.proposedEpoch)
                                            /\ BroadcastNotmsg(i, [mtype   |-> NOTIFICATION,
                                                                   msource |-> i,
                                                                   mstate  |-> LOOKING,
                                                                   mround  |-> logicalClock[i],
                                                                   mvote   |-> n.mvote])
                                         \/ /\ ~peerOk
                                            /\ UNCHANGED <<currentVote, electionMsgs>>
                                   /\ UNCHANGED logicalClock
                             /\ LET rcvsetModifiedTwice == n.mround > logicalClock[i]
                                IN \/ /\ rcvsetModifiedTwice   \* Since a variable cannot be changed more than once in one action, we handle receiveVotes here.
                                      /\ RvClearAndPut(i, n.msource, n.mvote, n.mround)  \* clear + put
                                   \/ /\ ~rcvsetModifiedTwice
                                      /\ RvPut(i, n.msource, n.mvote, n.mround, n.mstate)          \* put
                             /\ LET hasQuorums == HasQuorums(i, i, receiveVotes'[i], currentVote'[i], n.mround)
                                IN \/ /\ hasQuorums \* If hasQuorums, see action WaitNewNotmsg and WaitNewNotmsgEnd.
                                      /\ waitNotmsg' = [waitNotmsg EXCEPT ![i] = TRUE] 
                                   \/ /\ ~hasQuorums                            
                                      /\ UNCHANGED waitNotmsg
                          \/ \* n.round < my round, just discard it.
                             /\ n.mround < logicalClock[i]
                             /\ UNCHANGED <<logicalClock, currentVote, electionMsgs, receiveVotes, waitNotmsg>>
                       /\ UNCHANGED <<state, outOfElection, leadingVoteSet>>
                    \/ \* mainly contains receivedFollowingNotification(line 1146), receivedLeadingNotification(line 1185).
                       /\ n.mstate \in {LEADING, FOLLOWING}
                       /\ ReceivedFollowingAndLeadingNotification(i, n)
                       /\ UNCHANGED <<electionMsgs, waitNotmsg>>
        /\ recvQueue' = [recvQueue EXCEPT ![i] = Tail(recvQueue[i])]
        /\ UNCHANGED <<currentEpoch, lastZxid, idTable>>

\* On the premise that ReceiveVotes.HasQuorums = TRUE, corresponding to logic in line 1050-1055 in LFE.java.
WaitNewNotmsg(i) ==
        /\ state[i] = LOOKING
        /\ waitNotmsg[i] = TRUE
        /\ recvQueue[i] /= << >>
        /\ recvQueue[i][1].mtype = NOTIFICATION
        /\ LET n      == recvQueue[i][1]
               peerOk == TotalOrderPredicate(n.mvote, currentVote[i])
               delQ   == Tail(recvQueue[i])
           IN \/ /\ peerOk
                 /\ waitNotmsg' = [waitNotmsg EXCEPT ![i] = FALSE]
                 /\ recvQueue'  = [recvQueue  EXCEPT ![i] = Append(delQ, n)]
              \/ /\ ~peerOk
                 /\ recvQueue' = [recvQueue EXCEPT ![i] = delQ]
                 /\ UNCHANGED waitNotmsg
        /\ UNCHANGED <<serverVars, currentVote, logicalClock, receiveVotes, outOfElection, leaderVars, electionMsgs, idTable>>

\* On the premise that ReceiveVotes.HasQuorums = TRUE, corresponding to logic in line 1061-1066 in LFE.java.
WaitNewNotmsgEnd(i) ==
        /\ state[i] = LOOKING
        /\ waitNotmsg[i] = TRUE
        /\ \/ recvQueue[i] = << >>
           \/ /\ recvQueue[i] /= << >>
              /\ recvQueue[i][1].mtype = NONE
        /\ state'          = [state          EXCEPT ![i] = IF currentVote[i].proposedLeader = i THEN LEADING
                                                                                                ELSE FOLLOWING]
        /\ leadingVoteSet' = [leadingVoteSet EXCEPT ![i] = IF currentVote[i].proposedLeader = i THEN VoteSet(i, i, receiveVotes[i], currentVote[i], logicalClock[i])
                                                                                                ELSE @]
        /\ UNCHANGED <<currentEpoch, lastZxid, electionVars, electionMsgs, idTable>>

-----------------------------------------------------------------------------
(*Test - simulate modifying currentEpoch and lastZxid.
  We want to reach violations to achieve some traces and see whether the whole state of system is advancing.
  The actions below are completely not equal to implementation in real, 
  just simulate a process of leader updates state and followers get it. *)

LeaderAdvanceEpoch(i) ==
        /\ state[i] = LEADING
        /\ currentEpoch' = [currentEpoch EXCEPT ![i] = @ + 1]
        /\ UNCHANGED <<state, lastZxid, electionVars, leaderVars, electionMsgs, idTable>>

FollowerUpdateEpoch(i, j) ==
        /\ state[i] = FOLLOWING
        /\ currentVote[i].proposedLeader = j
        /\ state[j] = LEADING
        /\ currentEpoch[i] < currentEpoch[j]
        /\ currentEpoch' = [currentEpoch EXCEPT ![i] = currentEpoch[j]]
        /\ UNCHANGED <<state, lastZxid, electionVars, leaderVars, electionMsgs, idTable>>

LeaderAdvanceZxid(i) ==
        /\ state[i] = LEADING
        /\ lastZxid' = [lastZxid EXCEPT ![i] = IF lastZxid[i][1] = currentEpoch[i] 
                                               THEN <<currentEpoch[i], lastZxid[i][2] + 1>>
                                               ELSE <<currentEpoch[i], 1>>]
        /\ UNCHANGED <<state, currentEpoch, electionVars, leaderVars, electionMsgs, idTable>>

FollowerUpdateZxid(i, j) ==
        /\ state[i] = FOLLOWING
        /\ currentVote[i].proposedLeader = j
        /\ state[j] = LEADING
        /\ LET precede == \/ lastZxid[i][1] < lastZxid[j][1]
                          \/ /\ lastZxid[i][1] = lastZxid[j][1]
                             /\ lastZxid[i][2] < lastZxid[j][2]
           IN /\ precede
              /\ lastZxid' = [lastZxid EXCEPT ![i] = lastZxid[j]]
        /\ UNCHANGED <<state, currentEpoch, electionVars, leaderVars, electionMsgs, idTable>>

Next == 
        \/ \E i \in Server:     ZabTimeout(i)
        \/ \E i, j \in Server:  ReceiveNotmsg(i, j)
        \/ \E i \in Server:     NotmsgTimeout(i)
        \/ \E i \in Server:     HandleNotmsg(i)
        \/ \E i \in Server:     WaitNewNotmsg(i)
        \/ \E i \in Server:     WaitNewNotmsgEnd(i)
       
        \/ \E i \in Server:     LeaderAdvanceEpoch(i)
        \/ \E i, j \in Server:  FollowerUpdateEpoch(i, j)
        \/ \E i \in Server:     LeaderAdvanceZxid(i)
        \/ \E i, j \in Server:  FollowerUpdateZxid(i, j)

Spec == Init /\ [][Next]_varsL
       
\* These invariants should be violated after running for minutes.

ShouldBeTriggered1 == ~\E Q \in Quorums: /\ \A i \in Q: /\ state[i] \in {FOLLOWING, LEADING}
                                                        /\ currentEpoch[i] > 3
                                                        /\ logicalClock[i] > 2
                                                        /\ currentVote[i].proposedLeader \in Q
                                         /\ \A i, j \in Q: currentVote[i].proposedLeader = currentVote[j].proposedLeader

(*
ShouldBeTriggered2 == ~\E Q \in Quorums: /\ \A i \in Q: /\ state[i] \in {FOLLOWING, LEADING}
                                                        /\ currentEpoch[i] > 3
                                                        /\ currentVote[i].proposedLeader \in Q
                                         /\ \A i, j \in Q: currentVote[i].proposedLeader = currentVote[j].proposedLeader*)
=============================================================================
\* Modification History
\* Last modified Wed Jul 14 21:02:21 CST 2021 by Dell
\* Created Fri Jun 18 20:23:47 CST 2021 by Dell
