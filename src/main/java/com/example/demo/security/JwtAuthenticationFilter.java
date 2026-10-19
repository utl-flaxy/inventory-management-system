package com.example.demo.security;

import com.example.demo.exception.ServerFailureResponse;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // Shared with authorization: only the two canonical public POST endpoints skip JWT processing.
    static final RequestMatcher PUBLIC_AUTH_REQUESTS = request -> "POST".equals(request.getMethod())
            && ((request.getContextPath() + "/auth/login").equals(request.getRequestURI())
            || (request.getContextPath() + "/auth/register").equals(request.getRequestURI()));

    private final JwtUtil jwtUtil;
    private final JwtUserAuthenticator userAuthenticator;
    private final SecurityProblemWriter problemWriter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_AUTH_REQUESTS.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        JwtUtil.VerifiedJwt token;
        try {
            token = jwtUtil.verify(authHeader.substring(7));
        } catch (JwtException | IllegalArgumentException ex) {
            reject(ex);
            filterChain.doFilter(request, response);
            return;
        } catch (Exception ex) {
            writeServerFailure(ex, request, response);
            return;
        }
        try {
            if (SecurityContextHolder.getContext().getAuthentication() == null) {

                AuthPrincipal principal = userAuthenticator.authenticate(token);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                principal.authorities());

                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }

        } catch (UsernameNotFoundException ex) {
            reject(ex);
        } catch (Exception ex) {
            writeServerFailure(ex, request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(Exception exception) {
        SecurityContextHolder.clearContext();
        log.atWarn().addKeyValue("event", "security.jwt.rejected")
                .addKeyValue("exceptionType", exception.getClass().getName()).log("JWT rejected");
    }

    private void writeServerFailure(Exception exception, HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        var failure = ServerFailureResponse.from(exception);
        problemWriter.write(request, response, failure.status(), failure.code(), failure.detail());
    }
}
