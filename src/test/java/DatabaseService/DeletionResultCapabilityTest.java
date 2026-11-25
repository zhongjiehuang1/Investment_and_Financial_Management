package DatabaseService;

import jakarta.servlet.http.*;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeletionResultCapabilityTest {
    @Test public void digestBindsOperationOwnerCookieAndDomainButIgnoresOnlyKnownRoute(){
        String cookie="0123456789abcdef0123456789abcdef",op=UUID.randomUUID().toString(),owner="1:7:"+UUID.randomUUID();
        String hash=DeletionResultCapability.digest("synthetic-private",cookie+".n1",op,owner);
        assertEquals(64,hash.length());assertEquals(hash,DeletionResultCapability.digest("synthetic-private",cookie+".n2",op,owner));
        assertNotEquals(hash,DeletionResultCapability.digest("synthetic-private",cookie,UUID.randomUUID().toString(),owner));
        assertNotEquals(hash,DeletionResultCapability.digest("synthetic-private",cookie,op,owner+"x"));
        assertNotEquals(hash,DeletionResultCapability.digest("synthetic-private","fedcba9876543210fedcba9876543210",op,owner));
        assertNull(DeletionResultCapability.base(cookie+".unknown"));assertNull(DeletionResultCapability.base("short"));assertNull(DeletionResultCapability.base(null));
    }
    @Test public void existingCookieMustMatchAuthenticatedSessionButLostSessionQueryDoesNotCreateOne(){
        String cookie="0123456789abcdef0123456789abcdef";
        HttpSession session=(HttpSession)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpSession.class},(p,m,a)->{
            if(m.getName().equals("getId"))return cookie+".n2";throw new AssertionError(m.getName());
        });
        HttpServletRequest live=request(cookie+".n1",true,session);
        assertEquals(cookie,DeletionResultCapability.cookie(live,true));
        assertNull(DeletionResultCapability.cookie(request("fedcba9876543210fedcba9876543210",true,session),true));
        assertNull(DeletionResultCapability.cookie(request(cookie,false,session),false));
        assertEquals(cookie,DeletionResultCapability.cookie(request(cookie,true,null),false));
        assertNull(DeletionResultCapability.cookie(request(cookie,true,null),true));
    }
    private HttpServletRequest request(String cookie,boolean fromCookie,HttpSession session){
        return (HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletRequest.class},(p,m,a)->switch(m.getName()){
            case "getRequestedSessionId"->cookie;case "isRequestedSessionIdFromCookie"->fromCookie;
            case "getSession"->{assertNotNull(a);assertEquals(false,a[0]);yield session;}
            default->throw new AssertionError(m.getName());
        });
    }
}
