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
const_1619589859795107000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_1619589859795108000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_1619589859795109000 == 
Permutations(const_1619589859795107000) \union Permutations(const_1619589859795108000)
----

=============================================================================
\* Modification History
\* Created Wed Apr 28 14:04:19 CST 2021 by Dell
