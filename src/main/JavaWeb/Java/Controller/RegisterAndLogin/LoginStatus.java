package Controller.RegisterAndLogin;

import Controller.ApiSupport;
import Controller.ClusterAvailability;
import DatabaseService.FinanceCluster;
import org.json.JSONObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

/** No session/credential grant: exposes only a bound prior attempt's terminal HTTP code. */
@WebServlet("/loginStatus")
public final class LoginStatus extends HttpServlet {
    @Override protected void doPost(HttpServletRequest request,HttpServletResponse response){
        ApiSupport.prepare(response);
        try{
            request.setCharacterEncoding("UTF-8");ApiSupport.checkOrigin(request);
            JSONObject input=ApiSupport.input(request);String email=ApiSupport.text(input,"emailAddress");
            if(email.length()>50)throw new IllegalArgumentException("Invalid sign-in result details");
            if(!FinanceCluster.enabled())throw new ClusterAvailability.Unavailable();
            JSONObject result=FinanceCluster.get().loginStatus(ApiSupport.text(input,"operationId"),LoginThrottle.client(request),email);
            int status=result.getInt("httpStatus");response.setStatus(status);
            if(status==429)response.setHeader("Retry-After","60");
            ApiSupport.write(response,result);
        }catch(ApiSupport.Failure invalid){response.setStatus(invalid.httpStatus());ApiSupport.write(response,new JSONObject().put("status","failure").put("message","Sign-in result request denied."));}
        catch(IllegalArgumentException|org.json.JSONException invalid){response.setStatus(400);ApiSupport.write(response,new JSONObject().put("status","failure").put("message","Enter valid sign-in result details."));}
        catch(Exception unavailable){response.setStatus(503);ApiSupport.write(response,new JSONObject().put("status","failure").put("code","CLUSTER_UNAVAILABLE").put("message",ClusterAvailability.MESSAGE));}
    }
}
