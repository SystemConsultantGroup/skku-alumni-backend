package com.scg.alumni.global.security;

import static org.mockito.Mockito.*;

import com.scg.alumni.domain.access.AccessPermissionCache;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AccessCacheMutationFilterTest {
    @Test
    void ordinaryRequestsDoNotInvalidatePermissionCaches() throws Exception {
        AccessPermissionCache cache = mock(AccessPermissionCache.class);
        AccessCacheMutationFilter filter = new AccessCacheMutationFilter(cache);
        filter.doFilter(new MockHttpServletRequest("PATCH", "/api/v1/admin/payments/1/status"),
                new MockHttpServletResponse(), new MockFilterChain());
        verifyNoInteractions(cache);
        reset(cache);
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/admin/payments"),
                new MockHttpServletResponse(), new MockFilterChain());
        verifyNoInteractions(cache);
    }
}
