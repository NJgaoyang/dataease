package io.dataease.enhance;

import io.dataease.permission.util.V3UserUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

/**
 * Additional defense if an upstream interceptor changes native identity after
 * EnhanceTokenFilter has verified and installed the request principal.
 */
@Component
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceNativeIdentityGuard implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        var identity = EnhanceSession.peek();
        if (identity == null) {
            // Public URLs are processed without a logged-in user. The token filter
            // makes the public-route decision before an MVC handler is reached.
            return true;
        }
        if (!Objects.equals(V3UserUtil.getUid(), identity.userId())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }
}