package io.aster.validation.semantic;

import io.aster.validation.constraints.NotEmpty;
import io.aster.validation.constraints.Pattern;
import io.aster.validation.constraints.Range;
import io.aster.validation.metadata.ConstructorMetadataCache;
import io.aster.validation.metadata.UnreliableConstructorMappingException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 2 语义约束验证器，负责对领域对象实例执行业务规则校验。
 *
 * <p>实现要点：</p>
 * <ul>
 *     <li>获取类型及其父类的全部字段，保障继承场景也进行校验。</li>
 *     <li>基于注解元数据执行约束验证，支持 @Range、@NotEmpty、@Pattern。</li>
 *     <li>收集全部违规后一次性抛出 {@link SemanticValidationException}，避免一次只暴露一个错误。</li>
 *     <li>字段值为 {@code null} 时默认跳过校验，仅 @NotEmpty 对 null 判定为违规。</li>
 * </ul>
 */
public class SemanticValidator {

    private final ConstructorMetadataCache constructorMetadataCache;
    /** 缓存已编译的正则表达式，避免热路径上重复编译 */
    private final ConcurrentHashMap<String, java.util.regex.Pattern> patternCache = new ConcurrentHashMap<>();

    public SemanticValidator(ConstructorMetadataCache constructorMetadataCache) {
        this.constructorMetadataCache = constructorMetadataCache;
    }

    /**
     * 对给定实例执行语义验证。
     *
     * @param instance 待校验对象
     */
    public void validateSemantics(Object instance) {
        Objects.requireNonNull(instance, "语义验证对象不能为空");

        List<SemanticValidationException.ConstraintViolation> violations = new ArrayList<>();
        for (Field field : collectAllFields(instance.getClass())) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            // 仅当字段带有约束注解时才需要读取值；无注解的字段直接跳过，
            // 避免对所有字段都进行 trySetAccessible 调用。
            if (!hasAnyConstraint(field)) {
                continue;
            }

            Object value;
            try {
                value = readFieldValue(instance, field);
            } catch (FieldAccessSkippedException skip) {
                // Fail-closed: 反射不可访问时不再静默跳过，作为 violation 上报。
                // 之前的实现返回 null 并继续，等同于"绕过验证"——在 OverlayValidator
                // R2 codex 审查中被列为同性质的 silent-pass 风险。
                violations.add(new SemanticValidationException.ConstraintViolation(
                    field.getName(),
                    null,
                    "field-access",
                    "字段不可反射访问，无法执行约束校验：" + skip.getMessage()
                ));
                continue;
            }
            processRangeConstraint(field, value, violations);
            processNotEmptyConstraint(field, value, violations);
            processPatternConstraint(field, value, violations);
        }

        if (!violations.isEmpty()) {
            throw new SemanticValidationException(violations);
        }
    }

    private boolean hasAnyConstraint(Field field) {
        return field.isAnnotationPresent(Range.class)
            || field.isAnnotationPresent(NotEmpty.class)
            || field.isAnnotationPresent(Pattern.class);
    }

    /** 信号异常：反射不可访问字段。由 {@link #readFieldValue} 抛出，{@link #validateSemantics} 转译成 violation。 */
    private static final class FieldAccessSkippedException extends RuntimeException {
        FieldAccessSkippedException(String message) { super(message); }
    }

    private List<Field> collectAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                // 触发缓存构建，保持与 Schema 验证相同的数据来源
                for (Field field : constructorMetadataCache.getConstructorMetadata(current).getFields()) {
                    fields.add(field);
                }
            } catch (IllegalArgumentException | UnreliableConstructorMappingException ex) {
                // 父类可能缺少公共构造器，或构造器参数名不可用导致 field->parameter
                // 映射不可靠 —— 两种情况都回退到直接读取声明字段。
                //
                // ★必须同时捕 UnreliableConstructorMappingException（issue #43）：
                //   它直接继承 RuntimeException（**不是** IllegalArgumentException），
                //   于是在 ConstructorMetadataCache 配 THROW 策略时会从这里逃逸，
                //   让**根本不需要构造器映射**的语义校验整体崩溃——
                //   本方法只用 getFields()，与 field->parameter 映射毫无关系。
                //
                //   触发条件是「非 record 且编译时没带 -parameters」。本仓测试带该 flag，
                //   所以本地跑不出来；但把本库当依赖用的消费方未必带，
                //   那正是 THROW 策略存在的意义（fail fast 于**映射**场景）。
                for (Field field : current.getDeclaredFields()) {
                    fields.add(field);
                }
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    /**
     * 读取字段值。fail-closed：反射不可访问会抛 {@link FieldAccessSkippedException}，
     * 调用方应当转译成 violation；不再返回 null 静默跳过。
     */
    private Object readFieldValue(Object instance, Field field) {
        // ★不再翻转共享 Field 的 accessible 开关（issue #44）。
        //
        //   缓存里的 Field 实例被**所有线程共享**（ConstructorMetadata.getFields()
        //   只做数组浅拷贝，Field 对象本身是同一个）。原实现做
        //   trySetAccessible() → get() → finally setAccessible(false)：
        //   两个线程并发校验同一类型时，一方在 finally 里把开关关掉，
        //   另一方的 field.get 抛 IllegalAccessException，被 fail-closed 逻辑
        //   转成**虚假的 field-access violation**——校验结果随线程调度而变。
        //
        //   现在：只在必要时**打开**，打开后**不再关回去**。理由——
        //   - 关回去本就不提供任何安全保证：Field 是共享的，任何并发调用方都能
        //     再次打开；「用完就关」在共享对象上是幻觉；
        //   - 反射可访问性由模块系统与 SecurityManager 决定，不由这一次开关决定；
        //   - 不写共享状态即消除竞态，这是唯一能让并发结果确定的做法。
        try {
            if (!field.canAccess(instance) && !field.trySetAccessible()) {
                throw new FieldAccessSkippedException(
                    "field.trySetAccessible() returned false (module/JMH restriction?)"
                );
            }
            return field.get(instance);
        } catch (IllegalAccessException ex) {
            throw new FieldAccessSkippedException("IllegalAccessException: " + ex.getMessage());
        }
    }

    private void processRangeConstraint(Field field,
                                        Object value,
                                        List<SemanticValidationException.ConstraintViolation> violations) {
        Range range = field.getAnnotation(Range.class);
        if (range == null || value == null) {
            return;
        }
        if (!(value instanceof Number number)) {
            violations.add(new SemanticValidationException.ConstraintViolation(
                field.getName(),
                value,
                Range.class.getSimpleName(),
                "字段类型不是数值类型，无法套用范围约束"
            ));
            return;
        }

        // 界限只解析一次，之后 NaN 消息、违规判定、违规消息全部复用同一对界限：
        // 运行时类型只决定「值怎么变成 BigDecimal」，不再决定「读注解的哪组界限」。
        RangeBounds bounds = RangeBounds.of(range);

        // NaN 与任何数比较都为 false，±Infinity 也不可能落在有限区间内；
        // 二者都无法转成 BigDecimal，必须在比较前单独拒绝。只有浮点运行时类型会出现这些值。
        // 消息里不回显原始值，值由 violation 自身携带。
        if (isNonFinite(number)) {
            violations.add(new SemanticValidationException.ConstraintViolation(
                field.getName(),
                value,
                Range.class.getSimpleName(),
                "值为 NaN 或 Infinity，无法满足范围约束 " + bounds.interval()
            ));
            return;
        }

        if (!bounds.contains(toBigDecimal(number))) {
            violations.add(new SemanticValidationException.ConstraintViolation(
                field.getName(),
                value,
                Range.class.getSimpleName(),
                bounds.format(range.message())
            ));
        }
    }

    /**
     * {@code @Range} 四个界限属性归一化后的一对有效界限；{@code null} 表示该侧无界。
     *
     * <p>为什么归一化：{@code min/max} 与 {@code minDouble/maxDouble} 两组属性乘以
     * 整数、浮点、大数三类运行时值，若在各条分支里分别读取界限，总有一半组合被漏掉
     * （整型字段只设浮点界限、浮点字段只设整数界限），且 BigDecimal 会被压成 double
     * 比较而丢失精度，NaN 消息与违规消息又各自拼接。统一成一对 {@link BigDecimal}
     * 之后，这些差异全部消失。
     *
     * <p>解析规则（上下界各自独立）：显式设置了浮点界限则用浮点界限；否则用显式设置的
     * 整数界限；两组都是默认值则该侧无界。两组都设时浮点优先——它更精确，且与
     * 浮点分支原有的回退规则一致。
     */
    private record RangeBounds(BigDecimal min, BigDecimal max) {

        static RangeBounds of(Range range) {
            return new RangeBounds(
                resolve(range.minDouble(), -Double.MAX_VALUE, range.min(), Long.MIN_VALUE),
                resolve(range.maxDouble(), Double.MAX_VALUE, range.max(), Long.MAX_VALUE)
            );
        }

        private static BigDecimal resolve(double floating, double floatingDefault,
                                          long integral, long integralDefault) {
            if (floating != floatingDefault) {
                return BigDecimal.valueOf(floating);
            }
            if (integral != integralDefault) {
                return BigDecimal.valueOf(integral);
            }
            return null;
        }

        /** 闭区间判定。 */
        boolean contains(BigDecimal value) {
            return (min == null || value.compareTo(min) >= 0)
                && (max == null || value.compareTo(max) <= 0);
        }

        /** 把注解 message 模板中的 {@code {min}}/{@code {max}} 替换为有效界限。 */
        String format(String template) {
            return template
                .replace("{min}", describe(min, "-∞"))
                .replace("{max}", describe(max, "+∞"));
        }

        String interval() {
            return "[" + describe(min, "-∞") + ", " + describe(max, "+∞") + "]";
        }

        private static String describe(BigDecimal bound, String unbounded) {
            return bound == null ? unbounded : bound.toPlainString();
        }
    }

    /**
     * 按运行时类型精确转换。字段经反射读取必然装箱，所以无需再看声明类型：
     * BigDecimal/BigInteger 原样保留精度，Double/Float 走 {@link BigDecimal#valueOf(double)}
     * （保持十进制表示，与 double 的顺序一致），其余整型经 {@code longValue()}。
     */
    private static BigDecimal toBigDecimal(Number number) {
        if (number instanceof BigDecimal decimal) {
            return decimal;
        }
        if (number instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (number instanceof Double || number instanceof Float) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.valueOf(number.longValue());
    }

    private static boolean isNonFinite(Number number) {
        return (number instanceof Double || number instanceof Float)
            && !Double.isFinite(number.doubleValue());
    }

    private void processNotEmptyConstraint(Field field,
                                           Object value,
                                           List<SemanticValidationException.ConstraintViolation> violations) {
        NotEmpty notEmpty = field.getAnnotation(NotEmpty.class);
        if (notEmpty == null) {
            return;
        }
        if (value == null) {
            violations.add(new SemanticValidationException.ConstraintViolation(
                field.getName(),
                null,
                NotEmpty.class.getSimpleName(),
                notEmpty.message()
            ));
            return;
        }
        if (value instanceof String text) {
            if (text.isEmpty()) {
                violations.add(new SemanticValidationException.ConstraintViolation(
                    field.getName(),
                    value,
                    NotEmpty.class.getSimpleName(),
                    notEmpty.message()
                ));
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                violations.add(new SemanticValidationException.ConstraintViolation(
                    field.getName(),
                    value,
                    NotEmpty.class.getSimpleName(),
                    notEmpty.message()
                ));
            }
            return;
        }

        violations.add(new SemanticValidationException.ConstraintViolation(
            field.getName(),
            value,
            NotEmpty.class.getSimpleName(),
            "字段类型不支持 NotEmpty 约束"
        ));
    }

    private void processPatternConstraint(Field field,
                                          Object value,
                                          List<SemanticValidationException.ConstraintViolation> violations) {
        Pattern pattern = field.getAnnotation(Pattern.class);
        if (pattern == null || value == null) {
            return;
        }
        if (!(value instanceof CharSequence text)) {
            violations.add(new SemanticValidationException.ConstraintViolation(
                field.getName(),
                value,
                Pattern.class.getSimpleName(),
                "字段类型不是文本，无法执行正则匹配"
            ));
            return;
        }

        java.util.regex.Pattern compiled;
        try {
            compiled = patternCache.computeIfAbsent(pattern.regexp(), java.util.regex.Pattern::compile);
        } catch (java.util.regex.PatternSyntaxException e) {
            violations.add(new SemanticValidationException.ConstraintViolation(
                field.getName(),
                value,
                Pattern.class.getSimpleName(),
                "正则表达式语法错误: " + pattern.regexp() + " (" + e.getMessage() + ")"
            ));
            return;
        }
        if (!compiled.matcher(text).matches()) {
            violations.add(new SemanticValidationException.ConstraintViolation(
                field.getName(),
                value,
                Pattern.class.getSimpleName(),
                pattern.message().replace("{regexp}", pattern.regexp())
            ));
        }
    }
}
