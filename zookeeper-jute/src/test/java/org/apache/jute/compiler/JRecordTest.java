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

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.List;
import org.apache.jute.compiler.generated.ParseException;
import org.apache.jute.compiler.generated.Rcc;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"unchecked", "SameParameterValue"})
public class JRecordTest {

    @Test
    public void testEndOfLineComments() throws ParseException, NoSuchFieldException, IllegalAccessException {
        String juteStr = "module org.apache.zookeeper.data {\n"
                + "    // information explicitly stored by the server persistently\n"
                + "    class StatPersisted {\n"
                + "        long czxid;      // created zxid\n"
                + "        long mzxid;      // last modified zxid\n"
                + "        long ctime;      /* created */\n"
                + "        long mtime;      /** last modified */\n"
                + "        int version;     /* version */ /* testComment1 */\n"
                + "        int cversion;    /* child version */ /* child versionComment2 */ /* child versionComment3 */\n"
                + "        int aversion;    /** acl version */ /** acl versionComment2 */ /** acl version */\n"
                + "        long ephemeralOwner;  /* A multi-line end of line comment. */ /* Another multi-line end of line comment. */ /* Yet another\n"
                + "end of line comment. */ /* Comment belong to new field */\n"
                + "        long pzxid;     /* A multi-line end of line comment. */ /* Another multi-line end of line comment. */ /* Yet another\n"
                + "end of line comment. */ /* Comment belong to new field */\n"
                + "    }\n"
                + "}";

        try (StringReader stringReader = new StringReader(juteStr)) {
            Rcc parser = new Rcc(stringReader);
            JFile jFile = parser.Input();
            List<JRecord> mRecords = getField(jFile, "mRecords", List.class);
            assertEquals(1, mRecords.size());

            JRecord jRecord = mRecords.get(0);
            assertEquals("StatPersisted", jRecord.getName());
            List<JField> fields = jRecord.getFields();
            assertFiled(fields);

            assertEquals("// information explicitly stored by the server persistently\n", jRecord.getRecordComments());
            assertEquals("  // created zxid\n", jRecord.getJavaFieldComments(fields.get(0)));
            assertEquals("  // last modified zxid\n", jRecord.getJavaFieldComments(fields.get(1)));
            assertEquals("  /* created */\n", jRecord.getJavaFieldComments(fields.get(2)));
            assertEquals("  /** last modified */\n", jRecord.getJavaFieldComments(fields.get(3)));
            assertEquals("  /* version */\n  /* testComment1 */\n", jRecord.getJavaFieldComments(fields.get(4)));
            assertEquals("  /* child version */\n  /* child versionComment2 */\n  /* child versionComment3 */\n", jRecord.getJavaFieldComments(fields.get(5)));
            assertEquals("  /** acl version */\n  /** acl versionComment2 */\n  /** acl version */\n", jRecord.getJavaFieldComments(fields.get(6)));
            assertEquals(
                    "  /* A multi-line end of line comment. */\n"
                            + "  /* Another multi-line end of line comment. */\n"
                            + "  /* Yet another\n"
                            + "  end of line comment. */\n", jRecord.getJavaFieldComments(fields.get(7)));
            assertEquals(
                    "  /* Comment belong to new field */\n"
                            + "  /* A multi-line end of line comment. */\n"
                            + "  /* Another multi-line end of line comment. */\n"
                            + "  /* Yet another\n"
                            + "  end of line comment. */\n", jRecord.getJavaFieldComments(fields.get(8)));
        }
    }

    @Test
    public void testCommentBeforeLineAndEndOfLine() throws ParseException, NoSuchFieldException, IllegalAccessException {
        String juteStr = "module org.apache.zookeeper.data {\n"
                + "    /**\n"
                + "      * information explicitly stored by the server persistently\n"
                + "      */ \n"
                + "    class StatPersisted {\n"
                + "        // created zxid\n"
                + "        long czxid; // created zxid comment2\n"
                + "        /* last modified zxid */\n"
                + "        long mzxid; // last modified zxid comment2\n"
                + "        /* created */\n"
                + "        long ctime; // created comment2\n"
                + "        /* last modified */\n"
                + "        /* last modified */\n"
                + "        long mtime; // last modified comment2\n"
                + "        // version comment\n "
                + "        int version;     /* version comment1 */ /* version\n comment2 */\n"
                + "        /** child version */\n"
                + "        /** child version */\n"
                + "        int cversion;  /** child version */  // child version\n"
                + "        // acl version\n"
                + "        // acl version\n"
                + "        // acl version\n"
                + "        int aversion;    // acl version\n"
                + "        // ephemeralOwner comment\n"
                + "        long ephemeralOwner;  /* A multi-line end of line comment. */ /* Another multi-line end of line comment. */ /* Yet another\n"
                + "end of line comment. */ /* Comment belong to new field */\n"
                + "        // pzxid comment\n"
                + "        long pzxid;     /* A multi-line end of line comment. */ /* Another multi-line end of line comment. */ /* Yet another\n"
                + "end of line comment. */ /* Comment belong to new field */\n"
                + "    }\n"
                + "}";
        try (StringReader stringReader = new StringReader(juteStr)) {
            Rcc parser = new Rcc(stringReader);
            JFile jFile = parser.Input();
            List<JRecord> mRecords = getField(jFile, "mRecords", List.class);
            assertEquals(1, mRecords.size());

            JRecord jRecord = mRecords.get(0);
            assertEquals("StatPersisted", jRecord.getName());
            List<JField> fields = jRecord.getFields();
            assertFiled(fields);

            assertEquals("/**\n  * information explicitly stored by the server persistently\n  */\n", jRecord.getRecordComments());
            assertEquals("  // created zxid\n  // created zxid comment2\n", jRecord.getJavaFieldComments(fields.get(0)));
            assertEquals("  /* last modified zxid */\n  // last modified zxid comment2\n", jRecord.getJavaFieldComments(fields.get(1)));
            assertEquals("  /* created */\n  // created comment2\n", jRecord.getJavaFieldComments(fields.get(2)));
            assertEquals("  /* last modified */\n  /* last modified */\n  // last modified comment2\n", jRecord.getJavaFieldComments(fields.get(3)));

            assertEquals("  // version comment\n  /* version comment1 */\n  /* version\n  comment2 */\n", jRecord.getJavaFieldComments(fields.get(4)));
            assertEquals("  /** child version */\n  /** child version */\n  /** child version */\n  // child version\n", jRecord.getJavaFieldComments(fields.get(5)));
            assertEquals("  // acl version\n  // acl version\n  // acl version\n  // acl version\n", jRecord.getJavaFieldComments(fields.get(6)));
            assertEquals(
                    "  // ephemeralOwner comment\n"
                            + "  /* A multi-line end of line comment. */\n"
                            + "  /* Another multi-line end of line comment. */\n"
                            + "  /* Yet another\n"
                            + "  end of line comment. */\n", jRecord.getJavaFieldComments(fields.get(7)));
            assertEquals(
                    "  /* Comment belong to new field */\n"
                            + "  // pzxid comment\n"
                            + "  /* A multi-line end of line comment. */\n"
                            + "  /* Another multi-line end of line comment. */\n"
                            + "  /* Yet another\n"
                            + "  end of line comment. */\n", jRecord.getJavaFieldComments(fields.get(8)));
        }
    }

    @Test
    public void testCommentBeforeLine() throws ParseException, NoSuchFieldException, IllegalAccessException {
        String juteStr = "module org.apache.zookeeper.data {\n"
                + "    // information explicitly stored by the server persistently\n"
                + "    // StatPersisted Comment1\n"
                + "    // StatPersisted Comment2\n"
                + "    class StatPersisted {\n"
                + "        // created zxid comment1\n"
                + "        // created zxid comment2\n"
                + "        // created zxid comment3\n"
                + "        long czxid;\n"
                + "        // last modified zxid\n"
                + "     // last modified zxid\n"
                + "             // last modified zxid\n"
                + "        long mzxid;\n"
                + "        /* created */\n"
                + "           /* created */\n"
                + "      /* created */\n"
                + "        long ctime;\n"
                + "        /** last modified */\n"
                + "        /** last modified */\n"
                + "        long mtime;\n"
                + "        /** version */\n"
                + "             /** version */\n"
                + "        int version;\n"
                + "        // child version\n"
                + "        /** child version */\n"
                + "        /** child version */\n"
                + "        int cversion;\n"
                + "        /* acl version */\n"
                + "        // acl version\n"
                + "        /* acl version */\n"
                + "        int aversion;\n"
                + "        // owner id if ephemeral, 0 otw\n"
                + "        long ephemeralOwner;\n"
                + "        // last modified children\n"
                + "        long pzxid;\n"
                + "    }\n"
                + "}";
        try (StringReader stringReader = new StringReader(juteStr)) {
            Rcc parser = new Rcc(stringReader);
            JFile jFile = parser.Input();
            List<JRecord> mRecords = getField(jFile, "mRecords", List.class);
            assertEquals(1, mRecords.size());

            JRecord jRecord = mRecords.get(0);
            assertEquals("StatPersisted", jRecord.getName());
            List<JField> fields = jRecord.getFields();
            assertFiled(fields);

            assertEquals("// information explicitly stored by the server persistently\n// StatPersisted Comment1\n// StatPersisted Comment2\n", jRecord.getRecordComments());
            assertEquals("  // created zxid comment1\n  // created zxid comment2\n  // created zxid comment3\n", jRecord.getJavaFieldComments(fields.get(0)));
            assertEquals("  // last modified zxid\n  // last modified zxid\n  // last modified zxid\n", jRecord.getJavaFieldComments(fields.get(1)));
            assertEquals("  /* created */\n  /* created */\n  /* created */\n", jRecord.getJavaFieldComments(fields.get(2)));
            assertEquals("  /** last modified */\n  /** last modified */\n", jRecord.getJavaFieldComments(fields.get(3)));
            assertEquals("  /** version */\n  /** version */\n", jRecord.getJavaFieldComments(fields.get(4)));
            assertEquals("  // child version\n  /** child version */\n  /** child version */\n", jRecord.getJavaFieldComments(fields.get(5)));
            assertEquals("  /* acl version */\n  // acl version\n  /* acl version */\n", jRecord.getJavaFieldComments(fields.get(6)));
            assertEquals("  // owner id if ephemeral, 0 otw\n", jRecord.getJavaFieldComments(fields.get(7)));
            assertEquals("  // last modified children\n", jRecord.getJavaFieldComments(fields.get(8)));
        }
    }

    @Test
    public void testMultiLineComments() throws ParseException, NoSuchFieldException, IllegalAccessException {
        String juteStr = "module org.apache.zookeeper.data {\n"
                + "    /**\n"
                + "      * information explicitly stored by the server persistently\n"
                + "      */\n"
                + "     // StatPersisted Comment\n"
                + "     /* StatPersisted Comment */\n"
                + "    class StatPersisted {\n"
                + "        /**\n"
                + "         * created zxid\n"
                + "         */\n"
                + "        long czxid;\n"
                + "        /**\n"
                + "         * last modified zxid comment1\n"
                + "         */\n"
                + "        /**\n"
                + "         * last modified zxid comment2\n"
                + "         */\n"
                + "        long mzxid;\n"
                + "        /*\n"
                + "         * created\n"
                + "         */\n"
                + "        long ctime; /* multi-line\n"
                + "end of line */\n"
                + "        /*\n"
                + "         last modified\n"
                + "         */"
                + "        long mtime; /* A multi-line end of line comment. */ /* Another multi-line end of line comment. */ /* Yet another\n"
                + "end of line comment. */ /* Comment belong to new field */\n"
                + "        /* version comment */\n"
                + "        int version;     // version\n"
                + "        /**\n"
                + "         * child version\n"
                + "         */\n"
                + "        /**\n"
                + "         * child version comment2\n"
                + "         */\n"
                + "        int cversion;    // child version\n"
                + "        /* acl version */\n"
                + "        int aversion;  /* acl version */ /* acl version */  // acl version\n"
                + "        /*\n"
                + "        ephemeralOwner comment\n"
                + "        */\n"
                + "        // ephemeralOwner comment\n"
                + "        /*\n"
                + "        ephemeralOwner comment\n"
                + "        */\n"
                + "        long ephemeralOwner;  // owner id if ephemeral, 0 otw\n"
                + "        /*\n"
                + "        pzxid comment\n"
                + "        */\n"
                + "        long pzxid;      // last modified children\n"
                + "    }\n"
                + "}";
        try (StringReader stringReader = new StringReader(juteStr)) {
            Rcc parser = new Rcc(stringReader);
            JFile jFile = parser.Input();
            List<JRecord> mRecords = getField(jFile, "mRecords", List.class);
            assertEquals(1, mRecords.size());

            JRecord jRecord = mRecords.get(0);
            assertEquals("StatPersisted", jRecord.getName());
            List<JField> fields = jRecord.getFields();
            assertFiled(fields);

            assertEquals(
                    "/**\n"
                            + "  * information explicitly stored by the server persistently\n"
                            + "  */\n"
                            + "// StatPersisted Comment\n"
                            + "/* StatPersisted Comment */\n", jRecord.getRecordComments());
            assertEquals("  /**\n   * created zxid\n   */\n", jRecord.getJavaFieldComments(fields.get(0)));
            assertEquals("  /**\n   * last modified zxid comment1\n   */\n  /**\n   * last modified zxid comment2\n   */\n",
                    jRecord.getJavaFieldComments(fields.get(1)));
            assertEquals("  /*\n   * created\n   */\n  /* multi-line\n  end of line */\n", jRecord.getJavaFieldComments(fields.get(2)));
            assertEquals("  /*\n   last modified\n   */\n"
                            + "  /* A multi-line end of line comment. */\n"
                            + "  /* Another multi-line end of line comment. */\n"
                            + "  /* Yet another\n"
                            + "  end of line comment. */\n", jRecord.getJavaFieldComments(fields.get(3)));
            assertEquals("  /* Comment belong to new field */\n  /* version comment */\n  // version\n",
                    jRecord.getJavaFieldComments(fields.get(4)));
            assertEquals("  /**\n   * child version\n   */\n  /**\n   * child version comment2\n   */\n  // child version\n",
                    jRecord.getJavaFieldComments(fields.get(5)));
            assertEquals("  /* acl version */\n  /* acl version */\n  /* acl version */\n  // acl version\n", jRecord.getJavaFieldComments(fields.get(6)));
            assertEquals("  /*\n"
                    + "  ephemeralOwner comment\n"
                    + "  */\n"
                    + "  // ephemeralOwner comment\n"
                    + "  /*\n"
                    + "  ephemeralOwner comment\n"
                    + "  */\n"
                    + "  // owner id if ephemeral, 0 otw\n", jRecord.getJavaFieldComments(fields.get(7)));
            assertEquals("  /*\n  pzxid comment\n  */\n  // last modified children\n", jRecord.getJavaFieldComments(fields.get(8)));
        }
    }

    private void assertFiled(List<JField> fields) {
        assertEquals(9, fields.size());
        assertEquals("long", fields.get(0).getType().getJavaType());
        assertEquals("czxid", fields.get(0).getName());
        assertEquals("long", fields.get(1).getType().getJavaType());
        assertEquals("mzxid", fields.get(1).getName());
        assertEquals("long", fields.get(2).getType().getJavaType());
        assertEquals("ctime", fields.get(2).getName());
        assertEquals("long", fields.get(3).getType().getJavaType());
        assertEquals("mtime", fields.get(3).getName());
        assertEquals("int", fields.get(4).getType().getJavaType());
        assertEquals("version", fields.get(4).getName());
        assertEquals("int", fields.get(5).getType().getJavaType());
        assertEquals("cversion", fields.get(5).getName());
        assertEquals("int", fields.get(6).getType().getJavaType());
        assertEquals("aversion", fields.get(6).getName());
        assertEquals("long", fields.get(7).getType().getJavaType());
        assertEquals("ephemeralOwner", fields.get(7).getName());
        assertEquals("long", fields.get(8).getType().getJavaType());
        assertEquals("pzxid", fields.get(8).getName());
    }

    private <T> T getField(final Object target,
                           final String fieldName,
                           final Class<T> fieldClassType) throws NoSuchFieldException, IllegalAccessException {
        Class<?> targetClazz = target.getClass();
        Field field = targetClazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return fieldClassType.cast(field.get(target));
    }
}
