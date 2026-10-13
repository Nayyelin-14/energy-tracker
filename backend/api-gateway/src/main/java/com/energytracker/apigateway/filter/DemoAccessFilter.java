package com.energytracker.apigateway.filter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Restricts demo playground traffic to the demo user's data.
 *
 * Resolves the demo user ID from user-service on first use, then blocks
 * any request to user/device/usage/alert endpoints that references a
 * different userId.
 *
 * Uses a servlet filter (not GatewayFilter) because this is Spring Cloud
 * Gateway Server WebMVC — the servlet-based variant.
 */
@Component
public class DemoAccessFilter extends OncePerRequestFilter implements Ordered {

        private static final Logger log = LoggerFactory.getLogger(DemoAccessFilter.class);
        private static final Pattern NUMERIC = Pattern.compile("^\\d+$");

        /** Path prefixes that contain a {userId} path variable. */
        private static final List<String> USER_ID_PATH_PREFIXES = List.of(
                        "/api/v1/user/",
                        "/api/v1/device/user/",
                        "/api/v1/usage/",
                        "/api/v1/alert/user/");

        private final RestTemplate restTemplate = new RestTemplate();
        private final AtomicLong demoUserIdCache = new AtomicLong(-1);

        @Value("${demo.user.lookup-url:http://localhost:8100/api/v1/user/demo}")
        private String demoUserLookupUrl;

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                        HttpServletResponse response,
                        FilterChain filterChain) throws ServletException, IOException {

                final String path = request.getRequestURI();

                // Only protect endpoints that reference a userId
                if (!referencesUserId(path)) {
                        filterChain.doFilter(request, response);
                        return;
                }

                final Long demoUserId = resolveDemoUserId();
                if (demoUserId == null) {
                        // Cannot resolve demo user — fail open for availability
                        log.warn("Could not resolve demo user ID, allowing request through");
                        filterChain.doFilter(request, response);
                        return;
                }

                // Extract userId from path segments
                final String extractedUserId = extractUserIdFromPath(path);
                if (extractedUserId != null && NUMERIC.matcher(extractedUserId).matches()
                                && !extractedUserId.equals(String.valueOf(demoUserId))) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Demo playground only allows access to the demo user's data\"}");
                        return;
                }

                // Check query parameter userId
                final String userIdParam = request.getParameter("userId");
                if (userIdParam != null && NUMERIC.matcher(userIdParam).matches()
                                && !userIdParam.equals(String.valueOf(demoUserId))) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Demo playground only allows access to the demo user's data\"}");
                        return;
                }

                filterChain.doFilter(request, response);
        }

        private boolean referencesUserId(String path) {
                return USER_ID_PATH_PREFIXES.stream().anyMatch(path::startsWith);
        }

        private String extractUserIdFromPath(String path) {
                for (String prefix : USER_ID_PATH_PREFIXES) {
                        if (path.startsWith(prefix)) {
                                final String remainder = path.substring(prefix.length());
                                final String[] segments = remainder.split("/");
                                return segments.length > 0 && !segments[0].isEmpty() ? segments[0] : null;
                        }
                }
                return null;
        }

        @SuppressWarnings("unchecked")
        private Long resolveDemoUserId() {
                final long cached = demoUserIdCache.get();
                if (cached != -1) {
                        return cached;
                }
                try {
                        final Map<String, Object> body = restTemplate.getForObject(demoUserLookupUrl, Map.class);
                        if (body != null && body.get("userId") != null) {
                                final long id = Long.parseLong(body.get("userId").toString());
                                demoUserIdCache.compareAndSet(-1, id);
                                log.info("Resolved demo user ID: {}", id);
                                return id;
                        }
                } catch (Exception e) {
                        log.debug("Could not resolve demo user ID (user-service may not be ready): {}",
                                        e.getMessage());
                }
                return null;
        }

        @Override
        public int getOrder() {
                return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
                // Don't filter actuator or fallback endpoints
                final String path = request.getRequestURI();
                return path.startsWith("/actuator") || path.startsWith("/fallback");
        }
}
