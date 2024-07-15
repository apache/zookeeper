package org.apache.zookeeper.server.auth;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import javax.security.auth.login.Configuration;
import org.junit.Test;

public class SaslServerCallbackHandlerTest {

    @Test
    public void wrongUserNameShouldClearUserCredential() throws IOException, UnsupportedCallbackException {
        Configuration configuration = mock(Configuration.class);
        Map<String, String> userCredentials = new HashMap<>();
        userCredentials.put("user_exist_user", "password");
        AppConfigurationEntry appConfigurationEntry = new AppConfigurationEntry("test-module", LoginModuleControlFlag.REQUIRED, userCredentials);
        when(configuration.getAppConfigurationEntry("Server")).thenReturn(new AppConfigurationEntry[]{appConfigurationEntry});
        SaslServerCallbackHandler handler = new SaslServerCallbackHandler(configuration);

        // success login first
        NameCallback nc = mock(NameCallback.class);
        when(nc.getDefaultName()).thenReturn("exist_user");
        PasswordCallback pc = mock(PasswordCallback.class);
        handler.handle(new Callback[]{nc, pc});
        verify(pc, times(1)).setPassword(eq("password".toCharArray()));

        // login with wrong userName but right password
        when(nc.getDefaultName()).thenReturn("no_exist_user");
        handler.handle(new Callback[]{nc, pc});
        verify(pc, times(1)).clearPassword();
    }
}
