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
const_16195148622789000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_161951486227810000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_161951486227811000 == 
Permutations(const_16195148622789000) \union Permutations(const_161951486227810000)
----

=============================================================================
\* Modification History
\* Created Tue Apr 27 17:14:22 CST 2021 by Dell
