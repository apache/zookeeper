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

package org.apache.zookeeper.server.jersey.cfg;

public class HostPort {

   private String host;
   private int port;
   
   public HostPort(String hostPort) {
       String[] parts = hostPort.split(":");
       host = parts[0];
       port = Integer.parseInt(parts[1]);
   }

   public String getHost() {
       return host;
   }

   public int getPort() {
       return port;
   }

   @Override
   public boolean equals(Object o) {
       HostPort p = (HostPort) o;
       return host.equals(p.host) && port == p.port;
   }
   
   @Override
   public int hashCode() {
       return String.format("%s:%d", host, port).hashCode();
   }
   
}
