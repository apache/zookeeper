<!--
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
-->

1) This queue interface recipe implements the queue recipe
mentioned in ../../../docs/recipes.[html,pdf].
A more detailed explanation is at http://www.cloudera.com/blog/2009/05/28/building-a-distributed-concurrent-queue-with-apache-zookeeper/

2) This recipe does not handle KeeperException.ConnectionLossException or ZCONNECTIONLOSS. It will only work correctly once ZOOKEEPER-22 https://issues.apache.org/jira/browse/ZOOKEEPER-22 is resolved.

3) To compile the queue java recipe you can just run ant jar from 
this directory. 
Please report any bugs on the jira 

http://issues.apache.org/jira/browse/ZOOKEEPER

  
