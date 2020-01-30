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

package org.apache.zookeeper.server.instrument;

/**
 * TraceFieldFilter filter on value of ({@link TraceField}.
 *
 */
public interface TraceFieldFilter {

    TraceFieldFilter NO_FILTER = new NoFilter();

    /**
     * @param value  numerical value to filter.
     * @return  true to accept and false to reject.
     */
    boolean accept(long value);

    /**
     * @param value  string value to filter.
     * @return  true to accept string and false to reject.
     */
    boolean accept(String value);

    final class NoFilter implements TraceFieldFilter {
        @Override
        public boolean accept(long value) {
            return true;
        }

        @Override
        public boolean accept(String value) {
            return true;
        }
    }
}
