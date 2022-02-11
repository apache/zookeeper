package org.apache.zookeeper.inspector;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class LoggerTest {
    Logger LOG = LoggerFactory.getLogger(LoggerTest.class);
    String testMessage = "a test message";

    @Test
    public void testLogStdOutConfig() {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        PrintStream realStdOut = System.out;
        System.setOut(new PrintStream(byteArrayOutputStream));
        LOG.info(testMessage);
        System.setOut(realStdOut);

        //log to stdout for debug
        LOG.info(testMessage);
        String bufferMessage = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
        LOG.info(bufferMessage);

        Assert.assertTrue(bufferMessage.contains(testMessage));
    }
}
