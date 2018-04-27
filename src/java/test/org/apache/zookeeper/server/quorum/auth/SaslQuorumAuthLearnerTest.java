package org.apache.zookeeper.server.quorum.auth;

import org.apache.zookeeper.Login;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.sasl.SaslClientFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Socket;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;


public class SaslQuorumAuthLearnerTest extends QuorumAuthTestBase{

    @Test
    public void authenticate() throws IOException, NoSuchFieldException, IllegalAccessException {
        SaslQuorumAuthLearner saslQuorumAuthLearner = new SaslQuorumAuthLearner(true, "", "");
        Socket socket = new Socket();
        Class<? extends SaslQuorumAuthLearner> clz = saslQuorumAuthLearner.getClass();
        Field learnerLogin = clz.getDeclaredField("learnerLogin");
        Object object = learnerLogin.get(saslQuorumAuthLearner);
        Login login = (Login)object;
        // TODO not finish
        SaslClientFactory saslClientFactory = mock(SaslClientFactory);
        Subject subject = mock(Subject);
        when(subject.getPrincipals()).thenReturn(new HashSet<>());
        when(saslClientFactory.createSaslClient(any(String[].class), anyString(), anyString(), anyString(), any(Map.class), any(CallbackHandler.class))).thenReturn(null);


        try {
            saslQuorumAuthLearner.authenticate(socket, "");
        } catch (NullPointerException e) {

        } catch (RuntimeException e) {
            System.out.println("catch RTE here");
            fail("RTE");
        }
    }


}
