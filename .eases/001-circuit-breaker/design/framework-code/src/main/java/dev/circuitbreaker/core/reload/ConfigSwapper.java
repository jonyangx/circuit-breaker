package dev.circuitbreaker.core.reload;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceManager;

/**
 * RCU 配置热更新（BR-050/051/052）。
 * 关联用例：UC-008。
 * 实现步骤：swap 构造 newConfig(version=old.version+1) 并 CONFIGS.set(rid,newConfig)；STATES 原地不动。
 *   - 下次 acquire 读新 cfg（capacity 等经 min 截断即时生效）；release 经 token.version 感知换代。
 */
public final class ConfigSwapper {
    public static void swap(int resourceId, ResourceConfig newConfig) {
        throw new UnsupportedOperationException("TODO: CONFIGS[rid]=newConfig(version+1)，STATES 不动（UC-008/BR-050/051）");
    }
}
