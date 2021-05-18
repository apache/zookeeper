# Result of Verification
> Our verification of Zab using model checking is in progress, and we have obtained part of data set.  
> We show our result in this doc and the doc is currently not complete. 

## Experiment configuration

In all experiments, we set CONSTANTS to model value except *Server* and *Value*. We adjust the set of servers *Server* and the set of operations that client request *Value* in different experiments. We set *Server* and *Value* as symmetrical model value set to improve efficiency of verification of TLC. And we use 10 threads to do these experiments. We use *model-checking mode* and *simulation mode* respectively, and in *simulation mode*, we set maximum length of trace is 100.  

Our statistical results include: diameter of the system states that have been traversed, the number of states that have been traversed, the number of different states that have been discovered, and the time spent in the experiment.

The machine configuration used in the experiment is 2.40 GHz, 10-core CPU, 64GB memory. The TLC version number is 1.7.0.

## State space constraints in model checking

Due to the state space explosion in model checking and the complex actions of Zab protocol, as well as unlimited number of rounds and unlimited length of history, it is impossible to traverse all states.  
We try to let models can be run completely. We constrain the number of election[1], the number of restart from all servers[2], and the number of transactions delivered[3].  
 We prospectively add limits in *Election* to constrain [1], add limits in *Restart* and *RecoveryAfterRestart* to constrain [2], add limits in *ClientRequest* to constrain [3].


## Verification results of model checking  
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

## Verification results with parameters (count of servers, MaxTotalRestartNum, MaxElectionNum, MaxTransactionNum)
|  Mode  |     TLC model         |    Diameter   |     num of states  | time of checking(hh:mm:ss) |
| ----- | ---------------------- | ------------- | ------------------ | ------------------ |
| BFS   | (2,2,3,2,termination) |     55   |  10772649   |  00:02:21|
| BFS   | (3,1,1,2)             |     45   |  9602018536 |  31:01:57|

