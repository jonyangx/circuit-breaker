package dev.circuitbreaker.core.reload;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceManager;

/**
 * RCU hot-reload (UC-008; BR-050/051/052).
 * Atomically swaps CONFIGS[rid] (version+1 supplied by caller); STATES[rid] is left untouched.
 * Next acquire reads the new cfg; in-flight release detects the version change via token.
 */
public final class ConfigSwapper {
    private ConfigSwapper() {}

    /** Swap in a new immutable config (caller must bump version). */
    public static void swap(int resourceId, ResourceConfig newConfig) {
        ResourceManager.publishConfig(resourceId, newConfig);
    }
}
