package com.example.wallet_transfer.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Requires a valid Bearer token on API endpoints. On success, the authenticated
 * user id is placed on the request as an attribute ("authUserId") for controllers.
 *
 * Open paths (no auth): health/metrics/actuator (platform probes) and the token
 * mint helper (so a client can obtain a token to start with).
 */
@Component
@Order(2)   // after the correlation-id filter (Order 1)
public class BearerAuthFilter extends OncePerRequestFilter {

    public static final String AUTH_USER_ATTR = "authUserId";
    private final TokenService tokenService;

    public BearerAuthFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // leave probes and the token endpoint open
        return path.startsWith("/actuator")
                || path.equals("/metrics")
                || path.startsWith("/auth/token");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            unauthorized(response, "Missing or malformed Authorization header");
            return;
        }
        String token = header.substring("Bearer ".length()).trim();
        Optional<String> userId = tokenService.verify(token);
        if (userId.isEmpty()) {
            unauthorized(response, "Invalid token");
            return;
        }
        request.setAttribute(AUTH_USER_ATTR, userId.get());
        chain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);   // 401
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"" + msg + "\"}");
    }
}
