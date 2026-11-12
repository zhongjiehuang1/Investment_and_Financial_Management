package Controller;

import DatabaseService.FinanceCluster;
import DatabaseService.RequestIdentity;
import org.json.JSONObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

@WebServlet("/operationStatus")
public final class OperationStatus extends HttpServlet {
    @Override protected void doPost(HttpServletRequest request,HttpServletResponse response) {
        ApiSupport.prepare(response);
        try{
            request.setCharacterEncoding("UTF-8");ApiSupport.checkOrigin(request);
            JSONObject input=ApiSupport.input(request);String operation=ApiSupport.text(input,"operationId");
            if(!RequestIdentity.validGeneration(operation))throw new ApiSupport.Failure(400,"A valid operation identity is required.");
            DeletionResults.Proof proof=DeletionResults.candidate(request,operation);
            if(proof!=null&&FinanceCluster.enabled()){
                JSONObject deleted=FinanceCluster.get().deletedUserStatus(proof.actor(),operation);
                if(deleted!=null){DeletionResults.finish(request,proof);ApiSupport.write(response,deleted);return;}
            }
            if(FinanceCluster.enabled()){
                JSONObject deleted=FinanceCluster.get().deletedUserCookieStatus(request,operation);
                if(deleted!=null){ApiSupport.write(response,deleted);return;}
            }
            ApiSupport.serveParsedRead(request,response,input,(user,body)->{
                RequestIdentity.verifyCurrentUser();return FinanceCluster.get().status(user,operation);
            });
        }catch(ApiSupport.Failure invalid){response.setStatus(invalid.httpStatus());ApiSupport.write(response,new JSONObject().put("status","failure").put("message","Operation result request denied."));}
        catch(IllegalArgumentException|org.json.JSONException invalid){response.setStatus(400);ApiSupport.write(response,new JSONObject().put("status","failure").put("message","Enter a valid operation identity."));}
        catch(Exception unavailable){response.setStatus(503);ApiSupport.write(response,new JSONObject().put("status","failure").put("code","CLUSTER_UNAVAILABLE").put("message",ClusterAvailability.MESSAGE));}
    }
}
