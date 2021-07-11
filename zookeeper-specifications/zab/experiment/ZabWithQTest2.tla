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
--------------------------- MODULE ZabWithQTest2 ---------------------------
EXTENDS Zab

----------------------------------------------------------------------------
\* constants that uniquely used for constraining state space in model checking
CONSTANTS MaxElectionNum, MaxTotalRestartNum, MaxTransactionNum

----------------------------------------------------------------------------
\* variables that uniquely used for constraining state space in model checking
VARIABLES electionNum, \* the round of leader election, not equal to Maximum{currentEpoch[i]: i \in Server},
                       \* because currentEpoch will increase only when follower receives NEWEPOCH,
                       \* and it is common that some round of election ends without leader broadcasting NEWEPOCH
                       \* or follower receiving NEWEPOCH.
          totalRestartNum \* the number of restart from all servers, also as a global variable.

testVars   == <<electionNum, totalRestartNum>>

varsT == <<vars, testVars>>

----------------------------------------------------------------------------
InitT == /\ Init
         /\ electionNum     = 0
         /\ totalRestartNum = 0

----------------------------------------------------------------------------
ElectionT(i, Q) == \* test restrictions
        /\ electionNum < MaxElectionNum
        /\ Election(i, Q)
        /\ electionNum' = electionNum + 1

InitialElectionT(i, Q) ==
        /\ \A s \in Server: state[s] = Follower /\ leaderOracle[s] = NullPoint
        /\ ElectionT(i, Q)
        /\ UNCHANGED <<currentEpoch, history, commitIndex, currentCounter, 
                    sendCounter, recoveryVars, proposalMsgsLog, totalRestartNum>>

LeaderTimeoutT(i, j) ==
        /\ state[i] /= Follower
        /\ j /= i
        /\ j \in cluster[i]
        /\ LET newCluster == cluster[i] \ {j}
           IN /\ \/ /\ newCluster \in Quorums
                    /\ cluster' = [cluster EXCEPT ![i] = newCluster]
                    /\ clean(i, j)
                    /\ UNCHANGED<<state, cepochRecv,ackeRecv, ackldRecv, ackIndex, 
                               committedIndex, initialHistory, tempMaxEpoch, tempMaxLastEpoch,
                               tempInitialHistory, leaderOracle, leaderEpoch, cepochSent, electionNum>>
                 \/ /\ newCluster \notin Quorums
                    /\ \E Q \in Quorums: /\ i \in Q
                                         /\ \E v \in Q: ElectionT(v, Q)
        /\ UNCHANGED <<currentEpoch, history, commitIndex, currentCounter, sendCounter,
                     recoveryVars, proposalMsgsLog, totalRestartNum>>

FollowerTimeoutT(i) ==
        /\ state[i] = Follower
        /\ leaderOracle[i] /= NullPoint
        /\ \E Q \in Quorums: /\ i \in Q
                             /\ \E v \in Q: ElectionT(v, Q)
        /\ UNCHANGED <<currentEpoch, history, commitIndex, currentCounter, sendCounter,
                       recoveryVars, proposalMsgsLog, totalRestartNum>>

----------------------------------------------------------------------------
RestartT(i) == \* test restrictions
        /\ totalRestartNum < MaxTotalRestartNum
        /\ totalRestartNum' = totalRestartNum + 1
        /\ Restart(i)
        /\ UNCHANGED electionNum

RecoveryAfterRestartT(i) == \* test restrictions
        /\ totalRestartNum < MaxTotalRestartNum
        /\ RecoveryAfterRestart(i)
        /\ UNCHANGED testVars

HandleRecoveryRequestT(i, j) == /\ HandleRecoveryRequest(i, j)
                                /\ UNCHANGED testVars

HandleRecoveryResponseT(i, j) == /\ HandleRecoveryResponse(i, j)
                                 /\ UNCHANGED testVars

FindClusterT(i) == /\ FindCluster(i)
                   /\ UNCHANGED testVars
----------------------------------------------------------------------------
FollowerDiscovery1T(i) == /\ FollowerDiscovery1(i)
                          /\ UNCHANGED testVars

LeaderHandleCEPOCHT(i, j) == /\ LeaderHandleCEPOCH(i, j)
                             /\ UNCHANGED testVars

LeaderDiscovery1T(i) == /\ LeaderDiscovery1(i)
                        /\ UNCHANGED testVars

FollowerDiscovery2T(i, j) == /\ FollowerDiscovery2(i, j)
                             /\ UNCHANGED testVars

LeaderHandleACKET(i, j) == /\ LeaderHandleACKE(i, j)
                           /\ UNCHANGED testVars

LeaderDiscovery2Sync1T(i) == /\ LeaderDiscovery2Sync1(i)
                             /\ UNCHANGED testVars
----------------------------------------------------------------------------
FollowerSync1T(i, j) == /\ FollowerSync1(i, j)
                        /\ UNCHANGED testVars

LeaderHandleACKLDT(i, j) == /\ LeaderHandleACKLD(i, j)
                            /\ UNCHANGED testVars

LeaderSync2T(i) == /\ LeaderSync2(i)
                   /\ UNCHANGED testVars

FollowerSync2T(i, j) == /\ FollowerSync2(i, j)
                        /\ UNCHANGED testVars
---------------------------------------------------------------------------- 
ClientRequestT(i, v) == \* test restrictions
        /\ Len(history[i]) < MaxTransactionNum
        /\ ClientRequest(i, v)
        /\ UNCHANGED testVars
    
LeaderBroadcast1T(i) == /\ LeaderBroadcast1(i)
                        /\ UNCHANGED testVars

FollowerBroadcast1T(i, j) == /\ FollowerBroadcast1(i, j)
                             /\ UNCHANGED testVars

LeaderHandleACKT(i, j) == /\ LeaderHandleACK(i, j)
                          /\ UNCHANGED testVars

LeaderAdvanceCommitT(i) == /\ LeaderAdvanceCommit(i)
                           /\ UNCHANGED testVars

LeaderBroadcast2T(i) == /\ LeaderBroadcast2(i)
                        /\ UNCHANGED testVars

FollowerBroadcast2T(i, j) == /\ FollowerBroadcast2(i, j)
                             /\ UNCHANGED testVars
---------------------------------------------------------------------------- 
LeaderHandleCEPOCHinPhase3T(i, j) == /\ LeaderHandleCEPOCHinPhase3(i, j)
                                     /\ UNCHANGED testVars

LeaderHandleACKLDinPhase3T(i, j) == /\ LeaderHandleACKLDinPhase3(i, j)
                                    /\ UNCHANGED testVars
---------------------------------------------------------------------------- 
BecomeFollowerT(i) == /\ BecomeFollower(i)
                      /\ UNCHANGED testVars

DiscardStaleMessageT(i) == /\ DiscardStaleMessage(i)
                           /\ UNCHANGED testVars
                           
----------------------------------------------------------------------------
\* Defines how the variables may transition.
NextT ==
        \/ \E i \in Server, Q \in Quorums: InitialElectionT(i, Q)
        \/ \E i \in Server:      RestartT(i)
        \/ \E i \in Server:      RecoveryAfterRestartT(i)
        \/ \E i, j \in Server:   HandleRecoveryRequestT(i, j)
        \/ \E i, j \in Server:   HandleRecoveryResponseT(i, j)
        \/ \E i, j \in Server:   FindClusterT(i)
        \/ \E i, j \in Server:   LeaderTimeoutT(i, j)
        \/ \E i \in Server:      FollowerTimeoutT(i) 
        \/ \E i \in Server:      FollowerDiscovery1T(i)
        \/ \E i, j \in Server:   LeaderHandleCEPOCHT(i, j)
        \/ \E i \in Server:      LeaderDiscovery1T(i)
        \/ \E i, j \in Server:   FollowerDiscovery2T(i, j)
        \/ \E i, j \in Server:   LeaderHandleACKET(i, j)
        \/ \E i \in Server:      LeaderDiscovery2Sync1T(i)
        \/ \E i, j \in Server:   FollowerSync1T(i, j)
        \/ \E i, j \in Server:   LeaderHandleACKLDT(i, j)
        \/ \E i \in Server:      LeaderSync2T(i)
        \/ \E i, j \in Server:   FollowerSync2T(i, j)
        \/ \E i \in Server, v \in Value: ClientRequestT(i, v)
        \/ \E i \in Server:      LeaderBroadcast1T(i)
        \/ \E i, j \in Server:   FollowerBroadcast1T(i, j)
        \/ \E i, j \in Server:   LeaderHandleACKT(i, j)
        \/ \E i \in Server:      LeaderAdvanceCommitT(i)
        \/ \E i \in Server:      LeaderBroadcast2T(i)
        \/ \E i, j \in Server:   FollowerBroadcast2T(i, j)
        \/ \E i, j \in Server:   LeaderHandleCEPOCHinPhase3T(i, j)
        \/ \E i, j \in Server:   LeaderHandleACKLDinPhase3T(i, j)
        \/ \E i \in Server:      DiscardStaleMessageT(i)
        \/ \E i \in Server:      BecomeFollowerT(i)

SpecT == InitT /\ [][NextT]_varsT
=============================================================================
\* Modification History
\* Last modified Mon May 17 22:00:38 CST 2021 by Dell
\* Created Mon May 17 17:20:01 CST 2021 by Dell
