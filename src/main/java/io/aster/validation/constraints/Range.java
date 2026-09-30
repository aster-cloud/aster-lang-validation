package io.aster.validation.constraints;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数值范围约束注解（闭区间），对任何 {@link Number} 运行时值生效：
 * 整数、浮点、{@code BigInteger}、{@code BigDecimal} 都以精确值比较。
 *
 * <p><b>界限的解析规则</b>（上下界各自独立）：设置了 {@link #minDouble()}/{@link #maxDouble()}
 * 则以其为准；否则用 {@link #min()}/{@link #max()}；两组都未设置则该侧无界。
 *
 * <p><b>哨兵值即无界</b>：注解属性无法表达"是否显式设置"，只能以"等于默认值"判断。
 * 因此下面这些写法都等价于"该侧无界"，即使是显式写出来的：
 * <ul>
 *   <li>{@code min = Long.MIN_VALUE}、{@code max = Long.MAX_VALUE}</li>
 *   <li>{@code minDouble = -Double.MAX_VALUE}、{@code maxDouble = Double.MAX_VALUE}</li>
 *   <li>{@code minDouble = Double.NEGATIVE_INFINITY}、{@code maxDouble = Double.POSITIVE_INFINITY}，
 *       以及任何 NaN 界限（视同未设置该浮点界限）</li>
 * </ul>
 * 后果：对 {@code BigInteger}/{@code BigDecimal} 字段，{@code @Range(min = 0, max = Long.MAX_VALUE)}
 * 并<b>不</b>约束"必须能装进 long"——2<sup>70</sup> 会通过。需要该语义时请显式写
 * {@code max = Long.MAX_VALUE - 1} 之类的真实界限，或改用真实的 {@code double} 界限。
 *
 * <p>{@link #message()} 中的 {@code {min}}/{@code {max}} 会替换为生效界限，无界一侧显示为 -∞/+∞。
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Range {

    /** 整数下界；等于默认值 {@code Long.MIN_VALUE} 即该侧无界。 */
    long min() default Long.MIN_VALUE;

    /** 整数上界；等于默认值 {@code Long.MAX_VALUE} 即该侧无界。 */
    long max() default Long.MAX_VALUE;

    /** 浮点下界，优先于 {@link #min()}；等于默认值 {@code -Double.MAX_VALUE} 或非有限即视同未设置。 */
    double minDouble() default -Double.MAX_VALUE;

    /** 浮点上界，优先于 {@link #max()}；等于默认值 {@code Double.MAX_VALUE} 或非有限即视同未设置。 */
    double maxDouble() default Double.MAX_VALUE;

    String message() default "值必须在 {min} 到 {max} 之间";
}
