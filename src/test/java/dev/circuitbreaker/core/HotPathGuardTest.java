package dev.circuitbreaker.core;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Static guard for the hot-path invariants (T040; constitution 不变量2/3, SC-006).
 * Locks in what JMH empirically verifies: no per-request Math.exp, no synchronized hot-path methods.
 *
 * <ul>
 *   <li>EwmaAlpha builds its LUT via Math.exp in &lt;clinit&gt; (one-time, off hot path) → excluded.</li>
 *   <li>ResourceManager.register is a synchronized setup entry (not the request hot path) → excluded.</li>
 * </ul>
 */
class HotPathGuardTest {

    private static final JavaClasses CORE = new ClassFileImporter().importPackages("dev.circuitbreaker.core");

    @Test
    void hotPathMustNotCallMathExpPerRequest() {
        ArchRule rule = noClasses().that().doNotHaveSimpleName("EwmaAlpha")
                .should().callMethod(Math.class, "exp", double.class)
                .because("the hot path must use the EwmaAlpha LUT (BR-021), never Math.exp per request");
        rule.check(CORE);
    }

    @Test
    void hotPathMustNotDeclareSynchronizedMethods() {
        ArchRule rule = noMethods().that().areDeclaredInClassesThat().doNotHaveSimpleName("ResourceManager")
                .should().haveModifier(JavaModifier.SYNCHRONIZED)
                .because("the hot path is lock-free (single AtomicLong CAS); only setup (ResourceManager.register) may be synchronized");
        rule.check(CORE);
    }
}
