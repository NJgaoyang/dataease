package io.dataease.enhance;

import io.dataease.api.permissions.login.dto.PwdLoginDTO;
import io.dataease.auth.vo.TokenVO;
import io.dataease.utils.RsaUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Original DataEase login URLs and RSA transport are retained for Vue compatibility. */
@RestController("loginServer")
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceLoginServer {
    private final EnhanceIdentityService identity;
    public EnhanceLoginServer(EnhanceIdentityService identity) { this.identity = identity; }

    @PostMapping("/login/localLogin")
    public TokenVO login(@RequestBody PwdLoginDTO request) {
        return identity.login(RsaUtils.decryptStr(request.getName()), RsaUtils.decryptStr(request.getPwd()));
    }

    @GetMapping("/login/refresh")
    public TokenVO refresh() {
        var principal = EnhanceSession.current();
        return identity.issue(principal.userId(), principal.orgId());
    }

    @GetMapping("/logout")
    public void logout() { identity.logoutEverywhere(EnhanceSession.current().userId()); }
}