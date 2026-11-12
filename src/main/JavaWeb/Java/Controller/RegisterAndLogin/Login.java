package Controller.RegisterAndLogin;

import Controller.ApiSupport;
import org.json.JSONObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@WebServlet("/login")
public class Login extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.prepare(response);
        try {
            request.setCharacterEncoding("UTF-8");
            ApiSupport.checkOrigin(request);
            JSONObject input = ApiSupport.input(request);
            String email = ApiSupport.text(input, "emailAddress");
            String password = input.getString("password");
            if (email.length() > 50 || password.isEmpty() || password.length() > 1024)
                throw new IllegalArgumentException("Invalid credentials");
            DatabaseService.FinanceCluster cluster=DatabaseService.FinanceCluster.enabled()?DatabaseService.FinanceCluster.get():null;
            if(cluster!=null&&cluster.atomicMode()){
                var outcome=cluster.login(request,LoginThrottle.client(request),email,password);
                response.setStatus(outcome.httpStatus());
                if(outcome.httpStatus()==429)response.setHeader("Retry-After","300");
                JSONObject result=outcome.response();
                if(outcome.httpStatus()==200){
                    Model.User authenticated=outcome.user();
                    if(authenticated==null)throw new IllegalStateException("Confirmed sign-in identity is unavailable");
                    HttpSession session=request.getSession(true);
                    synchronized(session){request.changeSessionId();Controller.DeletionResults.forget(session);session.setAttribute("User",authenticated);}
                    result.put("userGeneration",authenticated.getGeneration()).put("userName",authenticated.getForename()+" "+authenticated.getSurname()).put("userAccount",authenticated.getEmailAddress());
                }
                ApiSupport.write(response,result);return;
            }
            if (!LoginThrottle.allow(LoginThrottle.client(request), System.currentTimeMillis())) {
                response.setStatus(429);
                response.setHeader("Retry-After", "300");
                ApiSupport.write(response, new JSONObject().put("status", "failure")
                        .put("message", "Too many sign-in attempts. Try again in five minutes."));
                return;
            }
            Model.User user = new Model.User();
            DatabaseService.FinanceCluster.Work login=()->{
                if(!Model.Controller.RegisterAndLogin.Login.userLogin(user,email,password))
                    throw new ApiSupport.Failure(401,"Invalid email or password.");
                return new JSONObject().put("status","success");
            };
            JSONObject confirmed=cluster!=null?cluster.run(request,null,input,false,login):login.run();
            HttpSession session = request.getSession(true);
            synchronized (session) {
                request.changeSessionId();
                Controller.DeletionResults.forget(session);
                session.setAttribute("User", user);
            }
            ApiSupport.write(response, confirmed.put("status", "success")
                    .put("userGeneration", user.getGeneration()).put("userName", user.getForename() + " " + user.getSurname()).put("userAccount", user.getEmailAddress()));
        } catch (Controller.ClusterAvailability.Unavailable failure) {
            response.setStatus(503);
            ApiSupport.write(response,new JSONObject().put("status","failure").put("code","CLUSTER_UNAVAILABLE").put("message",Controller.ClusterAvailability.MESSAGE));
        } catch (ApiSupport.Failure failure) {
            response.setStatus(failure.httpStatus());
            ApiSupport.write(response, new JSONObject().put("status", "failure").put("message", "Request denied."));
        } catch (IllegalArgumentException | org.json.JSONException exception) {
            response.setStatus(400);
            ApiSupport.write(response, new JSONObject().put("status", "failure").put("message", "Enter an email and password."));
        } catch (Exception exception) {
            response.setStatus(503);
            ApiSupport.write(response,new JSONObject().put("status","failure").put("code","CLUSTER_UNAVAILABLE").put("message",Controller.ClusterAvailability.MESSAGE));
        }
    }
}
