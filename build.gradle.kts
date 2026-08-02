import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    java
    jacoco
}

group = "dev.circuitbreaker"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// jdk.internal.vm.annotation.Contended lives in java.base; expose at compile time. At runtime we
// also force -XX:-RestrictContended so @Contended on app classes is actually honored (by default the
// JVM ignores it outside the bootstrap loader — measured: offsets go 12/16/20 → 280/412/544), and
// open jdk.internal.misc so ContendedPaddingGuardTest can read field offsets.
val compileJvmArgs = listOf(
    "--add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED" // ContendedPaddingGuardTest field-offset reads
)
val runtimeJvmArgs = compileJvmArgs + listOf(
    "-XX:-RestrictContended",                              // honor @Contended on user classes (else inert)
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"  // ContendedPaddingGuardTest field-offset reads
)

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(compileJvmArgs)
}
tasks.withType<Test>().configureEach {
    jvmArgs(runtimeJvmArgs)
    systemProperty("circuitbreaker.testMode", "true") // allows SystemOverload.setShedPermilleForTest
}

sourceSets {
    create("jmh") {
        java.srcDir("src/jmh/java")
    }
}

dependencies {
    implementation("io.projectreactor:reactor-core:3.6.10")
    implementation("io.prometheus:simpleclient:0.16.0")
    "jmhImplementation"("org.openjdk.jmh:jmh-core:1.37")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation("io.projectreactor:reactor-test:3.6.10")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

configurations.named("jmhImplementation").configure {
    extendsFrom(configurations.implementation.get())
}
sourceSets.named("jmh").configure {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs JMH benchmarks (timing + gc profiler for zero-allocation)."
    dependsOn("jmhClasses")
    classpath = sourceSets.getByName("jmh").runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args = listOf("-wi", "2", "-i", "3", "-f", "1", "-w", "1s", "-r", "1s", "-tu", "ns", "-prof", "gc")
    jvmArgs(runtimeJvmArgs)
}
