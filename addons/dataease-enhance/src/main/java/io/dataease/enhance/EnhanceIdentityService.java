package io.dataease.enhance;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.dataease.auth.vo.TokenVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceIdentityService {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder bcrypt;
    private final EnhanceSettings settings;
    private final Algorithm algorithm;

    public EnhanceIdentityService(JdbcTemplate jdbc, BCryptPasswordEncoder bcrypt, EnhanceSettings settings) {
        this.jdbc = jdbc;
        this.bcrypt = bcrypt;
        this.settings = settings;
        this.algorithm = Algorithm.HMAC256(settings.getTokenSecret() == null ? "" : settings.getTokenSecret());
    }

    public TokenVO login(String account, String password) {
        // A constant-time bcrypt comparison is performed even for unknown users.
        String dummyHash = "$2a$12$C6UzMDM.H6dfI/f/IKcEeOxYtB6GNTT0G2v7G7OSfHiXKb8rqLoW2";
        List<Map<String,Object>> matches = jdbc.queryForList(
                 "SELECT * FROM de_ext_user WHERE account = ?",
                account == null ? "" : account.trim().toLowerCase(java.util.Locale.ROOT));
        if (matches.isEmpty()) {
            bcrypt.matches(password == null ? "" : password, dummyHash);
            throw unauthenticated();
        }
        Map<String,Object> user = matches.get(0);
        long id = asLong(user.get("id"));
        if (asLong(user.get("enabled")) != 1 || asLong(user.get("locked_until")) > System.currentTimeMillis()) {
            throw unauthenticated();
        }
        boolean valid = password != null && bcrypt.matches(password, String.valueOf(user.get("password_hash")));
        if (!valid) {
            jdbc.update("UPDATE de_ext_user SET failed_attempts=failed_attempts+1," +
                    "locked_until=CASE WHEN failed_attempts+1 >= ? THEN ? ELSE locked_until END " +
                    "WHERE id=?", settings.getMaxLoginFailures(),
                    System.currentTimeMillis() + settings.getLockMinutes() * 60000L, id);
            throw unauthenticated();
        }
        jdbc.update("UPDATE de_ext_user SET failed_attempts=0,locked_until=0 WHERE id=?", id);
        List<Long> orgs = jdbc.queryForList(
                "SELECT org_id FROM de_ext_membership WHERE user_id=? ORDER BY org_id", Long.class, id);
        if (orgs.isEmpty()) throw unauthenticated();
        return issue(id, orgs.get(0));
    }

    public TokenVO issue(long uid, long orgId) {
        Map<String,Object> user = getEnabledUser(uid);
        Integer member = jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_membership m JOIN de_ext_org o ON o.id=m.org_id AND o.enabled=1 WHERE m.user_id=? AND m.org_id=?",
                Integer.class, uid, orgId);
        if (member == null || member == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not an organization member");
        Instant now = Instant.now();
        String token = JWT.create().withIssuer("dataease-enhance")
                .withClaim("uid", uid).withClaim("oid", orgId)
                .withClaim("tv", ((Number) user.get("token_version")).intValue())
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(java.util.Date.from(now))
                .withExpiresAt(java.util.Date.from(now.plus(settings.getSessionMinutes(), ChronoUnit.MINUTES)))
                .sign(algorithm);
        return new TokenVO(token, settings.getSessionMinutes() * 60L);
    }

    public EnhanceSession.Identity verify(String token) {
        if (token == null || token.isBlank()) throw unauthenticated();
        try {
            DecodedJWT decoded = JWT.require(algorithm).withIssuer("dataease-enhance").build().verify(token);
            Long uid = decoded.getClaim("uid").asLong();
            Long oid = decoded.getClaim("oid").asLong();
            Integer tv = decoded.getClaim("tv").asInt();
            if (uid == null || oid == null || tv == null) throw unauthenticated();
            Map<String,Object> user = getEnabledUser(uid);
            if (((Number) user.get("locked_until")).longValue() > System.currentTimeMillis()) {
                throw unauthenticated();
            }
            if (((Number)user.get("token_version")).intValue() != tv) throw unauthenticated();
            Integer member = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM de_ext_membership m JOIN de_ext_org o ON m.org_id=o.id AND o.enabled=1 WHERE m.user_id=? AND m.org_id=?",
                    Integer.class, uid, oid);
            if (member == null || member == 0) throw unauthenticated();
            return new EnhanceSession.Identity(uid, oid, String.valueOf(user.get("account")), isSuperAdmin(uid));
        } catch (Exception e) {
            throw unauthenticated();
        }
    }

    public void logoutEverywhere(long uid) {
        jdbc.update("UPDATE de_ext_user SET token_version=token_version+1 WHERE id=?", uid);
    }

    public Map<String,Object> getEnabledUser(long id) {
        List<Map<String,Object>> users = jdbc.queryForList("SELECT * FROM de_ext_user WHERE id=? AND enabled=1", id);
        if (users.isEmpty()) throw unauthenticated();
        return users.get(0);
    }

    public boolean isSuperAdmin(long uid) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_user_role ur JOIN de_ext_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code='SYS_ADMIN' AND r.system_role=1",
                Integer.class, uid);
        return count != null && count > 0;
    }

    public static long asLong(Object n) { return ((Number)n).longValue(); }

    public static ResponseStatusException unauthenticated() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials or session");
    }
}