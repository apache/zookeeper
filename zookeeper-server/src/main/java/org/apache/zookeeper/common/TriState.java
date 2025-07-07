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

/**
 * For storing configuration parameters where want to distinguish a default case
 * in addition to true and false.
 */
public enum TriState {
    True, False, Default;

    public static TriState parse(String value) {
        if (value == null || value.equalsIgnoreCase("default")) {
            return TriState.Default;
        } else if (value.equalsIgnoreCase("true")) {
            return TriState.True;
        } else {
            return TriState.False;
        }
    }

    public boolean isTrue() {
        return this == TriState.True;
    }

    public boolean isFalse() {
        return this == TriState.False;
    }

    public boolean isDefault() {
        return this == TriState.Default;
    }

    public boolean isNotDefault() {
        return this != TriState.Default;
    }
}
