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
EXTENDS ZabWithQTest, TLC

\* MV CONSTANT declarations@modelParameterConstants
CONSTANTS
v1, v2
----

\* MV CONSTANT declarations@modelParameterConstants
CONSTANTS
s1, s2
----

\* MV CONSTANT definitions Value
const_1621315001704200000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_1621315001704201000 == 
{s1, s2}
----

\* SYMMETRY definition
symm_1621315001704202000 == 
Permutations(const_1621315001704200000) \union Permutations(const_1621315001704201000)
----

\* CONSTANT definitions @modelParameterConstants:1MaxTotalRestartNum
const_1621315001704203000 == 
2
----

\* CONSTANT definitions @modelParameterConstants:6MaxElectionNum
const_1621315001704204000 == 
3
----

\* CONSTANT definitions @modelParameterConstants:15MaxTransactionNum
const_1621315001704205000 == 
2
----

=============================================================================
\* Modification History
\* Created Tue May 18 13:16:41 CST 2021 by Dell
