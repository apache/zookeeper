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
const_161952132133716000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_161952132133717000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_161952132133718000 == 
Permutations(const_161952132133716000) \union Permutations(const_161952132133717000)
----

=============================================================================
\* Modification History
\* Created Tue Apr 27 19:02:01 CST 2021 by Dell
