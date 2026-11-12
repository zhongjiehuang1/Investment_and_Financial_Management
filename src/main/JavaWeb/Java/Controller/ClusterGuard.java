package Controller;

import org.json.JSONObject;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Keep the frontend readable during an outage; never execute stateful routes on a passive node. */
@WebFilter("/*")
public final class ClusterGuard implements Filter {
    static boolean needsReadiness(String path, String method) {
        if ("/api/server".equals(path) || "/api/cluster-health".equals(path) || "/internal/recovery-ready".equals(path)) return false;
        if (!("GET".equals(method) || "HEAD".equals(method))) return true;
        return path != null && !path.isEmpty() && !"/".equals(path)
                && !path.startsWith("/assets/") && !path.startsWith("/View/") && !path.startsWith("/errors/")
                && !path.matches(".*\\.(html|js|css|json|svg|png|ico|txt|webmanifest|woff2?)$");
    }

    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        if (needsReadiness(req.getServletPath(), req.getMethod()) && !ClusterAvailability.ready()) {
            HttpServletResponse res = (HttpServletResponse) response;
            ApiSupport.prepare(res); res.setStatus(503); res.setHeader("Retry-After", "15");
            ApiSupport.write(res, new JSONObject().put("status", "failure").put("code", "CLUSTER_UNAVAILABLE")
                    .put("message", ClusterAvailability.MESSAGE));
            return;
        }
        chain.doFilter(request, response);
    }
}
