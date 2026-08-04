package dev.circuitbreaker.core;

import dev.circuitbreaker.core.reload.ConfigSwapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial test to verify hot-reload disable leak (BR-054).
 *
 * Test scenario:
 * 1. Register resource with concurrencyLimit=5 and CB enabled (mask=0x05)
 * 2. Acquire 5 tokens (concurrency=5)
 * 3. Hot-swap config to DISABLE concurrency (clear bit 0x04), keep CB only (mask=0x01)
 * 4. Release all 5 tokens
 * 5. Check concurrency counter: is it still >0? (indicates leak)
 * 6. Re-enable concurrency (bit 0x04, mask=0x05)
 * 7. Can new acquires succeed even though concurrency counter shows leaked count?
 *
 * If concurrency counter doesn't roll back to 0, this confirms a leak.
 */
public class HotReloadDisableLeakTest {

    @Test
    public void testHotReloadDisableConcurrencyLeak() {
        // Step 1: Register with mask=0x05 (CB=0x01 | CONCURRENCY=0x04), limit=16 (1 per segment)
        ResourceConfig cfg1 = new ResourceConfig(
            ResourceConfig.MASK_CIRCUIT_BREAKER | ResourceConfig.MASK_CONCURRENCY, // 0x05
            100,  // qps
            1000, // capacity
            500_000, // errThresholdPpm (50%)
            10,  // minCalls
            1000, // openMillis
            500,  // ewmaTauMs
            16,   // concurrencyLimit (1 per segment = limitPerSeg = 1)
            1     // version
        );

        int rid = ResourceManager.register(cfg1);
        ResourceState st = ResourceManager.state(rid);

        // Verify initial state
        assertEquals(0, st.sumConcurrency(), "Initial concurrency should be 0");

        // Step 2: Acquire 16 tokens (one per segment to guarantee success)
        java.util.List<Long> tokens = new java.util.ArrayList<>();
        for (int i = 0; i < 200 && tokens.size() < 16; i++) {
            long t = FlatExecutionEngine.tryAcquire(rid);
            if (t >= 0) {
                tokens.add(t);
            }
        }

        int acquired = tokens.size();
        assertTrue(acquired >= 5, "Should acquire at least 5 tokens. Acquired: " + acquired);

        long[] actualTokens = tokens.stream().mapToLong(Long::longValue).toArray();

        long concurrentAfterAcquire = st.sumConcurrency();
        assertEquals(acquired, concurrentAfterAcquire,
            "After " + acquired + " acquires, concurrency should be " + acquired + ". Actual: " + concurrentAfterAcquire);

        // Verify all tokens have concurrency bit set (bit 0x04)
        for (int i = 0; i < acquired; i++) {
            int tokenMask = TokenCodec.decodeMask(actualTokens[i]);
            assertEquals(ResourceConfig.MASK_CONCURRENCY, tokenMask & ResourceConfig.MASK_CONCURRENCY,
                "Token " + i + " should have CONCURRENCY bit set");
        }

        // Step 3: Hot-swap to disable concurrency (clear 0x04, keep CB)
        ResourceConfig cfg2 = new ResourceConfig(
            ResourceConfig.MASK_CIRCUIT_BREAKER, // 0x01 (CONCURRENCY disabled)
            100,  // qps
            1000, // capacity
            500_000, // errThresholdPpm
            10,  // minCalls
            1000, // openMillis
            500,  // ewmaTauMs
            5,    // concurrencyLimit (ignored when bit not set, but保持字段)
            2     // version (must be > current)
        );

        ConfigSwapper.swap(rid, cfg2);

        // Verify config swap succeeded
        ResourceConfig afterSwap = ResourceManager.config(rid);
        assertEquals(2, afterSwap.version, "Config version should be 2 after swap");
        assertEquals(ResourceConfig.MASK_CIRCUIT_BREAKER, afterSwap.mask,
            "Mask should be 0x01 (only CB, no CONCURRENCY)");

        // Step 4: Release all tokens with concurrency disabled
        for (int i = 0; i < acquired; i++) {
            FlatExecutionEngine.release(rid, actualTokens[i], true); // success=true
        }

        // Step 5: Check concurrency counter after release.
        // P2 fix: release uses the token's embedded mask (acquire-time CONCURRENCY bit),
        // so all slots MUST roll back to 0 regardless of the hot-swap that disabled concurrency.
        long concurrentAfterRelease = st.sumConcurrency();
        assertEquals(0, concurrentAfterRelease,
            "concurrency counter must be 0 after releasing all tokens (P2 fix: token-mask driven release); " +
            "a non-zero value indicates a slot leak when CONCURRENCY was hot-disabled");

        // Step 6: Re-enable concurrency (bit 0x04, mask=0x05)
        ResourceConfig cfg3 = new ResourceConfig(
            ResourceConfig.MASK_CIRCUIT_BREAKER | ResourceConfig.MASK_CONCURRENCY, // 0x05
            100,  // qps
            1000, // capacity
            500_000, // errThresholdPpm
            10,  // minCalls
            1000, // openMillis
            500,  // ewmaTauMs
            16,   // concurrencyLimit
            3     // version (must be > current)
        );

        ConfigSwapper.swap(rid, cfg3);

        ResourceConfig afterReEnable = ResourceManager.config(rid);
        assertEquals(3, afterReEnable.version, "Config version should be 3 after re-enable");
        assertEquals(ResourceConfig.MASK_CIRCUIT_BREAKER | ResourceConfig.MASK_CONCURRENCY,
            afterReEnable.mask, "Mask should be 0x05 (CB + CONCURRENCY re-enabled)");

        // Step 7: Since no leak occurred, a fresh acquire must succeed (counter is at 0).
        long newToken = FlatExecutionEngine.tryAcquire(rid);
        assertTrue(newToken >= 0,
            "After re-enable with no leaked slots, a fresh acquire must succeed; got " + newToken);

        // Cleanup the freshly acquired token
        if (newToken >= 0) {
            FlatExecutionEngine.release(rid, newToken, true);
        }
        assertEquals(0, st.sumConcurrency(), "concurrency counter must be 0 after cleanup");
    }
}