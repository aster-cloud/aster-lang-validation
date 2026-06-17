package io.aster.validation.metadata;

/**
 * 当目标类型既不是 record，又缺少可靠的构造器参数名（编译时未启用
 * {@code -parameters}）时抛出。此时无法可靠地建立字段名到构造器参数索引的映射，
 * 按字段声明顺序猜测会导致数值被错误绑定到不同的构造器参数。
 *
 * <p>仅在 {@link ConstructorMetadataCache.UnreliableMappingPolicy#THROW} 策略下抛出；
 * 默认 {@code SKIP} 策略会将 schema 映射视为不可用而非抛出。
 */
public class UnreliableConstructorMappingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Class<?> targetClass;
    private final int fieldCount;
    private final int parameterCount;

    public UnreliableConstructorMappingException(Class<?> targetClass, int fieldCount, int parameterCount) {
        super(buildMessage(targetClass, fieldCount, parameterCount));
        this.targetClass = targetClass;
        this.fieldCount = fieldCount;
        this.parameterCount = parameterCount;
    }

    public Class<?> getTargetClass() {
        return targetClass;
    }

    public int getFieldCount() {
        return fieldCount;
    }

    public int getParameterCount() {
        return parameterCount;
    }

    private static String buildMessage(Class<?> targetClass, int fieldCount, int parameterCount) {
        return "无法为非记录类型建立可靠的构造器参数映射: "
            + (targetClass == null ? "null" : targetClass.getName())
            + " (字段数量=" + fieldCount + ", 构造器参数数量=" + parameterCount + ")。"
            + " 该类型缺少构造器参数名（未启用 -parameters 编译），且非 record，"
            + " 按字段声明顺序猜测映射不安全。请将其声明为 record，或启用 -parameters 编译。";
    }
}
