package Controller;

import org.json.JSONObject;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

/** Keep the frontend readable during an outage; never execute stateful routes on a passive node. */
@WebFilter("/*")
public final class ClusterGuard implements Filter {
    // Bound business requests before readiness SQL. This leaves nominal pool
    // capacity for health; independent health/background probes can still contend.
    private static final Semaphore BUSINESS_SLOTS = new Semaphore(3);
    private final BooleanSupplier boundedSinglePool, ready;
    private final Semaphore businessSlots;
    public ClusterGuard() { this(DatabaseService.FinanceCluster::boundedSingleDatabasePoolSelected, ClusterAvailability::ready, BUSINESS_SLOTS); }
    ClusterGuard(BooleanSupplier boundedSinglePool,BooleanSupplier ready,Semaphore businessSlots) {
        this.boundedSinglePool=boundedSinglePool;this.ready=ready;this.businessSlots=businessSlots;
    }

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
        boolean business = needsReadiness(req.getServletPath(), req.getMethod());
        boolean held = false;
        try {
            if (business) {
                boolean bounded;
                try { bounded=boundedSinglePool.getAsBoolean(); }
                catch(RuntimeException invalid) { unavailable((HttpServletResponse) response); return; }
                if (bounded) {
                    // Admission is before *any* readiness SQL, identity checks or
                    // servlet work. Rejected operations never enter a transaction.
                    if (!businessSlots.tryAcquire()) {
                        HttpServletResponse res=(HttpServletResponse) response;
                        ApiSupport.prepare(res); ApiSupport.busy(res); return;
                    }
                    held=true;
                }
                if (!ready.getAsBoolean()) { unavailable((HttpServletResponse) response); return; }
            }
            chain.doFilter(request, response);
        } finally { if (held) businessSlots.release(); }
    }
    private static void unavailable(HttpServletResponse res) {
        ApiSupport.prepare(res); res.setStatus(503); res.setHeader("Retry-After", "15");
        ApiSupport.write(res, new JSONObject().put("status", "failure").put("code", "CLUSTER_UNAVAILABLE")
                .put("message", ClusterAvailability.MESSAGE));
    }
}
