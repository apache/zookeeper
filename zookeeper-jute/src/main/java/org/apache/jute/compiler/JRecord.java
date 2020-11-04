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
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class JRecord extends JCompType {

    private String mFQName;
    private String mName;
    private String mModule;
    private List<JField> mFields;

    /**
     * Creates a new instance of JRecord.
     */
    public JRecord(String name, ArrayList<JField> flist) {
        super("struct " + name.substring(name.lastIndexOf('.') + 1),
                name.replaceAll("\\.", "::"), getCsharpFQName(name), name, "Record", name, getCsharpFQName("IRecord"));
        mFQName = name;
        int idx = name.lastIndexOf('.');
        mName = name.substring(idx + 1);
        mModule = name.substring(0, idx);
        mFields = flist;
    }

    public String getName() {
        return mName;
    }

    public String getCsharpName() {
        return "Id".equals(mName) ? "ZKId" : mName;
    }

    public String getJavaFQName() {
        return mFQName;
    }

    public String getCppFQName() {
        return mFQName.replaceAll("\\.", "::");
    }

    public String getJavaPackage() {
        return mModule;
    }

    public String getCppNameSpace() {
        return mModule.replaceAll("\\.", "::");
    }

    public String getCsharpNameSpace() {
        String[] parts = mModule.split("\\.");
        StringBuffer namespace = new StringBuffer();
        for (int i = 0; i < parts.length; i++) {
            String capitalized = parts[i].substring(0, 1).toUpperCase() + parts[i].substring(1).toLowerCase();
            namespace.append(capitalized);
            if (i != parts.length - 1) {
                namespace.append(".");
            }
        }
        return namespace.toString();
    }

    public List<JField> getFields() {
        return mFields;
    }

    public String getSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append("L").append(mName).append("(");
        for (Iterator<JField> i = mFields.iterator(); i.hasNext(); ) {
            String s = i.next().getSignature();
            sb.append(s);
        }
        sb.append(")");
        return sb.toString();
    }

    public String genCppDecl(String fname) {
        return "  " + getCppNameSpace() + "::" + mName + " m" + fname + ";\n";
    }

    public String genJavaReadMethod(String fname, String tag) {
        return genJavaReadWrapper(fname, tag, false);
    }

    public String genJavaReadWrapper(String fname, String tag, boolean decl) {
        StringBuilder ret = new StringBuilder("");
        if (decl) {
            ret.append("    " + getJavaFQName() + " " + fname + ";\n");
        }
        ret.append("    " + fname + "= new " + getJavaFQName() + "();\n");
        ret.append("    a_.readRecord(" + fname + ",\"" + tag + "\");\n");
        return ret.toString();
    }

    public String genJavaWriteWrapper(String fname, String tag) {
        return "    a_.writeRecord(" + fname + ",\"" + tag + "\");\n";
    }

    String genCsharpReadMethod(String fname, String tag) {
        //return "    "+capitalize(fname)+"=a_.Read"+mMethodSuffix+"(" + capitalize(fname) + ",\""+tag+"\");\n";
        return genCsharpReadWrapper(capitalize(fname), tag, false);
    }

    public String genCsharpReadWrapper(String fname, String tag, boolean decl) {
        StringBuilder ret = new StringBuilder("");
        if (decl) {
            ret.append("    " + getCsharpFQName(mFQName) + " " + fname + ";\n");
        }
        ret.append("    " + fname + "= new " + getCsharpFQName(mFQName) + "();\n");
        ret.append("    a_.ReadRecord(" + fname + ",\"" + tag + "\");\n");
        return ret.toString();
    }

    public String genCsharpWriteWrapper(String fname, String tag) {
        return "    a_.WriteRecord(" + fname + ",\"" + tag + "\");\n";
    }

    static Map<String, String> vectorStructs = new HashMap<String, String>();

    public void genCCode(FileWriter h, FileWriter c) throws IOException {
        for (JField f : mFields) {
            if (f.getType() instanceof JVector) {
                JVector jv = (JVector) f.getType();
                JType jvType = jv.getElementType();
                String structName = JVector.extractVectorName(jvType);
                if (vectorStructs.get(structName) == null) {
                    vectorStructs.put(structName, structName);
                    h.write("struct " + structName + " {\n    int32_t count;\n" + jv.getElementType().genCDecl("*data") + "\n};\n");
                    h.write("int serialize_" + structName + "(struct oarchive *out, const char *tag, struct " + structName + " *v);\n");
                    h.write("int deserialize_" + structName + "(struct iarchive *in, const char *tag, struct " + structName + " *v);\n");
                    h.write("int allocate_" + structName + "(struct " + structName + " *v, int32_t len);\n");
                    h.write("int deallocate_" + structName + "(struct " + structName + " *v);\n");
                    c.write("int allocate_" + structName + "(struct " + structName + " *v, int32_t len) {\n");
                    c.write("    if (!len) {\n");
                    c.write("        v->count = 0;\n");
                    c.write("        v->data = 0;\n");
                    c.write("    } else {\n");
                    c.write("        v->count = len;\n");
                    c.write("        v->data = calloc(sizeof(*v->data), len);\n");
                    c.write("    }\n");
                    c.write("    return 0;\n");
                    c.write("}\n");
                    c.write("int deallocate_" + structName + "(struct " + structName + " *v) {\n");
                    c.write("    if (v->data) {\n");
                    c.write("        int32_t i;\n");
                    c.write("        for(i=0;i<v->count; i++) {\n");
                    c.write("            deallocate_" + JRecord.extractMethodSuffix(jvType) + "(&v->data[i]);\n");
                    c.write("        }\n");
                    c.write("        free(v->data);\n");
                    c.write("        v->data = 0;\n");
                    c.write("    }\n");
                    c.write("    return 0;\n");
                    c.write("}\n");
                    c.write("int serialize_" + structName + "(struct oarchive *out, const char *tag, struct " + structName + " *v)\n");
                    c.write("{\n");
                    c.write("    int32_t count = v->count;\n");
                    c.write("    int rc = 0;\n");
                    c.write("    int32_t i;\n");
                    c.write("    rc = out->start_vector(out, tag, &count);\n");
                    c.write("    for(i=0;i<v->count;i++) {\n");
                    genSerialize(c, jvType, "data", "data[i]");
                    c.write("    }\n");
                    c.write("    rc = rc ? rc : out->end_vector(out, tag);\n");
                    c.write("    return rc;\n");
                    c.write("}\n");
                    c.write("int deserialize_" + structName + "(struct iarchive *in, const char *tag, struct " + structName + " *v)\n");
                    c.write("{\n");
                    c.write("    int rc = 0;\n");
                    c.write("    int32_t i;\n");
                    c.write("    rc = in->start_vector(in, tag, &v->count);\n");
                    c.write("    v->data = calloc(v->count, sizeof(*v->data));\n");
                    c.write("    for(i=0;i<v->count;i++) {\n");
                    genDeserialize(c, jvType, "value", "data[i]");
                    c.write("    }\n");
                    c.write("    rc = in->end_vector(in, tag);\n");
                    c.write("    return rc;\n");
                    c.write("}\n");

                }
            }
        }
        String recName = getName();
        h.write("struct " + recName + " {\n");
        for (JField f : mFields) {
            h.write(f.genCDecl());
        }
        h.write("};\n");
        h.write("int serialize_" + recName + "(struct oarchive *out, const char *tag, struct " + recName + " *v);\n");
        h.write("int deserialize_" + recName + "(struct iarchive *in, const char *tag, struct " + recName + "*v);\n");
        h.write("void deallocate_" + recName + "(struct " + recName + "*);\n");
        c.write("int serialize_" + recName + "(struct oarchive *out, const char *tag, struct " + recName + " *v)");
        c.write("{\n");
        c.write("    int rc;\n");
        c.write("    rc = out->start_record(out, tag);\n");
        for (JField f : mFields) {
            genSerialize(c, f.getType(), f.getTag(), f.getName());
        }
        c.write("    rc = rc ? rc : out->end_record(out, tag);\n");
        c.write("    return rc;\n");
        c.write("}\n");
        c.write("int deserialize_" + recName + "(struct iarchive *in, const char *tag, struct " + recName + "*v)");
        c.write("{\n");
        c.write("    int rc;\n");
        c.write("    rc = in->start_record(in, tag);\n");
        for (JField f : mFields) {
            genDeserialize(c, f.getType(), f.getTag(), f.getName());
        }
        c.write("    rc = rc ? rc : in->end_record(in, tag);\n");
        c.write("    return rc;\n");
        c.write("}\n");
        c.write("void deallocate_" + recName + "(struct " + recName + "*v)");
        c.write("{\n");
        for (JField f : mFields) {
            if (f.getType() instanceof JRecord) {
                c.write("    deallocate_" + extractStructName(f.getType()) + "(&v->" + f.getName() + ");\n");
            } else if (f.getType() instanceof JVector) {
                JVector vt = (JVector) f.getType();
                c.write("    deallocate_" + JVector.extractVectorName(vt.getElementType()) + "(&v->" + f.getName() + ");\n");
            } else if (f.getType() instanceof JCompType) {
                c.write("    deallocate_" + extractMethodSuffix(f.getType()) + "(&v->" + f.getName() + ");\n");
            }
        }
        c.write("}\n");
    }

    private void genSerialize(FileWriter c, JType type, String tag, String name) throws IOException {
        if (type instanceof JRecord) {
            c.write("    rc = rc ? rc : serialize_" + extractStructName(type) + "(out, \"" + tag + "\", &v->" + name + ");\n");
        } else if (type instanceof JVector) {
            c.write("    rc = rc ? rc : serialize_" + JVector.extractVectorName(((JVector) type).getElementType()) + "(out, \"" + tag + "\", &v->" + name + ");\n");
        } else {
            c.write("    rc = rc ? rc : out->serialize_" + extractMethodSuffix(type) + "(out, \"" + tag + "\", &v->" + name + ");\n");
        }
    }

    private void genDeserialize(FileWriter c, JType type, String tag, String name) throws IOException {
        if (type instanceof JRecord) {
            c.write("    rc = rc ? rc : deserialize_" + extractStructName(type) + "(in, \"" + tag + "\", &v->" + name + ");\n");
        } else if (type instanceof JVector) {
            c.write("    rc = rc ? rc : deserialize_" + JVector.extractVectorName(((JVector) type).getElementType()) + "(in, \"" + tag + "\", &v->" + name + ");\n");
        } else {
            c.write("    rc = rc ? rc : in->deserialize_" + extractMethodSuffix(type) + "(in, \"" + tag + "\", &v->" + name + ");\n");
        }
    }

    static String extractMethodSuffix(JType t) {
        if (t instanceof JRecord) {
            return extractStructName(t);
        }
        return t.getMethodSuffix();
    }

    private static String extractStructName(JType t) {
        String type = t.getCType();

        if (!type.startsWith("struct ")) {
            return type;
        }

        return type.substring("struct ".length());
    }

    public void genCppCode(FileWriter hh, FileWriter cc)
            throws IOException {
        String[] ns = getCppNameSpace().split("::");
        for (int i = 0; i < ns.length; i++) {
            hh.write("namespace " + ns[i] + " {\n");
        }

        hh.write("class " + getName() + " : public ::hadoop::Record {\n");
        hh.write("private:\n");

        for (Iterator<JField> i = mFields.iterator(); i.hasNext(); ) {
            JField jf = i.next();
            hh.write(jf.genCppDecl());
        }
        hh.write("  mutable std::bitset<" + mFields.size() + "> bs_;\n");
        hh.write("public:\n");
        hh.write("  virtual void serialize(::hadoop::OArchive& a_, const char* tag) const;\n");
        hh.write("  virtual void deserialize(::hadoop::IArchive& a_, const char* tag);\n");
        hh.write("  virtual const ::std::string& type() const;\n");
        hh.write("  virtual const ::std::string& signature() const;\n");
        hh.write("  virtual bool validate() const;\n");
        hh.write("  virtual bool operator<(const " + getName() + "& peer_) const;\n");
        hh.write("  virtual bool operator==(const " + getName() + "& peer_) const;\n");
        hh.write("  virtual ~" + getName() + "() {};\n");
        int fIdx = 0;
        for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
            JField jf = i.next();
            hh.write(jf.genCppGetSet(fIdx));
        }
        hh.write("}; // end record " + getName() + "\n");
        for (int i = ns.length - 1; i >= 0; i--) {
            hh.write("} // end namespace " + ns[i] + "\n");
        }
        cc.write("void " + getCppFQName() + "::serialize(::hadoop::OArchive& a_, const char* tag) const {\n");
        cc.write("  if (!validate()) throw new ::hadoop::IOException(\"All fields not set.\");\n");
        cc.write("  a_.startRecord(*this,tag);\n");
        fIdx = 0;
        for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
            JField jf = i.next();
            String name = jf.getName();
            if (jf.getType() instanceof JBuffer) {
                cc.write("  a_.serialize(m" + name + ",m" + name + ".length(),\"" + jf.getTag() + "\");\n");
            } else {
                cc.write("  a_.serialize(m" + name + ",\"" + jf.getTag() + "\");\n");
            }
            cc.write("  bs_.reset(" + fIdx + ");\n");
        }
        cc.write("  a_.endRecord(*this,tag);\n");
        cc.write("  return;\n");
        cc.write("}\n");

        cc.write("void " + getCppFQName() + "::deserialize(::hadoop::IArchive& a_, const char* tag) {\n");
        cc.write("  a_.startRecord(*this,tag);\n");
        fIdx = 0;
        for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
            JField jf = i.next();
            String name = jf.getName();
            if (jf.getType() instanceof JBuffer) {
                cc.write("  { size_t len=0; a_.deserialize(m" + name + ",len,\"" + jf.getTag() + "\");}\n");
            } else {
                cc.write("  a_.deserialize(m" + name + ",\"" + jf.getTag() + "\");\n");
            }
            cc.write("  bs_.set(" + fIdx + ");\n");
        }
        cc.write("  a_.endRecord(*this,tag);\n");
        cc.write("  return;\n");
        cc.write("}\n");

        cc.write("bool " + getCppFQName() + "::validate() const {\n");
        cc.write("  if (bs_.size() != bs_.count()) return false;\n");
        for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
            JField jf = (JField) i.next();
            JType type = jf.getType();
            if (type instanceof JRecord) {
                cc.write("  if (!m" + jf.getName() + ".validate()) return false;\n");
            }
        }
        cc.write("  return true;\n");
        cc.write("}\n");

        cc.write("bool " + getCppFQName() + "::operator< (const " + getCppFQName() + "& peer_) const {\n");
        cc.write("  return (1\n");
        for (Iterator<JField> i = mFields.iterator(); i.hasNext(); ) {
            JField jf = i.next();
            String name = jf.getName();
            cc.write("    && (m" + name + " < peer_.m" + name + ")\n");
        }
        cc.write("  );\n");
        cc.write("}\n");

        cc.write("bool " + getCppFQName() + "::operator== (const " + getCppFQName() + "& peer_) const {\n");
        cc.write("  return (1\n");
        for (Iterator<JField> i = mFields.iterator(); i.hasNext(); ) {
            JField jf = i.next();
            String name = jf.getName();
            cc.write("    && (m" + name + " == peer_.m" + name + ")\n");
        }
        cc.write("  );\n");
        cc.write("}\n");

        cc.write("const ::std::string&" + getCppFQName() + "::type() const {\n");
        cc.write("  static const ::std::string type_(\"" + mName + "\");\n");
        cc.write("  return type_;\n");
        cc.write("}\n");

        cc.write("const ::std::string&" + getCppFQName() + "::signature() const {\n");
        cc.write("  static const ::std::string sig_(\"" + getSignature() + "\");\n");
        cc.write("  return sig_;\n");
        cc.write("}\n");

    }

    public void genJavaCode(File outputDirectory) throws IOException {
        String pkg = getJavaPackage();
        String pkgpath = pkg.replaceAll("\\.", "/");
        File pkgdir = new File(outputDirectory, pkgpath);
        if (!pkgdir.exists()) {
            // create the pkg directory
            if (!pkgdir.mkdirs()) {
                throw new IOException("Cannnot create directory: " + pkgpath);
            }
        } else if (!pkgdir.isDirectory()) {
            throw new IOException(pkgpath + " is not a directory.");
        }
        try (FileWriter jj = new FileWriter(new File(pkgdir, getName() + ".java"))) {
            jj.write("// File generated by hadoop record compiler. Do not edit.\n");
            jj.write("/**\n");
            jj.write("* Licensed to the Apache Software Foundation (ASF) under one\n");
            jj.write("* or more contributor license agreements.  See the NOTICE file\n");
            jj.write("* distributed with this work for additional information\n");
            jj.write("* regarding copyright ownership.  The ASF licenses this file\n");
            jj.write("* to you under the Apache License, Version 2.0 (the\n");
            jj.write("* \"License\"); you may not use this file except in compliance\n");
            jj.write("* with the License.  You may obtain a copy of the License at\n");
            jj.write("*\n");
            jj.write("*     http://www.apache.org/licenses/LICENSE-2.0\n");
            jj.write("*\n");
            jj.write("* Unless required by applicable law or agreed to in writing, software\n");
            jj.write("* distributed under the License is distributed on an \"AS IS\" BASIS,\n");
            jj.write("* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n");
            jj.write("* See the License for the specific language governing permissions and\n");
            jj.write("* limitations under the License.\n");
            jj.write("*/\n");
            jj.write("\n");
            jj.write("package " + getJavaPackage() + ";\n\n");
            jj.write("import org.apache.jute.*;\n");
            jj.write("import org.apache.jute.Record; // JDK14 needs explicit import due to clash with java.lang.Record\n");
            jj.write("import org.apache.yetus.audience.InterfaceAudience;\n");
            jj.write("@InterfaceAudience.Public\n");
            jj.write("public class " + getName() + " implements Record {\n");
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); ) {
                JField jf = i.next();
                jj.write(jf.genJavaDecl());
            }
            jj.write("  public " + getName() + "() {\n");
            jj.write("  }\n");

            jj.write("  public " + getName() + "(\n");
            int fIdx = 0;
            int fLen = mFields.size();
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                jj.write(jf.genJavaConstructorParam(jf.getName()));
                jj.write((fLen - 1 == fIdx) ? "" : ",\n");
            }
            jj.write(") {\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                jj.write(jf.genJavaConstructorSet(jf.getName()));
            }
            jj.write("  }\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                jj.write(jf.genJavaGetSet(fIdx));
            }
            jj.write("  public void serialize(OutputArchive a_, String tag) throws java.io.IOException {\n");
            jj.write("    a_.startRecord(this,tag);\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                jj.write(jf.genJavaWriteMethodName());
            }
            jj.write("    a_.endRecord(this,tag);\n");
            jj.write("  }\n");

            jj.write("  public void deserialize(InputArchive a_, String tag) throws java.io.IOException {\n");
            jj.write("    a_.startRecord(tag);\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                jj.write(jf.genJavaReadMethodName());
            }
            jj.write("    a_.endRecord(tag);\n");
            jj.write("}\n");

            jj.write("  public String toString() {\n");
            jj.write("    try {\n");
            jj.write("      java.io.ByteArrayOutputStream s =\n");
            jj.write("        new java.io.ByteArrayOutputStream();\n");
            jj.write("      ToStringOutputArchive a_ = \n");
            jj.write("        new ToStringOutputArchive(s);\n");
            jj.write("      a_.startRecord(this,\"\");\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                jj.write(jf.genJavaWriteMethodName());
            }
            jj.write("      a_.endRecord(this,\"\");\n");
            jj.write("      return new String(s.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);\n");
            jj.write("    } catch (Throwable ex) {\n");
            jj.write("      ex.printStackTrace();\n");
            jj.write("    }\n");
            jj.write("    return \"ERROR\";\n");
            jj.write("  }\n");

            jj.write("  public void write(java.io.DataOutput out) throws java.io.IOException {\n");
            jj.write("    BinaryOutputArchive archive = new BinaryOutputArchive(out);\n");
            jj.write("    serialize(archive, \"\");\n");
            jj.write("  }\n");

            jj.write("  public void readFields(java.io.DataInput in) throws java.io.IOException {\n");
            jj.write("    BinaryInputArchive archive = new BinaryInputArchive(in);\n");
            jj.write("    deserialize(archive, \"\");\n");
            jj.write("  }\n");

            jj.write("  public int compareTo (Object peer_) throws ClassCastException {\n");
            boolean unimplemented = false;
            for (JField f : mFields) {
                if ((f.getType() instanceof JMap)
                        || (f.getType() instanceof JVector)) {
                    unimplemented = true;
                }
            }
            if (unimplemented) {
                jj.write("    throw new UnsupportedOperationException(\"comparing "
                        + getName() + " is unimplemented\");\n");
            } else {
                jj.write("    if (!(peer_ instanceof " + getName() + ")) {\n");
                jj.write("      throw new ClassCastException(\"Comparing different types of records.\");\n");
                jj.write("    }\n");
                jj.write("    " + getName() + " peer = (" + getName() + ") peer_;\n");
                jj.write("    int ret = 0;\n");
                for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                    JField jf = i.next();
                    jj.write(jf.genJavaCompareTo());
                    jj.write("    if (ret != 0) return ret;\n");
                }
                jj.write("     return ret;\n");
            }
            jj.write("  }\n");

            jj.write("  public boolean equals(Object peer_) {\n");
            jj.write("    if (!(peer_ instanceof " + getName() + ")) {\n");
            jj.write("      return false;\n");
            jj.write("    }\n");
            jj.write("    if (peer_ == this) {\n");
            jj.write("      return true;\n");
            jj.write("    }\n");
            jj.write("    " + getName() + " peer = (" + getName() + ") peer_;\n");
            jj.write("    boolean ret = false;\n");
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                jj.write(jf.genJavaEquals());
                jj.write("    if (!ret) return ret;\n");
            }
            jj.write("     return ret;\n");
            jj.write("  }\n");

            jj.write("  public int hashCode() {\n");
            jj.write("    int result = 17;\n");
            jj.write("    int ret;\n");
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                jj.write(jf.genJavaHashCode());
                jj.write("    result = 37*result + ret;\n");
            }
            jj.write("    return result;\n");
            jj.write("  }\n");
            jj.write("  public static String signature() {\n");
            jj.write("    return \"" + getSignature() + "\";\n");
            jj.write("  }\n");

            jj.write("}\n");
        }
    }

    public void genCsharpCode(File outputDirectory) throws IOException {
        if (!outputDirectory.exists()) {
            // create the pkg directory
            if (!outputDirectory.mkdirs()) {
                throw new IOException("Cannnot create directory: " + outputDirectory);
            }
        } else if (!outputDirectory.isDirectory()) {
            throw new IOException(outputDirectory + " is not a directory.");
        }

        try (FileWriter cs = new FileWriter(new File(outputDirectory, getName() + ".cs"));) {
            cs.write("// File generated by hadoop record compiler. Do not edit.\n");
            cs.write("/**\n");
            cs.write("* Licensed to the Apache Software Foundation (ASF) under one\n");
            cs.write("* or more contributor license agreements.  See the NOTICE file\n");
            cs.write("* distributed with this work for additional information\n");
            cs.write("* regarding copyright ownership.  The ASF licenses this file\n");
            cs.write("* to you under the Apache License, Version 2.0 (the\n");
            cs.write("* \"License\"); you may not use this file except in compliance\n");
            cs.write("* with the License.  You may obtain a copy of the License at\n");
            cs.write("*\n");
            cs.write("*     http://www.apache.org/licenses/LICENSE-2.0\n");
            cs.write("*\n");
            cs.write("* Unless required by applicable law or agreed to in writing, software\n");
            cs.write("* distributed under the License is distributed on an \"AS IS\" BASIS,\n");
            cs.write("* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n");
            cs.write("* See the License for the specific language governing permissions and\n");
            cs.write("* limitations under the License.\n");
            cs.write("*/\n");
            cs.write("\n");
            cs.write("using System;\n");
            cs.write("using Org.Apache.Jute;\n");
            cs.write("\n");
            cs.write("namespace " + getCsharpNameSpace() + "\n");
            cs.write("{\n");

            String className = getCsharpName();
            cs.write("public class " + className + " : IRecord, IComparable \n");
            cs.write("{\n");
            cs.write("  public " + className + "() {\n");
            cs.write("  }\n");

            cs.write("  public " + className + "(\n");
            int fIdx = 0;
            int fLen = mFields.size();
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                cs.write(jf.genCsharpConstructorParam(jf.getCsharpName()));
                cs.write((fLen - 1 == fIdx) ? "" : ",\n");
            }
            cs.write(") {\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                cs.write(jf.genCsharpConstructorSet(jf.getCsharpName()));
            }
            cs.write("  }\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                cs.write(jf.genCsharpGetSet(fIdx));
                cs.write("\n");
            }
            cs.write("  public void Serialize(IOutputArchive a_, String tag) {\n");
            cs.write("    a_.StartRecord(this,tag);\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                cs.write(jf.genCsharpWriteMethodName());
            }
            cs.write("    a_.EndRecord(this,tag);\n");
            cs.write("  }\n");

            cs.write("  public void Deserialize(IInputArchive a_, String tag) {\n");
            cs.write("    a_.StartRecord(tag);\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                cs.write(jf.genCsharpReadMethodName());
            }
            cs.write("    a_.EndRecord(tag);\n");
            cs.write("}\n");

            cs.write("  public override String ToString() {\n");
            cs.write("    try {\n");
            cs.write("      System.IO.MemoryStream ms = new System.IO.MemoryStream();\n");
            cs.write("      MiscUtil.IO.EndianBinaryWriter writer =\n");
            cs.write("        new MiscUtil.IO.EndianBinaryWriter(MiscUtil.Conversion.EndianBitConverter.Big, ms, System.Text.Encoding.UTF8);\n");
            cs.write("      BinaryOutputArchive a_ = \n");
            cs.write("        new BinaryOutputArchive(writer);\n");
            cs.write("      a_.StartRecord(this,\"\");\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                cs.write(jf.genCsharpWriteMethodName());
            }
            cs.write("      a_.EndRecord(this,\"\");\n");
            cs.write("      ms.Position = 0;\n");
            cs.write("      return System.Text.Encoding.UTF8.GetString(ms.ToArray());\n");
            cs.write("    } catch (Exception ex) {\n");
            cs.write("      Console.WriteLine(ex.StackTrace);\n");
            cs.write("    }\n");
            cs.write("    return \"ERROR\";\n");
            cs.write("  }\n");

            cs.write("  public void Write(MiscUtil.IO.EndianBinaryWriter writer) {\n");
            cs.write("    BinaryOutputArchive archive = new BinaryOutputArchive(writer);\n");
            cs.write("    Serialize(archive, \"\");\n");
            cs.write("  }\n");

            cs.write("  public void ReadFields(MiscUtil.IO.EndianBinaryReader reader) {\n");
            cs.write("    BinaryInputArchive archive = new BinaryInputArchive(reader);\n");
            cs.write("    Deserialize(archive, \"\");\n");
            cs.write("  }\n");

            cs.write("  public int CompareTo (object peer_) {\n");
            boolean unimplemented = false;
            for (JField f : mFields) {
                if ((f.getType() instanceof JMap)
                        || (f.getType() instanceof JVector)) {
                    unimplemented = true;
                }
            }
            if (unimplemented) {
                cs.write("    throw new InvalidOperationException(\"comparing "
                        + getCsharpName() + " is unimplemented\");\n");
            } else {
                cs.write("    if (!(peer_ is " + getCsharpName() + ")) {\n");
                cs.write("      throw new InvalidOperationException(\"Comparing different types of records.\");\n");
                cs.write("    }\n");
                cs.write("    " + getCsharpName() + " peer = (" + getCsharpName() + ") peer_;\n");
                cs.write("    int ret = 0;\n");
                for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                    JField jf = i.next();
                    cs.write(jf.genCsharpCompareTo());
                    cs.write("    if (ret != 0) return ret;\n");
                }
                cs.write("     return ret;\n");
            }
            cs.write("  }\n");

            cs.write("  public override bool Equals(object peer_) {\n");
            cs.write("    if (!(peer_ is " + getCsharpName() + ")) {\n");
            cs.write("      return false;\n");
            cs.write("    }\n");
            cs.write("    if (peer_ == this) {\n");
            cs.write("      return true;\n");
            cs.write("    }\n");
            cs.write("    bool ret = false;\n");
            cs.write("    " + getCsharpName() + " peer = (" + getCsharpName() + ")peer_;\n");
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                cs.write(jf.genCsharpEquals());
                cs.write("    if (!ret) return ret;\n");
            }
            cs.write("     return ret;\n");
            cs.write("  }\n");

            cs.write("  public override int GetHashCode() {\n");
            cs.write("    int result = 17;\n");
            cs.write("    int ret;\n");
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                cs.write(jf.genCsharpHashCode());
                cs.write("    result = 37*result + ret;\n");
            }
            cs.write("    return result;\n");
            cs.write("  }\n");
            cs.write("  public static string Signature() {\n");
            cs.write("    return \"" + getSignature() + "\";\n");
            cs.write("  }\n");

            cs.write("}\n");
            cs.write("}\n");
        }
    }

    public static String getCsharpFQName(String name) {
        String[] packages = name.split("\\.");
        StringBuffer fQName = new StringBuffer();
        for (int i = 0; i < packages.length; i++) {
            String pack = packages[i];
            pack = capitalize(pack);
            pack = "Id".equals(pack) ? "ZKId" : pack;
            fQName.append(capitalize(pack));
            if (i != packages.length - 1) {
                fQName.append(".");
            }
        }
        return fQName.toString();
    }
}
