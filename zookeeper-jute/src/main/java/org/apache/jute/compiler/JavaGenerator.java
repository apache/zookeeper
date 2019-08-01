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
import java.util.Iterator;
import java.util.List;

/**
 * Java Code generator front-end for Hadoop record I/O.
 */
class JavaGenerator {
    private List<JRecord> mRecList;
    private final File outputDirectory;

    /**
     * Creates a new instance of JavaGenerator.
     *
     * @param name            possibly full pathname to the file
     * @param incl            included files (as JFile)
     * @param records         List of records defined within this file
     * @param outputDirectory
     */
    JavaGenerator(String name, List<JFile> incl,
                  List<JRecord> records, File outputDirectory) {
        mRecList = records;
        this.outputDirectory = outputDirectory;
    }

    /**
     * Generate Java code for records. This method is only a front-end to
     * JRecord, since one file is generated for each record.
     */
    void genCode() throws IOException {
        for (Iterator<JRecord> i = mRecList.iterator(); i.hasNext(); ) {
            JRecord rec = i.next();
            rec.genJavaCode(outputDirectory);
        }
    }
}
