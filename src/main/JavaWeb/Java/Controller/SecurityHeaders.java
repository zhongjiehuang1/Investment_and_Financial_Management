package Controller;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Security headers cover both the static shell and API responses. */
@WebFilter("/*")
public final class SecurityHeaders implements Filter {
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse http = (HttpServletResponse) response;
        http.setHeader("X-Content-Type-Options", "nosniff");
        http.setHeader("X-Frame-Options", "DENY");
        http.setHeader("Referrer-Policy", "no-referrer");
        http.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        http.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data: blob:; connect-src 'self'; font-src 'self'; object-src 'none'; "
                + "base-uri 'self'; form-action 'self'; frame-ancestors 'none'");
        if ("TRACE".equals(((HttpServletRequest) request).getMethod())) {
            http.sendError(405); return;
        }
        chain.doFilter(request, response);
    }
}
