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
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.jersey.ZooKeeperService;
import org.apache.zookeeper.server.jersey.jaxb.ZChildren;
import org.apache.zookeeper.server.jersey.jaxb.ZChildrenJSON;
import org.apache.zookeeper.server.jersey.jaxb.ZError;
import org.apache.zookeeper.server.jersey.jaxb.ZPath;
import org.apache.zookeeper.server.jersey.jaxb.ZStat;

import com.sun.jersey.api.json.JSONWithPadding;

/**
 * Version 1 implementation of the ZooKeeper REST specification.
 */
// TODO test octet fully
@Path("znodes/v1{path: /.*}")
public class ZNodeResource {
    private final ZooKeeper zk;

    public ZNodeResource(@DefaultValue("") @QueryParam("session") String session,
            @Context UriInfo ui,
            @Context HttpServletRequest request
            )
            throws IOException {

        String contextPath = request.getContextPath();
        if (contextPath.equals("")) {
            contextPath = "/";
        }
        if (session.equals("")) {
            session = null;
        } else if (!ZooKeeperService.isConnected(contextPath, session)) {
            throw new WebApplicationException(Response.status(
                    Response.Status.UNAUTHORIZED).build());
        }
        zk = ZooKeeperService.getClient(contextPath, session);
    }

    private void ensurePathNotNull(String path) {
        if (path == null) {
            throw new IllegalArgumentException("Invalid path \"" + path + "\"");
        }
    }

    @HEAD
    @Produces( { MediaType.APPLICATION_JSON, "application/javascript",
            MediaType.APPLICATION_XML })
    public Response existsZNode(@PathParam("path") String path,
            @Context UriInfo ui) throws InterruptedException, KeeperException {
        Stat stat = zk.exists(path, false);
        if (stat == null) {
            throwNotFound(path, ui);
        }
        return Response.status(Response.Status.OK).build();
    }

    @HEAD
    @Produces( { MediaType.APPLICATION_OCTET_STREAM })
    public Response existsZNodeAsOctet(@PathParam("path") String path,
            @Context UriInfo ui) throws InterruptedException, KeeperException {
        Stat stat = zk.exists(path, false);
        if (stat == null) {
            throwNotFound(path, ui);
        }
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    /*
     * getZNodeList and getZNodeListJSON are bogus - but necessary.
     * Unfortunately Jersey 1.0.3 is unable to render both xml and json properly
     * in the case where a object contains a list/array. It's impossible to get
     * it to render properly for both. As a result we need to split into two
     * jaxb classes.
     */

    @GET
    @Produces( { MediaType.APPLICATION_JSON, "application/javascript" })
    public Response getZNodeListJSON(
            @PathParam("path") String path,
            @QueryParam("callback") String callback,
            @DefaultValue("data") @QueryParam("view") String view,
            @DefaultValue("base64") @QueryParam("dataformat") String dataformat,
            @Context UriInfo ui) throws InterruptedException, KeeperException {
        return getZNodeList(true, path, callback, view, dataformat, ui);
    }

    @GET
    @Produces(MediaType.APPLICATION_XML)
    public Response getZNodeList(
            @PathParam("path") String path,
            @QueryParam("callback") String callback,
            @DefaultValue("data") @QueryParam("view") String view,
            @DefaultValue("base64") @QueryParam("dataformat") String dataformat,
            @Context UriInfo ui) throws InterruptedException, KeeperException {
        return getZNodeList(false, path, callback, view, dataformat, ui);
    }

    private Response getZNodeList(boolean json, String path, String callback,
            String view, String dataformat, UriInfo ui)
            throws InterruptedException, KeeperException {
        ensurePathNotNull(path);

        if (view.equals("children")) {
            List<String> children = new ArrayList<String>();
            for (String child : zk.getChildren(path, false)) {
                children.add(child);
            }

            Object child;
            String childTemplate = ui.getAbsolutePath().toString();
            if (!childTemplate.endsWith("/")) {
                childTemplate += "/";
            }
            childTemplate += "{child}";
            if (json) {
                child = new ZChildrenJSON(path,
                        ui.getAbsolutePath().toString(), childTemplate,
                        children);
            } else {
                child = new ZChildren(path, ui.getAbsolutePath().toString(),
                        childTemplate, children);
            }
            return Response.status(Response.Status.OK).entity(
                    new JSONWithPadding(child, callback)).build();
        } else {
            Stat stat = new Stat();
            byte[] data = zk.getData(path, false, stat);

            byte[] data64;
            String dataUtf8;
            if (data == null) {
                data64 = null;
                dataUtf8 = null;
            } else if (!dataformat.equals("utf8")) {
                data64 = data;
                dataUtf8 = null;
            } else {
                data64 = null;
                dataUtf8 = new String(data);
            }
            ZStat zstat = new ZStat(path, ui.getAbsolutePath().toString(),
                    data64, dataUtf8, stat.getCzxid(), stat.getMzxid(), stat
                            .getCtime(), stat.getMtime(), stat.getVersion(),
                    stat.getCversion(), stat.getAversion(), stat
                            .getEphemeralOwner(), stat.getDataLength(), stat
                            .getNumChildren(), stat.getPzxid());

            return Response.status(Response.Status.OK).entity(
                    new JSONWithPadding(zstat, callback)).build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response getZNodeListAsOctet(@PathParam("path") String path)
            throws InterruptedException, KeeperException {
        ensurePathNotNull(path);

        Stat stat = new Stat();
        byte[] data = zk.getData(path, false, stat);

        if (data == null) {
            return Response.status(Response.Status.NO_CONTENT).build();
        } else {
            return Response.status(Response.Status.OK).entity(data).build();
        }
    }

    @PUT
    @Produces( { MediaType.APPLICATION_JSON, "application/javascript",
            MediaType.APPLICATION_XML })
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response setZNode(
            @PathParam("path") String path,
            @QueryParam("callback") String callback,
            @DefaultValue("-1") @QueryParam("version") String versionParam,
            @DefaultValue("base64") @QueryParam("dataformat") String dataformat,
            @DefaultValue("false") @QueryParam("null") String setNull,
            @Context UriInfo ui, byte[] data) throws InterruptedException,
            KeeperException {
        ensurePathNotNull(path);

        int version;
        try {
            version = Integer.parseInt(versionParam);
        } catch (NumberFormatException e) {
            throw new WebApplicationException(Response.status(
                    Response.Status.BAD_REQUEST).entity(
                    new ZError(ui.getRequestUri().toString(), path
                            + " bad version " + versionParam)).build());
        }

        if (setNull.equals("true")) {
            data = null;
        }

        Stat stat = zk.setData(path, data, version);

        ZStat zstat = new ZStat(path, ui.getAbsolutePath().toString(), null,
                null, stat.getCzxid(), stat.getMzxid(), stat.getCtime(), stat
                        .getMtime(), stat.getVersion(), stat.getCversion(),
                stat.getAversion(), stat.getEphemeralOwner(), stat
                        .getDataLength(), stat.getNumChildren(), stat
                        .getPzxid());

        return Response.status(Response.Status.OK).entity(
                new JSONWithPadding(zstat, callback)).build();
    }

    @PUT
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public void setZNodeAsOctet(@PathParam("path") String path,
            @DefaultValue("-1") @QueryParam("version") String versionParam,
            @DefaultValue("false") @QueryParam("null") String setNull,
            @Context UriInfo ui, byte[] data) throws InterruptedException,
            KeeperException {
        ensurePathNotNull(path);

        int version;
        try {
            version = Integer.parseInt(versionParam);
        } catch (NumberFormatException e) {
            throw new WebApplicationException(Response.status(
                    Response.Status.BAD_REQUEST).entity(
                    new ZError(ui.getRequestUri().toString(), path
                            + " bad version " + versionParam)).build());
        }

        if (setNull.equals("true")) {
            data = null;
        }

        zk.setData(path, data, version);
    }

    @POST
    @Produces( { MediaType.APPLICATION_JSON, "application/javascript",
            MediaType.APPLICATION_XML })
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response createZNode(
            @PathParam("path") String path,
            @QueryParam("callback") String callback,
            @DefaultValue("create") @QueryParam("op") String op,
            @QueryParam("name") String name,
            @DefaultValue("base64") @QueryParam("dataformat") String dataformat,
            @DefaultValue("false") @QueryParam("null") String setNull,
            @DefaultValue("false") @QueryParam("sequence") String sequence,
            @DefaultValue("false") @QueryParam("ephemeral") String ephemeral,
            @Context UriInfo ui, byte[] data) throws InterruptedException,
            KeeperException {
        ensurePathNotNull(path);

        if (path.equals("/")) {
            path += name;
        } else {
            path += "/" + name;
        }

        if (!op.equals("create")) {
            throw new WebApplicationException(Response.status(
                    Response.Status.BAD_REQUEST).entity(
                    new ZError(ui.getRequestUri().toString(), path
                            + " bad operaton " + op)).build());
        }

        if (setNull.equals("true")) {
            data = null;
        }

        CreateMode createMode;
        if (sequence.equals("true")) {
            if (ephemeral.equals("false")) {
                createMode = CreateMode.PERSISTENT_SEQUENTIAL;
            } else {
                createMode = CreateMode.EPHEMERAL_SEQUENTIAL;
            }
        } else if (ephemeral.equals("false")) {
            createMode = CreateMode.PERSISTENT;
        } else {
            createMode = CreateMode.EPHEMERAL;
        }

        String newPath = zk.create(path, data, Ids.OPEN_ACL_UNSAFE, createMode);

        URI uri = ui.getAbsolutePathBuilder().path(newPath).build();

        return Response.created(uri).entity(
                new JSONWithPadding(new ZPath(newPath, ui.getAbsolutePath()
                        .toString()))).build();
    }

    @POST
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response createZNodeAsOctet(@PathParam("path") String path,
            @DefaultValue("create") @QueryParam("op") String op,
            @QueryParam("name") String name,
            @DefaultValue("false") @QueryParam("null") String setNull,
            @DefaultValue("false") @QueryParam("sequence") String sequence,
            @Context UriInfo ui, byte[] data) throws InterruptedException,
            KeeperException {
        ensurePathNotNull(path);

        if (path.equals("/")) {
            path += name;
        } else {
            path += "/" + name;
        }

        if (!op.equals("create")) {
            throw new WebApplicationException(Response.status(
                    Response.Status.BAD_REQUEST).entity(
                    new ZError(ui.getRequestUri().toString(), path
                            + " bad operaton " + op)).build());
        }

        if (setNull.equals("true")) {
            data = null;
        }

        CreateMode createMode;
        if (sequence.equals("true")) {
            createMode = CreateMode.PERSISTENT_SEQUENTIAL;
        } else {
            createMode = CreateMode.PERSISTENT;
        }

        String newPath = zk.create(path, data, Ids.OPEN_ACL_UNSAFE, createMode);

        URI uri = ui.getAbsolutePathBuilder().path(newPath).build();

        return Response.created(uri).entity(
                new ZPath(newPath, ui.getAbsolutePath().toString())).build();
    }

    @DELETE
    @Produces( { MediaType.APPLICATION_JSON, "application/javascript",
            MediaType.APPLICATION_XML, MediaType.APPLICATION_OCTET_STREAM })
    public void deleteZNode(@PathParam("path") String path,
            @DefaultValue("-1") @QueryParam("version") String versionParam,
            @Context UriInfo ui) throws InterruptedException, KeeperException {
        ensurePathNotNull(path);

        int version;
        try {
            version = Integer.parseInt(versionParam);
        } catch (NumberFormatException e) {
            throw new WebApplicationException(Response.status(
                    Response.Status.BAD_REQUEST).entity(
                    new ZError(ui.getRequestUri().toString(), path
                            + " bad version " + versionParam)).build());
        }

        zk.delete(path, version);
    }

    private static void throwNotFound(String path, UriInfo ui)
            throws WebApplicationException {
        throw new WebApplicationException(Response.status(
                Response.Status.NOT_FOUND).entity(
                new ZError(ui.getRequestUri().toString(), path + " not found"))
                .build());
    }

}
