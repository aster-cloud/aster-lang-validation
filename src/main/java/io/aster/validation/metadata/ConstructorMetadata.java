package io.aster.validation.metadata;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.Map;

/**
 * 构造器元数据，缓存构造方法及参数字段映射。
 */
public class ConstructorMetadata {

    private final Constructor<?> constructor;
    private final Parameter[] parameters;
    private final Field[] fields;
    private final Map<String, Integer> fieldNameToParameterIndex;
    private final boolean fallbackToFieldOrder;

    public ConstructorMetadata(Constructor<?> constructor,
                               Parameter[] parameters,
                               Field[] fields,
                               Map<String, Integer> fieldNameToParameterIndex,
                               boolean fallbackToFieldOrder) {
        this.constructor = constructor;
        this.parameters = parameters;
        this.fields = fields;
        this.fieldNameToParameterIndex = fieldNameToParameterIndex;
        this.fallbackToFieldOrder = fallbackToFieldOrder;
    }

    public Constructor<?> getConstructor() {
        return constructor;
    }

    /**
     * Returns a defensive copy of the constructor parameters. The array is cloned so
     * a caller cannot corrupt the process-wide cached metadata via {@code array[i] = ...}.
     */
    public Parameter[] getParameters() {
        return parameters == null ? null : parameters.clone();
    }

    /**
     * Returns a defensive copy of the declared fields. The array is cloned so a caller
     * cannot corrupt the process-wide cached metadata via {@code array[i] = ...}.
     */
    public Field[] getFields() {
        return fields == null ? null : fields.clone();
    }

    public Map<String, Integer> getFieldNameToParameterIndex() {
        return fieldNameToParameterIndex;
    }

    public boolean isFallbackToFieldOrder() {
        return fallbackToFieldOrder;
    }
}
