/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

public class ServerConfig {
    private int clientPort;
    private String dataDir;
    private String dataLogDir;
    
    protected ServerConfig(int port, String dataDir,String dataLogDir) {
        this.clientPort = port;
        this.dataDir = dataDir;
        this.dataLogDir=dataLogDir;
    }
    protected boolean isStandaloneServer(){
        return true;
    }

    public static int getClientPort(){
        assert instance!=null;
        return instance.clientPort;
    }
    public static String getDataDir(){
        assert instance!=null;
        return instance.dataDir;
    }
    public static String getDataLogDir(){
        assert instance!=null;
        return instance.dataLogDir;
    }
    public static boolean isStandalone(){
        assert instance!=null;
        return instance.isStandaloneServer();
    }
    
    protected static ServerConfig instance=null;
    
    public static void parse(String[] args) throws Exception {
        if(instance!=null)
            return;
        if (args.length != 2) {
            throw new IllegalArgumentException("Invalid usage.");
        }
        try {
              instance=new ServerConfig(Integer.parseInt(args[0]),args[1],args[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(args[0] + " is not a valid port number");
        }
    }

}
