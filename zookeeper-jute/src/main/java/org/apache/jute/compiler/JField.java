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

import org.apache.jute.compiler.generated.RccConstants;
import org.apache.jute.compiler.generated.Token;

/**
 *
 */
public class JField {
    private JType mType;
    private String mName;

    /**
     * {@link #mType} of token.
     */
    private Token mTypeToken;

    private Token previousToken;

    /**
     * Since we can only get the comments before the token through the {@link Token#specialToken},
     * we need to save the next token to get the end-of-line comment.
     *
     * <p>It may be the type of the next field, or it may be {@link RccConstants#RBRACE_TKN} of the class.
     */
    private Token nextToken;

    /**
     * Creates a new instance of JField.
     */
    public JField(JType type, String name) {
        mType = type;
        mName = name;
    }

    public Token getTypeToken() {
        return mTypeToken;
    }

    public void setTypeToken(Token typeToken) {
        this.mTypeToken = typeToken;
    }

    public Token getNextToken() {
        return nextToken;
    }

    public void setNextToken(Token nextToken) {
        this.nextToken = nextToken;
    }

    public Token getPreviousToken() {
        return previousToken;
    }

    public void setPreviousToken(Token previousToken) {
        this.previousToken = previousToken;
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
        return mType.genCsharpEquals(getCsharpName(), "peer." + getCsharpName());
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
        return mType.genJavaEquals(getName(), "peer." + getName());
    }

    public String genJavaHashCode() {
        return mType.genJavaHashCode(getName());
    }

    public String genJavaConstructorSet(String fname) {
        return mType.genJavaConstructorSet(mName, fname);
    }

}
