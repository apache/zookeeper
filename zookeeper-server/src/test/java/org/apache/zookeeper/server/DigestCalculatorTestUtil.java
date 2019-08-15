package org.apache.zookeeper.server;

import org.apache.zookeeper.server.util.DigestCalculator;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;


public class DigestCalculatorTestUtil {

    private static final Logger LOG = LoggerFactory.getLogger(DigestCalculatorTestUtil.class);

    public static void setDigestEnabled(boolean enabled) {
        try {
            Field field = DigestCalculator.class.getDeclaredField("digestEnabled");
            field.setAccessible(true);
            field.setBoolean(DigestCalculator.DIGEST_CALCULATOR, enabled);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOG.error("unable to set DigestCalculator.digestEnabled", e);
            Assert.fail("unable to set DigestCalculator.digestEnabled");
        }
    }

    public static void setDigestVersion(int version) {
        try {
            Field field = DigestCalculator.class.getDeclaredField("digestVersion");
            field.setAccessible(true);
            field.setInt(DigestCalculator.DIGEST_CALCULATOR, version);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOG.error("unable to set DigestCalculator.digestVersion", e);
            Assert.fail("unable to set DigestCalculator.digestVersion");
        }
    }
}
