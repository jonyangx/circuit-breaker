package dev.circuitbreaker.core;

/**
 * 策略构建器（链式，产出 Policy→ResourceConfig）。
 * 关联用例：UC-001（配置能力掩码）。
 * 实现步骤：累积参数，build() 时按 enableXxx 组合 mask 并构造 ResourceConfig（version=1）。
 */
public final class PolicyBuilder {
    long mask = 0;
    long ratePerMs; long capacity; int errThresholdPpm; int minCalls;
    long openMillis; long ewmaTauMs; int concurrencyLimit;

    public PolicyBuilder enableRateLimit(long qps) { throw new UnsupportedOperationException("TODO: mask|=0x02; ratePerMs=qps/1000; capacity=qps"); }
    public PolicyBuilder enableCircuitBreaker(float errThreshold) { throw new UnsupportedOperationException("TODO: mask|=0x01; errThresholdPpm=(int)(errThreshold*1e6)"); }
    public PolicyBuilder minimumCalls(int n) { throw new UnsupportedOperationException("TODO: minCalls=n"); }
    public PolicyBuilder ewmaHalfLife(long ms) { throw new UnsupportedOperationException("TODO: ewmaTauMs=ms"); }
    public PolicyBuilder enableConcurrency(int limit) { throw new UnsupportedOperationException("TODO: mask|=0x04; concurrencyLimit=limit"); }
    public Policy build() { throw new UnsupportedOperationException("TODO: 返回 Policy（封装 ResourceConfig，version=1）"); }
}
