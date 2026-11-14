package Model.ExternalAPI;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import static org.junit.Assert.*;

/** Local parser/cache policy evidence. No provider network calls or live money fixtures. */
public class EcbDailyRatesTest {
    private static final Instant RECEIVED = Instant.parse("2026-11-12T14:00:00Z");
    private byte[] fixture() throws IOException {
        try (var input = getClass().getResourceAsStream("/fx/ecb-daily-2026-11-11.xml")) {
            assertNotNull(input); return input.readAllBytes();
        }
    }
    private ExchangeRate.DailySnapshot parse(String text) throws IOException { return EcbDailyRates.parse(text.getBytes(StandardCharsets.UTF_8), RECEIVED); }

    @Test public void officialDatedFixtureNormalisesEurToUsdWithoutInventingCurrentDate() throws Exception {
        var snapshot=EcbDailyRates.parse(fixture(), RECEIVED);
        assertEquals("ecb",snapshot.source());assertEquals("2026-11-11",snapshot.asOf());assertEquals(RECEIVED.toString(),snapshot.fetchedAt());
        assertEquals(30,snapshot.values().size());assertEquals(1.0,snapshot.values().get("USD"),0);
        assertEquals(1.0/1.1127,snapshot.values().get("EUR"),1e-14);
        assertEquals(7.4225/0.83618,snapshot.values().get("CNY")/snapshot.values().get("GBP"),1e-12);
        for(String code:new String[]{"USD","GBP","CNY","EUR","JPY","HKD","AUD","CAD","CHF","SGD"})assertTrue(snapshot.values().get(code)>0);
        assertThrows(UnsupportedOperationException.class,()->snapshot.values().put("USD",2.0));
    }
    @Test public void referenceHashIsContentBasedAndDoesNotChangeOnlyBecauseFetchTimeChanged() throws Exception {
        var one=EcbDailyRates.parse(fixture(),RECEIVED);var two=EcbDailyRates.parse(fixture(),RECEIVED.plusSeconds(3600));
        assertEquals(one.sha256(),two.sha256());assertNotEquals(one.fetchedAt(),two.fetchedAt());
        assertTrue(one.sha256().matches("[a-f0-9]{64}"));
    }
    @Test public void weekendCacheKeepsTheProviderReferenceDate() throws Exception {
        var snapshot=EcbDailyRates.parse(fixture(),Instant.parse("2026-11-15T12:00:00Z"));
        assertEquals("2026-11-11",snapshot.asOf());assertFalse(snapshot.updatedAt().startsWith("2026-11-15"));
    }
    @Test public void dtdEntitiesAndExternalResourcesAreRejected() throws Exception {
        String base=new String(fixture(),StandardCharsets.UTF_8);
        assertThrows(IOException.class,()->parse(base.replace("<gesmes:Envelope","<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><gesmes:Envelope")));
        assertThrows(IOException.class,()->parse(base.replace("<gesmes:Envelope","<!DOCTYPE x SYSTEM 'http://127.0.0.1:9/must-not-fetch'><gesmes:Envelope")));
        assertThrows(IOException.class,()->parse(base.replace("rate='1.1127'","rate='&secret;'")));
    }
    @Test public void missingDuplicateInvalidOrExcessiveRatePayloadsAreRejected() throws Exception {
        String base=new String(fixture(),StandardCharsets.UTF_8);
        assertThrows(IOException.class,()->parse(base.replace("<Cube currency='USD' rate='1.1127'/>","")));
        assertThrows(IOException.class,()->parse(base.replace("<Cube currency='GBP' rate='0.83618'/>","")));
        assertThrows(IOException.class,()->parse(base.replace("<Cube currency='USD' rate='1.1127'/>","<Cube currency='USD' rate='1.1127'/><Cube currency='USD' rate='2'/>") ));
        for(String value:new String[]{"0","-1","NaN","Infinity","1e309"})assertThrows(IOException.class,()->parse(base.replace("rate='1.1127'","rate='"+value+"'")));
        assertThrows(IOException.class,()->EcbDailyRates.parse(new byte[EcbDailyRates.MAX_BYTES+1],RECEIVED));
    }
    @Test public void invalidFutureOrAmbiguousReferenceDatesAndStructureAreRejected() throws Exception {
        String base=new String(fixture(),StandardCharsets.UTF_8);
        for(String date:new String[]{"2026-11-13","2026-02-30","2026-1-1"})assertThrows(IOException.class,()->parse(base.replace("2026-11-11",date)));
        assertThrows(IOException.class,()->parse(base.replace("http://www.ecb.int/vocabulary/2002-08-01/eurofxref","https://untrusted.invalid/")));
        assertThrows(IOException.class,()->parse(base+base));
        assertThrows(IOException.class,()->parse(base.replace("<Cube time='2026-11-11'>","<Cube time='2026-11-11'><extra>")));
    }
    @Test public void defaultProviderIsEcbAndPaidCompatibilityRequiresExplicitSelection() {
        assertEquals("ecb",ExchangeRate.selectProvider(null));assertEquals("ecb",ExchangeRate.selectProvider(""));assertEquals("ecb",ExchangeRate.selectProvider("ecb"));
        assertEquals("exchangerate-api",ExchangeRate.selectProvider("exchangerate-api"));
        assertThrows(IllegalArgumentException.class,()->ExchangeRate.selectProvider("other"));
    }
    @Test public void ecbFailureAndSuccessGatesAreBoundedAndKeyedAttemptsAlwaysWaitOneDay() throws Exception {
        var field=ExchangeRate.class.getDeclaredField("attempts");field.setAccessible(true);Map<String,Object> attempts=(Map<String,Object>)field.get(null);Map<String,Object> original=Map.copyOf(attempts);
        var type=Class.forName("Model.ExternalAPI.ExchangeRate$Attempt");var constructor=type.getDeclaredConstructor(long.class,boolean.class);constructor.setAccessible(true);
        try {
            attempts.clear();assertTrue(ExchangeRate.due("ecb",1000));
            attempts.put("ecb",constructor.newInstance(1000L,false));assertFalse(ExchangeRate.due("ecb",900999));assertTrue(ExchangeRate.due("ecb",901000));
            attempts.put("ecb",constructor.newInstance(1000L,true));assertFalse(ExchangeRate.due("ecb",3600999));assertTrue(ExchangeRate.due("ecb",3601000));
            for(boolean success:new boolean[]{true,false}) {attempts.put("exchangerate-api",constructor.newInstance(1000L,success));assertFalse(ExchangeRate.due("exchangerate-api",86400999));assertTrue(ExchangeRate.due("exchangerate-api",86401000));}
            assertFalse(ExchangeRate.due("ecb",999));
        } finally {attempts.clear();attempts.putAll(original);}
    }
    @Test public void cacheRequiresRealSourceDateAndFetchedMetadata() throws Exception {
        var snapshot=EcbDailyRates.parse(fixture(),RECEIVED);JSONObject stored=new JSONObject().put("source","ecb").put("asOf",snapshot.asOf()).put("fetchedAt",snapshot.fetchedAt()).put("updatedAt",snapshot.updatedAt()).put("conversion_rates",snapshot.values());
        assertEquals(snapshot.sha256(),ExchangeRate.validatedSnapshot(stored,RECEIVED).sha256());
        for(String key:new String[]{"source","asOf","fetchedAt","updatedAt"}) {JSONObject broken=new JSONObject(stored.toString());broken.remove(key);assertThrows(RuntimeException.class,()->ExchangeRate.validatedSnapshot(broken,RECEIVED));}
        assertThrows(RuntimeException.class,()->ExchangeRate.validatedSnapshot(new JSONObject(stored.toString()).put("source","unknown"),RECEIVED));
        assertThrows(RuntimeException.class,()->ExchangeRate.validatedSnapshot(new JSONObject(stored.toString()).put("fetchedAt","2026-11-13T00:00:00Z"),RECEIVED));
    }
    @Test public void anUnwritableAttemptCacheRetainsThePreviousQuoteAndDoesNotNeedANetworkFetch() throws Exception {
        var loaded=ExchangeRate.class.getDeclaredField("cacheLoaded");var rates=ExchangeRate.class.getDeclaredField("rates");
        var attempt=ExchangeRate.class.getDeclaredField("lastAttemptAt");var budget=ExchangeRate.class.getDeclaredField("attempts");
        for(var field:new java.lang.reflect.Field[]{loaded,rates,attempt,budget})field.setAccessible(true);
        Object oldLoaded=loaded.get(null),oldRates=rates.get(null),oldAttempt=attempt.get(null);
        Map<Object,Object> oldBudget=new java.util.HashMap<>((Map<?,?>)budget.get(null));
        String cache=System.getProperty("iafm.exchangeRate.cache"),provider=System.getProperty("iafm.exchangeRate.provider");
        java.nio.file.Path parentFile=java.nio.file.Files.createTempFile("fx-parent-is-file-", ".tmp");
        try {
            loaded.set(null,true);((Map<?,?>)budget.get(null)).clear();
            System.setProperty("iafm.exchangeRate.cache",parentFile.resolve("rates.json").toString());
            System.setProperty("iafm.exchangeRate.provider","ecb");
            ExchangeRate.getExchangeRate(); // createDirectories fails before the fetch branch.
            assertSame(oldRates,rates.get(null));assertFalse(ExchangeRate.due("ecb",System.currentTimeMillis()));
            assertFalse(java.nio.file.Files.exists(parentFile.resolve("rates.json")));
        } finally {
            loaded.set(null,oldLoaded);rates.set(null,oldRates);attempt.set(null,oldAttempt);
            ((Map<Object,Object>)budget.get(null)).clear();((Map<Object,Object>)budget.get(null)).putAll(oldBudget);
            if(cache==null)System.clearProperty("iafm.exchangeRate.cache");else System.setProperty("iafm.exchangeRate.cache",cache);
            if(provider==null)System.clearProperty("iafm.exchangeRate.provider");else System.setProperty("iafm.exchangeRate.provider",provider);
            java.nio.file.Files.deleteIfExists(parentFile);
        }
    }

}
