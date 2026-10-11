package com.example.demo.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // Servlet FilterとしてSecurityより前に一度だけ登録する。
@Slf4j
public class RequestCorrelationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        boolean completed = false;
        MDC.put("requestId", requestId);
        try {
            response.setHeader("X-Request-Id", requestId);
            chain.doFilter(request, response);
            completed = true;
        } finally {
            try {
                log.atInfo().addKeyValue("event", "http.request.completed")
                        .addKeyValue("method", request.getMethod())
                        .addKeyValue("path", request.getRequestURI())
                        // 未処理例外が外へ出た場合、未設定の200を成功として記録しない。
                        .addKeyValue("status", completed ? response.getStatus() : 500)
                        .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                        .log("HTTP request completed");
            } finally {
                MDC.remove("requestId");
            }
        }
    }
}
