package dev.circuitbreaker.core;

/**
 * 策略（ResourceConfig 的构建产物封装）。
 * 关联用例：UC-001。Phase 3 由 PolicyBuilder.build() 填充。
 */
public final class Policy {
    final ResourceConfig config;
    public Policy(ResourceConfig config) { this.config = config; }
    public ResourceConfig config() { throw new UnsupportedOperationException("TODO"); }
}
