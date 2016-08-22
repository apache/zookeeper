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
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;

import org.apache.zookeeper.server.jersey.jaxb.ZError;

/**
 * Tell Jersey how to format an octet response error message.
 */
@Produces(MediaType.APPLICATION_OCTET_STREAM)
@Provider
public class ZErrorWriter implements MessageBodyWriter<ZError> {

    public long getSize(ZError t, Class<?> type, Type genericType,
            Annotation[] annotations, MediaType mediaType)  {
        return -1;
    }

    public boolean isWriteable(Class<?> type, Type genericType,
            Annotation[] annotations, MediaType mediaType) {
        return ZError.class.isAssignableFrom(type);
    }

    public void writeTo(ZError t, Class<?> type, Type genericType,
            Annotation[] annotations, MediaType mediaType,
            MultivaluedMap<String, Object> httpHeaders,
            OutputStream os)
        throws IOException, WebApplicationException
    {
        PrintStream p = new PrintStream(os);
        p.print("Request " + t.request + " failed due to " + t.message);
        p.flush();
    }
}
