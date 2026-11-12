package Controller;

import Model.User;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class SessionReplicationTouchTest {
    private static class Fixture {
        final User original=new User();final AtomicReference<Object> current=new AtomicReference<>();
        final AtomicBoolean expired=new AtomicBoolean(),created=new AtomicBoolean();final AtomicInteger sets=new AtomicInteger();
        final HttpSession session;final HttpServletRequest request;
        Fixture(){
            original.setId(1);original.setGeneration(UUID.randomUUID().toString());current.set(original);
            session=(HttpSession)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpSession.class},(p,m,a)->{
                if(expired.get())throw new IllegalStateException("expired");return switch(m.getName()){
                    case "getAttribute"->current.get();case "setAttribute"->{assertSame(current.get(),a[1]);sets.incrementAndGet();yield null;}default->throw new AssertionError(m.getName());};});
            request=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletRequest.class},(p,m,a)->{
                if(m.getName().equals("getSession")){if(a==null||Boolean.TRUE.equals(a[0]))created.set(true);return session;}throw new AssertionError(m.getName());});
        }
    }
    @Test public void finallyRepublishesTheCurrentPreferenceAfterNormalOrFailedRequests()throws Exception{
        Fixture f=new Fixture();new SessionReplicationTouch().doFilter(f.request,null,(q,r)->f.original.setCurrencyCurrentChosen("USD"));assertEquals(1,f.sets.get());assertEquals("USD",f.original.getCurrencyCurrentChosen());
        assertThrows(ServletException.class,()->new SessionReplicationTouch().doFilter(f.request,null,(q,r)->{throw new ServletException("synthetic");}));assertEquals(2,f.sets.get());assertFalse(f.created.get());
    }
    @Test public void revokedInvalidOrExpiredSessionIsNeverRecreatedOrRepopulated()throws Exception{
        for(int mode=0;mode<3;mode++){
            Fixture f=new Fixture();int action=mode;
            new SessionReplicationTouch().doFilter(f.request,null,(q,r)->{if(action==0)f.current.set(null);else if(action==1)f.expired.set(true);else f.original.setGeneration("revoked-invalid");});
            assertEquals(0,f.sets.get());assertFalse(f.created.get());
        }
        AtomicInteger creates=new AtomicInteger();HttpServletRequest absent=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletRequest.class},(p,m,a)->{assertEquals("getSession",m.getName());assertEquals(false,a[0]);creates.incrementAndGet();return null;});
        SessionReplicationTouch.touch(absent);assertEquals(1,creates.get());
    }
    @Test public void aNewerConcurrentLoginWinsOverTheOldRequestReference()throws Exception{
        Fixture f=new Fixture();User newer=new User();newer.setId(2);newer.setGeneration(UUID.randomUUID().toString());
        new SessionReplicationTouch().doFilter(f.request,null,(q,r)->f.current.set(newer));assertSame(newer,f.current.get());assertEquals(1,f.sets.get());assertFalse(f.created.get());
    }
}
