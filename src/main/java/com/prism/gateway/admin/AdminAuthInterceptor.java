package com.prism.gateway.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.prism.gateway.config.PrismProperties;

/**
 * Guards {@code /admin/**} with a single demo admin token, sent as {@code X-Admin-Token} or
 * {@code Authorization: Bearer}. Virtual keys never grant admin access.
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final byte[] token;

    public AdminAuthInterceptor(PrismProperties props) {
        this.token = props.adminToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }
        String presented = request.getHeader("X-Admin-Token");
        String auth = request.getHeader("Authorization");
        if (presented == null && auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            presented = auth.substring(7).trim();
        }
        if (presented != null && MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":{\"message\":\"Admin token required (X-Admin-Token header)\","
                + "\"type\":\"authentication_error\",\"code\":\"admin_auth_required\"}}");
        return false;
    }
}
