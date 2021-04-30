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
const_161970373470622000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_161970373470623000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_161970373470624000 == 
Permutations(const_161970373470622000) \union Permutations(const_161970373470623000)
----

=============================================================================
\* Modification History
\* Created Thu Apr 29 21:42:14 CST 2021 by Dell
