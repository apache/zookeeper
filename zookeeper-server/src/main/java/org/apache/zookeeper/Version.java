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

package org.apache.zookeeper;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class Version implements org.apache.zookeeper.version.Info {

    /*
     * Since the SVN to Git port this field doesn't return the revision anymore
     * In version 3.5.6, 3.5.7 and 3.6.0 this function is removed by accident.
     * From version 3.5.8+ and 3.6.1+ it is restored for backward compatibility, but will be removed later
     * @deprecated deprecated in 3.5.5, use @see {@link #getRevisionHash()} instead
     * @return the default value -1
     */
    @Deprecated
    public static int getRevision() {
        return REVISION;
    }

    public static String getRevisionHash() {
        return REVISION_HASH;
    }

    public static String getBuildDate() {
        return BUILD_DATE;
    }

    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NULL_VALUE",
            justification = "Missing QUALIFIER causes redundant null-check")
    public static String getVersion() {
        return MAJOR + "." + MINOR + "." + MICRO
            + (QUALIFIER == null ? "" : "-" + QUALIFIER);
    }

    public static String getVersionRevision() {
        return getVersion() + "-" + getRevisionHash();
    }

    public static String getFullVersion() {
        return getVersionRevision() + ", built on " + getBuildDate();
    }

    public static void printUsage() {
        System.out
                .print("Usage:\tjava -cp ... org.apache.zookeeper.Version "
                        + "[--full | --short | --revision],\n\tPrints --full version "
                        + "info if no arg specified.");
        System.exit(1);
    }

    /**
     * Prints the current version, revision and build date to the standard out.
     * 
     * @param args
     *            <ul>
     *            <li> --short - prints a short version string "1.2.3"
     *            <li> --revision - prints a short version string with the Git
     *            repository revision "1.2.3-${revision_hash}"
     *            <li> --full - prints the revision and the build date
     *            </ul>
     */
    public static void main(String[] args) {
        if (args.length > 1) {
            printUsage();
        }
        if (args.length == 0 || (args.length == 1 && args[0].equals("--full"))) {
            System.out.println(getFullVersion());
            System.exit(0);
        }
        if (args[0].equals("--short"))
            System.out.println(getVersion());
        else if (args[0].equals("--revision"))
            System.out.println(getVersionRevision());
        else
            printUsage();
        System.exit(0);
    }
}
