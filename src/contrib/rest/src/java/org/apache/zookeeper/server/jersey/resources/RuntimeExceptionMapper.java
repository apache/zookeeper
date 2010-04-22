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

package org.apache.zookeeper.server.jersey.resources;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import org.apache.zookeeper.server.jersey.jaxb.ZError;

/**
 * Map RuntimeException to HTTP status codes
 */
@Provider
public class RuntimeExceptionMapper
    implements ExceptionMapper<RuntimeException>
{
    private UriInfo ui;

    public RuntimeExceptionMapper(@Context UriInfo ui) {
        this.ui = ui;
    }

    public Response toResponse(RuntimeException e) {
        // don't try to handle jersey exceptions ourselves
        if (e instanceof WebApplicationException) { 
            WebApplicationException ie =(WebApplicationException) e; 
            return ie.getResponse(); 
        } 

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(
                new ZError(ui.getRequestUri().toString(),
                        "Error processing request due to " + e
                        )).build();
    }
}
