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
EXTENDS Zab, TLC

\* MV CONSTANT declarations@modelParameterConstants
CONSTANTS
v1, v2
----

\* MV CONSTANT declarations@modelParameterConstants
CONSTANTS
s1, s2, s3
----

\* MV CONSTANT definitions Value
const_1621262672484171000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_1621262672484172000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_1621262672484173000 == 
Permutations(const_1621262672484171000) \union Permutations(const_1621262672484172000)
----

=============================================================================
\* Modification History
\* Created Mon May 17 22:44:32 CST 2021 by Dell
