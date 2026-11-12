package Controller;

import Model.User;
import DatabaseService.RequestIdentity;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;

/** Publish in-place preference edits to Tomcat's session delta after each request. */
@WebFilter("/*")
public final class SessionReplicationTouch implements Filter {
    @Override public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain)throws IOException,ServletException{
        try { chain.doFilter(request,response); }
        finally { touch((HttpServletRequest)request); }
    }
    static void touch(HttpServletRequest request){
        try {
            // Never create a session or retain a pre-request User reference:
            // logout/revocation and a concurrent newer login must stay final.
            HttpSession session=request.getSession(false);
            if(session==null)return;
            synchronized(session){
                Object current=session.getAttribute("User");
                if(current instanceof User user&&user.getId()>0&&RequestIdentity.validGeneration(user.getGeneration()))
                    session.setAttribute("User",user);
            }
        }catch(IllegalStateException expired){ /* Concurrent invalidation must not resurrect a session. */ }
    }
}
