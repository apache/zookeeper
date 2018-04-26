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

package org.apache.zookeeper.server.admin;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.PropertyNamingStrategy;
import org.codehaus.jackson.map.SerializationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonOutputter implements CommandOutputter {
    static final Logger LOG = LoggerFactory.getLogger(JsonOutputter.class);

    public static final String ERROR_RESPONSE = "{\"error\": \"Exception writing command response to JSON\"}";

    private ObjectMapper mapper;

    public JsonOutputter() {
        mapper = new ObjectMapper();
        mapper.configure(SerializationConfig.Feature.WRITE_ENUMS_USING_TO_STRING, true);
        mapper.configure(SerializationConfig.Feature.INDENT_OUTPUT, true);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategy.CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES);
    }

    @Override
    public String getContentType() {
        return "application/json";
    }

    @Override
    public void output(CommandResponse response, PrintWriter pw) {
        try {
            mapper.writeValue(pw, response.toMap());
        } catch (JsonGenerationException e) {
            LOG.warn("Exception writing command response to JSON:", e);
            pw.write(ERROR_RESPONSE);
        } catch (JsonMappingException e) {
            LOG.warn("Exception writing command response to JSON:", e);
            pw.write(ERROR_RESPONSE);
        } catch (IOException e) {
            LOG.warn("Exception writing command response to JSON:", e);
            pw.write(ERROR_RESPONSE);
        }
    }

}
