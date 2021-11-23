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

---- MODULE MC ----
EXTENDS ZabWithFLEAndSYNC, TLC

\* MV CONSTANT declarations@modelParameterConstants
CONSTANTS
s1, s2, s3, s4
----

\* MV CONSTANT definitions Server
const_163765052196917000 == 
{s1, s2, s3, s4}
----

\* SYMMETRY definition
symm_163765052196918000 == 
Permutations(const_163765052196917000)
----

\* CONSTANT definitions @modelParameterConstants:14Parameters
const_163765052196919000 == 
[MaxTimeoutFailures |-> 2, MaxTransactionNum |-> 5, MaxEpoch |-> 3]
----

=============================================================================
\* Modification History
\* Created Tue Nov 23 14:55:21 CST 2021 by Dell
