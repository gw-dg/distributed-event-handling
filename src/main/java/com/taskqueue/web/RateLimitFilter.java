package com.taskqueue.web;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import com.taskqueue.ratelimit.RateLimiter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet filter that enforces the task submission rate limit at the API edge.
 *
 * <p>From rate-limiting.md: "The edge limiter's job is to reject early and clearly.
 * Reject with HTTP 429, include {@code Retry-After}, and include headers that tell
 * the client the current state of the bucket."
 *
 * <p>Only {@code POST /tasks} is rate-limited. Operator endpoints and health checks
 * are not — they have separate, less frequent traffic patterns.
 *
 * <p>Response headers sent on every allowed request:
 * <ul>
 *   <li>{@code X-RateLimit-Limit}     — bucket capacity
 *   <li>{@code X-RateLimit-Remaining} — tokens left after this request
 * </ul>
 *
 * <p>Response on rejection (HTTP 429):
 * <ul>
 *   <li>{@code Retry-After: 1} — refill happens continuously; 1s is a safe advisory
 *   <li>{@code X-RateLimit-Limit} — so the client knows the cap
 * </ul>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String TASK_SUBMIT_PATH = "/tasks";

    private final RateLimiter rateLimiter;

    public RateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        // Only rate-limit POST /tasks (task submission)
        boolean isTaskSubmit = "POST".equalsIgnoreCase(request.getMethod())
                && TASK_SUBMIT_PATH.equals(request.getRequestURI());

        if (!isTaskSubmit) {
            chain.doFilter(request, response);
            return;
        }

        if (rateLimiter.tryAcquire()) {
            // Request allowed — add informational headers
            response.setHeader("X-RateLimit-Limit",     String.valueOf(rateLimiter.capacity()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(rateLimiter.available()));
            chain.doFilter(request, response);
        } else {
            // Bucket empty — reject with 429 Too Many Requests
            response.setStatus(429);
            response.setHeader("Retry-After",        "1");
            response.setHeader("X-RateLimit-Limit",  String.valueOf(rateLimiter.capacity()));
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"code":"rate_limit_exceeded","message":"Too many requests. Slow down and retry."}
                    """);
        }
    }
}
