package io.dataease.enhance;

import io.dataease.permission.util.V3UserUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Preserve the native DataEase user context while servicing enhancement requests.
 * Uses only getUid/setUid, both public methods already used by upstream core code.
 */
@Component
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceNativeIdentityAdapter implements EnhanceNativeIdentity {
    @Override
    public Scope enter(EnhanceSession.Identity principal) {
        if (principal == null || principal.userId() <= 0L) {
            throw new IllegalArgumentException("Verified principal required");
        }
        Long previous = V3UserUtil.getUid();
        V3UserUtil.setUid(principal.userId());
        return () -> V3UserUtil.setUid(previous);
    }
}