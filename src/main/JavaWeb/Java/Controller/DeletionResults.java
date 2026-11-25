package Controller;

import DatabaseService.RequestIdentity;
import Model.User;
import jakarta.servlet.http.*;
import org.json.JSONObject;

/** A server-session proof permits only its actor's exact committed deletion result. */
public final class DeletionResults {
    static final String ATTRIBUTE="finance.last-deletion-result";
    private DeletionResults() { }
    public record Proof(int actorId,String generation,String operationId) implements java.io.Serializable {
        @java.io.Serial private static final long serialVersionUID=1L;
        public Proof{
            if(actorId<=0||!RequestIdentity.validGeneration(generation)||!RequestIdentity.validGeneration(operationId))throw new IllegalArgumentException("Invalid deletion result proof");
        }
        public User actor(){User user=new User();user.setId(actorId);user.setGeneration(generation);return user;}
    }
    private static boolean same(User current,Proof proof){return current.getId()==proof.actorId()&&proof.generation().equalsIgnoreCase(current.getGeneration());}
    /** Called inside the already-authorized atomic delete action, before SQL removal. */
    public static void prepare(HttpServletRequest request,User actor){
        if(!DatabaseService.FinanceCluster.enabled())return;
        String operation=request.getHeader("X-IAFM-Operation-Id");
        if(!"/deleteUser".equals(request.getServletPath())||!RequestIdentity.validGeneration(operation))throw new ApiSupport.Failure(400,"A stable deletion operation identity is required.");
        HttpSession session=request.getSession(false);if(session==null)throw new ApiSupport.Failure(401,"Please sign in again.");
        Proof proof=new Proof(actor.getId(),actor.getGeneration(),operation.toLowerCase(java.util.Locale.ROOT));
        synchronized(session){Object current=session.getAttribute("User");if(!(current instanceof User user)||!same(user,proof))throw new ApiSupport.Failure(401,"Please sign in again.");session.setAttribute(ATTRIBUTE,proof);}
    }
    /** An old trusted principal also survives a JVM loss before marker replication. */
    static Proof candidate(HttpServletRequest request,String operation){
        if(!RequestIdentity.validGeneration(operation))return null;
        try{
            HttpSession session=request.getSession(false);if(session==null)return null;
            synchronized(session){
                Object principal=session.getAttribute("User"),stored=session.getAttribute(ATTRIBUTE);
                if(stored instanceof Proof proof){
                    if(principal instanceof User current&&!same(current,proof)){session.removeAttribute(ATTRIBUTE);stored=null;}
                    else return proof.operationId().equalsIgnoreCase(operation)?proof:null;
                }
                if(principal instanceof User current&&current.getId()>0&&RequestIdentity.validGeneration(current.getGeneration()))
                    return new Proof(current.getId(),current.getGeneration(),operation.toLowerCase(java.util.Locale.ROOT));
                return null;
            }
        }catch(IllegalStateException expired){return null;}
    }
    /** Remove authentication, keep only a narrow result proof and the same cookie. */
    public static void finish(HttpServletRequest request,User actor,JSONObject result){
        if(!result.optBoolean("accountDeleted",false)||!RequestIdentity.validGeneration(result.optString("operationId",""))){ApiSupport.invalidateMatchingSession(request,actor);return;}
        finish(request,new Proof(actor.getId(),actor.getGeneration(),result.getString("operationId")));
    }
    static void finish(HttpServletRequest request,Proof proof){
        try{
            HttpSession session=request.getSession(false);if(session==null)return;
            synchronized(session){
                Object current=session.getAttribute("User");
                if(current instanceof User principal&&!same(principal,proof)){session.removeAttribute(ATTRIBUTE);return;}
                session.removeAttribute("User");session.setAttribute(ATTRIBUTE,proof);
                // Do not rotate/invalidate the cookie here: a lost response must
                // leave the original cookie able to query this one terminal result.
            }
        }catch(IllegalStateException expired){ /* Never recreate an expired session. */ }
    }
    public static void forget(HttpSession session){session.removeAttribute(ATTRIBUTE);}
}
