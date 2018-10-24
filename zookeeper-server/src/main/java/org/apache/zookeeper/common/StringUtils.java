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
import java.util.List;
import java.util.Collections;

public class StringUtils {

    private StringUtils() {/** non instantiable and non inheritable **/}

    /**
     * This method returns an immutable List<String>, but different from String's split()
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
     * This method takes a List<String> and a delimiter and joins the strings
     * into a single string, where the original strings are separated using 
     * the given delimiter.
     *
     */ 
    public static String joinStrings(List<String> list, String delim)
    {
        if (list == null)
            return null;

       StringBuilder builder = new StringBuilder(list.get(0));
        for (String s : list.subList(1, list.size())) {
            builder.append(delim).append(s);
        }

        return builder.toString();
    }
}
