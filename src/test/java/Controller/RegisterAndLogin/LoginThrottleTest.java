package Controller.RegisterAndLogin;

import org.junit.Test;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class LoginThrottleTest {
    @Test public void repeatedAttemptsAreBoundedAndBudgetExpires() {
        String client = "throttle-test-" + System.nanoTime();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) assertTrue(LoginThrottle.allow(client, now));
        assertFalse(LoginThrottle.allow(client, now));
        assertTrue(LoginThrottle.allow(client, now + 300000));
    }

    @Test public void externalClientsCannotChooseTheirThrottleIdentity() {
        assertEquals("203.0.113.10", LoginThrottle.client(request("203.0.113.10", "192.0.2.1")));
        assertEquals("192.0.2.1", LoginThrottle.client(request("127.0.0.1", "192.0.2.1")));
        assertEquals("127.0.0.1", LoginThrottle.client(request("127.0.0.1", "invalid,spoof")));
    }

    private static HttpServletRequest request(String peer, String forwarded) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class}, (p,m,a) -> switch (m.getName()) {
                    case "getRemoteAddr" -> peer;
                    case "getHeader" -> forwarded;
                    default -> null;
                });
    }
}
