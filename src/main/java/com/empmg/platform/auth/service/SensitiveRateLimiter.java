package com.empmg.platform.auth.service;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class SensitiveRateLimiter {
    // ponytail: single-node counters; use a shared store before running multiple API replicas.
    private final Map<String,Deque<Instant>> requests=new HashMap<>();
    public synchronized boolean allow(String key,int limit) {
        Instant cutoff=Instant.now().minusSeconds(900);
        requests.entrySet().removeIf(e->e.getValue().isEmpty()||e.getValue().peekLast().isBefore(cutoff));
        if(!requests.containsKey(key)&&requests.size()>=10000) return false;
        var queue=requests.computeIfAbsent(key,k->new ArrayDeque<>());
        while(!queue.isEmpty()&&queue.peekFirst().isBefore(cutoff)) queue.removeFirst();
        if(queue.size()>=limit) return false;
        queue.addLast(Instant.now()); return true;
    }
}
