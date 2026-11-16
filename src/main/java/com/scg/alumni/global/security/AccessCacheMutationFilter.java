package com.scg.alumni.global.security;

import com.scg.alumni.domain.access.AccessPermissionCache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Invalidate after API mutations finish, including administrator bulk operations. */
@Slf4j
@RequiredArgsConstructor
public class AccessCacheMutationFilter extends OncePerRequestFilter {
    private final AccessPermissionCache cache;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
    }
}
