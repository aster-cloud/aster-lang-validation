package io.aster.validation.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aster.validation.constraints.Range;
import io.aster.validation.metadata.ConstructorMetadataCache;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code @Range} 的四个界限属性必须归一为一对有效界限，对所有运行时数值类型一致生效
 * （issue #55/#56/#57/#58 同根因）。
 *
 * <p>修复前四个复现：
 * <ul>
 *   <li>#55：{@code @Range(minDouble = 0.0, maxDouble = 100.0) int v = 500} 静默通过；</li>
 *   <li>#56：{@code message = "..."} 被忽略，始终输出「值超出范围 [..]」；</li>
 *   <li>#57：{@code @Range(min = 0, max = 100) Double v = NaN} 的消息显示 ±1.79E308；</li>
 *   <li>#58：{@code @Range(min = 0, max = 100) BigDecimal v = 100.00000000000000001} 经 double 比较后通过。</li>
 * </ul>
 */
class RangeBoundsNormalizationTest {

    private SemanticValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SemanticValidator(new ConstructorMetadataCache());
    }

    private List<SemanticValidationException.ConstraintViolation> violationsOf(Object instance) {
        try {
            validator.validateSemantics(instance);
        } catch (SemanticValidationException ex) {
            return ex.getViolations();
        }
        throw new AssertionError("预期抛出 SemanticValidationException，实际未抛");
    }

    // ---------- #55：整型字段只设浮点界限 ----------

    public static class DoubleBoundsOnIntegralHolder {
        @Range(minDouble = 0.0, maxDouble = 100.0)
        public int i = 500;

        @Range(minDouble = 0.0, maxDouble = 100.0)
        public long l = 500L;

        @Range(minDouble = 0.0, maxDouble = 100.0)
        public short s = 500;

        @Range(minDouble = 0.0, maxDouble = 100.0)
        public BigInteger bi = BigInteger.valueOf(500);
    }

    @Test
    @DisplayName("#55 浮点界限对 int/long/short/BigInteger 都必须生效")
    void doubleBoundsApplyToIntegralTypes() {
        List<String> fields = violationsOf(new DoubleBoundsOnIntegralHolder()).stream()
            .map(SemanticValidationException.ConstraintViolation::fieldName)
            .toList();
        assertThat(fields)
            .withFailMessage("四个整型字段值都是 500、浮点界限都是 [0.0,100.0]，必须全部报违规；实际：%s", fields)
            .containsExactlyInAnyOrder("i", "l", "s", "bi");
    }

    public static class DoubleBoundsOnIntegralInRangeHolder {
        @Range(minDouble = 0.5, maxDouble = 100.5)
        public int i = 50;

        @Range(minDouble = 0.5, maxDouble = 100.5)
        public BigInteger bi = BigInteger.valueOf(100);
    }

    @Test
    @DisplayName("#55 反向护栏：浮点界限内的整型值不得误报")
    void integralValuesInsideDoubleBoundsPass() {
        assertThatCode(() -> validator.validateSemantics(new DoubleBoundsOnIntegralInRangeHolder()))
            .doesNotThrowAnyException();
    }

    // ---------- #56：自定义 message 与 {min}/{max} 占位 ----------

    public static class CustomMessageHolder {
        @Range(min = 1, max = 10, message = "年龄必须在1到10之间")
        public int age = 99;

        @Range(min = 1, max = 10, message = "年龄区间 {min}~{max}")
        public int placeholder = 99;
    }

    @Test
    @DisplayName("#56 违规消息来自 range.message()，{min}/{max} 替换为生效界限")
    void customMessageIsUsedWithPlaceholders() {
        List<SemanticValidationException.ConstraintViolation> violations =
            violationsOf(new CustomMessageHolder());
        assertThat(violations)
            .extracting(SemanticValidationException.ConstraintViolation::message)
            .containsExactlyInAnyOrder("年龄必须在1到10之间", "年龄区间 1~10");
    }

    public static class DefaultMessageHolder {
        @Range(minDouble = 0.5, maxDouble = 2.5)
        public double ratio = 9.0;
    }

    @Test
    @DisplayName("#56 默认模板填入浮点界限")
    void defaultTemplateShowsEffectiveBounds() {
        assertThat(violationsOf(new DefaultMessageHolder()))
            .extracting(SemanticValidationException.ConstraintViolation::message)
            .containsExactly("值必须在 0.5 到 2.5 之间");
    }

    public static class OneSidedHolder {
        @Range(max = 100)
        public long v = 500L;
    }

    @Test
    @DisplayName("只设单侧界限：另一侧显示为无界，而非 Long/Double 的哨兵值")
    void unboundedSideIsDescribedAsInfinity() {
        assertThat(violationsOf(new OneSidedHolder()))
            .extracting(SemanticValidationException.ConstraintViolation::message)
            .containsExactly("值必须在 -∞ 到 100 之间");
    }

    // ---------- #57：NaN 消息复用归一后的界限 ----------

    public static class NaNWithIntBoundsHolder {
        @Range(min = 0, max = 100)
        public Double v = Double.NaN;
    }

    @Test
    @DisplayName("#57 NaN 消息显示用户声明的整数界限 [0, 100]，而非 ±1.79E308")
    void nanMessageUsesResolvedBounds() {
        List<SemanticValidationException.ConstraintViolation> violations =
            violationsOf(new NaNWithIntBoundsHolder());
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message())
            .contains("[0, 100]")
            .doesNotContain("E308");
    }

    public static class InfinityHolder {
        @Range(min = 0)
        public double pos = Double.POSITIVE_INFINITY;

        @Range(max = 0)
        public float neg = Float.NEGATIVE_INFINITY;
    }

    @Test
    @DisplayName("±Infinity 与 NaN 同样被拒绝（无法转成 BigDecimal，且不可能落在有限区间内）")
    void infinityIsRejected() {
        assertThat(violationsOf(new InfinityHolder()))
            .extracting(SemanticValidationException.ConstraintViolation::fieldName)
            .containsExactlyInAnyOrder("pos", "neg");
    }

    // ---------- #58：BigDecimal 精确比较 ----------

    public static class BigDecimalPrecisionHolder {
        @Range(min = 0, max = 100)
        public BigDecimal over = new BigDecimal("100.00000000000000001");

        @Range(min = 0, max = 100)
        public BigDecimal under = new BigDecimal("-0.00000000000000001");

        @Range(min = 0, max = 100)
        public BigDecimal exact = new BigDecimal("100.000");
    }

    @Test
    @DisplayName("#58 BigDecimal 不经 double 比较：超出 double 精度的微越界必须被抓到，恰好等于界限则通过")
    void bigDecimalComparedExactly() {
        assertThat(violationsOf(new BigDecimalPrecisionHolder()))
            .extracting(SemanticValidationException.ConstraintViolation::fieldName)
            .containsExactlyInAnyOrder("over", "under");
    }

    public static class BigIntegerBeyondLongHolder {
        // 上界未设，BigInteger 超过 Long.MAX_VALUE 也不应被哨兵值误伤
        @Range(min = 0)
        public BigInteger huge = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN);

        @Range(min = 0, max = 1_000_000)
        public BigInteger tooBig = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.TEN);
    }

    @Test
    @DisplayName("BigInteger 超出 long 范围时按精确值比较，无界一侧不受哨兵值限制")
    void bigIntegerBeyondLongRange() {
        assertThat(violationsOf(new BigIntegerBeyondLongHolder()))
            .extracting(SemanticValidationException.ConstraintViolation::fieldName)
            .containsExactly("tooBig");
    }

    // ---------- 两组都设：浮点优先，对整型值同样成立 ----------

    public static class BothBoundsOnIntegralHolder {
        @Range(min = 0, max = 100, minDouble = 0.5, maxDouble = 50.0)
        public int v = 75;
    }

    @Test
    @DisplayName("两组界限都设时浮点优先，对整型值同样适用（规则不随运行时类型变化）")
    void doubleBoundsWinForIntegralValues() {
        assertThat(violationsOf(new BothBoundsOnIntegralHolder()))
            .extracting(SemanticValidationException.ConstraintViolation::message)
            .containsExactly("值必须在 0.5 到 50.0 之间");
    }

    // ---------- 整数界限超过 double 精度时也精确比较 ----------

    public static class LargeIntegerBoundHolder {
        // Long.MAX_VALUE 转 double 会向上取整到 2^63；精确比较下 Long.MAX_VALUE - 1 仍在范围内
        @Range(max = Long.MAX_VALUE - 1)
        public long v = Long.MAX_VALUE;
    }

    @Test
    @DisplayName("整数界限不再经 double 取整：Long.MAX_VALUE 对上界 Long.MAX_VALUE-1 必须越界")
    void largeIntegerBoundIsExact() {
        assertThatThrownBy(() -> validator.validateSemantics(new LargeIntegerBoundHolder()))
            .isInstanceOf(SemanticValidationException.class);
    }

    // ---------- #63：显式写哨兵值等价于无界（文档化契约） ----------

    public static class SentinelBoundsHolder {
        @Range(min = 0, max = Long.MAX_VALUE)
        public BigInteger beyondLong = BigInteger.TWO.pow(70);

        @Range(min = Long.MIN_VALUE, max = 0)
        public BigInteger belowLong = BigInteger.TWO.pow(70).negate();

        @Range(minDouble = -Double.MAX_VALUE, maxDouble = Double.MAX_VALUE)
        public BigDecimal beyondDouble = new BigDecimal("1e400");

        @Range(minDouble = -Double.MAX_VALUE, maxDouble = 0.0)
        public BigDecimal belowDouble = new BigDecimal("-1e400");
    }

    @Test
    @DisplayName("#63 显式 Long.MIN/MAX_VALUE 与 ∓Double.MAX_VALUE 界限对 BigInteger/BigDecimal 即无界（见 Range JavaDoc / README）")
    void explicitSentinelBoundsMeanUnboundedForBigNumbers() {
        assertThatCode(() -> validator.validateSemantics(new SentinelBoundsHolder()))
            .doesNotThrowAnyException();
    }

    public static class SentinelBoundMessageHolder {
        @Range(min = 0, max = Long.MAX_VALUE)
        public BigInteger v = BigInteger.ONE.negate();
    }

    @Test
    @DisplayName("#63 哨兵值一侧在违规消息里显示为无界，而非 Long.MAX_VALUE 数字")
    void sentinelBoundIsDescribedAsUnboundedInMessage() {
        assertThat(violationsOf(new SentinelBoundMessageHolder()))
            .extracting(SemanticValidationException.ConstraintViolation::message)
            .containsExactly("值必须在 0 到 +∞ 之间");
    }

    public static class RealBoundBesideSentinelHolder {
        // 想约束「必须能装进 long」，就得写真实界限
        @Range(min = 0, max = Long.MAX_VALUE - 1)
        public BigInteger v = BigInteger.TWO.pow(70);
    }

    @Test
    @DisplayName("#63 反向护栏：真实界限 Long.MAX_VALUE-1 对 BigInteger 2^70 仍然生效")
    void realBoundStillRejectsBigInteger() {
        assertThat(violationsOf(new RealBoundBesideSentinelHolder()))
            .extracting(SemanticValidationException.ConstraintViolation::fieldName)
            .containsExactly("v");
    }

    // ---------- #62：±Infinity / NaN 作为显式浮点界限 ----------

    public static class NegativeInfinityLowerBoundHolder {
        @Range(minDouble = Double.NEGATIVE_INFINITY, maxDouble = 100.0)
        public double v;

        NegativeInfinityLowerBoundHolder(double v) {
            this.v = v;
        }
    }

    public static class PositiveInfinityUpperBoundHolder {
        @Range(minDouble = 0.0, maxDouble = Double.POSITIVE_INFINITY)
        public double v;

        PositiveInfinityUpperBoundHolder(double v) {
            this.v = v;
        }
    }

    @Test
    @DisplayName("#62 -Infinity 作下界：该侧无界，范围内的值通过，另一侧仍生效")
    void negativeInfinityLowerBoundMeansUnbounded() {
        assertThatCode(() -> validator.validateSemantics(new NegativeInfinityLowerBoundHolder(50.0)))
            .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateSemantics(new NegativeInfinityLowerBoundHolder(-1e300)))
            .doesNotThrowAnyException();
        assertThat(violationsOf(new NegativeInfinityLowerBoundHolder(150.0)))
            .extracting(SemanticValidationException.ConstraintViolation::message)
            .containsExactly("值必须在 -∞ 到 100.0 之间");
    }

    @Test
    @DisplayName("#62 +Infinity 作上界：该侧无界，范围内的值通过，另一侧仍生效")
    void positiveInfinityUpperBoundMeansUnbounded() {
        assertThatCode(() -> validator.validateSemantics(new PositiveInfinityUpperBoundHolder(50.0)))
            .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateSemantics(new PositiveInfinityUpperBoundHolder(1e300)))
            .doesNotThrowAnyException();
        assertThat(violationsOf(new PositiveInfinityUpperBoundHolder(-1.0)))
            .extracting(SemanticValidationException.ConstraintViolation::message)
            .containsExactly("值必须在 0.0 到 +∞ 之间");
    }

    public static class NaNBoundHolder {
        @Range(minDouble = Double.NaN, maxDouble = 100.0)
        public double v;

        NaNBoundHolder(double v) {
            this.v = v;
        }
    }

    public static class NaNBoundFallsBackToIntegralHolder {
        // 浮点界限是 NaN 时视同未设置，退回到显式的整数界限
        @Range(min = 10, minDouble = Double.NaN, maxDouble = 100.0)
        public double v;

        NaNBoundFallsBackToIntegralHolder(double v) {
            this.v = v;
        }
    }

    @Test
    @DisplayName("#62 NaN 作界限：视同未设置，绝不让 NumberFormatException 逃出 validateSemantics")
    void nanBoundIsTreatedAsUnset() {
        assertThatCode(() -> validator.validateSemantics(new NaNBoundHolder(50.0)))
            .doesNotThrowAnyException();
        assertThat(violationsOf(new NaNBoundHolder(150.0)))
            .extracting(SemanticValidationException.ConstraintViolation::message)
            .containsExactly("值必须在 -∞ 到 100.0 之间");

        assertThatCode(() -> validator.validateSemantics(new NaNBoundFallsBackToIntegralHolder(50.0)))
            .doesNotThrowAnyException();
        assertThat(violationsOf(new NaNBoundFallsBackToIntegralHolder(5.0)))
            .extracting(SemanticValidationException.ConstraintViolation::message)
            .containsExactly("值必须在 10 到 100.0 之间");
    }

    public static class InfinityBoundWithNonFiniteValueHolder {
        @Range(minDouble = Double.NEGATIVE_INFINITY, maxDouble = Double.POSITIVE_INFINITY)
        public double v = Double.POSITIVE_INFINITY;
    }

    @Test
    @DisplayName("#62 两侧都是 Infinity 界限时，Infinity 值仍按「非有限值」拒绝，消息显示 [-∞, +∞]")
    void nonFiniteValueStillRejectedUnderInfinityBounds() {
        assertThat(violationsOf(new InfinityBoundWithNonFiniteValueHolder()))
            .extracting(SemanticValidationException.ConstraintViolation::message)
            .containsExactly("值为 NaN 或 Infinity，无法满足范围约束 [-∞, +∞]");
    }

    // ---------- #64：非整型的 Number 子类按 double 处理，而非 longValue() 截断 ----------

    private static DoubleAdder adderOf(double d) {
        DoubleAdder adder = new DoubleAdder();
        adder.add(d);
        return adder;
    }

    private static DoubleAccumulator accumulatorOf(double d) {
        DoubleAccumulator acc = new DoubleAccumulator(Double::sum, 0.0);
        acc.accumulate(d);
        return acc;
    }

    /** 用户自定义的 Number 子类：持有小数，longValue() 会截断。 */
    public static final class Fraction extends Number {
        private final double value;

        Fraction(double value) {
            this.value = value;
        }

        @Override public int intValue() { return (int) value; }
        @Override public long longValue() { return (long) value; }
        @Override public float floatValue() { return (float) value; }
        @Override public double doubleValue() { return value; }
    }

    public static class FractionalNumberSubclassHolder {
        @Range(min = 0, max = 100)
        public DoubleAdder adder = adderOf(100.7);

        @Range(min = 0, max = 100)
        public DoubleAccumulator accumulator = accumulatorOf(100.7);

        @Range(min = 0, max = 100)
        public Fraction custom = new Fraction(100.7);

        @Range(min = 0, max = 100)
        public DoubleAdder inRange = adderOf(50.5);
    }

    @Test
    @DisplayName("#64 DoubleAdder/DoubleAccumulator/自定义 Number 的 100.7 不得被 longValue() 截成 100 放行")
    void fractionalNumberSubclassesAreNotTruncated() {
        assertThat(violationsOf(new FractionalNumberSubclassHolder()))
            .extracting(SemanticValidationException.ConstraintViolation::fieldName)
            .containsExactlyInAnyOrder("adder", "accumulator", "custom");
    }

    public static class NaNInNumberSubclassHolder {
        @Range(min = 0, max = 100)
        public DoubleAdder nan = adderOf(Double.NaN);

        @Range(min = 0, max = 100)
        public Fraction infinity = new Fraction(Double.POSITIVE_INFINITY);
    }

    @Test
    @DisplayName("#64 Number 子类持有 NaN/Infinity 时按非有限值拒绝，而非 longValue()=0 通过")
    void nonFiniteInNumberSubclassIsRejected() {
        List<SemanticValidationException.ConstraintViolation> violations =
            violationsOf(new NaNInNumberSubclassHolder());
        assertThat(violations)
            .extracting(SemanticValidationException.ConstraintViolation::fieldName)
            .containsExactlyInAnyOrder("nan", "infinity");
        assertThat(violations)
            .extracting(SemanticValidationException.ConstraintViolation::message)
            .containsOnly("值为 NaN 或 Infinity，无法满足范围约束 [0, 100]");
    }

    public static class ExactIntegralWhitelistHolder {
        // 若这些类型误走 double，Long.MAX_VALUE-1 会取整到 2^63 而被误报越界
        @Range(max = Long.MAX_VALUE - 1)
        public AtomicLong atomicLong = new AtomicLong(Long.MAX_VALUE - 1);

        @Range(max = Long.MAX_VALUE - 1)
        public LongAdder longAdder = new LongAdder();

        @Range(max = Long.MAX_VALUE - 1)
        public LongAccumulator longAccumulator = new LongAccumulator(Long::sum, Long.MAX_VALUE - 1);

        @Range(min = 0, max = 100)
        public AtomicInteger atomicInteger = new AtomicInteger(100);
    }

    @Test
    @DisplayName("#64 反向护栏：AtomicLong/LongAdder/LongAccumulator/AtomicInteger 仍按 longValue() 精确比较")
    void exactIntegralTypesStayExact() {
        ExactIntegralWhitelistHolder holder = new ExactIntegralWhitelistHolder();
        holder.longAdder.add(Long.MAX_VALUE - 1);
        assertThatCode(() -> validator.validateSemantics(holder))
            .doesNotThrowAnyException();
    }

    public static class ExactIntegralOutOfRangeHolder {
        @Range(min = 0, max = 100)
        public AtomicLong v = new AtomicLong(101);
    }

    @Test
    @DisplayName("#64 反向护栏：白名单整型越界仍被拒绝")
    void exactIntegralOutOfRangeIsRejected() {
        assertThat(violationsOf(new ExactIntegralOutOfRangeHolder()))
            .extracting(SemanticValidationException.ConstraintViolation::fieldName)
            .containsExactly("v");
    }
}
