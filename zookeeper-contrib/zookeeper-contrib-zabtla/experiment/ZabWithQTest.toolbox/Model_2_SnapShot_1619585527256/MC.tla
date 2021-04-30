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
const_161958546004872000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_161958546004873000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_161958546004874000 == 
Permutations(const_161958546004872000) \union Permutations(const_161958546004873000)
----

=============================================================================
\* Modification History
\* Created Wed Apr 28 12:51:00 CST 2021 by Dell
