package io.dataease.enhance;

import io.dataease.api.permissions.auth.dto.BusiPerCheckDTO;
import io.dataease.constant.AuthEnum;
import io.dataease.constant.BusiResourceEnum;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Explicit community-owned fallback authorization. Never interprets UI visibility as permission.
 * Unknown types/actions, missing user identities and unregistered resources are denied.
 */
@Component
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceCoreAuthorization {
    private static final Map<BusiResourceEnum,String> RESOURCES = Map.of(
            BusiResourceEnum.PANEL,"dashboard",
            BusiResourceEnum.SCREEN,"screen",
            BusiResourceEnum.DATASET,"dataset",
            BusiResourceEnum.DATASOURCE,"datasource");
    private final EnhanceAcl acl;

    public EnhanceCoreAuthorization(EnhanceAcl acl) {
        this.acl=acl;
    }

    public boolean check(BusiPerCheckDTO request) {
        EnhanceSession.Identity principal=EnhanceSession.peek();
        if(principal==null || request==null || request.getId()==null ||
                request.getBusiEnum()==null || request.getAuthEnum()==null) {
            return false;
        }
        String type=RESOURCES.get(request.getBusiEnum());
        String action=action(request.getAuthEnum());
        if(type==null || action==null) return false;
        // This is an additional server-side check. Row/column filters and query identity
        // are not yet integrated, so EnhanceTokenFilter must still block non-admin BI routes.
        return acl.permitted(type,String.valueOf(request.getId()),action,principal.orgId());
    }

    private static String action(AuthEnum auth) {
        return switch(auth) {
            case READ -> "read";
            case MANAGE, AUTH -> "manage";
            case EXPORT, EXPORT_VIEW, EXPORT_DETAIL -> "export";
            default -> null;
        };
    }
}