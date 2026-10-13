package io.dataease.system.manage;

import io.dataease.api.permissions.auth.dto.BusiPerCheckDTO;
import io.dataease.enhance.EnhanceCoreAuthorization;
import io.dataease.license.config.XpackInteract;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CorePermissionManage {

    @Autowired(required = false)
    private EnhanceCoreAuthorization enhanceCoreAuthorization;

    @XpackInteract(value = "corePermissionManage", replace = true)
    public boolean checkAuth(BusiPerCheckDTO dto) {
        // Community fallback remains intact unless the independent enhancement
        // is explicitly activated. Do not alter licensed X-Pack interactions.
        return enhanceCoreAuthorization == null || enhanceCoreAuthorization.check(dto);
    }
}