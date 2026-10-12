package io.dataease.enhance;

import io.dataease.api.permissions.org.vo.MountedVO;
import io.dataease.api.permissions.org.vo.OrgPageVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController("orgServer")
@RequestMapping("/org")
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceOrgServer {
    private final JdbcTemplate jdbc;
    private final EnhanceAcl acl;
    public EnhanceOrgServer(JdbcTemplate jdbc,EnhanceAcl acl) { this.jdbc=jdbc;this.acl=acl; }

    @PostMapping("/mounted")
    public List<MountedVO> mounted(@RequestBody(required=false) Map<String,Object> query) {
        var p=EnhanceSession.current();
        List<Map<String,Object>> rows=p.superAdmin()
                ? jdbc.queryForList("SELECT id,name,parent_id FROM de_ext_org WHERE enabled=1 ORDER BY id")
                : jdbc.queryForList("SELECT o.id,o.name,o.parent_id FROM de_ext_org o JOIN de_ext_membership m ON m.org_id=o.id WHERE m.user_id=? AND o.enabled=1 ORDER BY o.id",p.userId());
        List<MountedVO> items=new ArrayList<>();
        for (var row:rows) {
            MountedVO v=new MountedVO();
            v.setId(((Number)row.get("id")).longValue());
            v.setName(String.valueOf(row.get("name")));
            v.setReadOnly(!p.superAdmin());
            v.setLeaf(true);
            items.add(v);
        }
        return items;
    }

    @GetMapping("/page/tree")
    public List<OrgPageVO> tree() {
        var p=EnhanceSession.current();
        List<Map<String,Object>> rows=p.superAdmin()
                ? jdbc.queryForList("SELECT id,name,parent_id FROM de_ext_org WHERE enabled=1 ORDER BY id")
                : jdbc.queryForList("SELECT o.id,o.name,o.parent_id FROM de_ext_org o JOIN de_ext_membership m ON m.org_id=o.id WHERE m.user_id=? AND o.enabled=1 ORDER BY o.id",p.userId());
        List<OrgPageVO> flat=new ArrayList<>();
        for(var row:rows) {
            var o=new OrgPageVO();
            o.setId(((Number)row.get("id")).longValue());
            o.setName(String.valueOf(row.get("name")));
            o.setReadOnly(!p.superAdmin());
            o.setChildren(new ArrayList<>());
            flat.add(o);
        }
        Map<Long,OrgPageVO> byId=new HashMap<>();
        for(var o:flat) byId.put(o.getId(),o);
        List<OrgPageVO> roots=new ArrayList<>();
        for(int i=0;i<flat.size();i++) {
            var parent=rows.get(i).get("parent_id");
            var ancestor=parent==null?null:byId.get(((Number)parent).longValue());
            if (ancestor!=null) ancestor.getChildren().add(flat.get(i));
            else roots.add(flat.get(i));
        }
        return roots;
    }

    @GetMapping("/subOrgs")
    public List<String> subOrgs() {
        var p=EnhanceSession.current();
        return jdbc.queryForList("SELECT org_id FROM de_ext_membership WHERE user_id=?",Long.class,p.userId())
                .stream().map(String::valueOf).toList();
    }

    @GetMapping("/logScope")
    public List<Long> logScope() {
        var p=EnhanceSession.current();
        if(p.superAdmin()) return null;
        return jdbc.queryForList("SELECT org_id FROM de_ext_membership WHERE user_id=?",Long.class,p.userId());
    }
}