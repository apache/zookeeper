package org.apache.zookeeper.util;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.common.X509KeyType;
import org.apache.zookeeper.common.X509TestContext;
import org.apache.zookeeper.common.X509Util;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.util.PemReader;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RunWith(Parameterized.class)
public class PemReaderTest extends ZKTestCase {

    @Parameterized.Parameters
    public static Collection<Object[]> params() {
        ArrayList<Object[]> result = new ArrayList<>();
        int paramIndex = 0;
        for (X509KeyType caKeyType : X509KeyType.values()) {
            for (X509KeyType certKeyType : X509KeyType.values()) {
                for (String keyPassword : new String[]{"", "pa$$w0rd"}) {
                    result.add(new Object[]{caKeyType, certKeyType, keyPassword, paramIndex++});
                }
            }
        }
        return result;
    }

    /**
     * Because key generation and writing / deleting files is kind of expensive, we cache the certs and on-disk files
     * between test cases. None of the test cases modify any of this data so it's safe to reuse between tests. This
     * caching makes all test cases after the first one for a given parameter combination complete almost instantly.
     */
    private static Map<Integer, X509TestContext> cachedTestContexts;
    static File tempDir;

    X509TestContext x509TestContext;

    public PemReaderTest(
            X509KeyType caKeyType,
            X509KeyType certKeyType,
            String keyPassword,
            Integer paramIndex) throws Exception {
        if (cachedTestContexts.containsKey(paramIndex)) {
            x509TestContext = cachedTestContexts.get(paramIndex);
        } else {
            x509TestContext = X509TestContext.newBuilder()
                    .setTempDir(tempDir)
                    .setKeyStoreKeyType(certKeyType)
                    .setTrustStoreKeyType(caKeyType)
                    .setKeyStorePassword(keyPassword)
                    .build();
            cachedTestContexts.put(paramIndex, x509TestContext);
        }
    }

    @BeforeClass
    public static void setUpClass() throws IOException {
        Security.addProvider(new BouncyCastleProvider());
        cachedTestContexts = new HashMap<>();
        tempDir = ClientBase.createEmptyTestDir();
    }

    @AfterClass
    public static void cleanUpClass() {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        cachedTestContexts.clear();
        cachedTestContexts = null;
    }

    @Test
    public void testLoadPrivateKeyFromKeyStore() throws IOException, GeneralSecurityException {
        Optional<String> optPassword = x509TestContext.getKeyStorePassword().length() > 0
                ? Optional.of(x509TestContext.getKeyStorePassword())
                : Optional.empty();
        PrivateKey privateKey = PemReader.loadPrivateKey(
                x509TestContext.getKeyStoreFile(X509Util.StoreFileType.PEM), optPassword);
        Assert.assertEquals(x509TestContext.getKeyStoreKeyPair().getPrivate(), privateKey);
    }

    // Try to load a password-protected private key without providing a password
    @Test(expected = GeneralSecurityException.class)
    public void testLoadEncryptedPrivateKeyFromKeyStoreWithoutPassword() throws GeneralSecurityException, IOException {
        if (!x509TestContext.isKeyStoreEncrypted()) {
            throw new GeneralSecurityException(); // this case is not tested so throw the expected exception
        }
        PemReader.loadPrivateKey(x509TestContext.getKeyStoreFile(X509Util.StoreFileType.PEM), Optional.empty());
    }

    // Try to load a password-protected private key with the wrong password
    @Test(expected = GeneralSecurityException.class)
    public void testLoadEncryptedPrivateKeyFromKeyStoreWithWrongPassword() throws GeneralSecurityException, IOException {
        if (!x509TestContext.isKeyStoreEncrypted()) {
            throw new GeneralSecurityException(); // this case is not tested so throw the expected exception
        }
        PemReader.loadPrivateKey(
                x509TestContext.getKeyStoreFile(X509Util.StoreFileType.PEM),
                Optional.of("wrong password"));
    }

    // Try to load a non-protected private key while providing a password
    @Test(expected = IOException.class)
    public void testLoadUnencryptedPrivateKeyFromKeyStoreWithWrongPassword() throws GeneralSecurityException, IOException {
        if (x509TestContext.isKeyStoreEncrypted()) {
            throw new IOException();
        }
        PemReader.loadPrivateKey(
                x509TestContext.getKeyStoreFile(X509Util.StoreFileType.PEM),
                Optional.of("wrong password"));
    }

    // Expect this to fail, the trust store does not contain a private key
    @Test(expected = KeyStoreException.class)
    public void testLoadPrivateKeyFromTrustStore() throws IOException, GeneralSecurityException {
        PemReader.loadPrivateKey(
                x509TestContext.getTrustStoreFile(X509Util.StoreFileType.PEM), Optional.empty());
    }

    // Expect this to fail, the trust store does not contain a private key
    @Test(expected = KeyStoreException.class)
    public void testLoadPrivateKeyFromTrustStoreWithPassword() throws IOException, GeneralSecurityException {
        PemReader.loadPrivateKey(
                x509TestContext.getTrustStoreFile(X509Util.StoreFileType.PEM), Optional.of("foobar"));
    }

    @Test
    public void testLoadCertificateFromKeyStore() throws IOException, GeneralSecurityException {
        List<X509Certificate> certs = PemReader.readCertificateChain(
                x509TestContext.getKeyStoreFile(X509Util.StoreFileType.PEM));
        Assert.assertEquals(1, certs.size());
        Assert.assertEquals(x509TestContext.getKeyStoreCertificate(), certs.get(0));
    }

    @Test
    public void testLoadCertificateFromTrustStore() throws IOException, GeneralSecurityException {
        List<X509Certificate> certs = PemReader.readCertificateChain(
                x509TestContext.getTrustStoreFile(X509Util.StoreFileType.PEM));
        Assert.assertEquals(1, certs.size());
        Assert.assertEquals(x509TestContext.getTrustStoreCertificate(), certs.get(0));
    }
}
