plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// JMH 微基准（SC-001/002 性能红线验证）。注：JMH 运行需额外 jmh 插件/注解处理器，
// 此处先保证编译通过；正式 JMH 执行为 Phase 3 打磨项（待引入 me.champeleg.jmh 插件）。
dependencies {
    implementation(project(":circuit-breaker-core"))
    implementation("org.openjdk.jmh:jmh-core:1.37")
}
