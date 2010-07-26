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

Configuration Recipe for monitoring ZooKeeper using Nagios
----------------------------------------------------------

I will start by making the assumption that you already have an working Nagios install.

WARNING: I have wrote these instructions while installing and configuring the plugin on my desktop computer running Ubuntu 9.10. I've installed Nagios using apt-get.

WARNING: You should customize the config files as suggested in order to match your Nagios and Zookeeper install. 

WARNING: This README assumes you know how to configure Nagios and how it works. 

WARNING: You should customize the warning and critical levels on service checks to meet your own needs. 

1. Install the plugin

$ cp check_zookeeper.py /usr/lib/nagios/plugins/

2. Install the new commands

$ cp zookeeper.cfg /etc/nagios-plugins/config

3. Update the list of servers in zookeeper.cfg for the command 'check_zookeeper' and update the port for the command 'check_zk_node' (default: 2181)

4. Create a virtual host in Nagios used for monitoring the cluster as a whole -OR-  Create a hostgroup named 'zookeeper-servers' and add all the zookeeper cluster nodes. 

5. Define service checks like I have ilustrated bellow or just use the provided definitions.

define service {
    use         generic-service
    host_name   zookeeper-cluster
    service_description ...
    check_command check_zookeeper!<exported-var>!<warning-level>!<critical-level>
}

define service {
    hostgroup_name  zookeeper-servers                    
    use generic-service                                  
    service_description ZK_Open_File_Descriptors_Count   
    check_command check_zk_node!<exported-var>!<warning-level>!<critical-level>
}

Ex: 

a. check the number of open file descriptors

define service{
        use         generic-service
        host_name   zookeeper-cluster
        service_description ZK_Open_File_Descriptor_Count
        check_command check_zookeeper!zk_open_file_descriptor_count!500!800
}

b. check the number of ephemerals nodes

define service {
        use generic-service
        host_name localhost
        service_description ZK_Ephemerals_Count
        check_command check_zookeeper!zk_ephemerals_count!10000!100000
}

c. check the number of open file descriptors for each host in the group

define service {
    hostgroup_name  zookeeper-servers                    
    use generic-service                                  
    service_description ZK_Open_File_Descriptors_Count   
    check_command check_zk_node!zk_open_file_descriptor_count!500!800
}

