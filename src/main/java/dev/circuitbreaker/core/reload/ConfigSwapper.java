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

    /**
     * Swap in a new immutable config. Enforces version monotonicity so that in-flight releases
     * never see a stale-version token accepted as current (BR-050/052).
     *
     * @throws IllegalArgumentException if {@code newConfig.version} is not strictly greater than the
     *         currently published version.
     */
    public static void swap(int resourceId, ResourceConfig newConfig) {
        ResourceConfig current;
        ResourceConfig published;
        do {
            current = ResourceManager.config(resourceId);
            if (current != null && newConfig.version <= current.version) {
                throw new IllegalArgumentException(
                    "new config version " + newConfig.version
                        + " must be > current version " + current.version);
            }
            // P2 fix: version must be non-negative (protect against config service bugs that might emit -1)
            if (newConfig.version < 0) {
                throw new IllegalArgumentException(
                    "new config version must be non-negative, got: " + newConfig.version);
            }
            // CAS loop ensures only one thread publishes this version
            published = ResourceManager.compareAndExchangeConfig(resourceId, current, newConfig);
        } while (published != current);
    }
}
