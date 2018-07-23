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

public class Endpoint {

   private String context;
   private HostPortSet hostPort;
   private Credentials credentials;
   private Credentials zookeeperAuth;

   public Endpoint(String context, String hostPortList) {
       this.context = context;
       this.hostPort = new HostPortSet(hostPortList);
   }

   public String getContext() {
       return context;
   }

   public String getHostPort() {
       return hostPort.toString();
   }

   public Credentials getCredentials() {
       return credentials;
   }
   
   public void setCredentials(String c) {
       this.credentials = new Credentials(c);
   }
   
   public void setZooKeeperAuthInfo(String digest) {
       zookeeperAuth = new Credentials(digest);
   }
   
   public final Credentials getZooKeeperAuthInfo() {
       return zookeeperAuth;
   }

   @Override
   public boolean equals(Object o) {
       Endpoint e = (Endpoint) o;
       return context.equals(e.context);
   }

   @Override
   public int hashCode() {
       return context.hashCode();
   }

   @Override
   public String toString() {
       return String.format("<Endpoint %s %s>", context, hostPort.toString());
   }
}
