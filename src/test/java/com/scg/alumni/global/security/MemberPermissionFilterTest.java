package com.scg.alumni.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.scg.alumni.domain.access.AccessLevel;
import com.scg.alumni.domain.access.AccessQualificationService;
import com.scg.alumni.domain.access.AccessPermissionCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class MemberPermissionFilterTest {
    private final AccessQualificationService service = mock(AccessQualificationService.class);
    private final AccessPermissionCache cache = mock(AccessPermissionCache.class);
    private final MemberPermissionFilter filter = new MemberPermissionFilter(service, cache);

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void limitedMemberCanReadNoticeButCannotReadDirectory() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.MEMBER, "Test"), null));
        when(cache.currentLevel(1L)).thenReturn(AccessLevel.LIMITED);
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/notices"), allowed, new MockFilterChain());
        assertThat(allowed.getStatus()).isEqualTo(200);
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/members"), denied, new MockFilterChain());
        assertThat(denied.getStatus()).isEqualTo(403);
    }

    @Test
    void lookupFailureDoesNotPermitAccess() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.MEMBER, "Test"), null));
        when(cache.currentLevel(1L)).thenThrow(new IllegalStateException());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/notices"), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void limitedMemberMayReadOnlyTheirOwnProfileImage() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.MEMBER, "Test"), null));
        when(cache.currentLevel(1L)).thenReturn(AccessLevel.LIMITED);
        when(service.ownsProfileImage(1L, "/api/v1/profile-images/abc-123.jpg")).thenReturn(true);
        MockHttpServletResponse own = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/profile-images/abc-123.jpg"), own, new MockFilterChain());
        assertThat(own.getStatus()).isEqualTo(200);
        MockHttpServletResponse other = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/profile-images/def-456.jpg"), other, new MockFilterChain());
        assertThat(other.getStatus()).isEqualTo(403);
    }
}
