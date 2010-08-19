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
 * This is a Hedwig client side exception when the PubSubRequest is being
 * redirected to a server where the request has already been sent to previously. 
 * To avoid having a cyclical redirect loop, this condition is checked for
 * and this exception will be thrown to the client caller. 
 */
public class ServerRedirectLoopException extends Exception {

    private static final long serialVersionUID = 98723508723152897L;

    public ServerRedirectLoopException(String message) {
        super(message);
    }

    public ServerRedirectLoopException(String message, Throwable t) {
        super(message, t);
    }

}
