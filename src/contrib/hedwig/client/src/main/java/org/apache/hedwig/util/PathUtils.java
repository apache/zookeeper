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
package org.apache.hedwig.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class PathUtils {

    /** Generate all prefixes for a path. "/a/b/c" -> ["/a","/a/b","/a/b/c"] */
    public static List<String> prefixes(String path) {
        List<String> prefixes = new ArrayList<String>();
        String prefix = "";
        for (String comp : path.split("/+")) {
            // Skip the first (empty) path component.
            if (!comp.equals("")) {
                prefix += "/" + comp;
                prefixes.add(prefix);
            }
        }
        return prefixes;
    }

    /** Return true iff prefix is a prefix of path. */
    public static boolean isPrefix(String prefix, String path) {
        String[] as = prefix.split("/+"), bs = path.split("/+");
        if (as.length > bs.length)
            return false;
        for (int i = 0; i < as.length; i++)
            if (!as[i].equals(bs[i]))
                return false;
        return true;
    }

    /** Like File.getParent but always uses the / separator. */
    public static String parent(String path) {
        return new File(path).getParent().replace("\\", "/");
    }

}
