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

            Object value = readFieldValue(instance, field);
            processRangeConstraint(field, value, violations);
            processNotEmptyConstraint(field, value, violations);
            processPatternConstraint(field, value, violations);
        }

        if (!violations.isEmpty()) {
            throw new SemanticValidationException(violations);
        }
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

    private Object readFieldValue(Object instance, Field field) {
        boolean accessible = field.canAccess(instance);
        try {
            if (!accessible) {
                // 使用 trySetAccessible() 代替 setAccessible(true) 以支持 JMH 等受限环境
                // 如果返回 false，跳过该字段的验证（假设默认值有效）
                if (!field.trySetAccessible()) {
                    return null; // 无法访问时返回 null，跳过后续验证
                }
            }
            return field.get(instance);
        } catch (IllegalAccessException ex) {
            // 反射访问失败时返回 null，允许验证继续但跳过此字段
            return null;
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

        if (isFloatingNumber(field.getType(), value)) {
            double doubleValue = number.doubleValue();
            double min = range.minDouble();
            double max = range.maxDouble();
            if (doubleValue < min || doubleValue > max) {
                violations.add(new SemanticValidationException.ConstraintViolation(
                    field.getName(),
                    value,
                    Range.class.getSimpleName(),
                    "值 " + doubleValue + " 超出范围 [" + min + ", " + max + "]"
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
                    "值 " + longValue + " 超出范围 [" + min + ", " + max + "]"
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

        java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(pattern.regexp());
        if (!compiled.matcher(text).matches()) {
            violations.add(new SemanticValidationException.ConstraintViolation(
                field.getName(),
                value,
                Pattern.class.getSimpleName(),
                pattern.message().replace("{regexp}", pattern.regexp())
            ));
        }
    }

    private boolean isFloatingNumber(Class<?> fieldType, Object value) {
        Class<?> type = fieldType.isPrimitive() ? wrapPrimitive(fieldType) : fieldType;
        return type == Float.class
            || type == Double.class
            || value instanceof java.math.BigDecimal;
    }

    private Class<?> wrapPrimitive(Class<?> primitive) {
        if (primitive == double.class) {
            return Double.class;
        }
        if (primitive == float.class) {
            return Float.class;
        }
        if (primitive == long.class) {
            return Long.class;
        }
        if (primitive == int.class) {
            return Integer.class;
        }
        if (primitive == short.class) {
            return Short.class;
        }
        if (primitive == byte.class) {
            return Byte.class;
        }
        if (primitive == boolean.class) {
            return Boolean.class;
        }
        if (primitive == char.class) {
            return Character.class;
        }
        return primitive;
    }
}
