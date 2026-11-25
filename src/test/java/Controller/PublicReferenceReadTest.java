package Controller;

import DatabaseService.FinanceCluster;
import Model.ExternalAPI.ExchangeRate;
import Model.Tools.ExchangeRateForVariousCurrencies;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.JSONObject;
import org.junit.*;

import java.io.*;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Reference-only reads keep admission and snapshot safety without a financial lock/commit. */
public class PublicReferenceReadTest {
    private HttpServer health;
    private final AtomicInteger checks = new AtomicInteger();
    private volatile int rejectFrom = Integer.MAX_VALUE;
    private final Map<String,String> prior = new HashMap<>();
    private java.nio.file.Path testClusterConfig;
    private Field provider, legacy;
    private Object oldProvider, oldLegacy;

    @Before public void setup() throws Exception {
        for (String key : List.of("iafm.cluster.enabled", "iafm.cluster.health.url", "iafm.cluster.health.required", "iafm.coordinator.mode", "iafm.cluster.config"))
            prior.put(key, System.getProperty(key));
        health = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        health.createContext("/local-admit/iafm", exchange -> {
            exchange.sendResponseHeaders(checks.incrementAndGet() >= rejectFrom ? 403 : 204, -1); exchange.close();
        });
        health.start();
        testClusterConfig = java.nio.file.Files.createTempFile("iafm-replicated-test", ".properties");
        java.nio.file.Files.writeString(testClusterConfig, "atomic.deployment=replicated\n");
        System.setProperty("iafm.cluster.config",testClusterConfig.toString());
        System.setProperty("iafm.cluster.enabled", "true");
        System.setProperty("iafm.cluster.health.required", "true");
        System.setProperty("iafm.coordinator.mode", "active");
        System.setProperty("iafm.cluster.health.url", "http://127.0.0.1:" + health.getAddress().getPort() + "/local-admit/iafm");
        provider = ExchangeRate.class.getDeclaredField("rates"); provider.setAccessible(true); oldProvider = provider.get(null);
        legacy = ExchangeRateForVariousCurrencies.class.getDeclaredField("rates"); legacy.setAccessible(true); oldLegacy = legacy.get(null);
        legacy.set(null, new ExchangeRateForVariousCurrencies.RateSnapshot(0, "unavailable", null));
        publish(.5, 5.0, "2026-11-12T12:00:00Z");
    }

    @After public void cleanup() throws Exception {
        if (health != null) health.stop(0);
        if (testClusterConfig != null) java.nio.file.Files.deleteIfExists(testClusterConfig);
        for (var entry : prior.entrySet()) if (entry.getValue() == null) System.clearProperty(entry.getKey()); else System.setProperty(entry.getKey(), entry.getValue());
        if (provider != null) provider.set(null, oldProvider);
        if (legacy != null) legacy.set(null, oldLegacy);
    }

    private void publish(double gbp, double cny, String fetched) throws Exception {
        Constructor<?> constructor = Class.forName("Model.ExternalAPI.ExchangeRate$Snapshot").getDeclaredConstructor(Map.class, String.class, String.class, String.class, String.class);
        constructor.setAccessible(true);
        provider.set(null, constructor.newInstance(Map.of("USD",1.0,"GBP",gbp,"CNY",cny,"EUR",.9,"JPY",150.0,"HKD",7.8,"AUD",1.5,"CAD",1.3,"CHF",.8,"SGD",1.4), "2026-11-12T00:00:00Z", "ecb", "2026-11-12", fetched));
    }

    @Test public void publicQuoteDoesNotWaitForFinanceClassMonitorOrClaimReplicatedResult() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> holder = workers.submit(() -> { synchronized (FinanceCluster.class) { held.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } } });
        try {
            assertTrue(held.await(3, TimeUnit.SECONDS));
            for (String path : List.of("/getExchangeRate", "/getExchangeRates")) {
                Fixture f = new Fixture(path, "GET");
                workers.submit(() -> ApiSupport.servePublicRead(f.request(), f.response(), (user,input) -> {
                    assertNull(user);
                    return new JSONObject().put("source", "ecb").put("durability", "dual").put("replicationMode", "dual").put("degraded", false);
                })).get(3, TimeUnit.SECONDS);
                assertEquals(200, f.status); assertTrue(f.body().getBoolean("referenceOnly"));
                assertFalse(f.body().has("durability")); assertFalse(f.body().has("replicationMode")); assertFalse(f.body().has("degraded"));
                assertFalse(f.headers.containsKey("X-Four-Project-Durability"));
                assertEquals("no-store", f.headers.get("Cache-Control"));
            }
            assertEquals(4, checks.get());
        } finally { release.countDown(); holder.get(3, TimeUnit.SECONDS); workers.shutdownNow(); }
    }

    @Test public void quoteKeepsOneImmutableReferenceAcrossConcurrentRefreshAndReleasesPin() throws Exception {
        Fixture f = new Fixture("/getExchangeRate", "GET");
        String original = ExchangeRateForVariousCurrencies.getProviderSnapshot().sha256();
        ApiSupport.servePublicRead(f.request(), f.response(), (user,input) -> {
            publish(1.0, 20.0, "2026-11-12T13:00:00Z");
            assertEquals(10.0, ExchangeRateForVariousCurrencies.getGbpToCny(), 0);
            assertEquals(original, ExchangeRateForVariousCurrencies.getProviderSnapshot().sha256());
            return new JSONObject().put("reference", ExchangeRateForVariousCurrencies.referenceMetadata()).put("rate", ExchangeRateForVariousCurrencies.getGbpToCny());
        });
        assertEquals(200, f.status); assertEquals(original, f.body().getJSONObject("reference").getString("referenceSha256"));
        assertNotEquals(original, ExchangeRateForVariousCurrencies.getProviderSnapshot().sha256());
        assertEquals(20.0, ExchangeRateForVariousCurrencies.currencyConversion("CNY", 1.0, "GBP"), 0);
    }

    @Test public void publicEcbRatesIdentifyTheirSourceAndCalculatedCurrencyBases() throws Exception {
        for (String path : List.of("/getExchangeRate", "/getExchangeRates")) {
            Fixture fixture = new Fixture(path, "GET");
            Object servlet = path.equals("/getExchangeRate")
                    ? new Controller.RegisterAndLogin.GetExchangeRate()
                    : new Controller.RegisterAndLogin.GetExchangeRates();
            Method read = servlet.getClass().getDeclaredMethod("doGet", HttpServletRequest.class, HttpServletResponse.class);
            read.setAccessible(true);
            read.invoke(servlet, fixture.request(), fixture.response());
            assertEquals(200, fixture.status);
            JSONObject body = fixture.body();
            JSONObject reference = path.equals("/getExchangeRate") ? body.getJSONObject("reference") : body;
            assertEquals("ecb", reference.getString("source"));
            assertEquals("European Central Bank (ECB)", reference.getString("sourceName"));
            assertTrue(reference.getString("sourceUrl").startsWith("https://www.ecb.europa.eu/"));
            assertTrue(reference.getBoolean("sourceFreeOfCharge"));
            assertTrue(reference.getBoolean("modified"));
            assertTrue(reference.getString("modification").contains("converted"));
        }
        JSONObject other = ExchangeRateForVariousCurrencies.discloseSource(new JSONObject(), "exchangerate-api");
        assertFalse(other.has("sourceName"));
        assertFalse(other.has("modified"));
    }

    @Test public void admissionRejectionBeforeReadDoesNotExecuteAction() {
        rejectFrom = 1; AtomicBoolean ran = new AtomicBoolean(); Fixture f = new Fixture("/getExchangeRates", "GET");
        ApiSupport.servePublicRead(f.request(), f.response(), (user,input) -> { ran.set(true); return new JSONObject(); });
        assertFalse(ran.get()); assertEquals(503, f.status); assertEquals("CLUSTER_UNAVAILABLE", f.body().getString("code"));
    }

    @Test public void admissionLostAfterCaptureCannotPublishQuote() {
        rejectFrom = 2; AtomicBoolean ran = new AtomicBoolean(); Fixture f = new Fixture("/getExchangeRates", "GET");
        ApiSupport.servePublicRead(f.request(), f.response(), (user,input) -> { ran.set(true); return new JSONObject().put("rates", new JSONObject()).put("source", "ecb"); });
        assertTrue(ran.get()); assertEquals(503, f.status); assertFalse(f.body().has("rates"));
        assertEquals("CLUSTER_UNAVAILABLE", f.body().getString("code"));
    }

    @Test public void postApiAliasAndAuthenticatedFinancialReadsCannotUseReferenceBypass() {
        for (String[] route : List.of(new String[]{"/getExchangeRate","POST"}, new String[]{"/api/v1/exchange-rate","GET"})) {
            Fixture f = new Fixture(route[0], route[1]); AtomicBoolean ran = new AtomicBoolean();
            ApiSupport.servePublicRead(f.request(), f.response(), (user,input) -> { ran.set(true); return new JSONObject(); });
            assertFalse(route[0] + " must retain its existing authentication/transaction boundary", ran.get());
            assertNotEquals(200, f.status); assertFalse(f.body().has("referenceOnly"));
        }
        for (String path : List.of("/getExchangeRate", "/getAssetSummary")) {
            Fixture f = new Fixture(path, "GET"); AtomicBoolean ran = new AtomicBoolean();
            ApiSupport.serve(f.request(), f.response(), false, (user,input) -> { ran.set(true); return new JSONObject(); });
            assertFalse(ran.get()); assertEquals(401, f.status);
        }
    }

    private static Object zero(Class<?> type) {
        if(type==boolean.class)return false;
        if(type==int.class)return 0;
        if(type==long.class)return 0L;
        return null;
    }
    private static final class Fixture {
        final String path, method; final StringWriter out = new StringWriter(); final Map<String,String> headers = new HashMap<>(); int status = 200;
        Fixture(String path,String method) { this.path=path;this.method=method; }
        JSONObject body() { return new JSONObject(out.toString()); }
        HttpServletRequest request() {
            ServletContext context=(ServletContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ServletContext.class},(p,m,a)->zero(m.getReturnType()));
            return (HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{HttpServletRequest.class},(p,m,a)->switch(m.getName()) {
                case "getMethod" -> method; case "getServletPath" -> path; case "getContentType" -> "application/json";
                case "getContentLengthLong" -> 0L; case "getReader" -> new BufferedReader(new StringReader(""));
                case "getParameterMap" -> Map.of(); case "getServletContext" -> context; default -> zero(m.getReturnType());
            });
        }
        HttpServletResponse response() {
            return (HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{HttpServletResponse.class},(p,m,a)-> {
                if(m.getName().equals("getWriter"))return new PrintWriter(out);
                if(m.getName().equals("setStatus"))status=(int)a[0];
                if(m.getName().equals("setHeader"))headers.put((String)a[0],(String)a[1]);
                return zero(m.getReturnType());
            });
        }
    }
}
