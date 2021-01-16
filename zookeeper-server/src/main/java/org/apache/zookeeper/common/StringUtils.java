/* Licensed to the Apache Software Foundation (ASF) under one
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class StringUtils {

    private StringUtils() {/** non instantiable and non inheritable **/}

    /**
     * This method returns an immutable List&lt;String&gt;, but different from String's split()
     * it trims the results in the input String, and removes any empty string from
     * the resulting List.
     *
     */
    public static List<String> split(String value, String separator) {
        String[] splits = value.split(separator);
        List<String> results = new ArrayList<String>();
        for (int i = 0; i < splits.length; i++) {
            splits[i] = splits[i].trim();
            if (splits[i].length() > 0) {
                results.add(splits[i]);
            }
        }
        return Collections.unmodifiableList(results);
    }

    /**
     * This method takes a List&lt;String&gt; and a delimiter and joins the
     * strings into a single string, where the original strings are separated
     * using the given delimiter. This method is a null-safe version of
     * {@link String#join(CharSequence, Iterable)}
     *
     * <p>
     * Note that if an individual element is null, then "null" is added.
     * </p>
     * @param list a {@code List} that will have its elements joined together
     * @param delim a sequence of characters that is used to separate each of the
     *          elements in the resulting String
     * @return a new String that is composed from the elements argument or
     *         {@code null} if list is {@code null}
     * @throws NullPointerException if delim is {@code null}
     */
    public static String joinStrings(List<String> list, String delim) {
        Objects.requireNonNull(delim);
        return list == null ? null : String.join(delim, list);
    }

    /**
     * Returns true if the string is null or it does not contain any non space characters.
     * @param s the string
     * @return true if the string is null or it does not contain any non space characters.
     */
    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * <p>Checks if a String is empty ("") or null.</p>
     *
     * <pre>
     * StringUtils.isEmpty(null)      = true
     * StringUtils.isEmpty("")        = true
     * StringUtils.isEmpty(" ")       = false
     * StringUtils.isEmpty("bob")     = false
     * StringUtils.isEmpty("  bob  ") = false
     * </pre>
     *
     * @param str  the String to check, may be null
     * @return <code>true</code> if the String is empty or null
     */
    public static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }

}
