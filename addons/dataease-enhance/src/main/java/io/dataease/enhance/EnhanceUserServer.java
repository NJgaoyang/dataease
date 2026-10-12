package io.dataease.enhance;

import io.dataease.api.permissions.user.vo.UserFormVO;
import io.dataease.api.permissions.user.vo.UserGridVO;
import io.dataease.api.permissions.user.vo.UserGridRoleItem;
import io.dataease.result.PageResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController("userServer")
@RequestMapping("/user")
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceUserServer {
    private final EnhanceIdentityService identity;
    private final JdbcTemplate jdbc;
    private final EnhanceAcl acl;
    public EnhanceUserServer(EnhanceIdentityService identity, JdbcTemplate jdbc, EnhanceAcl acl) {
        this.identity=identity;this.jdbc=jdbc;this.acl=acl;
    }

    @GetMapping("/info")
    public Map<String,Object> info() {
        var p=EnhanceSession.current();
        Map<String,Object> out=new HashMap<>();
        out.put("id",String.valueOf(p.userId()));
        out.put("uid",String.valueOf(p.userId()));
        out.put("name",identity.getEnabledUser(p.userId()).get("display_name"));
        out.put("oid",String.valueOf(p.orgId()));
        out.put("language","zh-CN");
        return out;
    }

    @GetMapping("/personInfo")
    public UserFormVO personInfo() { return form(EnhanceSession.current().userId()); }

    @GetMapping("/queryById/{id}")
    public UserFormVO byId(@PathVariable long id) {
        var p=EnhanceSession.current();
        if (p.userId()!=id && !p.superAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return form(id);
    }

    private UserFormVO form(long uid) {
        var u=identity.getEnabledUser(uid);
        var vo=new UserFormVO();
        vo.setId(uid);
        vo.setDefaultOid(EnhanceSession.current().orgId());
        vo.setAccount(String.valueOf(u.get("account")));
        vo.setName(String.valueOf(u.get("display_name")));
        vo.setEnable(true);
        vo.setModel("enhance");
        vo.setRoleIds(jdbc.queryForList("SELECT role_id FROM de_ext_user_role WHERE user_id=?",Long.class,uid)
                .stream().map(String::valueOf).toList());
        return vo;
    }

    @GetMapping("/ipInfo")
    public Map<String,Object> ipInfo() {
        var p=EnhanceSession.current();
        return Map.of("account",p.account(),"name",identity.getEnabledUser(p.userId()).get("display_name"));
    }

    @PostMapping({"/pager/{page}/{size}","/mumberPager/{page}/{size}"})
    public PageResult<UserGridVO> users(@PathVariable int page,@PathVariable int size,
                                        @RequestBody(required=false) Map<String,Object> query) {
        if (page<1 || size<1 || size>200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var p=EnhanceSession.current();
        long oid=p.orgId();
        if (query!=null && query.get("oid")!=null) {
            oid=Long.parseLong(query.get("oid").toString());
        }
        if (!p.superAdmin() && !acl.member(p.userId(),oid)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM de_ext_user u JOIN de_ext_membership m ON m.user_id=u.id WHERE m.org_id=?",
                Long.class,oid);
        List<UserGridVO> results=new ArrayList<>();
        for (Map<String,Object> item: jdbc.queryForList(
                "SELECT u.* FROM de_ext_user u JOIN de_ext_membership m ON m.user_id=u.id WHERE m.org_id=? ORDER BY u.created_at DESC LIMIT ? OFFSET ?",
                oid,size,(page-1)*size)) {
            long id=((Number)item.get("id")).longValue();
            var v=new UserGridVO();
            v.setId(id);
            v.setAccount(String.valueOf(item.get("account")));
            v.setName(String.valueOf(item.get("display_name")));
            v.setEnable(((Number)item.get("enabled")).intValue()==1);
            v.setOrigin(0);
            v.setCreateTime(((Number)item.get("created_at")).longValue());
            v.setLocked(((Number)item.get("locked_until")).longValue()>System.currentTimeMillis());
            List<UserGridRoleItem> roles=new ArrayList<>();
            for (Map<String,Object> r:jdbc.queryForList(
                    "SELECT r.id,r.name FROM de_ext_role r JOIN de_ext_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? AND r.org_id=?",
                    id,oid)) {
                var role=new UserGridRoleItem();
                role.setId(((Number)r.get("id")).longValue());
                role.setName(String.valueOf(r.get("name")));
                roles.add(role);
            }
            v.setRoleItems(roles);
            results.add(v);
        }
        return new PageResult<>(results,total==null?0L:total,PageRequest.of(page-1,size));
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