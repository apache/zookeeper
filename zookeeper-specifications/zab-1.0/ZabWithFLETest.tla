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
--------------------------- MODULE ZabWithFLETest ---------------------------
EXTENDS ZabWithFLE
-----------------------------------------------------------------------------
\* constants that uniquely used for constraining state space in model checking
CONSTANTS MaxTotalTimeoutNum, MaxTransactionNum
-----------------------------------------------------------------------------
\* variables that uniquely used for constraining state space in model checking
VARIABLES totalTimeoutNum \* the number of timeout from all servers, as a global variable.
testVars == <<totalTimeoutNum>>

varsT == <<vars, testVars>>
-----------------------------------------------------------------------------
InitT == /\ InitZ
         /\ totalTimeoutNum = 0
-----------------------------------------------------------------------------
FLEReceiveNotmsgT(i, j) == /\ FLEReceiveNotmsg(i, j)
                           /\ UNCHANGED testVars

FLENotmsgTimeoutT(i) == /\ FLENotmsgTimeout(i)
                        /\ UNCHANGED testVars

FLEHandleNotmsgT(i) == /\ FLEHandleNotmsg(i)
                       /\ UNCHANGED testVars

FLEWaitNewNotmsgT(i) == /\ FLEWaitNewNotmsg(i)
                        /\ UNCHANGED testVars

FLEWaitNewNotmsgEndT(i) == /\ FLEWaitNewNotmsgEnd(i)
                           /\ UNCHANGED testVars
-----------------------------------------------------------------------------
FollowerTimoutT(i) == \* test restrictions
        /\ totalTimeoutNum < MaxTotalTimeoutNum
        /\ totalTimeoutNum' = totalTimeoutNum + 1
        /\ FollowerTimout(i)

LeaderTimeoutT(i) == \* test restrictions
        /\ totalTimeoutNum < MaxTotalTimeoutNum
        /\ totalTimeoutNum' = totalTimeoutNum + 1
        /\ LeaderTimeout(i)

TimeoutT(i, j) == \* test restrictions
        /\ totalTimeoutNum < MaxTotalTimeoutNum
        /\ totalTimeoutNum' = totalTimeoutNum + 1
        /\ Timeout(i, j)
-----------------------------------------------------------------------------
EstablishConnectionT(i, j) == /\ EstablishConnection(i, j)
                              /\ UNCHANGED testVars

FollowerSendFOLLOWERINFOT(i) == /\ FollowerSendFOLLOWERINFO(i)
                                /\ UNCHANGED testVars

LeaderHandleFOLLOWERINFOT(i, j) == /\ LeaderHandleFOLLOWERINFO(i, j)
                                   /\ UNCHANGED testVars

LeaderDiscovery1T(i) == /\ LeaderDiscovery1(i) 
                        /\ UNCHANGED testVars

FollowerHandleLEADERINFOT(i, j) == /\ FollowerHandleLEADERINFO(i, j)
                                   /\ UNCHANGED testVars

LeaderHandleACKEPOCHT(i, j) == /\ LeaderHandleACKEPOCH(i, j)
                               /\ UNCHANGED testVars

LeaderDiscovery2T(i) == /\ LeaderDiscovery2(i)
                        /\ UNCHANGED testVars

RECOVERYSYNCT(i, j) == /\ RECOVERYSYNC(i, j)
                       /\ UNCHANGED testVars

FollowerHandleNEWLEADERT(i, j) == /\ FollowerHandleNEWLEADER(i, j)
                                  /\ UNCHANGED testVars

LeaderHandleACKLDT(i, j) == /\ LeaderHandleACKLD(i, j)
                            /\ UNCHANGED testVars

LeaderSync2T(i) == /\ LeaderSync2(i)
                   /\ UNCHANGED testVars

FollowerHandleUPTODATET(i, j) == /\ FollowerHandleUPTODATE(i, j)
                                 /\ UNCHANGED testVars
-----------------------------------------------------------------------------
ClientRequestT(i, v) == \* test restrictions
        /\ Len(history[i]) < MaxTransactionNum
        /\ ClientRequest(i, v)
        /\ UNCHANGED testVars

LeaderBroadcast1T(i) == /\ LeaderBroadcast1(i) 
                        /\ UNCHANGED testVars

FollowerHandlePROPOSALT(i, j) == /\ FollowerHandlePROPOSAL(i, j)
                                 /\ UNCHANGED testVars

LeaderHandleACKT(i, j) == /\ LeaderHandleACK(i, j)
                          /\ UNCHANGED testVars

LeaderAdvanceCommitT(i) == /\ LeaderAdvanceCommit(i)
                           /\ UNCHANGED testVars

LeaderBroadcast2T(i) == /\ LeaderBroadcast2(i)
                        /\ UNCHANGED testVars
       
FollowerHandleCOMMITT(i, j) == /\ FollowerHandleCOMMIT(i, j)
                               /\ UNCHANGED testVars
-----------------------------------------------------------------------------
FilterNonexistentMessageT(i) == /\ FilterNonexistentMessage(i)
                                /\ UNCHANGED testVars
-----------------------------------------------------------------------------
NextT ==
        (* FLE modlue *)
        \/ \E i, j \in Server: FLEReceiveNotmsgT(i, j)
        \/ \E i \in Server:    FLENotmsgTimeoutT(i)
        \/ \E i \in Server:    FLEHandleNotmsgT(i)
        \/ \E i \in Server:    FLEWaitNewNotmsgT(i)
        \/ \E i \in Server:    FLEWaitNewNotmsgEndT(i)
        (* Some conditions like failure, network delay *)
        \/ \E i \in Server:    FollowerTimoutT(i)
        \/ \E i \in Server:    LeaderTimeoutT(i)
        \/ \E i, j \in Server: TimeoutT(i, j)
        (* Zab module - Discovery and Synchronization part *)
        \/ \E i, j \in Server: EstablishConnectionT(i, j)
        \/ \E i \in Server:    FollowerSendFOLLOWERINFOT(i)
        \/ \E i, j \in Server: LeaderHandleFOLLOWERINFOT(i, j)
        \/ \E i \in Server:    LeaderDiscovery1T(i) 
        \/ \E i, j \in Server: FollowerHandleLEADERINFOT(i, j)
        \/ \E i, j \in Server: LeaderHandleACKEPOCHT(i, j)
        \/ \E i \in Server:    LeaderDiscovery2T(i)
        \/ \E i, j \in Server: RECOVERYSYNCT(i, j)
        \/ \E i, j \in Server: FollowerHandleNEWLEADERT(i, j)
        \/ \E i, j \in Server: LeaderHandleACKLDT(i, j)
        \/ \E i \in Server:    LeaderSync2T(i)
        \/ \E i, j \in Server: FollowerHandleUPTODATET(i, j)
        (* Zab module - Broadcast part *)
        \/ \E i \in Server, v \in Value: ClientRequestT(i, v)
        \/ \E i \in Server:    LeaderBroadcast1T(i) 
        \/ \E i, j \in Server: FollowerHandlePROPOSALT(i, j)
        \/ \E i, j \in Server: LeaderHandleACKT(i, j)
        \/ \E i \in Server:    LeaderAdvanceCommitT(i)
        \/ \E i \in Server:    LeaderBroadcast2T(i)
        \/ \E i, j \in Server: FollowerHandleCOMMITT(i, j)
        (* An action used to judge whether there are redundant messages in network *)
        \/ \E i \in Server:    FilterNonexistentMessageT(i)

SpecT == InitT /\ [][NextT]_varsT

=============================================================================
\* Modification History
\* Last modified Wed Jul 14 18:46:35 CST 2021 by Dell
\* Created Wed Jul 14 15:36:39 CST 2021 by Dell
