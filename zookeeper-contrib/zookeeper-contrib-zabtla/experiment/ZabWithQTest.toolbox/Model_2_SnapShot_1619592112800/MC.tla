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
const_1619591924373121000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_1619591924373122000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_1619591924373123000 == 
Permutations(const_1619591924373121000) \union Permutations(const_1619591924373122000)
----

=============================================================================
\* Modification History
\* Created Wed Apr 28 14:38:44 CST 2021 by Dell
