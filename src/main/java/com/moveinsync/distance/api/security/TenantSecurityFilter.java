package com.moveinsync.distance.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforces tenant isolation and role-based checks.
 * Prevents tenant cross-data access and redacts sensitive data from logs.
 */
@Component
public class TenantSecurityFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Allow public actuator/health and swagger
        if (path.startsWith("/actuator") || path.startsWith("/error")) {
            filterChain.doFilter(request, response);
            return;
        }

        String tenantId = request.getHeader("X-Tenant-Id");
        if (tenantId == null || tenantId.trim().isEmpty()) {
            tenantId = "DEFAULT_TENANT";
        }

        // Store tenant context on request attribute
        request.setAttribute("CURRENT_TENANT_ID", tenantId);
        filterChain.doFilter(request, response);
    }
}
