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
const_16197021598582000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_16197021598583000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_16197021598584000 == 
Permutations(const_16197021598582000) \union Permutations(const_16197021598583000)
----

=============================================================================
\* Modification History
\* Created Thu Apr 29 21:15:59 CST 2021 by Dell
