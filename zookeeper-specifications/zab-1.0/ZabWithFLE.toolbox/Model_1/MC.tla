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
EXTENDS ZabWithFLE, TLC

\* MV CONSTANT declarations@modelParameterConstants
CONSTANTS
s1, s2, s3
----

\* MV CONSTANT declarations@modelParameterConstants
CONSTANTS
v1, v2
----

\* MV CONSTANT definitions Server
const_1633702797348494000 == 
{s1, s2, s3}
----

\* MV CONSTANT definitions Value
const_1633702797348495000 == 
{v1, v2}
----

\* SYMMETRY definition
symm_1633702797348496000 == 
Permutations(const_1633702797348494000) \union Permutations(const_1633702797348495000)
----

\* CONSTANT definitions @modelParameterConstants:12Parameters
const_1633702797348497000 == 
[MaxTimeoutFailures |-> 2,
MaxTransactionNum |-> 2,
MaxEpoch |-> 2]
----

\* CONSTRAINT definition @modelParameterContraint:0
constr_1633702797349499000 ==
CheckStateConstraints
----
=============================================================================
\* Modification History
\* Created Fri Oct 08 22:19:57 CST 2021 by Dell
