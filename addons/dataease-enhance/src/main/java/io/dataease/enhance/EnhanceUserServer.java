package io.dataease.enhance;

import io.dataease.api.permissions.user.dto.*;
import io.dataease.api.permissions.user.vo.*;
import io.dataease.result.PageResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.*;

/** V3 UserApi-compatible administration. Stored profiles belong only to de_ext_* metadata. */
@RestController("userServer")
@RequestMapping("/user")
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceUserServer {
    private static final SecureRandom PASSWORD_RANDOM = new SecureRandom();
    private final EnhanceIdentityService identity;
    private final JdbcTemplate jdbc;
    private final EnhanceAcl acl;
    private final EnhanceAdminController admin;
    private final EnhanceOrgGovernanceController governance;
    private final BCryptPasswordEncoder encoder;

    public EnhanceUserServer(EnhanceIdentityService identity, JdbcTemplate jdbc, EnhanceAcl acl,
                             EnhanceAdminController admin, EnhanceOrgGovernanceController governance,
                             BCryptPasswordEncoder encoder) {
        this.identity = identity;
        this.jdbc = jdbc;
        this.acl = acl;
        this.admin = admin;
        this.governance = governance;
        this.encoder = encoder;
    }

    @GetMapping("/info")
    public Map<String,Object> info() {
        var p = EnhanceSession.current();
        return Map.of("id", String.valueOf(p.userId()),
                "uid", String.valueOf(p.userId()),
                "name", identity.getEnabledUser(p.userId()).get("display_name"),
                "oid", String.valueOf(p.orgId()), "language", "zh-CN");
    }

    @GetMapping("/personInfo")
    public UserFormVO personInfo() {
        return form(EnhanceSession.current().userId(), EnhanceSession.current().orgId());
    }

    @GetMapping("/queryById/{id}")
    public UserFormVO byId(@PathVariable long id) {
        var actor = EnhanceSession.current();
        long oid = actor.orgId();
        if (actor.userId() != id && !actor.superAdmin()) {
            acl.requireOrgAdmin(oid);
            if (!acl.member(id, oid)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return form(id, oid);
    }

    private UserFormVO form(long uid, long preferredOid) {
        var user = visibleUser(uid);
        List<Long> memberships = jdbc.queryForList(
                "SELECT org_id FROM de_ext_membership WHERE user_id=? ORDER BY org_id",
                Long.class, uid);
        if (memberships.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        long oid = memberships.contains(preferredOid) ? preferredOid : memberships.get(0);
        var vo = new UserFormVO();
        vo.setId(uid);
        vo.setDefaultOid(oid);
        vo.setAccount(String.valueOf(user.get("account")));
        vo.setName(String.valueOf(user.get("display_name")));
        vo.setEmail((String) user.get("email"));
        vo.setPhone((String) user.get("phone"));
        vo.setPhonePrefix((String) user.get("phone_prefix"));
        vo.setEnable(((Number) user.get("enabled")).intValue() == 1);
        vo.setModel("enhance");
        vo.setVariables(List.of());
        vo.setRoleIds(jdbc.queryForList(
                "SELECT ur.role_id FROM de_ext_user_role ur JOIN de_ext_role r ON r.id=ur.role_id " +
                "WHERE ur.user_id=? AND r.org_id=?", Long.class, uid, oid)
                .stream().map(String::valueOf).toList());
        return vo;
    }

    private Map<String,Object> visibleUser(long id) {
        var matches = jdbc.queryForList("SELECT * FROM de_ext_user WHERE id=? AND deleted=0", id);
        if (matches.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return matches.get(0);
    }

    @GetMapping("/ipInfo")
    public Map<String,Object> ipInfo() {
        var p = EnhanceSession.current();
        return Map.of("account",p.account(),"name", identity.getEnabledUser(p.userId()).get("display_name"));
    }

    @PostMapping("/pager/{page}/{size}")
    public PageResult<UserGridVO> globalUsers(@PathVariable int page,@PathVariable int size,
                                              @RequestBody(required = false) Map<String,Object> query) {
        acl.requireSystemAdmin();
        return users(page,size,query,true);
    }

    @PostMapping("/mumberPager/{page}/{size}")
    public PageResult<UserGridVO> memberUsers(@PathVariable int page,@PathVariable int size,
                                               @RequestBody(required = false) Map<String,Object> query) {
        return users(page,size,query,false);
    }

    private PageResult<UserGridVO> users(int page,int size,Map<String,Object> query,boolean global) {
        if (page < 1 || size < 1 || size > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var actor = EnhanceSession.current();
        Long oid = query != null && query.get("oid") != null && !query.get("oid").toString().isBlank()
                ? parseId(query.get("oid")) : (global ? null : actor.orgId());
        if (!global) acl.requireOrgAdmin(oid);
        String where = oid == null ? "WHERE u.deleted=0" :
                "JOIN de_ext_membership m ON m.user_id=u.id WHERE u.deleted=0 AND m.org_id=?";
        String keyword = query == null || query.get("keyword")==null ? "" : query.get("keyword").toString().trim();
        if (keyword.length() > 128) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        List<Object> bind = new ArrayList<>();
        if (oid != null) bind.add(oid);
        if (!keyword.isEmpty()) {
            // The keyword is always a JDBC parameter. LIKE wildcards are escaped by treating
            // them as ordinary literals using the database's SQL-standard ESCAPE clause.
            where += " AND (LOWER(u.account) LIKE ? ESCAPE '!' OR LOWER(u.display_name) LIKE ? ESCAPE '!')";
            String pattern = "%" + keyword.toLowerCase(Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            bind.add(pattern);
            bind.add(pattern);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM de_ext_user u " + where,
                Long.class, bind.toArray());
        List<Object> pageArgs = new ArrayList<>(bind);
        pageArgs.add(size);
        pageArgs.add((page-1)*size);
        List<UserGridVO> result = new ArrayList<>();
        for (Map<String,Object> user : jdbc.queryForList(
                "SELECT u.* FROM de_ext_user u " + where +
                " ORDER BY u.created_at DESC, u.id DESC LIMIT ? OFFSET ?", pageArgs.toArray())) {
            long id = ((Number) user.get("id")).longValue();
            var vo = new UserGridVO();
            vo.setId(id);
            vo.setAccount((String) user.get("account"));
            vo.setName((String) user.get("display_name"));
            vo.setEmail((String) user.get("email"));
            vo.setPhone((String) user.get("phone"));
            vo.setPhonePrefix((String) user.get("phone_prefix"));
            vo.setEnable(((Number) user.get("enabled")).intValue()==1);
            vo.setOrigin(0);
            vo.setCreateTime(((Number) user.get("created_at")).longValue());
            vo.setLocked(((Number) user.get("locked_until")).longValue()>System.currentTimeMillis());
            List<UserGridRoleItem> roles = new ArrayList<>();
            String sql = "SELECT r.id,r.name FROM de_ext_role r JOIN de_ext_user_role ur ON ur.role_id=r.id " +
                         "WHERE ur.user_id=?" + (oid == null ? "" : " AND r.org_id=?");
            Object[] args = oid == null ? new Object[]{id} : new Object[]{id,oid};
            for (Map<String,Object> role : jdbc.queryForList(sql,args)) {
                var item = new UserGridRoleItem();
                item.setId(((Number) role.get("id")).longValue());
                item.setName(String.valueOf(role.get("name")));
                roles.add(item);
            }
            vo.setRoleItems(roles);
            result.add(vo);
        }
        return new PageResult<>(result,total == null ? 0L : total,PageRequest.of(page-1,size));
    }

    private static long parseId(Object raw) {
        try { return Long.parseLong(String.valueOf(raw)); }
        catch (NumberFormatException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid ID"); }
    }

    private static String optionalProfile(String value,int max) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Profile value too long");
        return value.trim();
    }

    private static String newPassword() {
        byte[] bytes = new byte[24];
        PASSWORD_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * One-time initial password, never available from /user/defaultPwd.
     * The frontend displays this value once, then discards it.
     */
    @Transactional
    @PostMapping("/create")
    public String create(@RequestBody UserCreator creator, jakarta.servlet.http.HttpServletResponse response) {
        acl.requireSystemAdmin();
        if (creator == null || creator.getName() == null || creator.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String password = newPassword();
        long oid = creator.getDefaultOid() == null
                ? EnhanceSession.current().orgId() : creator.getDefaultOid();
        long id = Long.parseLong(admin.addUser(new EnhanceAdminController.NewUser(
                creator.getAccount(), creator.getName(), password, oid)).get("id"));
        jdbc.update("UPDATE de_ext_user SET email=?,phone_prefix=?,phone=?,enabled=? WHERE id=?",
                optionalProfile(creator.getEmail(),255), optionalProfile(creator.getPhonePrefix(),16),
                optionalProfile(creator.getPhone(),64), Boolean.FALSE.equals(creator.getEnable()) ? 0 : 1, id);
        if (creator.getRoleIds() != null && !creator.getRoleIds().isEmpty()) {
            governance.replaceMemberRoles(new EnhanceOrgGovernanceController.RoleChange(
                    oid,id,creator.getRoleIds()));
        }
        response.setHeader("Cache-Control", "no-store, no-cache, private");
        return "DE_ENHANCE_ONETIME:" + password;
    }

    @Transactional
    @PostMapping("/edit")
    public void edit(@RequestBody UserEditor editor) {
        if (editor == null || editor.getId() == null || editor.getDefaultOid() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        long uid = editor.getId();
        long oid = editor.getDefaultOid();
        acl.requireOrgAdmin(oid);
        if (!acl.member(uid,oid)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Map<String,Object> existing = visibleUser(uid);
        if (!String.valueOf(existing.get("account")).equalsIgnoreCase(editor.getAccount())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Account cannot be changed");
        }
        if (editor.getName() == null || editor.getName().isBlank() || editor.getName().length()>255)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (uid == 1L && Boolean.FALSE.equals(editor.getEnable()))
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        // Organization administrators cannot disable arbitrary global users.
        if (!EnhanceSession.current().superAdmin() && editor.getEnable()!=null &&
                (((Number)existing.get("enabled")).intValue()==1) != editor.getEnable()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        jdbc.update("UPDATE de_ext_user SET display_name=?,email=?,phone_prefix=?,phone=?," +
                "enabled=?,token_version=token_version+1 WHERE id=?",
                editor.getName().trim(),optionalProfile(editor.getEmail(),255),
                optionalProfile(editor.getPhonePrefix(),16), optionalProfile(editor.getPhone(),64),
                editor.getEnable()==null || editor.getEnable()?1:0,uid);
        if (editor.getRoleIds()!=null) {
            List<Long> requestedRoles = editor.getRoleIds();
            if (EnhanceSession.current().superAdmin() && uid == 1L) {
                requestedRoles = requestedRoles.stream().filter(r -> r != 1L).toList();
            }
            governance.replaceMemberRoles(new EnhanceOrgGovernanceController.RoleChange(
                    oid,uid,requestedRoles));
        }
    }

    @Transactional
    @PostMapping("/personEdit")
    public void personEdit(@RequestBody UserEditor editor) {
        long uid = EnhanceSession.current().userId();
        if (editor == null || editor.getId() == null || editor.getId()!=uid)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (editor.getName()==null || editor.getName().isBlank() || editor.getName().length()>255)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        jdbc.update("UPDATE de_ext_user SET display_name=?,email=?,phone_prefix=?,phone=? WHERE id=?",
                editor.getName().trim(), optionalProfile(editor.getEmail(),255),
                optionalProfile(editor.getPhonePrefix(),16), optionalProfile(editor.getPhone(),64), uid);
    }

    @Transactional
    @PostMapping("/enable")
    public void enable(@RequestBody EnableSwitchRequest request) {
        acl.requireSystemAdmin();
        if(request == null || request.getId()==null || request.getEnable()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        admin.toggle(request.getId(),new EnhanceAdminController.Toggle(request.getEnable()));
    }

    @Transactional
    @PostMapping("/delete/{id}")
    public void delete(@PathVariable long id) {
        admin.deleteUser(id);
    }

    @Transactional
    @PostMapping("/batchDel")
    public void batchDelete(@RequestBody List<Long> users) {
        acl.requireSystemAdmin();
        if(users==null || users.size()>100 || users.contains(null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        for(Long id:new HashSet<>(users)) admin.deleteUser(id);
    }

    @Transactional
    @PostMapping("/unlock/{id}")
    public void unlock(@PathVariable long id) {
        acl.requireSystemAdmin();
        visibleUser(id);
        jdbc.update("UPDATE de_ext_user SET failed_attempts=0,locked_until=0 WHERE id=?",id);
    }

    @GetMapping("/defaultPwd")
    public String defaultPassword() {
        acl.requireSystemAdmin();
        return "创建成功后生成独立初始密码";
    }

    @PostMapping("/resetPwd/{id}")
    public void resetPassword(@PathVariable long id) {
        // No fixed/shared default password; use /enhance/admin/users/{id}/reset-password.
        acl.requireSystemAdmin();
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Use the explicit password reset endpoint");
    }

    @Transactional
    @PostMapping("/modifyPwd")
    public void modifyPassword(@RequestBody ModifyPwdRequest request) {
        var self=EnhanceSession.current();
        if(request==null || (request.getUid()!=null && request.getUid()!=self.userId()) ||
                request.getPwd()==null || request.getNewPwd()==null ||
                request.getNewPwd().length()<12) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String,Object> current=identity.getEnabledUser(self.userId());
        if(!encoder.matches(request.getPwd(),String.valueOf(current.get("password_hash"))))
            throw EnhanceIdentityService.unauthenticated();
        jdbc.update("UPDATE de_ext_user SET password_hash=?,failed_attempts=0,locked_until=0," +
                "token_version=token_version+1 WHERE id=?",
                encoder.encode(request.getNewPwd()), self.userId());
    }

    @PostMapping("/switch/{id}")
    public io.dataease.auth.vo.TokenVO switchOrganization(@PathVariable long id) {
        return identity.issue(EnhanceSession.current().userId(),id);
    }

    @GetMapping("/orgAdmin")
    public boolean orgAdmin() {
        var p=EnhanceSession.current();
        return p.superAdmin() || acl.organizationAdmin(p.userId(),p.orgId());
    }
}