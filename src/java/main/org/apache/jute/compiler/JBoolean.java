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

package org.apache.jute.compiler;

/**
 *
 */
public class JBoolean extends JType {
    
    /** Creates a new instance of JBoolean */
    public JBoolean() {
        super("int32_t", "bool", "bool", "boolean", "Bool", "Boolean", "bool", "toBoolean");
    }
    
    public String getSignature() {
        return "z";
    }
    
    public String genJavaCompareTo(String fname) {
        return "    ret = ("+fname+" == peer."+fname+")? 0 : ("+fname+"?1:-1);\n";
    }
    
    public String genJavaHashCode(String fname) {
        return "     ret = ("+fname+")?0:1;\n";
    }

    String genCsharpHashCode(String fname) {
        return "     ret = ("+capitalize(fname)+")?0:1;\n";
    }

    String genCsharpCompareTo(String name) {
        return "    ret = ("+capitalize(name)+" == peer."+capitalize(name)+")? 0 : ("+capitalize(name)+"?1:-1);\n";
    }
}
