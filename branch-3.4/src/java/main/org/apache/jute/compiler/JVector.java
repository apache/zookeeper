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
public class JVector extends JCompType {
    
    static private int level = 0;
    
    static private String getId(String id) { return id+getLevel(); }
    
    static private String getLevel() { return Integer.toString(level); }
    
    static private void incrLevel() { level++; }
    
    static private void decrLevel() { level--; }
    
    private JType mElement;
    
    /** Creates a new instance of JVector */
    public JVector(JType t) {
        super("struct " + extractVectorName(t), " ::std::vector<"+t.getCppType()+">", "System.Collections.Generic.List<" + t.getCsharpType() + ">", "java.util.List<" + t.getJavaType() + ">", "Vector",
                "System.Collections.Generic.List<" + t.getCsharpType() + ">", "java.util.ArrayList<" + t.getJavaType() + ">");
        mElement = t;
    }
    
    public String getSignature() {
        return "[" + mElement.getSignature() + "]";
    }
    
    public String genJavaCompareTo(String fname) {
        return "    throw new UnsupportedOperationException(\"comparing "
            + fname + " is unimplemented\");\n";
    }
    
    public String genJavaReadWrapper(String fname, String tag, boolean decl) {
        StringBuilder ret = new StringBuilder("");
        if (decl) {
            ret.append("      java.util.List "+fname+";\n");
        }
        ret.append("    {\n");
        incrLevel();
        ret.append("      Index "+getId("vidx")+" = a_.startVector(\""+tag+"\");\n");
        ret.append("      if ("+getId("vidx")+"!= null) {");
        ret.append("          "+fname+"=new java.util.ArrayList<"+ mElement.getJavaType() + ">();\n");
        ret.append("          for (; !"+getId("vidx")+".done(); "+getId("vidx")+".incr()) {\n");
        ret.append(mElement.genJavaReadWrapper(getId("e"), getId("e"), true));
        ret.append("            "+fname+".add("+getId("e")+");\n");
        ret.append("          }\n");
        ret.append("      }\n");
        ret.append("    a_.endVector(\""+tag+"\");\n");
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
        ret.append("      a_.startVector("+fname+",\""+tag+"\");\n");
        ret.append("      if ("+fname+"!= null) {");
        ret.append("          int "+getId("len")+" = "+fname+".size();\n");
        ret.append("          for(int "+getId("vidx")+" = 0; "+getId("vidx")+"<"+getId("len")+"; "+getId("vidx")+"++) {\n");
        ret.append("            "+mElement.getJavaWrapperType()+" "+getId("e")+" = ("+mElement.getJavaWrapperType()+") "+fname+".get("+getId("vidx")+");\n");
        ret.append(mElement.genJavaWriteWrapper(getId("e"), getId("e")));
        ret.append("          }\n");
        ret.append("      }\n");
        ret.append("      a_.endVector("+fname+",\""+tag+"\");\n");
        ret.append("    }\n");
        decrLevel();
        return ret.toString();
    }
    
    public String genJavaWriteMethod(String fname, String tag) {
        return genJavaWriteWrapper(fname, tag);
    }
    
    public JType getElementType() {
    	return mElement;
    }

    public String genCsharpWriteWrapper(String fname, String tag) {
        StringBuilder ret = new StringBuilder("    {\n");
        incrLevel();
        ret.append("      a_.StartVector("+capitalize(fname)+",\""+tag+"\");\n");
        ret.append("      if ("+capitalize(fname)+"!= null) {");
        ret.append("          int "+getId("len")+" = "+capitalize(fname)+".Count;\n");
        ret.append("          for(int "+getId("vidx")+" = 0; "+getId("vidx")+"<"+getId("len")+"; "+getId("vidx")+"++) {\n");
        ret.append("            "+mElement.getCsharpWrapperType()+" "+getId("e")+" = ("+mElement.getCsharpWrapperType()+") "+capitalize(fname)+"["+getId("vidx")+"];\n");
        ret.append(mElement.genCsharpWriteWrapper(getId("e"), getId("e")));
        ret.append("          }\n");
        ret.append("      }\n");
        ret.append("      a_.EndVector("+capitalize(fname)+",\""+tag+"\");\n");
        ret.append("    }\n");
        decrLevel();
        return ret.toString();
    }

    String genCsharpWriteMethod(String fname, String tag) {
        return genCsharpWriteWrapper(fname, tag);
    }

    public String genCsharpReadWrapper(String fname, String tag, boolean decl) {
        StringBuilder ret = new StringBuilder("");
        if (decl) {
            ret.append("      System.Collections.Generic.List<" + mElement.getCsharpType()+ "> "+capitalize(fname)+";\n");
        }
        ret.append("    {\n");
        incrLevel();
        ret.append("      IIndex "+getId("vidx")+" = a_.StartVector(\""+tag+"\");\n");
        ret.append("      if ("+getId("vidx")+"!= null) {");
        ret.append("          "+capitalize(fname)+"=new System.Collections.Generic.List<"+ mElement.getCsharpType() + ">();\n");
        ret.append("          for (; !"+getId("vidx")+".Done(); "+getId("vidx")+".Incr()) {\n");
        ret.append(mElement.genCsharpReadWrapper(getId("e"), getId("e"), true));
        ret.append("            "+capitalize(fname)+".Add("+getId("e")+");\n");
        ret.append("          }\n");
        ret.append("      }\n");
        ret.append("    a_.EndVector(\""+tag+"\");\n");
        decrLevel();
        ret.append("    }\n");
        return ret.toString();
    }
    
    String genCsharpReadMethod(String fname, String tag) {
        return genCsharpReadWrapper(fname, tag, false);
    }

    static public String extractVectorName(JType jvType) {
		return JRecord.extractMethodSuffix(jvType)+"_vector";
	}
}
