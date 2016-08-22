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
public class JField {
    private JType mType;
    private String mName;
    /**
     * Creates a new instance of JField
     */
    public JField(JType type, String name) {
        mType = type;
        mName = name;
    }
    
    public String getSignature() {
        return mType.getSignature();
    }
    
    public String genCppDecl() {
        return mType.genCppDecl(mName);
    }
    
	public String genCDecl() {
		return mType.genCDecl(mName);
	}

    public String genCsharpDecl() {
        return mType.genCsharpDecl(mName);
    }

    public String genCsharpConstructorParam(String fname) {
        return mType.genCsharpConstructorParam(fname);
    }
	
    public String genJavaDecl() {
        return mType.genJavaDecl(mName);
    }
    
    public String genJavaConstructorParam(String fname) {
        return mType.genJavaConstructorParam(fname);
    }
    
    public String getName() {
        return mName;
    }

    public String getCsharpName() {
        return "Id".equals(mName) ? "ZKId" : mName;
    }
    
    public String getTag() {
        return mName;
    }
    
    public JType getType() {
        return mType;
    }
    
    public String genCppGetSet(int fIdx) {
        return mType.genCppGetSet(mName, fIdx);
    }

    public String genCsharpConstructorSet(String fname) {
        return mType.genCsharpConstructorSet(mName, fname);
    }

    public String genCsharpGetSet(int fIdx) {
        return mType.genCsharpGetSet(getCsharpName(), fIdx);
    }

    public String genCsharpWriteMethodName() {
        return mType.genCsharpWriteMethod(getCsharpName(), getTag());
    }

    public String genCsharpReadMethodName() {
        return mType.genCsharpReadMethod(getCsharpName(), getTag());
    }

    public String genCsharpCompareTo() {
        return mType.genCsharpCompareTo(getCsharpName());
    }

    public String genCsharpEquals() {
        return mType.genCsharpEquals(getCsharpName(), "peer."+getCsharpName());
    }

    public String genCsharpHashCode() {
        return mType.genCsharpHashCode(getCsharpName());
    }

    
    public String genJavaGetSet(int fIdx) {
        return mType.genJavaGetSet(mName, fIdx);
    }
    
    public String genJavaWriteMethodName() {
        return mType.genJavaWriteMethod(getName(), getTag());
    }
    
    public String genJavaReadMethodName() {
        return mType.genJavaReadMethod(getName(), getTag());
    }
    
    public String genJavaCompareTo() {
        return mType.genJavaCompareTo(getName());
    }
    
    public String genJavaEquals() {
        return mType.genJavaEquals(getName(), "peer."+getName());
    }
    
    public String genJavaHashCode() {
        return mType.genJavaHashCode(getName());
    }

    public String genJavaConstructorSet(String fname) {
        return mType.genJavaConstructorSet(mName, fname);
    }

}
