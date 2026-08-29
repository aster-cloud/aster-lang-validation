package io.aster.validation.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aster.validation.constraints.Range;
import io.aster.validation.metadata.ConstructorMetadataCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code @Range} 的整数界限必须对浮点值生效（issue #42）。
 *
 * <p>★真实缺陷：浮点分支只读 {@code minDouble()/maxDouble()}，其默认值是
 * ∓{@code Double.MAX_VALUE}。于是
 * {@code @Range(min = 0, max = 100) double x = 1e9} <b>静默通过</b>——
 * 用户写了约束，校验器什么都没做，也不告警。
 *
 * <p>而 {@code double} 字段经反射读取<b>必然</b>装箱为 {@code Double}，
 * {@code isFloatingValue} 恒为 true，所以「对 double 字段写整数界限」这一最自然的
 * 写法<b>必然</b>落进这个空洞。
 *
 * <p>实测（修复前）：四个字段都设 {@code @Range(min=0, max=100)} 且都赋值 1e9，
 * 只有 {@code long} 那个被抓到，{@code double}/{@code float}/{@code BigDecimal}
 * 全部静默通过。
 */
class RangeFloatingBoundsTest {

    private SemanticValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SemanticValidator(new ConstructorMetadataCache());
    }

    /** 只写整数界限（用户最自然的写法），字段是各种浮点类型。 */
    public static class IntBoundsOnFloatingHolder {
        @Range(min = 0, max = 100)
        public double d = 1e9;

        @Range(min = 0, max = 100)
        public float f = 1e9f;

        @Range(min = 0, max = 100)
        public java.math.BigDecimal bd = new java.math.BigDecimal("1e9");

        @Range(min = 0, max = 100)
        public long l = 1_000_000_000L;
    }

    @Test
    @DisplayName("★整数界限对 double/float/BigDecimal 都必须生效")
    void intBoundsApplyToAllFloatingTypes() {
        assertThatThrownBy(() -> validator.validateSemantics(new IntBoundsOnFloatingHolder()))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                var fields = ((SemanticValidationException) ex).getViolations().stream()
                    .map(SemanticValidationException.ConstraintViolation::fieldName)
                    .toList();
                // 修复前这里只有 ["l"]——三个浮点字段全部静默通过
                assertThat(fields)
                    .withFailMessage(
                        "四个字段值都是 1e9、约束都是 [0,100]，必须全部报违规；实际：%s", fields)
                    .containsExactlyInAnyOrder("d", "f", "bd", "l");
            });
    }

    /** 只写浮点界限——本分支的原生语义，不得被本次改动破坏。 */
    public static class DoubleBoundsHolder {
        @Range(minDouble = 0.1, maxDouble = 50.0)
        public double ratio;

        DoubleBoundsHolder(double ratio) {
            this.ratio = ratio;
        }
    }

    @Test
    @DisplayName("反向护栏：只写浮点界限时行为不变")
    void doubleBoundsStillWork() {
        assertThatCode(() -> validator.validateSemantics(new DoubleBoundsHolder(25.0)))
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validateSemantics(new DoubleBoundsHolder(99.0)))
            .isInstanceOf(SemanticValidationException.class);
        assertThatThrownBy(() -> validator.validateSemantics(new DoubleBoundsHolder(0.01)))
            .isInstanceOf(SemanticValidationException.class);
    }

    /** 两组界限都写：浮点界限更精确，应以它为准。 */
    public static class BothBoundsHolder {
        // 整数界限 [0,100] 宽，浮点界限 [0.1,50.0] 窄——取窄的那组
        @Range(min = 0, max = 100, minDouble = 0.1, maxDouble = 50.0)
        public double v;

        BothBoundsHolder(double v) {
            this.v = v;
        }
    }

    @Test
    @DisplayName("★两组界限都设时以浮点界限为准（更精确，且是本分支原生语义）")
    void doubleBoundsWinWhenBothSet() {
        assertThatCode(() -> validator.validateSemantics(new BothBoundsHolder(25.0)))
            .doesNotThrowAnyException();
        // 75.0 在整数界限 [0,100] 内、但超出浮点界限 [0.1,50.0] → 必须报违规
        assertThatThrownBy(() -> validator.validateSemantics(new BothBoundsHolder(75.0)))
            .isInstanceOf(SemanticValidationException.class);
    }

    /** 合法值不得被误报。 */
    public static class InRangeHolder {
        @Range(min = 0, max = 100)
        public double d = 42.5;

        @Range(min = 0, max = 100)
        public java.math.BigDecimal bd = new java.math.BigDecimal("42.5");
    }

    @Test
    @DisplayName("★反向护栏：范围内的浮点值不得误报")
    void inRangeFloatingValuesPass() {
        // 没有这条，把浮点分支写成「恒违规」也能让上面几条变绿。
        assertThatCode(() -> validator.validateSemantics(new InRangeHolder()))
            .doesNotThrowAnyException();
    }

    /** 边界值：恰好等于上下界应通过（闭区间）。 */
    public static class BoundaryHolder {
        @Range(min = 0, max = 100)
        public double lo = 0.0;

        @Range(min = 0, max = 100)
        public double hi = 100.0;
    }

    @Test
    @DisplayName("边界值 0 与 100 属闭区间，应通过")
    void boundaryValuesPass() {
        assertThatCode(() -> validator.validateSemantics(new BoundaryHolder()))
            .doesNotThrowAnyException();
    }

    /** 无任何界限：不应有约束。 */
    public static class NoBoundsHolder {
        @Range
        public double anything = 1e300;
    }

    @Test
    @DisplayName("两组界限都未设时维持无约束（不得因回退逻辑误伤）")
    void noBoundsMeansNoConstraint() {
        assertThatCode(() -> validator.validateSemantics(new NoBoundsHolder()))
            .doesNotThrowAnyException();
    }
}
