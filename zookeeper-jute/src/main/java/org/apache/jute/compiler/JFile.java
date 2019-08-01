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

package org.apache.jute.compiler;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Container for the Hadoop Record DDL.
 * The main components of the file are filename, list of included files,
 * and records defined in that file.
 */
public class JFile {

    private String mName;
    private List<JFile> mInclFiles;
    private List<JRecord> mRecords;

    /**
     * Creates a new instance of JFile.
     *
     * @param name      possibly full pathname to the file
     * @param inclFiles included files (as JFile)
     * @param recList   List of records defined within this file
     */
    public JFile(String name, ArrayList<JFile> inclFiles,
                 ArrayList<JRecord> recList) {
        mName = name;
        mInclFiles = inclFiles;
        mRecords = recList;
    }

    /**
     * Strip the other pathname components and return the basename.
     */
    String getName() {
        int idx = mName.lastIndexOf('/');
        return (idx > 0) ? mName.substring(idx) : mName;
    }

    /**
     * Generate record code in given language. Language should be all
     * lowercase.
     *
     * @param outputDirectory
     */
    public void genCode(String language, File outputDirectory)
            throws IOException {
        if ("c++".equals(language)) {
            CppGenerator gen = new CppGenerator(mName, mInclFiles, mRecords,
                    outputDirectory);
            gen.genCode();
        } else if ("java".equals(language)) {
            JavaGenerator gen = new JavaGenerator(mName, mInclFiles, mRecords,
                    outputDirectory);
            gen.genCode();
        } else if ("c".equals(language)) {
            CGenerator gen = new CGenerator(mName, mInclFiles, mRecords,
                    outputDirectory);
            gen.genCode();
        } else if ("csharp".equals(language)) {
            CSharpGenerator gen = new CSharpGenerator(mName, mInclFiles, mRecords,
                    outputDirectory);
            gen.genCode();
        } else {
            throw new IOException("Cannnot recognize language:" + language);
        }
    }
}
