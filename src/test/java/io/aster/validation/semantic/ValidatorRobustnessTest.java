package io.aster.validation.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.aster.validation.constraints.Range;
import io.aster.validation.metadata.ConstructorMetadataCache;
import io.aster.validation.metadata.UnreliableConstructorMappingException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SemanticValidator 的健壮性（issues #43 #44）。
 *
 * <p>#43：{@code collectAllFields} 只捕 {@link IllegalArgumentException}，
 * 而 {@link UnreliableConstructorMappingException} 直接继承 {@code RuntimeException}——
 * THROW 策略下它会逃逸，让**根本不需要构造器映射**的语义校验整体崩溃
 * （本方法只用 {@code getFields()}）。
 *
 * <p>#44：缓存里的 {@code Field} 实例被<b>所有线程共享</b>
 * （{@code getFields()} 只做数组浅拷贝）。原实现 trySetAccessible → get →
 * finally setAccessible(false) 会翻转共享状态：并发校验同一类型时，
 * 一方关掉开关、另一方 {@code get} 抛 {@code IllegalAccessException}，
 * 被 fail-closed 逻辑转成<b>虚假的 field-access violation</b>。
 */
class ValidatorRobustnessTest {

    /**
     * ★字段必须是 **private**：public 字段恒可访问，{@code setAccessible(false)}
     * 对它毫无影响（实测 {@code canAccess} 仍为 true、{@code get} 照常返回值）——
     * 用 public 字段写并发测试是**假绿**：无论有没有翻转开关它都通过。
     * 我第一版正是这样写的，变异「无条件 setAccessible(false)」照样全绿才发现。
     */
    public static class Holder {
        @Range(min = 0, max = 100)
        private final int score;

        public Holder(int score) {
            this.score = score;
        }
    }

    @Test
    @DisplayName("THROW 策略下语义校验正常工作（issue #43，非变异杀手——见注释）")
    void throwPolicyDoesNotBreakSemanticValidation() {
        // 语义校验只用 getFields()，与 field->parameter 映射无关——
        // 映射不可靠不该让它崩。
        //
        // ★如实标注：这**不是**变异杀手。撤掉 catch 里的
        //   UnreliableConstructorMappingException 后本用例仍绿，因为该异常只在
        //   「非 record 且编译时未带 -parameters」时抛出，而本仓 build.gradle
        //   带了 -parameters（见其 compilerArgs），本地根本触发不到。
        //   真正会踩到的是**把本库当依赖、自己编译时未带该 flag 的消费方**——
        //   那正是 THROW 策略存在的场景。
        //   修复依据是类型层事实：UnreliableConstructorMappingException 直接继承
        //   RuntimeException，而非 IllegalArgumentException，故原 catch 必然漏。
        //   保留本条作为「THROW 策略下不崩」的正向覆盖，不冒充回归守卫。
        var cache = new ConstructorMetadataCache(ConstructorMetadataCache.UnreliableMappingPolicy.THROW);
        var validator = new SemanticValidator(cache);

        assertThatCode(() -> validator.validateSemantics(new Holder(50)))
            .as("合法值在 THROW 策略下应正常通过")
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★THROW 策略下约束仍然生效（不是靠吞掉一切换来的通过）")
    void throwPolicyStillEnforcesConstraints() {
        // 没有这条，把 collectAllFields 写成「异常时返回空列表」也能让上一条变绿——
        // 那样字段一个都不校验，属于用「什么都不做」冒充「不崩溃」。
        var cache = new ConstructorMetadataCache(ConstructorMetadataCache.UnreliableMappingPolicy.THROW);
        var validator = new SemanticValidator(cache);

        assertThatCode(() -> validator.validateSemantics(new Holder(999)))
            .as("超出 [0,100] 必须报违规")
            .isInstanceOf(SemanticValidationException.class);
    }

    @Test
    @DisplayName("★并发校验同一类型不得产生虚假 field-access violation（issue #44）")
    void concurrentValidationOfSameTypeIsStable() throws Exception {
        var cache = new ConstructorMetadataCache();
        var validator = new SemanticValidator(cache);
        // 预热：让 Field 进入缓存，后续线程共享同一批 Field 实例
        validator.validateSemantics(new Holder(1));

        int threads = 16;
        int rounds = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        var errors = new ConcurrentLinkedQueue<Throwable>();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < rounds; i++) {
                            // 合法值：任何异常都是伪违规
                            validator.validateSemantics(new Holder(50));
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("并发校验超时").isTrue();
            assertThat(errors)
                .as("合法值在并发下不得产生任何违规——出现即说明共享 Field 的 "
                    + "accessible 开关被并发翻转（issue #44）")
                .isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }
}
