package Controller.RegisterAndLogin;

import Controller.ApiSupport;
import org.json.JSONObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/logOff")
public class LogOff extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.serve(request, response, true, (user, input) -> {
            jakarta.servlet.http.HttpSession session=request.getSession(false);
            if(session==null)throw new ApiSupport.Failure(401,"Please sign in again.");
            synchronized(session) {
                Model.User current=(Model.User)session.getAttribute("User");
                if(current==null||!user.getGeneration().equals(current.getGeneration()))throw new ApiSupport.Failure(401,"Please sign in again.");
            }
            return new JSONObject().put("status", "success");
        },(user,result)->{
            if(!ApiSupport.invalidateMatchingSession(request,user))throw new ApiSupport.Failure(401,"Please sign in again.");
        });
    }
}
