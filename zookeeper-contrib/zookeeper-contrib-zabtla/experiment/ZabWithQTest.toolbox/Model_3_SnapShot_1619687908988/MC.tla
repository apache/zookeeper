---- MODULE MC ----
EXTENDS ZabWithQTest, TLC

\* MV CONSTANT declarations@modelParameterConstants
CONSTANTS
v1, v2
----

\* MV CONSTANT declarations@modelParameterConstants
CONSTANTS
s1, s2, s3
----

\* MV CONSTANT definitions Value
const_1619687837868212000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_1619687837868213000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_1619687837868214000 == 
Permutations(const_1619687837868212000) \union Permutations(const_1619687837868213000)
----

=============================================================================
\* Modification History
\* Created Thu Apr 29 17:17:17 CST 2021 by Dell
