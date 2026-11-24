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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.jute.compiler.generated.ParseException;
import org.apache.jute.compiler.generated.Rcc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification test suite for Rust code generation and Jute binary serialization fixtures. */
public class RustGeneratorTest {

    private static final File JUTE = new File("src/main/resources/zookeeper.jute");

    private static final File FIXTURES = new File(
            "../zookeeper-client/zookeeper-client-rust/crates/zk-jute/tests/fixtures");

    @Test
    public void testOutputIsDeterministic(@TempDir Path dir) throws Exception {
        File a = dir.resolve("a").toFile();
        File b = dir.resolve("b").toFile();
        Rcc.parseFile(JUTE).genCode("rust", a);
        Rcc.parseFile(JUTE).genCode("rust", b);
        byte[] first = Files.readAllBytes(new File(a, "zookeeper_jute.rs").toPath());
        byte[] second = Files.readAllBytes(new File(b, "zookeeper_jute.rs").toPath());
        assertArrayEquals(first, second);
        assertEquals(1, a.list().length);

        String text = new String(first, StandardCharsets.UTF_8);
        assertTrue(text.contains("pub mod data {"));
        assertTrue(text.contains("pub mod proto {"));
        assertFalse(text.contains("QuorumPacket"));
        assertFalse(text.contains("FileHeader"));
        assertFalse(text.contains("TxnHeader"));
        assertFalse(text.contains("unwrap"));
        assertFalse(text.contains("unsafe"));
        assertFalse(text.contains("\r"));
        assertTrue(text.contains("pub last_zxid_seen: i64,"));
        assertTrue(text.contains("pub r#type: i32,"));
        assertTrue(text.contains("pub acl: crate::JVec<super::data::ACL>,"));
    }

    @Test
    public void testUnsupportedTypeNamesRecordAndField() throws Exception {
        String jute = "module org.apache.zookeeper.data {\n"
                + "    class Bad {\n"
                + "        int ok;\n"
                + "        float ratio;\n"
                + "    }\n"
                + "}\n";
        RustGenerator gen = new RustGenerator("bad.jute", null, records(jute), new File("."));
        IOException e = assertThrows(IOException.class, gen::generate);
        assertTrue(e.getMessage().contains("org.apache.zookeeper.data.Bad"), e.getMessage());
        assertTrue(e.getMessage().contains("field ratio"), e.getMessage());
        assertTrue(e.getMessage().contains("float"), e.getMessage());
    }

    @Test
    public void testUnsupportedVectorElement() throws Exception {
        String jute = "module org.apache.zookeeper.proto {\n"
                + "    class Bad {\n"
                + "        vector<double> values;\n"
                + "    }\n"
                + "}\n";
        RustGenerator gen = new RustGenerator("bad.jute", null, records(jute), new File("."));
        IOException e = assertThrows(IOException.class, gen::generate);
        assertTrue(e.getMessage().contains("field values"), e.getMessage());
    }

    @Test
    public void testNames() {
        assertEquals("rust_jute.rs", RustGenerator.outputFileName("rust.jute"));
        assertEquals("last_zxid_seen", RustGenerator.fieldIdent("lastZxidSeen"));
        assertEquals("czxid", RustGenerator.fieldIdent("czxid"));
        assertEquals("r#type", RustGenerator.fieldIdent("type"));
        assertEquals("self_", RustGenerator.fieldIdent("self"));
        assertEquals("xml_parser", RustGenerator.toSnakeCase("XMLParser"));
        assertEquals("set_watches2", RustGenerator.toSnakeCase("setWatches2"));
    }

    /**
     * Verifies that committed binary test fixtures in `crates/zk-jute` match current Java serialization,
     * preventing cross-language drift.
     */
    @Test
    public void testRustFixturesAreCurrent() throws Exception {
        assumeTrue(FIXTURES.isDirectory(), "the Rust fixture directory is not present");
        Map<String, byte[]> expected = RustFixtures.build(JUTE);
        assertFalse(expected.isEmpty());
        for (Map.Entry<String, byte[]> e : expected.entrySet()) {
            File f = new File(FIXTURES, e.getKey());
            assertTrue(f.isFile(), "missing fixture " + f);
            assertArrayEquals(e.getValue(), Files.readAllBytes(f.toPath()), "stale fixture " + f);
        }
        String[] present = FIXTURES.list((d, n) -> n.endsWith(".bin"));
        assertEquals(expected.size(), present == null ? 0 : present.length);
    }

    @SuppressWarnings("unchecked")
    private static List<JRecord> records(String jute) throws ParseException, ReflectiveOperationException {
        try (StringReader reader = new StringReader(jute)) {
            JFile jFile = new Rcc(reader).Input();
            Field f = JFile.class.getDeclaredField("mRecords");
            f.setAccessible(true);
            return (List<JRecord>) f.get(jFile);
        }
    }
}
