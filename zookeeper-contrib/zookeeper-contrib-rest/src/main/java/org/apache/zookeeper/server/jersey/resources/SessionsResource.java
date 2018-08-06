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

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.server.jersey.ZooKeeperService;
import org.apache.zookeeper.server.jersey.jaxb.ZError;
import org.apache.zookeeper.server.jersey.jaxb.ZSession;

import com.sun.jersey.api.json.JSONWithPadding;

@Path("sessions/v1/{session: .*}")
public class SessionsResource {

    private static Logger LOG = LoggerFactory.getLogger(SessionsResource.class);

    private String contextPath;

    public SessionsResource(@Context HttpServletRequest request) {
        contextPath = request.getContextPath();
        if (contextPath.equals("")) {
            contextPath = "/";
        }
    }

    @PUT
    @Produces( { MediaType.APPLICATION_JSON, "application/javascript",
            MediaType.APPLICATION_XML })
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response keepAliveSession(@PathParam("session") String session,
            @Context UriInfo ui, byte[] data) {

        if (!ZooKeeperService.isConnected(contextPath, session)) {
            throwNotFound(session, ui);
        }

        ZooKeeperService.resetTimer(contextPath, session);
        return Response.status(Response.Status.OK).build();
    }

    @POST
    @Produces( { MediaType.APPLICATION_JSON, "application/javascript",
            MediaType.APPLICATION_XML })
    public Response createSession(@QueryParam("op") String op,
            @DefaultValue("5") @QueryParam("expire") String expire,
            @Context UriInfo ui) {
        if (!op.equals("create")) {
            throw new WebApplicationException(Response.status(
                    Response.Status.BAD_REQUEST).entity(
                    new ZError(ui.getRequestUri().toString(), "")).build());
        }

        int expireInSeconds;
        try {
            expireInSeconds = Integer.parseInt(expire);
        } catch (NumberFormatException e) {
            throw new WebApplicationException(Response.status(
                    Response.Status.BAD_REQUEST).build());
        }

        String uuid = UUID.randomUUID().toString();
        while (ZooKeeperService.isConnected(contextPath, uuid)) {
            uuid = UUID.randomUUID().toString();
        }

        // establish the connection to the ZooKeeper cluster
        try {
            ZooKeeperService.getClient(contextPath, uuid, expireInSeconds);
        } catch (IOException e) {
            LOG.error("Failed while trying to create a new session", e);

            throw new WebApplicationException(Response.status(
                    Response.Status.INTERNAL_SERVER_ERROR).build());
        }

        URI uri = ui.getAbsolutePathBuilder().path(uuid).build();
        return Response.created(uri).entity(
                new JSONWithPadding(new ZSession(uuid, uri.toString())))
                .build();
    }

    @DELETE
    @Produces( { MediaType.APPLICATION_JSON, "application/javascript",
            MediaType.APPLICATION_XML, MediaType.APPLICATION_OCTET_STREAM })
    public void deleteSession(@PathParam("session") String session,
            @Context UriInfo ui) {
        ZooKeeperService.close(contextPath, session);
    }

    private static void throwNotFound(String session, UriInfo ui)
            throws WebApplicationException {
        throw new WebApplicationException(Response.status(
                Response.Status.NOT_FOUND).entity(
                new ZError(ui.getRequestUri().toString(), session
                        + " not found")).build());
    }

}
