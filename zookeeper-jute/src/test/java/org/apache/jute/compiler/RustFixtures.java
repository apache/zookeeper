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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.jute.compiler.generated.Rcc;

/**
 * Generates canonical binary serialization test fixtures using the Java Jute implementation
 * to validate cross-language compatibility with the Rust codec.
 *
 * <p>For each client protocol record, constructs a deterministic suite of test instances
 * serialized using {@link BinaryOutputArchive}. The Rust test suite decodes these payloads,
 * verifies field values, re-serializes them, and ensures byte-for-byte fidelity with the Java output.
 *
 * <p>Fixture files are stored under {@code <module>.<Record>.bin} as length prefixed frame streams.
 * Deterministic pseudorandom generation based on record name hashing ensures consistent output
 * across generator invocations.
 */
public final class RustFixtures {

    /** Number of test instances generated per Jute record type. */
    static final int INSTANCES = 24;

    private static final Map<String, String> MODULES = new LinkedHashMap<>();

    static {
        MODULES.put("org.apache.zookeeper.data", "data");
        MODULES.put("org.apache.zookeeper.proto", "proto");
    }

    /** Multi-byte UTF-8 code point corpus for testing boundary conditions and string encoding. */
    private static final int[] CODE_POINTS = {
        'a', 'z', 'A', '/', '0', '9', '-', '_', ' ', 0xe9, 0x4e2d, 0x1f600,
    };

    private RustFixtures() {
    }

    /**
     * CLI entry point for regenerating binary fixtures when modifying protocol schemas.
     *
     * @param args target fixture directory, with optional path to zookeeper.jute
     * @throws Exception if fixture generation or file writes fail
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("usage: RustFixtures <fixture-dir> [zookeeper.jute]");
        }
        File jute = new File(args.length > 1 ? args[1] : "src/main/resources/zookeeper.jute");
        write(jute, new File(args[0]));
    }

    /**
     * Serializes and writes binary fixture files for each client Jute record to disk.
     *
     * @param jute the source jute definition file
     * @param dir  destination directory for generated fixtures
     * @return names of generated fixture files in definition order
     * @throws Exception if serialization or file writing fails
     */
    static List<String> write(File jute, File dir) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("unable to create " + dir);
        }
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : build(jute).entrySet()) {
            Files.write(new File(dir, e.getKey()).toPath(), e.getValue());
            names.add(e.getKey());
        }
        return names;
    }

    /**
     * Constructs the in-memory binary fixture payloads for all client Jute records in schema order.
     *
     * @param jute the source jute definition file
     * @return map of fixture file names to serialized binary contents
     * @throws Exception if record instantiations or serialization fail
     */
    static Map<String, byte[]> build(File jute) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (JRecord jr : records(jute)) {
            String module = MODULES.get(jr.getJavaPackage());
            if (module == null) {
                continue;
            }
            String name = module + "." + jr.getName() + ".bin";
            Random random = new Random(seed(jr.getJavaFQName()));
            ByteArrayOutputStream file = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(file);
            for (int i = 0; i < INSTANCES; i++) {
                Record r = instance(jr, random, i);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                r.serialize(BinaryOutputArchive.getArchive(bytes), "");
                dos.writeInt(bytes.size());
                bytes.writeTo(dos);
            }
            dos.flush();
            out.put(name, file.toByteArray());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<JRecord> records(File jute) throws Exception {
        JFile jf = Rcc.parseFile(jute);
        Field f = JFile.class.getDeclaredField("mRecords");
        f.setAccessible(true);
        return (List<JRecord>) f.get(jf);
    }

    /** Derives a deterministic 64-bit random seed from the fully qualified Jute record name. */
    private static long seed(String name) {
        long h = 1125899906842597L;
        for (int i = 0; i < name.length(); i++) {
            h = 31 * h + name.charAt(i);
        }
        return h;
    }

    /**
     * Synthesizes a test record instance covering boundary conditions. Instance 0 exercises
     * null and zero defaults. Instance 1 tests extreme and empty values. Subsequent instances
     * generate seeded pseudorandom payloads.
     */
    private static Record instance(JRecord jr, Random random, int index) throws Exception {
        Class<?> cls = Class.forName(jr.getJavaFQName());
        Record r = (Record) cls.getDeclaredConstructor().newInstance();
        for (JField f : jr.getFields()) {
            Object value = value(f.getType(), random, index);
            Method setter = setter(cls, f.getName());
            setter.invoke(r, value);
        }
        return r;
    }

    private static Method setter(Class<?> cls, String field) throws NoSuchMethodException {
        String name = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                return m;
            }
        }
        throw new NoSuchMethodException(cls.getName() + "." + name);
    }

    private static Object value(JType t, Random random, int index) throws Exception {
        if (t instanceof JInt) {
            if (index == 0) {
                return 0;
            } else if (index == 1) {
                return random.nextBoolean() ? Integer.MIN_VALUE : Integer.MAX_VALUE;
            }
            return random.nextInt();
        } else if (t instanceof JLong) {
            if (index == 0) {
                return 0L;
            } else if (index == 1) {
                return random.nextBoolean() ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
            return random.nextLong();
        } else if (t instanceof JBoolean) {
            return index != 0 && (index == 1 || random.nextBoolean());
        } else if (t instanceof JString) {
            if (index == 0 || (index > 1 && random.nextInt(6) == 0)) {
                return null;
            }
            int len = index == 1 ? 0 : random.nextInt(24);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                sb.appendCodePoint(CODE_POINTS[random.nextInt(CODE_POINTS.length)]);
            }
            return sb.toString();
        } else if (t instanceof JBuffer) {
            if (index == 0 || (index > 1 && random.nextInt(6) == 0)) {
                return null;
            }
            byte[] b = new byte[index == 1 ? 0 : random.nextInt(40)];
            random.nextBytes(b);
            return b;
        } else if (t instanceof JVector) {
            if (index == 0 || (index > 1 && random.nextInt(6) == 0)) {
                return null;
            }
            int len = index == 1 ? 0 : random.nextInt(5);
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < len; i++) {
                list.add(value(((JVector) t).getElementType(), random, 2 + random.nextInt(4)));
            }
            return list;
        } else if (t instanceof JRecord) {
            return instance((JRecord) t, random, index);
        }
        throw new IllegalArgumentException("unsupported jute type " + t.getSignature());
    }
}
