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
public class JMap extends JCompType {
   
    static private int level = 0;
    
    static private String getLevel() { return Integer.toString(level); }
    
    static private void incrLevel() { level++; }
    
    static private void decrLevel() { level--; }
    
    static private String getId(String id) { return id+getLevel(); }
    
    private JType mKey;
    private JType mValue;
    
    /** Creates a new instance of JMap */
    public JMap(JType t1, JType t2) {
        super("#error", " ::std::map<"+t1.getCppType()+","+t2.getCppType()+">",
                "System.Collections.Generic.SortedDictionary<string, string>", "java.util.TreeMap", "Map", "System.Collections.Generic.SortedDictionary<string, string>", "java.util.TreeMap");
        mKey = t1;
        mValue = t2;
    }
    
    public String getSignature() {
        return "{" + mKey.getSignature() + mValue.getSignature() +"}";
    }
    
    public String genJavaCompareTo(String fname) {
        return "    throw new UnsupportedOperationException(\"comparing "
            + fname + " is unimplemented\");\n";
    }
    
    public String genJavaReadWrapper(String fname, String tag, boolean decl) {
        StringBuilder ret = new StringBuilder("");
        if (decl) {
            ret.append("    java.util.TreeMap "+fname+";\n");
        }
        ret.append("    {\n");
        incrLevel();
        ret.append("      org.apache.jute.Index "+getId("midx")+" = a_.startMap(\""+tag+"\");\n");
        ret.append("      "+fname+"=new java.util.TreeMap();\n");
        ret.append("      for (; !"+getId("midx")+".done(); "+getId("midx")+".incr()) {\n");
        ret.append(mKey.genJavaReadWrapper(getId("k"),getId("k"),true));
        ret.append(mValue.genJavaReadWrapper(getId("v"),getId("v"),true));
        ret.append("        "+fname+".put("+getId("k")+","+getId("v")+");\n");
        ret.append("      }\n");
        ret.append("    a_.endMap(\""+tag+"\");\n");
        decrLevel();
        ret.append("    }\n");
        return ret.toString();
    }
    
    public String genJavaReadMethod(String fname, String tag) {
        return genJavaReadWrapper(fname, tag, false);
    }
    
    public String genJavaWriteWrapper(String fname, String tag) {
        StringBuilder ret = new StringBuilder("    {\n");
        incrLevel();
        ret.append("      a_.startMap("+fname+",\""+tag+"\");\n");
        ret.append("      java.util.Set "+getId("es")+" = "+fname+".entrySet();\n");
        ret.append("      for(java.util.Iterator "+getId("midx")+" = "+getId("es")+".iterator(); "+getId("midx")+".hasNext(); ) {\n");
        ret.append("        java.util.Map.Entry "+getId("me")+" = (java.util.Map.Entry) "+getId("midx")+".next();\n");
        ret.append("        "+mKey.getJavaWrapperType()+" "+getId("k")+" = ("+mKey.getJavaWrapperType()+") "+getId("me")+".getKey();\n");
        ret.append("        "+mValue.getJavaWrapperType()+" "+getId("v")+" = ("+mValue.getJavaWrapperType()+") "+getId("me")+".getValue();\n");
        ret.append(mKey.genJavaWriteWrapper(getId("k"),getId("k")));
        ret.append(mValue.genJavaWriteWrapper(getId("v"),getId("v")));
        ret.append("      }\n");
        ret.append("      a_.endMap("+fname+",\""+tag+"\");\n");
        ret.append("    }\n");
        decrLevel();
        return ret.toString();
    }
    
    public String genJavaWriteMethod(String fname, String tag) {
        return genJavaWriteWrapper(fname, tag);
    }

    public String genCsharpWriteWrapper(String fname, int tag) {
        StringBuilder ret = new StringBuilder("    {\n");
        incrLevel();
        ret.append("      a_.StartMap("+fname+",\""+tag+"\");\n");
        ret.append("      java.util.Set "+getId("es")+" = "+fname+".entrySet();\n");
        ret.append("      for(java.util.Iterator "+getId("midx")+" = "+getId("es")+".iterator(); "+getId("midx")+".hasNext(); ) {\n");
        ret.append("        java.util.Map.Entry "+getId("me")+" = (java.util.Map.Entry) "+getId("midx")+".next();\n");
        ret.append("        "+mKey.getCsharpWrapperType()+" "+getId("k")+" = ("+mKey.getCsharpWrapperType()+") "+getId("me")+".getKey();\n");
        ret.append("        "+mValue.getCsharpWrapperType()+" "+getId("v")+" = ("+mValue.getCsharpWrapperType()+") "+getId("me")+".getValue();\n");
        ret.append(mKey.genCsharpWriteWrapper(getId("k"),getId("k")));
        ret.append(mValue.genCsharpWriteWrapper(getId("v"),getId("v")));
        ret.append("      }\n");
        ret.append("      a_.EndMap("+fname+",\""+tag+"\");\n");
        ret.append("    }\n");
        decrLevel();
        return ret.toString();
    }

    String genCsharpWriteMethod(String fname, int tag) {
        return genCsharpWriteWrapper(fname, tag);
    }

    public String genCsharpReadWrapper(String fname, int tag, boolean decl) {
        StringBuilder ret = new StringBuilder("");
        if (decl) {
            ret.append("    System.Collections.SortedDictionary<string,string> "+capitalize(fname)+";\n");
        }
        ret.append("    {\n");
        incrLevel();
        ret.append("      Org.Apache.Jute.IIndex "+getId("midx")+" = a_.StartMap(\""+tag+"\");\n");
        ret.append("      "+fname+"= new System.Collections.SortedDictionary<string,string>();\n");
        ret.append("      for (; !"+getId("midx")+".done(); "+getId("midx")+".incr()) {\n");
        ret.append(mKey.genCsharpReadWrapper(getId("k"),getId("k"),true));
        ret.append(mValue.genCsharpReadWrapper(getId("v"),getId("v"),true));
        ret.append("        "+fname+".Add("+getId("k")+","+getId("v")+");\n");
        ret.append("      }\n");
        ret.append("    a_.EndMap(\""+tag+"\");\n");
        decrLevel();
        ret.append("    }\n");
        return ret.toString();
    }



    String genCsharpReadMethod(String fname, int tag) {
        return genCsharpReadWrapper(fname, tag, false);
    }
}
