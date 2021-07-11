/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.cli;

import org.apache.commons.cli.Option;

/**
 * Replacement class for OptionBuilder without the static access issues.
 * This roughly mirrors the Option.Builder class available in commons-cli-1.3.SNAPSHOT
 */
public class OptionBuilder {
    private final String shortName;
    private String longName = null;
    private String description = null;
    private String argName = null;
    private Class<?> argType = null;
    private int args = 0;
    private boolean isRequired = false;

    public OptionBuilder(final String shortName) {
        this.shortName = shortName;
    }

    public OptionBuilder() {
        this(null);
    }

    public OptionBuilder longOpt(final String longName) {
        this.longName = longName;
        return this;
    }

    public OptionBuilder desc(final String description) {
        this.description = description;
        return this;
    }

    public OptionBuilder argName(final String argName) {
        this.argName = argName;
        return this;
    }

    public OptionBuilder type(final Class<?> type) {
        this.argType = type;
        return this;
    }

    public OptionBuilder hasArg() {
        this.args = 1;
        return this;
    }

    public OptionBuilder hasArgs() {
        this.args = Option.UNLIMITED_VALUES;
        return this;
    }

    public OptionBuilder required() {
        this.isRequired = true;
        return this;
    }

    public Option build() {
        Option o = new Option(shortName, longName, args != 0, description);

        o.setRequired(isRequired);

        if (args != 0) {
            o.setArgs(args);
        }

        if (argType != null) {
            o.setType(argType);
        }

        if (argName != null) {
            o.setArgName(argName);
        }

        return o;
    }
}
    
