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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.sun.grizzly.http.SelectorThread;
import com.sun.jersey.api.container.grizzly.GrizzlyWebContainerFactory;

/**
 * Demonstration of how to run the REST service using Grizzly
 */
public class RestMain {
    private String baseUri;

    public RestMain(String baseUri) {
        this.baseUri = baseUri;
    }

    public SelectorThread execute() throws IOException {
        final Map<String, String> initParams = new HashMap<String, String>();

        initParams.put("com.sun.jersey.config.property.packages",
                       "org.apache.zookeeper.server.jersey.resources");

        System.out.println("Starting grizzly...");
        SelectorThread threadSelector =
            GrizzlyWebContainerFactory.create(baseUri, initParams);

        return threadSelector;
    }

    /**
     * The entry point for starting the server
     *
     * @param args requires 2 arguments; the base uri of the service (e.g.
     *        http://localhost:9998/) and the zookeeper host:port string
     */
    public static void main(String[] args) throws Exception {
        final String baseUri = args[0];
        final String zkHostPort = args[1];

        ZooKeeperService.mapUriBase(baseUri, zkHostPort);

        RestMain main = new RestMain(baseUri);
        SelectorThread sel = main.execute();

        System.out.println(String.format(
                "Jersey app started with WADL available at %sapplication.wadl\n” +"
                + "Try out %szookeeper\nHit enter to stop it...",
                baseUri, baseUri));

        System.in.read();
        sel.stopEndpoint();

        ZooKeeperService.close(baseUri);
        System.exit(0);
    }

}
