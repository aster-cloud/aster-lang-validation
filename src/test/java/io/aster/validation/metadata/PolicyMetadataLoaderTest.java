package io.aster.validation.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-fix 6 regression tests for the package allowlist (Round-3 codex finding).
 *
 * <p>The previous implementation called {@code Class.forName} on any
 * qualified name without restriction. The fix introduced an allowlist
 * gated <em>before</em> {@code Class.forName}; these tests pin the
 * behavior down.
 */
class PolicyMetadataLoaderTest {

    @Test
    @DisplayName("默认空 allowlist: 任何加载都被 SecurityException 拒绝")
    void emptyAllowlistRejectsAll() {
        // Use the programmatic ctor with explicit empty allowlist so the test
        // doesn't depend on env vars.
        PolicyMetadataLoader loader = new PolicyMetadataLoader(Set.of());
        assertThatThrownBy(() -> loader.loadPolicyMetadata("com.example.policy.foo"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("not in allowlist");
    }

    @Test
    @DisplayName("Package 不在 allowlist: SecurityException 且不调用 Class.forName")
    void packageNotInAllowlistRejectsBeforeReflection() {
        PolicyMetadataLoader loader = new PolicyMetadataLoader(Set.of("io.aster.policy"));
        // 'com.evil.attacker' is not in allowlist → must be rejected.
        assertThatThrownBy(() -> loader.loadPolicyMetadata("com.evil.attacker.run"))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Package 前缀匹配: 'io.aster.policy' allowlist 允许 'io.aster.policy.sub.fn'")
    void subPackagePrefixMatch() {
        PolicyMetadataLoader loader = new PolicyMetadataLoader(Set.of("io.aster.policy"));
        // Sub-package allowed; class not found (since we're not providing one)
        // → RuntimeException wrapping ClassNotFound, NOT SecurityException.
        assertThatThrownBy(() -> loader.loadPolicyMetadata("io.aster.policy.demo.greet"))
            .isInstanceOf(RuntimeException.class)
            .isNotInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Allowlist setter 运行时变更")
    void allowlistCanBeUpdatedAtRuntime() {
        PolicyMetadataLoader loader = new PolicyMetadataLoader(Set.of());
        assertThat(loader.getPackageAllowlist()).isEmpty();
        loader.setPackageAllowlist(Set.of("io.aster.policy", "com.example.rules"));
        assertThat(loader.getPackageAllowlist()).containsExactlyInAnyOrder(
            "io.aster.policy", "com.example.rules");
    }

    @Test
    @DisplayName("preloadPolicies 在遇到 SecurityException 时必须 rethrow,不能降级为 warn")
    void preloadPropagatesSecurityException() {
        PolicyMetadataLoader loader = new PolicyMetadataLoader(Set.of());  // 全拒绝
        // The first qualified name will trigger SecurityException — and that
        // MUST propagate so callers see deployment misconfiguration loudly.
        assertThatThrownBy(() -> loader.preloadPolicies(java.util.List.of("com.example.foo.bar")))
            .isInstanceOf(SecurityException.class);
    }
}
