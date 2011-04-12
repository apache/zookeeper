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

package org.apache.zookeeper.server.jersey;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.server.jersey.cfg.Credentials;
import org.apache.zookeeper.server.jersey.cfg.Endpoint;
import org.apache.zookeeper.server.jersey.cfg.RestCfg;
import org.apache.zookeeper.server.jersey.filters.HTTPBasicAuth;

import com.sun.grizzly.SSLConfig;
import com.sun.grizzly.http.embed.GrizzlyWebServer;
import com.sun.grizzly.http.servlet.ServletAdapter;
import com.sun.jersey.spi.container.servlet.ServletContainer;

/**
 * Demonstration of how to run the REST service using Grizzly
 */
public class RestMain {

   private static Logger LOG = LoggerFactory.getLogger(RestMain.class);

   private GrizzlyWebServer gws;
   private RestCfg cfg;

   public RestMain(RestCfg cfg) {
       this.cfg = cfg;
   }

   public void start() throws IOException {
       System.out.println("Starting grizzly ...");

       boolean useSSL = cfg.useSSL();
       gws = new GrizzlyWebServer(cfg.getPort(), "/tmp/23cxv45345/2131xc2/", useSSL);
       // BUG: Grizzly needs a doc root if you are going to register multiple adapters

       for (Endpoint e : cfg.getEndpoints()) {
           ZooKeeperService.mapContext(e.getContext(), e);
           gws.addGrizzlyAdapter(createJerseyAdapter(e), new String[] { e
                   .getContext() });
       }
       
       if (useSSL) {
           System.out.println("Starting SSL ...");
           String jks = cfg.getJKS("keys/rest.jks");
           String jksPassword = cfg.getJKSPassword();

           SSLConfig sslConfig = new SSLConfig();
           URL resource = getClass().getClassLoader().getResource(jks);
           if (resource == null) {
               LOG.error("Unable to find the keystore file: " + jks);
               System.exit(2);
           }
           try {
               sslConfig.setKeyStoreFile(new File(resource.toURI())
                       .getAbsolutePath());
           } catch (URISyntaxException e1) {
               LOG.error("Unable to load keystore: " + jks, e1);
               System.exit(2);
           }
           sslConfig.setKeyStorePass(jksPassword);
           gws.setSSLConfig(sslConfig);
       }

       gws.start();
   }

   public void stop() {
       gws.stop();
       ZooKeeperService.closeAll();
   }

   private ServletAdapter createJerseyAdapter(Endpoint e) {
       ServletAdapter jersey = new ServletAdapter();

       jersey.setServletInstance(new ServletContainer());
       jersey.addInitParameter("com.sun.jersey.config.property.packages",
               "org.apache.zookeeper.server.jersey.resources");
       jersey.setContextPath(e.getContext());

       Credentials c = Credentials.join(e.getCredentials(), cfg
               .getCredentials());
       if (!c.isEmpty()) {
           jersey.addFilter(new HTTPBasicAuth(c), e.getContext()
                   + "-basic-auth", null);
       }

       return jersey;
   }

   /**
    * The entry point for starting the server
    * 
    */
   public static void main(String[] args) throws Exception {
       RestCfg cfg = new RestCfg("rest.properties");

       final RestMain main = new RestMain(cfg);
       main.start();

       Runtime.getRuntime().addShutdownHook(new Thread() {
           @Override
           public void run() {
               main.stop();
               System.out.println("Got exit request. Bye.");
           }
       });

       printEndpoints(cfg);
       System.out.println("Server started.");
   }

   private static void printEndpoints(RestCfg cfg) {
       int port = cfg.getPort();

       for (Endpoint e : cfg.getEndpoints()) {

           String context = e.getContext();
           if (context.charAt(context.length() - 1) != '/') {
               context += "/";
           }

           System.out.println(String.format(
                   "Started %s - WADL: http://localhost:%d%sapplication.wadl",
                   context, port, context));
       }
   }

}
