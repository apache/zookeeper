package org.apache.jute;

import org.junit.Assert;
import org.junit.Test;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;

public class XmlInputArchiveTest {

    void checkWriterAndReader(TestWriter writer, TestReader reader) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            XmlOutputArchive oa = XmlOutputArchive.getArchive(baos);
            writer.write(oa);
        } catch (IOException e) {
            Assert.fail("Should not throw IOException");
        }
        InputStream is = new ByteArrayInputStream(baos.toByteArray());
        try {
            XmlInputArchive ia = XmlInputArchive.getArchive(is);
            reader.read(ia);
        } catch (ParserConfigurationException e) {
            Assert.fail("Should not throw ParserConfigurationException while reading back");
        } catch (SAXException e) {
            Assert.fail("Should not throw SAXException while reading back");
        }  catch (IOException e) {
            Assert.fail("Should not throw IOException while reading back");
        }
    }

    @Test
    public void testWriteInt() {
        final int expected = 4;
        final String tag = "tag1";
        checkWriterAndReader(
                (oa) -> oa.writeInt(expected, tag),
                (ia) -> {
                    int actual = ia.readInt(tag);
                    Assert.assertEquals(expected, actual);
                }
        );
    }

    @Test
    public void testWriteBool() {
        final boolean expected = false;
        final String tag = "tag1";
        checkWriterAndReader(
                (oa) -> oa.writeBool(expected, tag),
                (ia) -> {
                    boolean actual = ia.readBool(tag);
                    Assert.assertEquals(expected, actual);
                }
        );
    }

    @Test
    public void testWriteString() {
        final String expected = "hello";
        final String tag = "tag1";
        checkWriterAndReader(
                (oa) -> oa.writeString(expected, tag),
                (ia) -> {
                    String actual = ia.readString(tag);
                    Assert.assertEquals(expected, actual);
                }
        );
    }

    @Test
    public void testWriteFloat() {
        final float expected = 3.14159f;
        final String tag = "tag1";
        final float delta = 1e-10f;
        checkWriterAndReader(
                (oa) -> oa.writeFloat(expected, tag),
                (ia) -> {
                    float actual = ia.readFloat(tag);
                    Assert.assertEquals(expected, actual, delta);
                }
        );
    }

    @Test
    public void testWriteDouble() {
        final double expected = 3.14159f;
        final String tag = "tag1";
        final float delta = 1e-20f;
        checkWriterAndReader(
                (oa) -> oa.writeDouble(expected, tag),
                (ia) -> {
                    double actual = ia.readDouble(tag);
                    Assert.assertEquals(expected, actual, delta);
                }
        );
    }
}
