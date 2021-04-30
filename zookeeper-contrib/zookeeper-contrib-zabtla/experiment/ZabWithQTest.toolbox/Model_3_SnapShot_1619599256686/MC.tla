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
const_1619594687657177000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_1619594687657178000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_1619594687657179000 == 
Permutations(const_1619594687657177000) \union Permutations(const_1619594687657178000)
----

=============================================================================
\* Modification History
\* Created Wed Apr 28 15:24:47 CST 2021 by Dell
