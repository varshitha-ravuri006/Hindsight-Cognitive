package com.vishwas.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Tags every API request with a short id (MDC + X-Request-Id header) and logs method, path, status and time. */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    static final String KEY = "requestId";
    private static final Logger log = LoggerFactory.getLogger("http");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(KEY, id);
        res.setHeader("X-Request-Id", id);
        long started = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            if (req.getRequestURI().startsWith("/api/") && !req.getRequestURI().contains("/status")) {
                log.info("{} {} -> {} in {} ms", req.getMethod(), req.getRequestURI(), res.getStatus(),
                        (System.nanoTime() - started) / 1_000_000);
            }
            MDC.remove(KEY);
        }
    }
}
