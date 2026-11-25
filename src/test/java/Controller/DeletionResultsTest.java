package Controller;

import Model.User;
import jakarta.servlet.http.*;
import org.junit.Test;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.Assert.*;

public class DeletionResultsTest {
    static final class Fixture {
        final Map<String,Object> values=new HashMap<>();boolean valid=true;int creates,rotations,invalidations;
        final String op=UUID.randomUUID().toString();final User user=actor(1);
        final HttpSession session;final HttpServletRequest request;
        Fixture(){
            values.put("User",user);
            session=(HttpSession)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpSession.class},(p,m,a)->{
                if(!valid)throw new IllegalStateException("expired");return switch(m.getName()){
                    case "getAttribute"->values.get(a[0]);case "setAttribute"->{values.put((String)a[0],a[1]);yield null;}
                    case "removeAttribute"->{values.remove(a[0]);yield null;}case "invalidate"->{invalidations++;valid=false;yield null;}
                    default->throw new AssertionError(m.getName());};});
            request=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletRequest.class},(p,m,a)->switch(m.getName()){
                case "getSession"->{if(a==null||Boolean.TRUE.equals(a[0]))creates++;yield session;}
                case "getHeader"->"X-IAFM-Operation-Id".equals(a[0])?op:null;case "getServletPath"->"/deleteUser";
                case "changeSessionId"->{rotations++;yield "changed";}default->throw new AssertionError(m.getName());});
        }
    }
    static User actor(int id){User u=new User();u.setId(id);u.setGeneration(UUID.randomUUID().toString());return u;}
    @Test public void committedDeletionRetainsOnlyItsExactResultProofWithoutCreatingOrRotatingSession()throws Exception{
        String old=System.getProperty("iafm.cluster.enabled");System.setProperty("iafm.cluster.enabled","true");
        try{
            Fixture f=new Fixture();DeletionResults.prepare(f.request,f.user);
            DeletionResults.Proof proof=DeletionResults.candidate(f.request,f.op);assertNotNull(proof);
            DeletionResults.finish(f.request,proof);assertFalse(f.values.containsKey("User"));assertEquals(proof,DeletionResults.candidate(f.request,f.op));
            assertNull(DeletionResults.candidate(f.request,UUID.randomUUID().toString()));assertEquals(0,f.creates);assertEquals(0,f.rotations);assertEquals(0,f.invalidations);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(proof);}
            try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){assertEquals(proof,in.readObject());}
        }finally{if(old==null)System.clearProperty("iafm.cluster.enabled");else System.setProperty("iafm.cluster.enabled",old);}
    }
    @Test public void newerPrincipalAndExpiredSessionCannotRestoreOldAuthenticationOrResultScope(){
        Fixture f=new Fixture();DeletionResults.Proof old=new DeletionResults.Proof(f.user.getId(),f.user.getGeneration(),f.op);
        f.values.put(DeletionResults.ATTRIBUTE,old);User newer=actor(2);f.values.put("User",newer);
        DeletionResults.Proof candidate=DeletionResults.candidate(f.request,f.op);assertEquals(2,candidate.actorId());assertEquals(newer.getGeneration(),candidate.generation());assertFalse(f.values.containsKey(DeletionResults.ATTRIBUTE));
        DeletionResults.finish(f.request,old);assertSame(newer,f.values.get("User"));assertFalse(f.values.containsKey(DeletionResults.ATTRIBUTE));
        f.valid=false;assertNull(DeletionResults.candidate(f.request,f.op));DeletionResults.finish(f.request,old);assertEquals(0,f.creates);
    }
    @Test public void oldServerPrincipalCanProveItsIdentityAfterJvmLossButLoginExplicitlyForgetsTheProof(){
        Fixture f=new Fixture();DeletionResults.Proof candidate=DeletionResults.candidate(f.request,f.op);assertEquals(f.user.getGeneration(),candidate.generation());
        f.values.put(DeletionResults.ATTRIBUTE,candidate);DeletionResults.forget(f.session);assertFalse(f.values.containsKey(DeletionResults.ATTRIBUTE));assertSame(f.user,f.values.get("User"));
        assertThrows(IllegalArgumentException.class,()->new DeletionResults.Proof(0,f.user.getGeneration(),f.op));
    }
}
