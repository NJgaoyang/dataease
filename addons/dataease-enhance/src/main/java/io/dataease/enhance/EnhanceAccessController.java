package io.dataease.enhance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/enhance")
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceAccessController {
    private final EnhanceIdentityService identity;
    private final EnhanceAcl acl;
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;

    public EnhanceAccessController(EnhanceIdentityService identity,EnhanceAcl acl,
                                   JdbcTemplate jdbc,BCryptPasswordEncoder encoder) {
        this.identity=identity;this.acl=acl;this.jdbc=jdbc;this.encoder=encoder;
    }

    @GetMapping("/permissions/check")
    public Map<String,Boolean> check(@RequestParam String type,@RequestParam String id,@RequestParam String action) {
        long orgId=EnhanceSession.current().orgId();
        if(type.length()>80 || id.length()>128 || !java.util.Set.of("read","manage","export").contains(action))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return Map.of("allowed",acl.permitted(type,id,action,orgId));
    }

    @GetMapping("/menus/check")
    public Map<String,Boolean> menu(@RequestParam String code) {
        if(code==null || code.length()>128) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return Map.of("allowed",acl.menuAllowed(code,EnhanceSession.current().orgId()));
    }

    public record PasswordChange(String oldPassword,String newPassword) {}

    @Transactional
    @PostMapping("/me/password")
    public void changePassword(@RequestBody PasswordChange change) {
        long uid=EnhanceSession.current().userId();
        var user=identity.getEnabledUser(uid);
        if(change==null || change.oldPassword()==null || change.newPassword()==null ||
                change.newPassword().length()<12 ||
                !encoder.matches(change.oldPassword(),String.valueOf(user.get("password_hash"))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid password");
        jdbc.update("UPDATE de_ext_user SET password_hash=?,token_version=token_version+1 WHERE id=?",
                encoder.encode(change.newPassword()),uid);
    }
}