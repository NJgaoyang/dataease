package io.dataease.enhance;

import io.dataease.permission.util.V3UserUtil;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnhanceNativeIdentityAdapterTest {
    @Test
    void bindsNativeIdentityAndRestoresThePriorValueEvenForNestedScopes() {
        var adapter = new EnhanceNativeIdentityAdapter();
        V3UserUtil.setUid(1L);
        try {
            var external = new EnhanceSession.Identity(101L, 1L, "operator", false);
            var nested = new EnhanceSession.Identity(202L, 1L, "viewer", false);
            try (var first = adapter.enter(external)) {
                assertEquals(101L,V3UserUtil.getUid());
                try (var second = adapter.enter(nested)) {
                    assertEquals(202L,V3UserUtil.getUid());
                }
                assertEquals(101L,V3UserUtil.getUid());
            }
            assertEquals(1L,V3UserUtil.getUid());
            assertThrows(IllegalArgumentException.class,()->adapter.enter(null));
            assertEquals(1L,V3UserUtil.getUid());
        } finally {
            V3UserUtil.setUid(null);
        }
    }

    @Test
    void restoresNullNativeIdentity() {
        var adapter = new EnhanceNativeIdentityAdapter();
        V3UserUtil.setUid(null);
        try (var scope = adapter.enter(new EnhanceSession.Identity(50L,1L,"worker",false))) {
            assertEquals(50L,V3UserUtil.getUid());
        }
        assertNull(V3UserUtil.getUid());
    }
}