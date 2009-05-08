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

import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.server.jersey.jaxb.ZError;


/**
 * Map KeeperException to HTTP status codes
 */
@Provider
public class KeeperExceptionMapper implements ExceptionMapper<KeeperException> {
    private UriInfo ui;

    public KeeperExceptionMapper(@Context UriInfo ui) {
        this.ui = ui;
    }

    public Response toResponse(KeeperException e) {
        Response.Status status;
        String message;

        String path = e.getPath();

        switch(e.code()) {
        case AUTHFAILED:
            status = Response.Status.UNAUTHORIZED;
            message = path + " not authorized";
            break;
        case BADARGUMENTS:
            status = Response.Status.BAD_REQUEST;
            message = path + " bad arguments";
            break;
        case BADVERSION:
            status = Response.Status.PRECONDITION_FAILED;
            message = path + " bad version";
            break;
        case INVALIDACL:
            status = Response.Status.BAD_REQUEST;
            message = path + " invalid acl";
            break;
        case NODEEXISTS:
            status = Response.Status.CONFLICT;
            message = path + " already exists";
            break;
        case NONODE:
            status = Response.Status.NOT_FOUND;
            message = path + " not found";
            break;
        case NOTEMPTY:
            status = Response.Status.CONFLICT;
            message = path + " not empty";
            break;
        default:
            status = Response.Status.fromStatusCode(502); // bad gateway
            message = "Error processing request for " + path
                + " : " + e.getMessage();
        }

        return Response.status(status).entity(
                new ZError(ui.getRequestUri().toString(), message)).build();
    }
}
