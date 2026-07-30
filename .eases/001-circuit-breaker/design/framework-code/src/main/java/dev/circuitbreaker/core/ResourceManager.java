package dev.circuitbreaker.core;

/**
 * 资源注册与整数寻址（BR-001）。
 * 关联用例：UC-001（注册资源与策略）。
 * 实现步骤：维护 volatile ResourceConfig[] CONFIGS 与 final ResourceState[] STATES；
 *   register 分配下一个 resourceId，初始化 CONFIGS[id]/STATES[id]，返回 id。
 *   - BR-001：废弃 Map，数组寻址；resourceId 上限 1024。
 */
public final class ResourceManager {
    static final int MAX_RESOURCES = 1024;
    @SuppressWarnings("unchecked")
    static volatile ResourceConfig[] CONFIGS = new ResourceConfig[MAX_RESOURCES];
    static final ResourceState[] STATES = new ResourceState[MAX_RESOURCES];
    static int nextId = 0;

    private ResourceManager() {}

    public static int register(String name, Policy policy) {
        throw new UnsupportedOperationException("TODO: 分配 resourceId，CONFIGS[id]=config, STATES[id]=new ResourceState()，返回 id（UC-001/BR-001）");
    }
}
