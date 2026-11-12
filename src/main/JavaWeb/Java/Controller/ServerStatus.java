package Controller;

import org.json.JSONObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Public status deliberately contains no provider, region, address or database details. */
@WebServlet({"/api/server", "/api/cluster-health"})
public final class ServerStatus extends HttpServlet {
    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.prepare(response);
        if ("/api/server".equals(request.getServletPath())) {
            int id = ClusterAvailability.serverId();
            if (id == 0) {
                response.setStatus(503);
                ApiSupport.write(response, new JSONObject().put("status", "unavailable"));
            } else ApiSupport.write(response, new JSONObject().put("serverId", id));
            return;
        }
        boolean ready = ClusterAvailability.ready() && DatabaseService.FinanceCluster.enabled();
        String mode=null;
        if(ready)try {
            mode=DatabaseService.FinanceCluster.get().durabilityMode();
            ready="dual".equals(mode)||"single".equals(mode);
        }catch(Exception unavailable){ready=false;}
        response.setStatus(ready ? 200 : 503);
        JSONObject result = new JSONObject().put("status", ready ? "ready" : "unavailable");
        if(ready&&("dual".equals(mode)||"single".equals(mode)))
            result.put("replicationMode",mode).put("degraded","single".equals(mode));
        if (!ready) result.put("message", ClusterAvailability.MESSAGE);
        ApiSupport.write(response, result);
    }
}
