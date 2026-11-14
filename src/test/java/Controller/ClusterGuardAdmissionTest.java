package Controller;
import jakarta.servlet.*;import jakarta.servlet.http.*;
import org.json.JSONObject;import org.junit.Test;
import java.io.*;import java.lang.reflect.Proxy;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Exercises the actual filter boundary, before readiness, authentication or servlet dispatch. */
public class ClusterGuardAdmissionTest {
 static HttpServletRequest request(String path,String method){return (HttpServletRequest)Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),new Class[]{HttpServletRequest.class},(o,m,a)->{
  if(m.getName().equals("getServletPath"))return path;if(m.getName().equals("getMethod"))return method;return null;
 });}
 static final class Reply {
  int status;StringWriter body=new StringWriter();Map<String,String> headers=new HashMap<>();
  HttpServletResponse response(){return (HttpServletResponse)Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),new Class[]{HttpServletResponse.class},(o,m,a)->{
   if(m.getName().equals("setStatus")){status=(int)a[0];return null;}if(m.getName().equals("setHeader")){headers.put((String)a[0],(String)a[1]);return null;}if(m.getName().equals("getWriter"))return new PrintWriter(body);return null;
  });}
 }
 @Test public void excessRequestsAreRejectedBeforeReadinessAndHealthRemainsIndependent()throws Exception{
  Semaphore slots=new Semaphore(3);AtomicInteger readyCalls=new AtomicInteger(),dispatch=new AtomicInteger();ClusterGuard filter=new ClusterGuard(()->true,()->{readyCalls.incrementAndGet();return true;},slots);
  CountDownLatch inside=new CountDownLatch(3),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(3);List<Future<?>> running=new ArrayList<>();
  try {
   for(int i=0;i<3;i++)running.add(pool.submit(()->{try{filter.doFilter(request("/pageData","POST"),new Reply().response(),(req,res)->{dispatch.incrementAndGet();inside.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new ServletException("timeout");}catch(InterruptedException x){Thread.currentThread().interrupt();throw new ServletException(x);}});}catch(Exception e){throw new RuntimeException(e);}}));
   assertTrue(inside.await(3,TimeUnit.SECONDS));Reply rejected=new Reply();long start=System.nanoTime();filter.doFilter(request("/addNonInvestmentAsset","POST"),rejected.response(),(req,res)->fail("mutation dispatched"));long millis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
   assertEquals(429,rejected.status);assertTrue("no admission queue",millis<250);assertEquals(3,readyCalls.get());assertEquals(3,dispatch.get());JSONObject body=new JSONObject(rejected.body.toString());assertEquals("SERVER_BUSY",body.getString("code"));assertEquals("NOT_STARTED",body.getString("dispatchState"));assertFalse(body.has("durability"));
   AtomicInteger exempt=new AtomicInteger();for(String path:List.of("/api/server","/api/cluster-health","/internal/recovery-ready","/assets/app.js"))filter.doFilter(request(path,"GET"),new Reply().response(),(req,res)->exempt.incrementAndGet());assertEquals(4,exempt.get());assertEquals(3,readyCalls.get());
  } finally {release.countDown();for(Future<?> f:running)f.get(5,TimeUnit.SECONDS);pool.shutdownNow();}
  assertEquals(3,slots.availablePermits());
 }
 @Test public void acceptedRequestStillChecksReadinessAndRealOutageIs503()throws Exception{
  Semaphore slots=new Semaphore(3);ClusterGuard filter=new ClusterGuard(()->true,()->false,slots);Reply r=new Reply();filter.doFilter(request("/pageData","POST"),r.response(),(req,res)->fail("must not run"));assertEquals(503,r.status);assertEquals("CLUSTER_UNAVAILABLE",new JSONObject(r.body.toString()).getString("code"));assertEquals(3,slots.availablePermits());
 }
 @Test public void invalidSinglePoolConfigurationFailsClosed()throws Exception{
  Semaphore slots=new Semaphore(3);AtomicInteger ready=new AtomicInteger();ClusterGuard filter=new ClusterGuard(()->{throw new IllegalStateException("synthetic");},()->{ready.incrementAndGet();return true;},slots);Reply r=new Reply();filter.doFilter(request("/pageData","POST"),r.response(),(req,res)->fail("must not run"));assertEquals(503,r.status);assertEquals(0,ready.get());assertEquals(3,slots.availablePermits());
 }
 @Test public void permitsAreReturnedEvenWhenBusinessThrows()throws Exception{
  Semaphore slots=new Semaphore(3);ClusterGuard filter=new ClusterGuard(()->true,()->true,slots);try{filter.doFilter(request("/deleteUser","POST"),new Reply().response(),(req,res)->{throw new ServletException("synthetic");});fail();}catch(ServletException expected){}assertEquals(3,slots.availablePermits());
 }
 @Test public void nonPooledModesPreserveExistingBehavior()throws Exception{
  Semaphore slots=new Semaphore(0);AtomicInteger calls=new AtomicInteger();ClusterGuard filter=new ClusterGuard(()->false,()->true,slots);filter.doFilter(request("/api/v1/assets","GET"),new Reply().response(),(req,res)->calls.incrementAndGet());assertEquals(1,calls.get());
 }
 @Test public void statefulAndAnonymousReadinessRoutesAreCovered(){
  for(String path:List.of("/login","/register","/logOff","/operationStatus","/loginStatus","/pageData","/api/v1/assets","/getExchangeRates"))assertTrue(path,ClusterGuard.needsReadiness(path,path.equals("/getExchangeRates")||path.startsWith("/api/")?"GET":"POST"));
  assertFalse(ClusterGuard.needsReadiness("/api/server","GET"));assertFalse(ClusterGuard.needsReadiness("/internal/recovery-ready","GET"));assertFalse(ClusterGuard.needsReadiness("/assets/app.js","GET"));
 }
}
