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

package org.apache.zookeeper.server.jersey.filters;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.zookeeper.server.jersey.cfg.Credentials;

import com.sun.jersey.core.util.Base64;

public class HTTPBasicAuth implements Filter {

    private Credentials credentials;

    public HTTPBasicAuth(Credentials c) {
       credentials = c;
    }

    @Override
    public void doFilter(ServletRequest req0, ServletResponse resp0,
            FilterChain chain) throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req0;
        HttpServletResponse response = (HttpServletResponse) resp0;

        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            String c[] = parseAuthorization(authorization);
            if (c != null && credentials.containsKey(c[0])
                    && credentials.get(c[0]).equals(c[1])) {
                chain.doFilter(request, response);
                return;
            }
        }

        response.setHeader("WWW-Authenticate", "Basic realm=\"Restricted\"");
        response.sendError(401);
    }

    private String[] parseAuthorization(String authorization) {
        String parts[] = authorization.split(" ");
        if (parts.length == 2 && parts[0].equalsIgnoreCase("Basic")) {
            String userPass = Base64.base64Decode(parts[1]);

            int p = userPass.indexOf(":");
            if (p != -1) {
                return new String[] { userPass.substring(0, p),
                        userPass.substring(p + 1) };
            }
        }
        return null;
    }

    @Override
    public void init(FilterConfig arg0) throws ServletException {
    }

    @Override
    public void destroy() {
    }

}
