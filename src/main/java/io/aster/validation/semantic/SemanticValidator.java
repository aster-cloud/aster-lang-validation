package io.aster.validation.semantic;

import io.aster.validation.constraints.NotEmpty;
import io.aster.validation.constraints.Pattern;
import io.aster.validation.constraints.Range;
import io.aster.validation.metadata.ConstructorMetadataCache;

import java.lang.reflect.Field;
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
            } catch (IllegalArgumentException ex) {
                // 父类可能缺少公共构造器，回退到直接读取声明字段
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
        boolean accessible = field.canAccess(instance);
        try {
            if (!accessible) {
                if (!field.trySetAccessible()) {
                    throw new FieldAccessSkippedException(
                        "field.trySetAccessible() returned false (module/JMH restriction?)"
                    );
                }
            }
            return field.get(instance);
        } catch (IllegalAccessException ex) {
            throw new FieldAccessSkippedException("IllegalAccessException: " + ex.getMessage());
        } finally {
            if (!accessible && field.canAccess(instance)) {
                field.setAccessible(false);
            }
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

        // NaN can never satisfy any range (every comparison against NaN is false),
        // so the previous "doubleValue > max" guard let it slip through. Reject it
        // explicitly. Only floating runtime types can be NaN.
        if ((number instanceof Double || number instanceof Float)
            && Double.isNaN(number.doubleValue())) {
            violations.add(new SemanticValidationException.ConstraintViolation(
                field.getName(),
                value,
                Range.class.getSimpleName(),
                // Redacted: do not echo the raw field value into the message.
                "值为 NaN，无法满足范围约束 [" + range.minDouble() + ", " + range.maxDouble() + "]"
            ));
            return;
        }

        // Classify by the RUNTIME value type, not the declared field type: a field
        // declared Number/Object holding a Double must be range-checked as floating,
        // and a BigInteger/BigDecimal must not be wrapped through longValue().
        if (isFloatingValue(number)) {
            double doubleValue = number.doubleValue();
            // ★浮点分支必须能用整数界限（issue #42）。
            //
            //   此前这里只读 minDouble()/maxDouble()，其默认值是 ∓Double.MAX_VALUE。
            //   于是 `@Range(min = 0, max = 100) double x = 1e9` **静默通过**——
            //   用户写了约束，校验器什么都没做，也不告警。
            //
            //   而 double 字段经反射读取必然装箱为 Double，isFloatingValue 恒为 true，
            //   所以「对 double 字段写整数界限」这一最自然的写法**必然**落进这个空洞。
            //   实测：double/float/BigDecimal 三个字段值 1e9、约束 [0,100]，
            //   四个字段里只有 long 那个被抓到。
            //
            //   回退规则：用户**显式设置过**整数界限（≠ 默认 Long.MIN/MAX）而浮点界限
            //   仍是默认值时，采用整数界限。两组都设过则以浮点界限为准（更精确，
            //   且是本分支的原生语义）；两组都没设则维持原样（无约束）。
            double min = range.minDouble();
            double max = range.maxDouble();
            if (min == -Double.MAX_VALUE && range.min() != Long.MIN_VALUE) {
                min = range.min();
            }
            if (max == Double.MAX_VALUE && range.max() != Long.MAX_VALUE) {
                max = range.max();
            }
            if (doubleValue < min || doubleValue > max) {
                violations.add(new SemanticValidationException.ConstraintViolation(
                    field.getName(),
                    value,
                    Range.class.getSimpleName(),
                    // Redacted: value is carried on the violation, never in the message.
                    "值超出范围 [" + min + ", " + max + "]"
                ));
            }
        } else if (number instanceof java.math.BigInteger bigInteger) {
            // Compare without wrapping through long (BigInteger can exceed long range).
            java.math.BigInteger min = java.math.BigInteger.valueOf(range.min());
            java.math.BigInteger max = java.math.BigInteger.valueOf(range.max());
            if (bigInteger.compareTo(min) < 0 || bigInteger.compareTo(max) > 0) {
                violations.add(new SemanticValidationException.ConstraintViolation(
                    field.getName(),
                    value,
                    Range.class.getSimpleName(),
                    "值超出范围 [" + range.min() + ", " + range.max() + "]"
                ));
            }
        } else {
            long longValue = number.longValue();
            long min = range.min();
            long max = range.max();
            if (longValue < min || longValue > max) {
                violations.add(new SemanticValidationException.ConstraintViolation(
                    field.getName(),
                    value,
                    Range.class.getSimpleName(),
                    // Redacted: value is carried on the violation, never in the message.
                    "值超出范围 [" + min + ", " + max + "]"
                ));
            }
        }
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

    /**
     * Classify by the runtime value type. A field read reflectively is always boxed,
     * so a {@code double}/{@code float} primitive arrives as {@link Double}/{@link Float}
     * here — meaning we no longer need the declared type to decide. {@link java.math.BigDecimal}
     * is treated as floating; {@link java.math.BigInteger} is handled separately by the caller.
     */
    private boolean isFloatingValue(Number value) {
        return value instanceof Double
            || value instanceof Float
            || value instanceof java.math.BigDecimal;
    }
}
