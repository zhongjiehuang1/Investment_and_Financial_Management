package Controller;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Test;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ClusterAvailabilityTest {
    @After public void clear() {
        for (String name : new String[]{"iafm.server.id", "iafm.coordinator.mode", "iafm.cluster.health.required",
                "iafm.cluster.health.url", "iafm.cluster.health.token", "iafm.cluster.authority.url"}) System.clearProperty(name);
    }

    @Test public void serverIdentityRequiresAnExplicitPhysicalNode() {
        for (String invalid : new String[]{"", "0", "01", "99999999999999", "https://private.invalid", "London"}) {
            System.setProperty("iafm.server.id", invalid); assertEquals(0, ClusterAvailability.serverId());
        }
        System.setProperty("iafm.server.id", "1"); assertEquals(1, ClusterAvailability.serverId());
        System.setProperty("iafm.server.id", "2"); assertEquals(2, ClusterAvailability.serverId());
        System.setProperty("iafm.server.id", "3"); assertEquals(3, ClusterAvailability.serverId());
        System.setProperty("iafm.server.id", "10"); assertEquals(10, ClusterAvailability.serverId());
    }

    @Test public void productionAndPassiveModesFailClosedWithoutReadiness() {
        System.setProperty("iafm.cluster.health.required", "true"); assertFalse(ClusterAvailability.ready());
        System.setProperty("iafm.cluster.health.required", "false"); assertTrue(ClusterAvailability.ready());
        System.setProperty("iafm.coordinator.mode", "passive"); assertFalse(ClusterAvailability.ready());
    }

    @Test public void readyStatusRequiresExactAuthenticated204AndDoesNotFollowRedirects() throws Exception {
        AtomicInteger status = new AtomicInteger(204);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ready", exchange -> {
            assertEquals("Bearer test-only-token", exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Location", "/other");
            exchange.sendResponseHeaders(status.get(), -1); exchange.close();
        });
        server.start();
        try {
            System.setProperty("iafm.cluster.health.url", "http://127.0.0.1:" + server.getAddress().getPort() + "/ready");
            System.setProperty("iafm.cluster.health.token", "test-only-token");
            assertTrue(ClusterAvailability.ready());
            for (int code : new int[]{200, 302, 401, 503}) { status.set(code); assertFalse(ClusterAvailability.ready()); }
        } finally { server.stop(0); }
        assertFalse(ClusterAvailability.ready());
    }

    @Test public void identityAndFrontendRemainIndependentFromDatabaseReadiness() {
        assertFalse(ClusterGuard.needsReadiness("/api/server", "GET"));
        assertFalse(ClusterGuard.needsReadiness("/api/cluster-health", "GET"));
        assertFalse(ClusterGuard.needsReadiness("/", "GET"));
        assertFalse(ClusterGuard.needsReadiness("/assets/js/app.js", "GET"));
        assertTrue(ClusterGuard.needsReadiness("/addIncomeOrExpense", "POST"));
        assertTrue(ClusterGuard.needsReadiness("/api/v1/transactions", "GET"));
        assertTrue(ClusterGuard.needsReadiness("/login", "POST"));
    }

    @Test public void replicatedRecoveryRequiresAuthenticatedLocalAuthorityWithoutRedirects() throws Exception {
        assertTrue(ClusterAvailability.recoveryAuthorityReady(false));
        assertFalse(ClusterAvailability.recoveryAuthorityReady(true));
        AtomicInteger status = new AtomicInteger(204);
        String token="test-only-authority-token-at-least-32-chars";
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/authority",exchange->{
            assertEquals("Bearer "+token,exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Location","/other");
            exchange.getResponseHeaders().set("X-Four-Project-MySQL-UUID","11111111-1111-4111-8111-111111111111");
            exchange.sendResponseHeaders(status.get(),-1);exchange.close();
        });
        server.start();
        String origin="http://127.0.0.1:"+server.getAddress().getPort();
        try {
            System.setProperty("iafm.cluster.authority.url",origin+"/authority");
            System.setProperty("iafm.cluster.health.token",token);
            assertTrue(ClusterAvailability.recoveryAuthorityReady(true));
            assertEquals("11111111-1111-4111-8111-111111111111",ClusterAvailability.recoveryAuthorityIdentity());
            for(int code:new int[]{200,302,401,503}){status.set(code);assertFalse(ClusterAvailability.recoveryAuthorityReady(true));}
            status.set(204);
            System.setProperty("iafm.cluster.authority.url",origin+"/ready");
            assertFalse(ClusterAvailability.recoveryAuthorityReady(true));
            System.setProperty("iafm.cluster.authority.url",origin+"/authority?other=1");
            assertFalse(ClusterAvailability.recoveryAuthorityReady(true));
            System.setProperty("iafm.cluster.authority.url",origin+"/authority");
            System.setProperty("iafm.coordinator.mode","passive");
            assertFalse(ClusterAvailability.recoveryAuthorityReady(true));
        } finally {server.stop(0);}
    }
    @Test public void privateHealthAndStartupAuthorityBypassAnyDefaultProxySelector()throws Exception{
        var previous=java.net.ProxySelector.getDefault();AtomicInteger selected=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ready",e->{e.sendResponseHeaders(204,-1);e.close();});
        server.createContext("/authority",e->{e.getResponseHeaders().set("X-Four-Project-MySQL-UUID","11111111-1111-4111-8111-111111111111");e.sendResponseHeaders(204,-1);e.close();});server.start();
        java.net.ProxySelector.setDefault(new java.net.ProxySelector(){
            public java.util.List<java.net.Proxy> select(java.net.URI uri){selected.incrementAndGet();throw new AssertionError("Private health used proxy selection");}
            public void connectFailed(java.net.URI uri,java.net.SocketAddress socket,java.io.IOException failure){throw new AssertionError("Private health used a proxy");}
        });
        try{
            String base="http://127.0.0.1:"+server.getAddress().getPort();System.setProperty("iafm.cluster.health.url",base+"/ready");System.setProperty("iafm.cluster.authority.url",base+"/authority");System.setProperty("iafm.cluster.health.token","synthetic-private-health-token-at-least32");
            assertTrue(ClusterAvailability.ready());assertNotNull(ClusterAvailability.recoveryAuthorityIdentity());assertEquals(0,selected.get());
        }finally{java.net.ProxySelector.setDefault(previous);server.stop(0);}
    }
}
