package Controller;

import DatabaseService.FinanceCluster;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Private recovery status is deliberately independent of the external readiness admission check. */
@WebServlet("/internal/recovery-ready")
public final class ClusterRecoveryStatus extends HttpServlet {
    @Override protected void doGet(HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        String token=ClusterAvailability.setting("iafm.cluster.health.token","IAFM_CLUSTER_HEALTH_TOKEN","");
        String supplied=request.getHeader("Authorization");
        if(token.isEmpty()||supplied==null||!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(404);return;
        }
        try { response.setStatus(FinanceCluster.get().recoveryProbe()?204:503); }
        catch(Exception failure){response.setStatus(503);}
    }
}
