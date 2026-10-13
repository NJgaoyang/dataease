package io.dataease.enhance;

import io.dataease.permission.util.V3UserUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class EnhanceNativeIdentityGuardTest {
    @Test
    void refusesARequestWhenNativeIdentityIsOverwritten() throws Exception {
        var guard = new EnhanceNativeIdentityGuard();
        var request = new MockHttpServletRequest("GET", "/de2api/dataset/tree");
        var response = new MockHttpServletResponse();
        V3UserUtil.setUid(1L);
        EnhanceSession.set(new EnhanceSession.Identity(42L, 1L, "operator", false));
        try {
            assertFalse(guard.preHandle(request, response, new Object()));
            assertEquals(403, response.getStatus());
            V3UserUtil.setUid(42L);
            assertTrue(guard.preHandle(request, new MockHttpServletResponse(), new Object()));
        } finally {
            EnhanceSession.clear();
            V3UserUtil.setUid(null);
        }
        assertTrue(guard.preHandle(request, new MockHttpServletResponse(), new Object()));
    }
}