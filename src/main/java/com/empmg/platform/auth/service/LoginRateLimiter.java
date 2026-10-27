package com.empmg.platform.auth.service;

import com.empmg.platform.common.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login throttling: after N failed attempts for the same email+IP within the
 * window, further attempts are locked out.
 */
// ponytail: in-memory, per-node only; add Redis (or DB-backed counters) when this runs on >1 node
@Service
@RequiredArgsConstructor
public class LoginRateLimiter {

    private final AppProperties props;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public boolean isBlocked(String email, String ip) {
        Deque<Instant> dq = failures.get(key(email, ip));
        if (dq == null) return false;
        Instant windowStart = Instant.now().minus(java.time.Duration.ofMinutes(props.getRateLimit().getLoginWindowMinutes()));
        synchronized (dq) {
            evictOld(dq, windowStart);
            if (dq.size() < props.getRateLimit().getLoginMaxFailures()) return false;
            // blocked until the newest failure is older than the lock duration
            return dq.peekLast().plus(java.time.Duration.ofMinutes(props.getRateLimit().getLoginLockMinutes()))
                    .isAfter(Instant.now());
        }
    }

    public void recordFailure(String email, String ip) {
        // Persistent per-account lockout remains authoritative when this bounded cache is full.
        if(failures.size()>=10000 && !failures.containsKey(key(email,ip))) return;
        failures.computeIfAbsent(key(email, ip), k -> new ArrayDeque<>());
        Deque<Instant> dq = failures.get(key(email, ip));
        synchronized (dq) {
            dq.addLast(Instant.now());
        }
    }

    public void clear(String email, String ip) {
        failures.remove(key(email, ip));
    }

    private String key(String email, String ip) {
        return (email == null ? "" : email.toLowerCase()) + "|" + (ip == null ? "" : ip);
    }

    private void evictOld(Deque<Instant> dq, Instant windowStart) {
        while (!dq.isEmpty() && dq.peekFirst().isBefore(windowStart)) {
            dq.pollFirst();
        }
    }
}
