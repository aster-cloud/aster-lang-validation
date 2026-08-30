package io.aster.validation.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * allowlist 收紧后，缓存命中不得绕过新 allowlist（issue #45）。
 *
 * <p>★真实缺陷：allowlist 校验原先只发生在 {@code computeIfAbsent} 的<b>映射函数内部</b>
 * （{@code createMetadata}），而条目写入完成于其<b>之后</b>；收紧时的
 * {@code evictForbiddenFromCache()} 用 {@code keySet().removeIf(...)}，
 * 是 {@code ConcurrentHashMap} 的<b>弱一致</b>迭代——既不等待、也看不见在途条目。
 *
 * <p>已用最小复现实测确认该前提（不是推断）：在 {@code computeIfAbsent} 的映射函数里挂起，
 * 并发调用 {@code keySet().removeIf(k -> true)}，结果是 <b>removeIf 不阻塞、直接返回</b>，
 * 随后插入的条目<b>存活</b>（map 最终为 {@code {k=v}}）。
 *
 * <p>于是「旧 allowlist 下开始加载 → 期间收紧 → 加载完成」会留下一个绕过新 allowlist 的
 * 缓存条目，此后每次 cache hit 都放行。而 {@code evictForbiddenFromCache} 的 javadoc
 * 却声称 “cache hits can never outlive the permission that admitted them”——
 * 属本仓最高产的一类缺陷：<b>注释声称 ≠ 实现</b>。
 *
 * <p>★本测试<b>不靠线程时序</b>：直接把「条目已在缓存里 + allowlist 已收紧」这个
 * 竞态<b>结果状态</b>构造出来再断言。碰运气式的并发测试触发概率极低，等同于假绿
 * （本仓已有前车之鉴）。竞态能产生该状态，前提已由上述最小复现证明。
 */
class PolicyMetadataLoaderToctouTest {

    private static final String ALLOWED_PKG = "io.aster.validation.testdata";
    private static final String QUALIFIED = ALLOWED_PKG + ".single";

    @Test
    @DisplayName("★缓存里已有条目 + allowlist 收紧 → 必须拒绝（此前 cache hit 直接放行）")
    void cachedEntryMustNotBypassTightenedAllowlist() {
        PolicyMetadataLoader loader = new PolicyMetadataLoader(Set.of(ALLOWED_PKG));

        // 1) 旧 allowlist 下正常加载 —— 条目进入缓存
        assertThat(loader.loadPolicyMetadata(QUALIFIED)).isNotNull();

        // 2) 收紧 allowlist：该包不再被允许
        //    （真实竞态里，这一步与"条目写入"并发；此处直接构造出等价的结果状态）
        loader.setPackageAllowlist(Set.of("com.example.nothing"));

        // 3) ★核心断言：此后必须拒绝。
        //    修复前：若驱逐漏掉该条目（弱一致迭代 / 在途插入），这里 cache hit 直接返回。
        assertThatThrownBy(() -> loader.loadPolicyMetadata(QUALIFIED))
            .withFailMessage("allowlist 已收紧，仍取到 %s 的元数据——缓存绕过了新 allowlist", QUALIFIED)
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("not in allowlist");
    }

    /**
     * ★直接模拟「驱逐没清干净」——这是竞态的真正后果，也是修复必须扛住的场景。
     *
     * <p>手工把条目放回缓存（绕过 setPackageAllowlist 的驱逐），等价于「在途加载在
     * removeIf 扫过之后才完成插入」。若强制点只在驱逐上，这里必然放行。
     */
    @Test
    @DisplayName("★条目在驱逐之后才落入缓存（在途加载的等价状态）→ 仍必须拒绝")
    void entryLandingAfterEvictionMustStillBeRejected() throws Exception {
        PolicyMetadataLoader loader = new PolicyMetadataLoader(Set.of(ALLOWED_PKG));
        PolicyMetadata admitted = loader.loadPolicyMetadata(QUALIFIED);

        // 收紧 + 驱逐
        loader.setPackageAllowlist(Set.of("com.example.nothing"));

        // 反射把条目塞回缓存 —— 模拟"removeIf 扫过之后，在途的 computeIfAbsent 才完成插入"
        var cacheField = PolicyMetadataLoader.class.getDeclaredField("metadataCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var cache = (java.util.Map<String, PolicyMetadata>) cacheField.get(loader);
        cache.put(QUALIFIED, admitted);
        assertThat(cache).containsKey(QUALIFIED); // 前提成立：条目确实在缓存里

        // ★即便条目就在缓存里，也必须被拒 —— 强制点在返回路径上，不在驱逐上
        assertThatThrownBy(() -> loader.loadPolicyMetadata(QUALIFIED))
            .withFailMessage("条目在缓存中且 allowlist 已收紧，仍被放行——强制点仍只在驱逐上")
            .isInstanceOf(SecurityException.class);
    }

    // ── 反向护栏：没有这些，把 loadPolicyMetadata 写成「一律抛 SecurityException」
    //    也能让上面两条变绿。

    @Test
    @DisplayName("反向护栏：allowlist 正常时加载可用，且缓存仍生效")
    void normalLoadStillWorksAndCaches() {
        PolicyMetadataLoader loader = new PolicyMetadataLoader(Set.of(ALLOWED_PKG));

        PolicyMetadata first = loader.loadPolicyMetadata(QUALIFIED);
        assertThat(first).isNotNull();

        // 同一实例 → 证明缓存仍在工作，没被「每次重建」绕过
        assertThat(loader.loadPolicyMetadata(QUALIFIED)).isSameAs(first);
    }

    @Test
    @DisplayName("反向护栏：allowlist 放宽后，此前被拒的策略应能加载（拒绝不得被缓存住）")
    void wideningAllowlistReenablesLoading() {
        PolicyMetadataLoader loader = new PolicyMetadataLoader(Set.of("com.example.nothing"));

        assertThatThrownBy(() -> loader.loadPolicyMetadata(QUALIFIED))
            .isInstanceOf(SecurityException.class);

        loader.setPackageAllowlist(Set.of(ALLOWED_PKG));
        assertThatCode(() -> loader.loadPolicyMetadata(QUALIFIED)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("反向护栏：畸形限定名（无包名）响亮失败，不得静默通过")
    void malformedQualifiedNameIsRejected() {
        PolicyMetadataLoader loader = new PolicyMetadataLoader(Set.of(ALLOWED_PKG));

        assertThatThrownBy(() -> loader.loadPolicyMetadata("nodot"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> loader.loadPolicyMetadata(".leadingDot"))
            .isInstanceOf(SecurityException.class);
    }
}
