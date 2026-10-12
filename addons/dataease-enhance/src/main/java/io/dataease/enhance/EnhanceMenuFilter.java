package io.dataease.enhance;

import io.dataease.api.menu.vo.MenuVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Menu visibility is cosmetic; EnhanceTokenFilter and EnhanceAcl provide enforcement. */
@Component
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceMenuFilter {
    private final EnhanceAcl acl;
    public EnhanceMenuFilter(EnhanceAcl acl) { this.acl=acl; }

    public List<MenuVO> filter(List<MenuVO> menus) {
        var user=EnhanceSession.current();
        if(user.superAdmin()) return menus;
        List<MenuVO> result=new ArrayList<>();
        for(MenuVO menu:menus) {
            List<MenuVO> children=menu.getChildren()==null ? List.of() : filter(menu.getChildren());
            // Root menu nodes survive only when a permitted descendant exists.
            boolean allowed=menu.getName()!=null && acl.menuAllowed(menu.getName(),user.orgId());
            if(allowed || !children.isEmpty()) {
                menu.setChildren(children);
                result.add(menu);
            }
        }
        return result;
    }
}