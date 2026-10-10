package com.example.demo.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// 無効な管理/開発URLはJWT認証前に404とする。endpoint自体もpropertiesで生成しない。
final class UnavailableEndpointsFilter extends OncePerRequestFilter {
    private final boolean local;
    private final SecurityProblemWriter writer;

    UnavailableEndpointsFilter(Environment environment, SecurityProblemWriter writer) {
        this.local = environment.matchesProfiles("local & !prod");
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean docs = under(path, "/swagger-ui") || path.equals("/swagger-ui.html") || under(path, "/v3/api-docs");
        boolean unavailable = (!local && docs) || under(path, "/h2-console")
                || (under(path, "/actuator") && !path.equals("/actuator/health"));
        if (unavailable) {
            writer.write(request, response, HttpStatus.NOT_FOUND, "INVALID_REQUEST", "リソースが存在しません");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean under(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }
}
