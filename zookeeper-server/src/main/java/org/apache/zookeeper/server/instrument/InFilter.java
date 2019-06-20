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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * InFilter performs IN operation on a list of
 * accepted values on specified trace field.
 *
 */
public class InFilter implements TraceFieldFilter {
    private static final Logger LOG = LoggerFactory.getLogger(InFilter.class);

    private final TraceField traceField;
    private final Set<Long> acceptedLongValues;
    private final Set<String> acceptedTextValues;

    public InFilter(TraceField traceField, List<String> values) {
        this.traceField = traceField;
        if (this.traceField.getType() == TraceField.Types.STRING) {
            this.acceptedTextValues = setTextValues(values);
            this.acceptedLongValues = new HashSet<>(0);
        } else if (this.traceField.getType() == TraceField.Types.INTEGER) {
            this.acceptedLongValues = setLongValues(values);
            this.acceptedTextValues = new HashSet<>(0);
        } else {
            // no support for types other than text and number.
            this.acceptedLongValues = new HashSet<>(0);
            this.acceptedTextValues = new HashSet<>(0);
        }
    }

    @Override
    public boolean accept(long value) {
        return acceptedLongValues.isEmpty() || acceptedLongValues.contains(value);
    }

    @Override
    public boolean accept(String value) {
        return acceptedTextValues.isEmpty() || acceptedTextValues.contains(value);
    }

    private Set<Long> setLongValues(List<String> values) {
        if (values != null && values.size() > 0) {
            Set<Long> longValues = new HashSet<>(values.size());
            for (String v: values) {
                try {
                    longValues.add(Long.parseLong(v));
                } catch (NumberFormatException nfe) {
                    LOG.warn("Number {} is in wrong format", v, nfe);
                }
            }
            return longValues;
        }
        return new HashSet<>(0);
    }

    private Set<String> setTextValues(List<String> values) {
        if (values != null && values.size() > 0) {
            Set<String> textValues = new HashSet<>(values.size());
            textValues.addAll(values);
            return textValues;
        }
        return new HashSet<>(0);
    }
}
