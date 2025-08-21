/*
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

package org.apache.zookeeper.common;

@SuppressWarnings("serial")
public class X509Exception extends Exception {

    public X509Exception(String message) {
        super(message);
    }

    public X509Exception(Throwable cause) {
        super(cause);
    }

    public X509Exception(String message, Throwable cause) {
        super(message, cause);
    }

    public static class KeyManagerException extends X509Exception {

        public KeyManagerException(String message) {
            super(message);
        }

        public KeyManagerException(Throwable cause) {
            super(cause);
        }

    }

    public static class TrustManagerException extends X509Exception {

        public TrustManagerException(String message) {
            super(message);
        }

        public TrustManagerException(Throwable cause) {
            super(cause);
        }

    }

    public static class SSLContextException extends X509Exception {

        public SSLContextException(String message) {
            super(message);
        }

        public SSLContextException(Throwable cause) {
            super(cause);
        }

        public SSLContextException(String message, Throwable cause) {
            super(message, cause);
        }

    }

}
