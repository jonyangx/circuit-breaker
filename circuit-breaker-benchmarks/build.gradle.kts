import org.gradle.api.tasks.JavaExec

plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Manual JMH setup (no third-party plugin) — empirically verify SC-001 (ns-level) and SC-002 (zero allocation).
// Run: ./gradlew :circuit-breaker-benchmarks:jmh
sourceSets {
    create("jmh") {
        java.srcDir("src/jmh/java")
    }
}

dependencies {
    "jmhImplementation"(project(":circuit-breaker-core"))
    "jmhImplementation"("org.openjdk.jmh:jmh-core:1.37")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs JMH benchmarks (timing + gc profiler for zero-allocation)."
    dependsOn("jmhClasses")
    val jmhSourceSet = sourceSets.getByName("jmh")
    classpath = jmhSourceSet.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    // Fast verification run: 2 warmup + 3 measurement iterations, 1 fork, gc profiler for alloc.rate.norm.
    args = listOf("-wi", "2", "-i", "3", "-f", "1", "-w", "1s", "-r", "1s", "-tu", "ns", "-prof", "gc")
}
