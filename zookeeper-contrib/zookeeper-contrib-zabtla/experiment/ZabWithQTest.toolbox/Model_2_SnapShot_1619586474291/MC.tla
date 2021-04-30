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
const_161958633354786000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_161958633354787000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_161958633354788000 == 
Permutations(const_161958633354786000) \union Permutations(const_161958633354787000)
----

=============================================================================
\* Modification History
\* Created Wed Apr 28 13:05:33 CST 2021 by Dell
