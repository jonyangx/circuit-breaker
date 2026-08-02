package dev.circuitbreaker.core;

import jdk.internal.misc.Unsafe;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard for the {@code @Contended} padding invariant (design §6.1; SC-006/false-sharing).
 *
 * <p>{@code @jdk.internal.vm.annotation.Contended} on <b>user classes is silently ignored by the
 * JVM unless {@code -XX:-RestrictContended} is set</b> ({@code RestrictContended} defaults to true,
 * restricting the annotation to bootstrap classes). Measured on JDK 21 without the flag the three
 * hot AtomicLongs sat at offsets 12/16/20 — 4 bytes apart, sharing cache lines (false sharing live).
 * With the flag they sit ~132 bytes apart, each in its own cache line group.</p>
 *
 * <p>The build sets the flag for {@code test}/{@code jmh}; this test enforces it. If it fails in an
 * IDE, add {@code -XX:-RestrictContended --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED} to the
 * test VM options. Production JVMs embedding this library MUST launch with the same flag, or the
 * design's anti-false-sharing padding is inert.</p>
 */
class ContendedPaddingGuardTest {

    private static final Unsafe U = loadUnsafe();

    private static Unsafe loadUnsafe() {
        try {
            for (Field f : Unsafe.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == Unsafe.class) {
                    f.setAccessible(true);
                    return (Unsafe) f.get(null);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                "Cannot obtain jdk.internal.misc.Unsafe — is --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED set?", e);
        }
        throw new IllegalStateException("No Unsafe instance field found");
    }

    private static long offset(String fieldName) throws Exception {
        return U.objectFieldOffset(ResourceState.class.getDeclaredField(fieldName));
    }

    @Test
    void hotAtomicLongsAreCacheLineIsolated() throws Exception {
        // If @Contended is honored, each annotated field is in its own ~128+ byte group.
        // If @Contended is inert (no -XX:-RestrictContended), the gaps collapse to ~4-8 bytes.
        long bucket = offset("bucketState");
        long breaker = offset("breakerState");
        long ewma = offset("ewmaState");

        long gap1 = breaker - bucket;
        long gap2 = ewma - breaker;

        assertThat(gap1)
                .as("@Contended on breakerState must isolate it from bucketState (gap >= 128B); "
                        + "if this fails, run with -XX:-RestrictContended (the JVM ignores @Contended on user classes by default)")
                .isGreaterThanOrEqualTo(128L);
        assertThat(gap2)
                .as("@Contended on ewmaState must isolate it from breakerState (gap >= 128B); "
                        + "if this fails, run with -XX:-RestrictContended")
                .isGreaterThanOrEqualTo(128L);
    }
}
