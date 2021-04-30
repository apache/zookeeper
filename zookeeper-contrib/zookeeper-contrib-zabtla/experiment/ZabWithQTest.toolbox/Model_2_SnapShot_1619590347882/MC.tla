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
const_1619590248357114000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_1619590248357115000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_1619590248357116000 == 
Permutations(const_1619590248357114000) \union Permutations(const_1619590248357115000)
----

=============================================================================
\* Modification History
\* Created Wed Apr 28 14:10:48 CST 2021 by Dell
