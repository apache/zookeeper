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
package org.apache.hedwig.client.exceptions;

/**
 * This is a Hedwig client side exception when there have been too many server
 * redirects during a publish/subscribe call. We only allow a certain number of
 * server redirects to find the topic master. If we have exceeded this
 * configured amount, the publish/subscribe will fail with this exception.
 * 
 */
public class TooManyServerRedirectsException extends Exception {

    private static final long serialVersionUID = 2341192937965635310L;

    public TooManyServerRedirectsException(String message) {
        super(message);
    }

    public TooManyServerRedirectsException(String message, Throwable t) {
        super(message, t);
    }

}
