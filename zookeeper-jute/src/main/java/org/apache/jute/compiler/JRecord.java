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

import java.io.File;
import java.io.FileWriter;
import java.io.BufferedWriter;
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
     * Creates a new instance of JRecord
     */
    public JRecord(String name, ArrayList<JField> flist) {
        super("struct " + name.substring(name.lastIndexOf('.')+1),
                name.replaceAll("\\.","::"), getCsharpFQName(name), name, "Record", name, getCsharpFQName("IRecord"));
        mFQName = name;
        int idx = name.lastIndexOf('.');
        mName = name.substring(idx+1);
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
            if (i != parts.length - 1) namespace.append(".");
        }
        return namespace.toString();
    }

    public List<JField> getFields() {
        return mFields;
    }

    public String getSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append("L").append(mName).append("(");
        for (Iterator<JField> i = mFields.iterator(); i.hasNext();) {
            String s = i.next().getSignature();
            sb.append(s);
        }
        sb.append(")");
        return sb.toString();
    }

    public String genCppDecl(String fname) {
        return "  "+ getCppNameSpace() + "::" + mName+" m"+fname+";\n";
    }

    public String genJavaReadMethod(String fname, String tag) {
        return genJavaReadWrapper(fname, tag, false);
    }

    public String genJavaReadWrapper(String fname, String tag, boolean decl) {
        StringBuilder ret = new StringBuilder("");
        if (decl) {
            ret.append("    "+getJavaFQName()+" "+fname+";\n");
        }
        ret.append("    "+fname+"= new "+getJavaFQName()+"();\n");
        ret.append("    a_.readRecord("+fname+",\""+tag+"\");\n");
        return ret.toString();
    }

    public String genJavaWriteWrapper(String fname, String tag) {
        return "    a_.writeRecord("+fname+",\""+tag+"\");\n";
    }

    String genCsharpReadMethod(String fname, String tag) {
        //return "    "+capitalize(fname)+"=a_.Read"+mMethodSuffix+"(" + capitalize(fname) + ",\""+tag+"\");\n";
        return genCsharpReadWrapper(capitalize(fname), tag, false);
    }

    public String genCsharpReadWrapper(String fname, String tag, boolean decl) {
        StringBuilder ret = new StringBuilder("");
        if (decl) {
            ret.append("    "+getCsharpFQName(mFQName)+" "+fname+";\n");
        }
        ret.append("    "+fname+"= new "+getCsharpFQName(mFQName)+"();\n");
        ret.append("    a_.ReadRecord("+fname+",\""+tag+"\");\n");
        return ret.toString();
    }

    public String genCsharpWriteWrapper(String fname, String tag) {
        return "    a_.WriteRecord("+fname+",\""+tag+"\");\n";
    }

    static Map<String, String> vectorStructs = new HashMap<String, String>();
    public void genCCode(FileWriter h, FileWriter c) throws IOException {
	BufferedWriter bwh = new BufferedWriter(h);
	BufferedWriter bwc = new BufferedWriter(c);
        for (JField f : mFields) {
            if (f.getType() instanceof JVector) {
                JVector jv = (JVector) f.getType();
                JType jvType = jv.getElementType();
                String struct_name = JVector.extractVectorName(jvType);
                if (vectorStructs.get(struct_name) == null) {
                    vectorStructs.put(struct_name, struct_name);
                    bwh.write("struct " + struct_name + " {\n    int32_t count;\n" + jv.getElementType().genCDecl("*data") + "\n};\n");
                    bwh.write("int serialize_" + struct_name + "(struct oarchive *out, const char *tag, struct " + struct_name + " *v);\n");
                    bwh.write("int deserialize_" + struct_name + "(struct iarchive *in, const char *tag, struct " + struct_name + " *v);\n");
                    bwh.write("int allocate_" + struct_name + "(struct " + struct_name + " *v, int32_t len);\n");
                    bwh.write("int deallocate_" + struct_name + "(struct " + struct_name + " *v);\n");
                    bwc.write("int allocate_" + struct_name + "(struct " + struct_name + " *v, int32_t len) {\n");
                    bwc.write("    if (!len) {\n");
                    bwc.write("        v->count = 0;\n");
                    bwc.write("        v->data = 0;\n");
                    bwc.write("    } else {\n");
                    bwc.write("        v->count = len;\n");
                    bwc.write("        v->data = calloc(sizeof(*v->data), len);\n");
                    bwc.write("    }\n");
                    bwc.write("    return 0;\n");
                    bwc.write("}\n");
                    bwc.write("int deallocate_" + struct_name + "(struct " + struct_name + " *v) {\n");
                    bwc.write("    if (v->data) {\n");
                    bwc.write("        int32_t i;\n");
                    bwc.write("        for(i=0;i<v->count; i++) {\n");
                    bwc.write("            deallocate_" + JRecord.extractMethodSuffix(jvType) + "(&v->data[i]);\n");
                    bwc.write("        }\n");
                    bwc.write("        free(v->data);\n");
                    bwc.write("        v->data = 0;\n");
                    bwc.write("    }\n");
                    bwc.write("    return 0;\n");
                    bwc.write("}\n");
                    bwc.write("int serialize_" + struct_name + "(struct oarchive *out, const char *tag, struct " + struct_name + " *v)\n");
                    bwc.write("{\n");
                    bwc.write("    int32_t count = v->count;\n");
                    bwc.write("    int rc = 0;\n");
                    bwc.write("    int32_t i;\n");
                    bwc.write("    rc = out->start_vector(out, tag, &count);\n");
                    bwc.write("    for(i=0;i<v->count;i++) {\n");
                    genSerialize(c, jvType, "data", "data[i]");
                    bwc.write("    }\n");
                    bwc.write("    rc = rc ? rc : out->end_vector(out, tag);\n");
                    bwc.write("    return rc;\n");
                    bwc.write("}\n");
                    bwc.write("int deserialize_" + struct_name + "(struct iarchive *in, const char *tag, struct " + struct_name + " *v)\n");
                    bwc.write("{\n");
                    bwc.write("    int rc = 0;\n");
                    bwc.write("    int32_t i;\n");
                    bwc.write("    rc = in->start_vector(in, tag, &v->count);\n");
                    bwc.write("    v->data = calloc(v->count, sizeof(*v->data));\n");
                    bwc.write("    for(i=0;i<v->count;i++) {\n");
                    genDeserialize(c, jvType, "value", "data[i]");
                    bwc.write("    }\n");
                    bwc.write("    rc = in->end_vector(in, tag);\n");
                    bwc.write("    return rc;\n");
                    bwc.write("}\n");

                }
            }
        }
        String rec_name = getName();
        bwh.write("struct " + rec_name + " {\n");
        for (JField f : mFields) {
            bwh.write(f.genCDecl());
        }
        bwh.write("};\n");
        bwh.write("int serialize_" + rec_name + "(struct oarchive *out, const char *tag, struct " + rec_name + " *v);\n");
        bwh.write("int deserialize_" + rec_name + "(struct iarchive *in, const char *tag, struct " + rec_name + "*v);\n");
        bwh.write("void deallocate_" + rec_name + "(struct " + rec_name + "*);\n");
        bwc.write("int serialize_" + rec_name + "(struct oarchive *out, const char *tag, struct " + rec_name + " *v)");
        bwc.write("{\n");
        bwc.write("    int rc;\n");
        bwc.write("    rc = out->start_record(out, tag);\n");
        for (JField f : mFields) {
            genSerialize(c, f.getType(), f.getTag(), f.getName());
        }
        bwc.write("    rc = rc ? rc : out->end_record(out, tag);\n");
        bwc.write("    return rc;\n");
        bwc.write("}\n");
        bwc.write("int deserialize_" + rec_name + "(struct iarchive *in, const char *tag, struct " + rec_name + "*v)");
        bwc.write("{\n");
        bwc.write("    int rc;\n");
        bwc.write("    rc = in->start_record(in, tag);\n");
        for (JField f : mFields) {
            genDeserialize(c, f.getType(), f.getTag(), f.getName());
        }
        bwc.write("    rc = rc ? rc : in->end_record(in, tag);\n");
        bwc.write("    return rc;\n");
        bwc.write("}\n");
        bwc.write("void deallocate_" + rec_name + "(struct " + rec_name + "*v)");
        bwc.write("{\n");
        for (JField f : mFields) {
            if (f.getType() instanceof JRecord) {
                bwc.write("    deallocate_" + extractStructName(f.getType()) + "(&v->" + f.getName() + ");\n");
            } else if (f.getType() instanceof JVector) {
                JVector vt = (JVector) f.getType();
                bwc.write("    deallocate_" + JVector.extractVectorName(vt.getElementType()) + "(&v->" + f.getName() + ");\n");
            } else if (f.getType() instanceof JCompType) {
                bwc.write("    deallocate_" + extractMethodSuffix(f.getType()) + "(&v->" + f.getName() + ");\n");
            }
        }
        bwc.write("}\n");
    }

    private void genSerialize(FileWriter c, JType type, String tag, String name) throws IOException {
        if (type instanceof JRecord) {
            c.write("    rc = rc ? rc : serialize_" + extractStructName(type) + "(out, \"" + tag + "\", &v->" + name + ");\n");
        } else if (type instanceof JVector) {
            c.write("    rc = rc ? rc : serialize_" + JVector.extractVectorName(((JVector)type).getElementType()) + "(out, \"" + tag + "\", &v->" + name + ");\n");
        } else {
            c.write("    rc = rc ? rc : out->serialize_" + extractMethodSuffix(type) + "(out, \"" + tag + "\", &v->" + name + ");\n");
        }
    }

    private void genDeserialize(FileWriter c, JType type, String tag, String name) throws IOException {
        if (type instanceof JRecord) {
            c.write("    rc = rc ? rc : deserialize_" + extractStructName(type) + "(in, \"" + tag + "\", &v->" + name + ");\n");
        } else if (type instanceof JVector) {
            c.write("    rc = rc ? rc : deserialize_" + JVector.extractVectorName(((JVector)type).getElementType()) + "(in, \"" + tag + "\", &v->" + name + ");\n");
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

    static private String extractStructName(JType t) {
        String type = t.getCType();
        if (!type.startsWith("struct ")) return type;
        return type.substring("struct ".length());
    }

    public void genCppCode(FileWriter hh, FileWriter cc)
        throws IOException {
	BufferedWriter bwhh = new BufferedWriter(hh);
	BufferedWriter bwcc = new BufferedWriterd(cc);
	
        String[] ns = getCppNameSpace().split("::");
        for (int i = 0; i < ns.length; i++) {
            hh.write("namespace "+ns[i]+" {\n");
        }

        bwhh.write("class "+getName()+" : public ::hadoop::Record {\n");
        bwhh.write("private:\n");

        for (Iterator<JField> i = mFields.iterator(); i.hasNext();) {
            JField jf = i.next();
            bwhh.write(jf.genCppDecl());
        }
        bwhh.write("  mutable std::bitset<"+mFields.size()+"> bs_;\n");
        bwhh.write("public:\n");
        bwhh.write("  virtual void serialize(::hadoop::OArchive& a_, const char* tag) const;\n");
        bwhh.write("  virtual void deserialize(::hadoop::IArchive& a_, const char* tag);\n");
        bwhh.write("  virtual const ::std::string& type() const;\n");
        bwhh.write("  virtual const ::std::string& signature() const;\n");
        bwhh.write("  virtual bool validate() const;\n");
        bwhh.write("  virtual bool operator<(const "+getName()+"& peer_) const;\n");
        bwhh.write("  virtual bool operator==(const "+getName()+"& peer_) const;\n");
        bwhh.write("  virtual ~"+getName()+"() {};\n");
        int fIdx = 0;
        for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
            JField jf = i.next();
            bwhh.write(jf.genCppGetSet(fIdx));
        }
        bwhh.write("}; // end record "+getName()+"\n");
        for (int i=ns.length-1; i>=0; i--) {
            bwhh.write("} // end namespace "+ns[i]+"\n");
        }
        bwcc.write("void "+getCppFQName()+"::serialize(::hadoop::OArchive& a_, const char* tag) const {\n");
        bwcc.write("  if (!validate()) throw new ::hadoop::IOException(\"All fields not set.\");\n");
        bwcc.write("  a_.startRecord(*this,tag);\n");
        fIdx = 0;
        for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
            JField jf = i.next();
            String name = jf.getName();
            if (jf.getType() instanceof JBuffer) {
                bwcc.write("  a_.serialize(m"+name+",m"+name+".length(),\""+jf.getTag()+"\");\n");
            } else {
                bwcc.write("  a_.serialize(m"+name+",\""+jf.getTag()+"\");\n");
            }
            bwcc.write("  bs_.reset("+fIdx+");\n");
        }
        bwcc.write("  a_.endRecord(*this,tag);\n");
        bwcc.write("  return;\n");
        bwcc.write("}\n");

        bwcc.write("void "+getCppFQName()+"::deserialize(::hadoop::IArchive& a_, const char* tag) {\n");
        bwcc.write("  a_.startRecord(*this,tag);\n");
        fIdx = 0;
        for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
            JField jf = i.next();
            String name = jf.getName();
            if (jf.getType() instanceof JBuffer) {
                bwcc.write("  { size_t len=0; a_.deserialize(m"+name+",len,\""+jf.getTag()+"\");}\n");
            } else {
                bwcc.write("  a_.deserialize(m"+name+",\""+jf.getTag()+"\");\n");
            }
            bwcc.write("  bs_.set("+fIdx+");\n");
        }
        bwcc.write("  a_.endRecord(*this,tag);\n");
        bwcc.write("  return;\n");
        bwcc.write("}\n");

        bwcc.write("bool "+getCppFQName()+"::validate() const {\n");
        bwcc.write("  if (bs_.size() != bs_.count()) return false;\n");
        for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
            JField jf = (JField) i.next();
            JType type = jf.getType();
            if (type instanceof JRecord) {
                bwcc.write("  if (!m"+jf.getName()+".validate()) return false;\n");
            }
        }
        bwcc.write("  return true;\n");
        bwcc.write("}\n");

        bwcc.write("bool "+getCppFQName()+"::operator< (const "+getCppFQName()+"& peer_) const {\n");
        bwcc.write("  return (1\n");
        for (Iterator<JField> i = mFields.iterator(); i.hasNext();) {
            JField jf = i.next();
            String name = jf.getName();
            bwcc.write("    && (m"+name+" < peer_.m"+name+")\n");
        }
        bwcc.write("  );\n");
        bwcc.write("}\n");

        bwcc.write("bool "+getCppFQName()+"::operator== (const "+getCppFQName()+"& peer_) const {\n");
        bwcc.write("  return (1\n");
        for (Iterator<JField> i = mFields.iterator(); i.hasNext();) {
            JField jf = i.next();
            String name = jf.getName();
            bwcc.write("    && (m"+name+" == peer_.m"+name+")\n");
        }
        bwcc.write("  );\n");
        bwcc.write("}\n");

        bwcc.write("const ::std::string&"+getCppFQName()+"::type() const {\n");
        bwcc.write("  static const ::std::string type_(\""+mName+"\");\n");
        bwcc.write("  return type_;\n");
        bwcc.write("}\n");

        bwcc.write("const ::std::string&"+getCppFQName()+"::signature() const {\n");
        bwcc.write("  static const ::std::string sig_(\""+getSignature()+"\");\n");
        bwcc.write("  return sig_;\n");
        bwcc.write("}\n");

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
        try (FileWriter jj = new FileWriter(new File(pkgdir, getName()+".java"))) {
            BufferedWriter bwjj = new BufferedWriter(jj);

            bwjj.write("// File generated by hadoop record compiler. Do not edit.\n");
            bwjj.write("/**\n");
            bwjj.write("* Licensed to the Apache Software Foundation (ASF) under one\n");
            bwjj.write("* or more contributor license agreements.  See the NOTICE file\n");
            bwjj.write("* distributed with this work for additional information\n");
            bwjj.write("* regarding copyright ownership.  The ASF licenses this file\n");
            bwjj.write("* to you under the Apache License, Version 2.0 (the\n");
            bwjj.write("* \"License\"); you may not use this file except in compliance\n");
            bwjj.write("* with the License.  You may obtain a copy of the License at\n");
            bwjj.write("*\n");
            bwjj.write("*     http://www.apache.org/licenses/LICENSE-2.0\n");
            bwjj.write("*\n");
            bwjj.write("* Unless required by applicable law or agreed to in writing, software\n");
            bwjj.write("* distributed under the License is distributed on an \"AS IS\" BASIS,\n");
            bwjj.write("* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n");
            bwjj.write("* See the License for the specific language governing permissions and\n");
            bwjj.write("* limitations under the License.\n");
            bwjj.write("*/\n");
            bwjj.write("\n");
            bwjj.write("package " + getJavaPackage() + ";\n\n");
            bwjj.write("import org.apache.jute.*;\n");
            bwjj.write("import org.apache.yetus.audience.InterfaceAudience;\n");
            bwjj.write("@InterfaceAudience.Public\n");
            bwjj.write("public class " + getName() + " implements Record {\n");
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); ) {
                JField jf = i.next();
                bwjj.write(jf.genJavaDecl());
            }
            bwjj.write("  public " + getName() + "() {\n");
            bwjj.write("  }\n");

            bwjj.write("  public " + getName() + "(\n");
            int fIdx = 0;
            int fLen = mFields.size();
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwjj.write(jf.genJavaConstructorParam(jf.getName()));
                bwjj.write((fLen - 1 == fIdx) ? "" : ",\n");
            }
            bwjj.write(") {\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwjj.write(jf.genJavaConstructorSet(jf.getName()));
            }
            bwjj.write("  }\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwjj.write(jf.genJavaGetSet(fIdx));
            }
            bwjj.write("  public void serialize(OutputArchive a_, String tag) throws java.io.IOException {\n");
            bwjj.write("    a_.startRecord(this,tag);\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwjj.write(jf.genJavaWriteMethodName());
            }
            bwjj.write("    a_.endRecord(this,tag);\n");
            bwjj.write("  }\n");

            bwjj.write("  public void deserialize(InputArchive a_, String tag) throws java.io.IOException {\n");
            bwjj.write("    a_.startRecord(tag);\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwjj.write(jf.genJavaReadMethodName());
            }
            bwjj.write("    a_.endRecord(tag);\n");
            bwjj.write("}\n");

            bwjj.write("  public String toString() {\n");
            bwjj.write("    try {\n");
            bwjj.write("      java.io.ByteArrayOutputStream s =\n");
            bwjj.write("        new java.io.ByteArrayOutputStream();\n");
            bwjj.write("      CsvOutputArchive a_ = \n");
            bwjj.write("        new CsvOutputArchive(s);\n");
            bwjj.write("      a_.startRecord(this,\"\");\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwjj.write(jf.genJavaWriteMethodName());
            }
            bwjj.write("      a_.endRecord(this,\"\");\n");
            bwjj.write("      return new String(s.toByteArray(), \"UTF-8\");\n");
            bwjj.write("    } catch (Throwable ex) {\n");
            bwjj.write("      ex.printStackTrace();\n");
            bwjj.write("    }\n");
            bwjj.write("    return \"ERROR\";\n");
            bwjj.write("  }\n");

            bwjj.write("  public void write(java.io.DataOutput out) throws java.io.IOException {\n");
            bwjj.write("    BinaryOutputArchive archive = new BinaryOutputArchive(out);\n");
            bwjj.write("    serialize(archive, \"\");\n");
            bwjj.write("  }\n");

            bwjj.write("  public void readFields(java.io.DataInput in) throws java.io.IOException {\n");
            bwjj.write("    BinaryInputArchive archive = new BinaryInputArchive(in);\n");
            bwjj.write("    deserialize(archive, \"\");\n");
            bwjj.write("  }\n");

            bwjj.write("  public int compareTo (Object peer_) throws ClassCastException {\n");
            boolean unimplemented = false;
            for (JField f : mFields) {
                if ((f.getType() instanceof JMap)
                        || (f.getType() instanceof JVector)) {
                    unimplemented = true;
                }
            }
            if (unimplemented) {
                bwjj.write("    throw new UnsupportedOperationException(\"comparing "
                        + getName() + " is unimplemented\");\n");
            } else {
                bwjj.write("    if (!(peer_ instanceof " + getName() + ")) {\n");
                bwjj.write("      throw new ClassCastException(\"Comparing different types of records.\");\n");
                bwjj.write("    }\n");
                bwjj.write("    " + getName() + " peer = (" + getName() + ") peer_;\n");
                bwjj.write("    int ret = 0;\n");
                for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                    JField jf = i.next();
                    bwjj.write(jf.genJavaCompareTo());
                    bwjj.write("    if (ret != 0) return ret;\n");
                }
                bwjj.write("     return ret;\n");
            }
            bwjj.write("  }\n");

            bwjj.write("  public boolean equals(Object peer_) {\n");
            bwjj.write("    if (!(peer_ instanceof " + getName() + ")) {\n");
            bwjj.write("      return false;\n");
            bwjj.write("    }\n");
            bwjj.write("    if (peer_ == this) {\n");
            bwjj.write("      return true;\n");
            bwjj.write("    }\n");
            bwjj.write("    " + getName() + " peer = (" + getName() + ") peer_;\n");
            bwjj.write("    boolean ret = false;\n");
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwjj.write(jf.genJavaEquals());
                bwjj.write("    if (!ret) return ret;\n");
            }
            bwjj.write("     return ret;\n");
            bwjj.write("  }\n");

            bwjj.write("  public int hashCode() {\n");
            bwjj.write("    int result = 17;\n");
            bwjj.write("    int ret;\n");
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwjj.write(jf.genJavaHashCode());
                bwjj.write("    result = 37*result + ret;\n");
            }
            bwjj.write("    return result;\n");
            bwjj.write("  }\n");
            bwjj.write("  public static String signature() {\n");
            bwjj.write("    return \"" + getSignature() + "\";\n");
            bwjj.write("  }\n");

            bwjj.write("}\n");
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
            BufferedWriter bwcs = new BufferedWriter(cs);
            bwcs.write("// File generated by hadoop record compiler. Do not edit.\n");
            bwcs.write("/**\n");
            bwcs.write("* Licensed to the Apache Software Foundation (ASF) under one\n");
            bwcs.write("* or more contributor license agreements.  See the NOTICE file\n");
            bwcs.write("* distributed with this work for additional information\n");
            bwcs.write("* regarding copyright ownership.  The ASF licenses this file\n");
            bwcs.write("* to you under the Apache License, Version 2.0 (the\n");
            bwcs.write("* \"License\"); you may not use this file except in compliance\n");
            bwcs.write("* with the License.  You may obtain a copy of the License at\n");
            bwcs.write("*\n");
            bwcs.write("*     http://www.apache.org/licenses/LICENSE-2.0\n");
            bwcs.write("*\n");
            bwcs.write("* Unless required by applicable law or agreed to in writing, software\n");
            bwcs.write("* distributed under the License is distributed on an \"AS IS\" BASIS,\n");
            bwcs.write("* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n");
            bwcs.write("* See the License for the specific language governing permissions and\n");
            bwcs.write("* limitations under the License.\n");
            bwcs.write("*/\n");
            bwcs.write("\n");
            bwcs.write("using System;\n");
            bwcs.write("using Org.Apache.Jute;\n");
            bwcs.write("\n");
            bwcs.write("namespace " + getCsharpNameSpace() + "\n");
            bwcs.write("{\n");

            String className = getCsharpName();
            bwcs.write("public class " + className + " : IRecord, IComparable \n");
            bwcs.write("{\n");
            bwcs.write("  public " + className + "() {\n");
            bwcs.write("  }\n");

            bwcs.write("  public " + className + "(\n");
            int fIdx = 0;
            int fLen = mFields.size();
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwcs.write(jf.genCsharpConstructorParam(jf.getCsharpName()));
                bwcs.write((fLen - 1 == fIdx) ? "" : ",\n");
            }
            bwcs.write(") {\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwcs.write(jf.genCsharpConstructorSet(jf.getCsharpName()));
            }
            bwcs.write("  }\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwcs.write(jf.genCsharpGetSet(fIdx));
                bwcs.write("\n");
            }
            bwcs.write("  public void Serialize(IOutputArchive a_, String tag) {\n");
            bwcs.write("    a_.StartRecord(this,tag);\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwcs.write(jf.genCsharpWriteMethodName());
            }
            bwcs.write("    a_.EndRecord(this,tag);\n");
            bwcs.write("  }\n");

            bwcs.write("  public void Deserialize(IInputArchive a_, String tag) {\n");
            bwcs.write("    a_.StartRecord(tag);\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwcs.write(jf.genCsharpReadMethodName());
            }
            bwcs.write("    a_.EndRecord(tag);\n");
            bwcs.write("}\n");

            bwcs.write("  public override String ToString() {\n");
            bwcs.write("    try {\n");
            bwcs.write("      System.IO.MemoryStream ms = new System.IO.MemoryStream();\n");
            bwcs.write("      MiscUtil.IO.EndianBinaryWriter writer =\n");
            bwcs.write("        new MiscUtil.IO.EndianBinaryWriter(MiscUtil.Conversion.EndianBitConverter.Big, ms, System.Text.Encoding.UTF8);\n");
            bwcs.write("      BinaryOutputArchive a_ = \n");
            bwcs.write("        new BinaryOutputArchive(writer);\n");
            bwcs.write("      a_.StartRecord(this,\"\");\n");
            fIdx = 0;
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwcs.write(jf.genCsharpWriteMethodName());
            }
            bwcs.write("      a_.EndRecord(this,\"\");\n");
            bwcs.write("      ms.Position = 0;\n");
            bwcs.write("      return System.Text.Encoding.UTF8.GetString(ms.ToArray());\n");
            bwcs.write("    } catch (Exception ex) {\n");
            bwcs.write("      Console.WriteLine(ex.StackTrace);\n");
            bwcs.write("    }\n");
            bwcs.write("    return \"ERROR\";\n");
            bwcs.write("  }\n");

            bwcs.write("  public void Write(MiscUtil.IO.EndianBinaryWriter writer) {\n");
            bwcs.write("    BinaryOutputArchive archive = new BinaryOutputArchive(writer);\n");
            bwcs.write("    Serialize(archive, \"\");\n");
            bwcs.write("  }\n");

            bwcs.write("  public void ReadFields(MiscUtil.IO.EndianBinaryReader reader) {\n");
            bwcs.write("    BinaryInputArchive archive = new BinaryInputArchive(reader);\n");
            bwcs.write("    Deserialize(archive, \"\");\n");
            bwcs.write("  }\n");

            bwcs.write("  public int CompareTo (object peer_) {\n");
            boolean unimplemented = false;
            for (JField f : mFields) {
                if ((f.getType() instanceof JMap)
                        || (f.getType() instanceof JVector)) {
                    unimplemented = true;
                }
            }
            if (unimplemented) {
                bwcs.write("    throw new InvalidOperationException(\"comparing "
                        + getCsharpName() + " is unimplemented\");\n");
            } else {
                bwcs.write("    if (!(peer_ is " + getCsharpName() + ")) {\n");
                bwcs.write("      throw new InvalidOperationException(\"Comparing different types of records.\");\n");
                bwcs.write("    }\n");
                bwcs.write("    " + getCsharpName() + " peer = (" + getCsharpName() + ") peer_;\n");
                bwcs.write("    int ret = 0;\n");
                for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                    JField jf = i.next();
                    bwcs.write(jf.genCsharpCompareTo());
                    bwcs.write("    if (ret != 0) return ret;\n");
                }
                bwcs.write("     return ret;\n");
            }
            bwcs.write("  }\n");

            bwcs.write("  public override bool Equals(object peer_) {\n");
            bwcs.write("    if (!(peer_ is " + getCsharpName() + ")) {\n");
            bwcs.write("      return false;\n");
            bwcs.write("    }\n");
            bwcs.write("    if (peer_ == this) {\n");
            bwcs.write("      return true;\n");
            bwcs.write("    }\n");
            bwcs.write("    bool ret = false;\n");
            bwcs.write("    " + getCsharpName() + " peer = (" + getCsharpName() + ")peer_;\n");
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwcs.write(jf.genCsharpEquals());
                bwcs.write("    if (!ret) return ret;\n");
            }
            bwcs.write("     return ret;\n");
            bwcs.write("  }\n");

            bwcs.write("  public override int GetHashCode() {\n");
            bwcs.write("    int result = 17;\n");
            bwcs.write("    int ret;\n");
            for (Iterator<JField> i = mFields.iterator(); i.hasNext(); fIdx++) {
                JField jf = i.next();
                bwcs.write(jf.genCsharpHashCode());
                bwcs.write("    result = 37*result + ret;\n");
            }
            bwcs.write("    return result;\n");
            bwcs.write("  }\n");
            bwcs.write("  public static string Signature() {\n");
            bwcs.write("    return \"" + getSignature() + "\";\n");
            bwcs.write("  }\n");

            bwcs.write("}\n");
            bwcs.write("}\n");

            bwcs.close();
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
            if (i != packages.length - 1) fQName.append(".");
        }
        return fQName.toString();
    }    
}
