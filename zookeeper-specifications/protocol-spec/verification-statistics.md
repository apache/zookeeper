<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
//-->

# Verification Statistics 
##### Experiment configuration

Our statistical results include: diameter of the system states that have been traversed, the number of states that have been traversed, the number of different states that have been discovered, and the time spent in the experiment.

The machine configuration used in the experiment is 2.40 GHz, 10-core CPU, 64GB memory. The TLC version number is 1.7.0.



##### State space constraints in model checking

Due to the state space explosion in model checking and the complex actions of Zab protocol, as well as unlimited number of rounds and unlimited length of history, it is impossible to traverse all states.  
We try to let models can tap into larger state space. See CONSTANT *Parameters* for details.



##### Verification statistics of model checking  
|  Mode  |     TLC model         |    Diameter   |     num of states  | time of checking(hh:mm:ss) |
| ----- | ---------------------- | ------------- | ------------------ | ------------------ |
| BFS   | (2 servers,3 rounds,2 transactions)    |     59   |  7758091583 |  17:28:17|
| Simulation | (2 servers,3 rounds,2 transactions)   |   -|  6412825222| 17:07:20  |
| BFS   | (3 servers,2 rounds,2 transactions)    |     19   |  4275801206 |  09:40:08|
| Simulation | (3 servers,2 rounds,2 transactions)   |   -|  10899460942| 20:15:11  |
| BFS   | (3 servers,2 rounds,3 transactions)   |    22    |  8740566213  | 17:49:09 |
| Simulation | (3 servers,2 rounds,3 transactions)  |  -    | 9639135842  |   20:10:00 |
| BFS    |  (3 servers,3 rounds,2 transactions)    |    21    | 7079744342    |14:17:45 |
| Simulation | (3 servers,3 rounds,2 transactions)    |  -  |  6254964039   | 15:08:42 |
| BFS    |  (4 servers,3 rounds,2 transactions)    |    16    | 5634112480  |15:42:09 |
| Simulation | (4 servers,3 rounds,2 transactions)    |  -  |  3883461291   | 15:52:03 |



##### Verification statistics with parameters (count of servers, MaxTotalRestartNum, MaxElectionNum, MaxTransactionNum)

|  Mode  |     TLC model         |    Diameter   |     num of states  | time of checking(hh:mm:ss) |
| ----- | ---------------------- | ------------- | ------------------ | ------------------ |
| BFS   | (2,2,3,2,termination) |     55   |  10772649   |  00:02:21|
| BFS   | (3,1,1,2)             |     45   |  9602018536 |  31:01:57|



##### Issues

Besides, we have found several issues related to the ambiguous description of the Zab protocol. Details can be found at [issues.md](issues.md).
