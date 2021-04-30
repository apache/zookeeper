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
const_161958152191965000 == 
{v1, v2}
----

\* MV CONSTANT definitions Server
const_161958152191966000 == 
{s1, s2, s3}
----

\* SYMMETRY definition
symm_161958152191967000 == 
Permutations(const_161958152191965000) \union Permutations(const_161958152191966000)
----

=============================================================================
\* Modification History
\* Created Wed Apr 28 11:45:21 CST 2021 by Dell
