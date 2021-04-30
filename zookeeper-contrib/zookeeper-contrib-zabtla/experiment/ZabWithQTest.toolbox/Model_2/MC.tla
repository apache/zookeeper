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
const_1619593087843128000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_1619593087843129000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_1619593087843130000 == 
Permutations(const_1619593087843128000) \union Permutations(const_1619593087843129000)
----

=============================================================================
\* Modification History
\* Created Wed Apr 28 14:58:07 CST 2021 by Dell
