package com.bugdigger.agent.debugger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Tracks threads currently suspended at a breakpoint. A thread enters {@link #parkCurrent()}
 * from inside breakpoint advice and remains parked until a Resume RPC removes it from the map
 * and unparks it.
 */
public class ThreadRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ThreadRegistry.class);

    private static volatile ThreadRegistry instance;

    private final Map<Long, Thread> parkedThreads = new ConcurrentHashMap<>();

    private ThreadRegistry() {
    }

    public static ThreadRegistry getInstance() {
        if (instance == null) {
            synchronized (ThreadRegistry.class) {
                if (instance == null) {
                    instance = new ThreadRegistry();
                }
            }
        }
        return instance;
    }

    public void parkCurrent() {
        Thread current = Thread.currentThread();
        long id = current.getId();
        parkedThreads.put(id, current);
        logger.info("Parking thread {} ({})", id, current.getName());
        try {
            while (parkedThreads.containsKey(id)) {
                LockSupport.park(this);
                // Loop guards against spurious wakeups.
            }
        } finally {
            parkedThreads.remove(id);
        }
        logger.info("Thread {} ({}) resumed", id, current.getName());
    }

    public int resume(long threadId) {
        if (threadId == 0) {
            List<Thread> toResume = new ArrayList<>(parkedThreads.values());
            parkedThreads.clear();
            for (Thread t : toResume) {
                LockSupport.unpark(t);
            }
            return toResume.size();
        }
        Thread t = parkedThreads.remove(threadId);
        if (t != null) {
            LockSupport.unpark(t);
            return 1;
        }
        return 0;
    }

    public Set<Long> suspendedThreadIds() {
        return new HashSet<>(parkedThreads.keySet());
    }

    public boolean isSuspended(long threadId) {
        return parkedThreads.containsKey(threadId);
    }

    public int suspendedCount() {
        return parkedThreads.size();
    }
}
