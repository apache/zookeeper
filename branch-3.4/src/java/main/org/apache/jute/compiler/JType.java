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
 * Abstract Base class for all types supported by Hadoop Record I/O.
 * 
 */
abstract public class JType {
    
	private String mCName;
    private String mCppName;
    private String mCsharpName;
    private String mJavaName;
    protected String mMethodSuffix;
    private String mWrapper;
    private String mSharpWrapper;
    private String mUnwrapMethod;

    /**
     * Creates a new instance of JType
     */
    JType(String cname, String cppname, String csharpName, String javaname, String suffix, String wrapper, String csharpWrapper, String unwrap) {
    	mCName = cname;
        mCppName = cppname;
        mCsharpName = "Id".equals(csharpName) ? "ZKId" : csharpName;
        mJavaName = javaname;
        mMethodSuffix = suffix;
        mWrapper = wrapper;
        mSharpWrapper = csharpWrapper;
        mUnwrapMethod = unwrap;
    }
    
    abstract String getSignature();
    
    String genCppDecl(String fname) {
        return "  "+mCppName+" m"+fname+";\n"; 
    }
    
	String genCDecl(String name) {
		return "    " + mCName + " "+name+";\n"; 
	}

    public String genCsharpDecl(String name) {
        return "  private "+mCsharpName+" " +name+";\n";
    }

    String genJavaDecl (String fname) {
        return "  private "+mJavaName+" " +fname+";\n";
    }
    
    String genJavaConstructorParam (String fname) {
        return "        "+mJavaName+" "+fname;
    }
    
    String genCppGetSet(String fname, int fIdx) {
        String getFunc = "  virtual "+mCppName+" get"+fname+"() const {\n";
        getFunc += "    return m"+fname+";\n";
        getFunc += "  }\n";
        String setFunc = "  virtual void set"+fname+"("+mCppName+" m_) {\n";
        setFunc += "    m"+fname+"=m_; bs_.set("+fIdx+");\n";
        setFunc += "  }\n";
        return getFunc+setFunc;
    }

    String genCsharpGetSet(String fname, int fIdx) {
        String getFunc = "  public " + getCsharpType() + " " + capitalize(fname) + " { get; set; } ";
        return getFunc;
    }
    
    static String capitalize(String s) {
        return s.substring(0,1).toUpperCase()+s.substring(1);
    }
    String genJavaGetSet(String fname, int fIdx) {
        String getFunc = "  public "+mJavaName+" get"+capitalize(fname)+"() {\n";
        getFunc += "    return "+fname+";\n";
        getFunc += "  }\n";
        String setFunc = "  public void set"+capitalize(fname)+"("+mJavaName+" m_) {\n";
        setFunc += "    " + fname+"=m_;\n";
        setFunc += "  }\n";
        return getFunc+setFunc;
    }
    
    String getCType() {
    	return mCName;
    }
    String getCppType() {
        return mCppName;
    }
    
    String getCsharpType() {
        return mCsharpName;
    }

    String getJavaType() {
        return mJavaName;
    }
   
    String getJavaWrapperType() {
        return mWrapper;
    }

    String getCsharpWrapperType() {
        return mSharpWrapper;
    }
    
    String getMethodSuffix() {
        return mMethodSuffix;
    }
    
    String genJavaWriteMethod(String fname, String tag) {
        return "    a_.write"+mMethodSuffix+"("+fname+",\""+tag+"\");\n";
    }
    
    String genJavaReadMethod(String fname, String tag) {
        return "    "+fname+"=a_.read"+mMethodSuffix+"(\""+tag+"\");\n";
    }
    
    String genJavaReadWrapper(String fname, String tag, boolean decl) {
        String ret = "";
        if (decl) {
            ret = "    "+mWrapper+" "+fname+";\n";
        }
        return ret + "    "+fname+"=new "+mWrapper+"(a_.read"+mMethodSuffix+"(\""+tag+"\"));\n";
    }
    
    String genJavaWriteWrapper(String fname, String tag) {
        return "        a_.write"+mMethodSuffix+"("+fname+"."+mUnwrapMethod+"(),\""+tag+"\");\n";
    }
    
    String genJavaCompareTo(String fname) {
        return "    ret = ("+fname+" == peer."+fname+")? 0 :(("+fname+"<peer."+fname+")?-1:1);\n";
    }
    
    String genJavaEquals(String fname, String peer) {
        return "    ret = ("+fname+"=="+peer+");\n";
    }
    
    String genJavaHashCode(String fname) {
        return "    ret = (int)"+fname+";\n";
    }

    String genJavaConstructorSet(String fname, String name) {
        return "    this."+fname+"="+name+";\n";
    }

    String genCsharpWriteMethod(String fname, String tag) {
        return "    a_.Write"+mMethodSuffix+"("+capitalize(fname)+",\""+tag+"\");\n";
    }

    String genCsharpReadMethod(String fname, String tag) {
        return "    "+capitalize(fname)+"=a_.Read"+mMethodSuffix+"(\""+tag+"\");\n";
    }

    String genCsharpReadWrapper(String fname, String tag, boolean decl) {
        String ret = "";
        if (decl) {
            ret = "    "+mWrapper+" "+fname+";\n";
        }
        return ret + "    "+fname+"=a_.Read"+mMethodSuffix+"(\""+tag+"\");\n";
    }

    String genCsharpWriteWrapper(String fname, String tag) {
        if (mUnwrapMethod == null) return "        a_.Write"+mMethodSuffix+"("+fname+","+tag+");\n";
        return "        a_.Write"+mMethodSuffix+"("+fname+"."+mUnwrapMethod+"(),\""+tag+"\");\n";
    }

    String genCsharpCompareTo(String name) {
        return "    ret = ("+capitalize(name)+" == peer."+capitalize(name)+")? 0 :(("+capitalize(name)+"<peer."+capitalize(name)+")?-1:1);\n";
    }

    String genCsharpEquals(String name, String peer) {
        String[] peerSplit = peer.split("\\.");
        return "    ret = ("+capitalize(name)+"=="+peerSplit[0] + "." + capitalize(peerSplit[1]) + ");\n";
    }

    String genCsharpHashCode(String fname) {
        return "    ret = (int)"+capitalize(fname)+";\n";
    }

    String genCsharpConstructorSet(String mName, String fname) {
        return capitalize(fname)+"="+mName+";\n";
    }

    public String genCsharpConstructorParam(String fname) {
        return "  "+mCsharpName+" " +fname+"\n";
    }
}
