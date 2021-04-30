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
const_1619620283999191000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_1619620283999192000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_1619620283999193000 == 
Permutations(const_1619620283999191000) \union Permutations(const_1619620283999192000)
----

=============================================================================
\* Modification History
\* Created Wed Apr 28 22:31:23 CST 2021 by Dell
