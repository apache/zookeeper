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
 * This is a Hedwig client side exception when the local client wants to do
 * subscribe type of operations. Currently, to distinguish between local and hub
 * subscribers, the subscriberId will have a specific format.
 */
public class InvalidSubscriberIdException extends Exception {

    private static final long serialVersionUID = 873259807218723523L;

    public InvalidSubscriberIdException(String message) {
        super(message);
    }

    public InvalidSubscriberIdException(String message, Throwable t) {
        super(message, t);
    }

}
