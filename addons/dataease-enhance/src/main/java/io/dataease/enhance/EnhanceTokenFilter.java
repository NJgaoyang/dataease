package io.dataease.enhance;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Set;

/** Fail closed for authenticated API requests. Existing signed public-share APIs retain their own verification. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceTokenFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC = Set.of(
            "/login/localLogin", "/dekey", "/model", "/sysParameter/ui",
            "/sysParameter/defaultLogin", "/favicon.ico");
    private final EnhanceIdentityService identity;
    private EnhanceNativeIdentity nativeIdentity;

    public EnhanceTokenFilter(EnhanceIdentityService identity) { this.identity = identity; }

    @Autowired(required = false)
    public void setNativeIdentity(EnhanceNativeIdentity nativeIdentity) {
        this.nativeIdentity = nativeIdentity;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/de2api/")) path = path.substring("/de2api".length());
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || PUBLIC.contains(path)) return true;
        // Only expected public SPA entrypoints and static assets are exempt.
        // Share/embed URLs remain protected until upstream signed-link checks are
        // integrated and verified with the enhancement's identity flow.
        if (path.equals("/") || path.equals("/index.html") || path.equals("/panel.html") ||
            path.equals("/mobile.html") || path.startsWith("/assets/") ||
            path.startsWith("/static/") || path.startsWith("/websocket")) return true;
        // API endpoints always require login, including non-de2api development routes.
        return path.matches("^/[^/]+\\.(?:js|css|png|jpg|jpeg|gif|ico|svg|woff2?)$");
    }

    private boolean limitedNonAdminEndpoint(String path) {
        return path.startsWith("/enhance/") ||
                java.util.Set.of("/user/info", "/user/personInfo", "/user/ipInfo",
                    "/user/orgAdmin", "/org/mounted", "/org/page/tree",
                    "/menu/query", "/role/query", "/sysParameter/defaultSettings",
                    "/role/user/option", "/role/user/selected",
                    "/role/create", "/role/edit", "/role/mountUser",
                    "/role/unMountUser", "/user/edit", "/user/personEdit",
                    "/user/modifyPwd", "/org/page/lazyTree",
                    "/org/logScope", "/org/subOrgs", "/logout", "/login/refresh").contains(path) ||
                path.startsWith("/user/switch/") ||
                path.startsWith("/sys/org/member/") ||
                path.startsWith("/role/queryWithOid/") ||
                path.startsWith("/role/detail/") ||
                path.startsWith("/role/delete/") ||
                path.startsWith("/user/queryById/") ||
                path.startsWith("/user/mumberPager/") ||
                path.startsWith("/user/pager/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String token = request.getHeader("X-DE-TOKEN");
            EnhanceSession.Identity principal = identity.verify(token);
            EnhanceSession.set(principal);
            if (nativeIdentity == null) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Native user identity integration is unavailable");
            }
            // Bind to the native DataEase identity for every authenticated request, even
            // if a later core service only reads V3UserUtil.getUid(). Restore on completion.
            try (EnhanceNativeIdentity.Scope scope = nativeIdentity.enter(principal)) {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if (path.startsWith("/de2api/")) path = path.substring("/de2api".length());
                // Core query exports cannot yet safely enforce row/column policies.
                // Keep denying them for non-system administrators until proven safe.
                if (!principal.superAdmin() && !limitedNonAdminEndpoint(path)) {
                    throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                            "Core resource authorization is not installed");
                }
                chain.doFilter(request, response);
            }
        } catch (ResponseStatusException e) {
            if (!response.isCommitted()) {
                response.setStatus(e.getStatusCode().value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"message\":\"Authentication required\"}");
            }
        } finally {
            EnhanceSession.clear();
        }
    }
}