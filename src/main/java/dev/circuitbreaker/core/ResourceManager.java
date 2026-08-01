package dev.circuitbreaker.core;

import dev.circuitbreaker.core.ratelimit.LazyTokenBucket;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Resource registration + integer-array addressing (BR-001). UC-001.
 * CONFIGS: AtomicReferenceArray for safe RCU publication; STATES: set-once, stable (BR-051).
 */
public final class ResourceManager {
    public static final int MAX_RESOURCES = 1024;
    static final AtomicReferenceArray<ResourceConfig> CONFIGS = new AtomicReferenceArray<>(MAX_RESOURCES);
    static final ResourceState[] STATES = new ResourceState[MAX_RESOURCES];
    private static int nextIdCounter = 0; // monotonic fast-path cursor (register is synchronized)

    private ResourceManager() {}

    /** Register a resource; returns its global integer resourceId. */
    public static synchronized int register(String name, ResourceConfig config) {
        int id = nextFreeId();
        if (id < 0) {
            throw new IllegalStateException("resource limit reached: " + MAX_RESOURCES);
        }
        ResourceState st = new ResourceState();
        STATES[id] = st;
        if ((config.mask & ResourceConfig.MASK_RATE_LIMIT) != 0) {
            LazyTokenBucket.seed(st, config.capacity); // burst available immediately
        }
        CONFIGS.set(id, config); // publish after state is in place
        return id;
    }

    private static int nextFreeId() {
        // O(1) fast path: no deregistration exists, so the next slot is the cursor.
        if (nextIdCounter < MAX_RESOURCES) {
            return nextIdCounter++;
        }
        // Defensive fallback in case deregistration is ever introduced.
        for (int i = 0; i < MAX_RESOURCES; i++) {
            if (STATES[i] == null) {
                return i;
            }
        }
        return -1;
    }

    public static ResourceConfig config(int resourceId) {
        return CONFIGS.get(resourceId);
    }

    public static ResourceState state(int resourceId) {
        return STATES[resourceId];
    }

    /** Controlled RCU publish point (used by ConfigSwapper). BR-050. */
    public static void publishConfig(int resourceId, ResourceConfig config) {
        CONFIGS.set(resourceId, config);
    }
}
