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
const_16197044801372000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_16197044801373000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_16197044801374000 == 
Permutations(const_16197044801372000) \union Permutations(const_16197044801373000)
----

=============================================================================
\* Modification History
\* Created Thu Apr 29 21:54:40 CST 2021 by Dell
