package com.minikun.agent.minikun_agent.api;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_KEY = "trace_id";
    private static final String TRACE_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        MDC.put(TRACE_ID_KEY, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        long started = System.nanoTime();
        try {
            org.slf4j.LoggerFactory.getLogger(RequestTraceFilter.class).info(
                    "process=http_request event=start method={} path={}",
                    request.getMethod(), request.getRequestURI());
            filterChain.doFilter(request, response);
        } finally {
            org.slf4j.LoggerFactory.getLogger(RequestTraceFilter.class).info(
                    "process=http_request event=end status={} duration_ms={}",
                    response.getStatus(), (System.nanoTime() - started) / 1_000_000);
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
