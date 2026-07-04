package io.aster.validation.schema;

import io.aster.validation.metadata.ConstructorMetadata;
import io.aster.validation.metadata.ConstructorMetadataCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Schema 验证器，负责在构造领域对象前校验输入字段是否与目标类型匹配。
 */
public class SchemaValidator {

    private static final Logger logger = LoggerFactory.getLogger(SchemaValidator.class);

    /**
     * Controls how missing required fields are handled.
     */
    public enum MissingFieldPolicy {
        /**
         * Default. Reject (throw {@link SchemaValidationException}) when a required
         * constructor parameter is missing from the input. A parameter is considered
         * optional — and therefore allowed to be missing — only when its declared type
         * is {@link java.util.Optional}.
         */
        STRICT,
        /**
         * Legacy behavior: only log a warning for missing fields and let the caller
         * fill defaults downstream.
         */
        LENIENT
    }

    private final ConstructorMetadataCache constructorMetadataCache;
    private final MissingFieldPolicy missingFieldPolicy;

    public SchemaValidator(ConstructorMetadataCache constructorMetadataCache) {
        this(constructorMetadataCache, MissingFieldPolicy.STRICT);
    }

    public SchemaValidator(ConstructorMetadataCache constructorMetadataCache,
                           MissingFieldPolicy missingFieldPolicy) {
        this.constructorMetadataCache = constructorMetadataCache;
        this.missingFieldPolicy = missingFieldPolicy == null ? MissingFieldPolicy.STRICT : missingFieldPolicy;
    }

    public MissingFieldPolicy getMissingFieldPolicy() {
        return missingFieldPolicy;
    }

    /**
     * 校验输入 Map 是否匹配目标类型的字段定义。
     *
     * @param targetClass 目标类型
     * @param inputMap    原始输入 Map
     */
    public void validateSchema(Class<?> targetClass, Map<String, Object> inputMap) {
        if (targetClass == null) {
            throw new IllegalArgumentException("目标类型不能为空");
        }

        ConstructorMetadata metadata = constructorMetadataCache.getConstructorMetadata(targetClass);
        Map<String, Integer> fieldMapping = metadata.getFieldNameToParameterIndex();
        if (fieldMapping == null || fieldMapping.isEmpty()) {
            // An empty mapping means we have no reliable field->parameter binding to
            // validate against. This happens for a no-arg constructor (safe, nothing
            // to validate) OR because the class is a non-record compiled without
            // -parameters, in which case the mapping was deliberately left empty and
            // metadata.isFallbackToFieldOrder() is set. In the latter case STRICT must
            // NOT silently validate nothing: that would let unknown/missing fields pass
            // unchecked. Fail closed with a clear signal instead. LENIENT keeps the
            // legacy pass-through behavior.
            if (metadata.isFallbackToFieldOrder()
                && missingFieldPolicy == MissingFieldPolicy.STRICT) {
                throw new SchemaValidationException(
                    "Schema 验证失败：无法为类型 " + targetClass.getName()
                        + " 建立可靠的字段映射（非 record 且构造器参数名不可用，fallbackToFieldOrder=true）。"
                        + "STRICT 模式拒绝在无法校验的情况下静默放行；请将该类型声明为 record，"
                        + "或在编译时启用 -parameters。");
            }
            return;
        }

        Map<String, Object> safeMap = inputMap == null ? Collections.emptyMap() : inputMap;
        Set<String> allowedFields = fieldMapping.keySet();

        List<String> unknownFields = computeUnknownFields(safeMap, allowedFields);
        List<String> missingFields = computeMissingFields(safeMap, allowedFields);

        List<String> missingRequiredFields = missingFieldPolicy == MissingFieldPolicy.STRICT
            ? filterRequired(missingFields, fieldMapping, metadata)
            : Collections.emptyList();

        if (!missingFields.isEmpty()) {
            if (missingRequiredFields.isEmpty()) {
                logger.warn("类型{}缺失字段：{}，将按默认值填充。", targetClass.getName(), missingFields);
            } else {
                logger.warn("类型{}缺失必填字段：{}", targetClass.getName(), missingRequiredFields);
            }
        }

        if (!unknownFields.isEmpty() || !missingRequiredFields.isEmpty()) {
            throw new SchemaValidationException(unknownFields, missingRequiredFields);
        }
    }

    /**
     * Restrict the missing-field list to fields backed by a non-Optional constructor
     * parameter. Optional parameters are allowed to be absent.
     */
    private static List<String> filterRequired(List<String> missingFields,
                                               Map<String, Integer> fieldMapping,
                                               ConstructorMetadata metadata) {
        Parameter[] parameters = metadata.getParameters();
        List<String> required = new ArrayList<>();
        for (String field : missingFields) {
            Integer index = fieldMapping.get(field);
            if (index == null) {
                // No backing parameter index known; treat as required (conservative).
                required.add(field);
                continue;
            }
            if (parameters != null && index >= 0 && index < parameters.length
                && isOmittableParameter(parameters[index])) {
                continue; // optional/nullable parameter, may be omitted
            }
            required.add(field);
        }
        Collections.sort(required);
        return Collections.unmodifiableList(required);
    }

    /**
     * A parameter may be omitted from the input when it declares an optional-style
     * container ({@link Optional}, {@link java.util.OptionalInt},
     * {@link java.util.OptionalLong}, {@link java.util.OptionalDouble}) or is
     * annotated with any {@code @Nullable} annotation (matched by simple name so it
     * works across jakarta/jspecify/jsr305/jetbrains variants).
     */
    private static boolean isOmittableParameter(Parameter parameter) {
        Class<?> type = parameter.getType();
        if (Optional.class.equals(type)
            || java.util.OptionalInt.class.equals(type)
            || java.util.OptionalLong.class.equals(type)
            || java.util.OptionalDouble.class.equals(type)) {
            return true;
        }
        for (java.lang.annotation.Annotation annotation : parameter.getAnnotations()) {
            if ("Nullable".equals(annotation.annotationType().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> computeUnknownFields(Map<String, Object> inputMap, Set<String> allowedFields) {
        List<String> unknown = new ArrayList<>();
        for (String key : inputMap.keySet()) {
            if (!allowedFields.contains(key)) {
                unknown.add(key);
            }
        }
        Collections.sort(unknown);
        return Collections.unmodifiableList(unknown);
    }

    private static List<String> computeMissingFields(Map<String, Object> inputMap, Set<String> allowedFields) {
        List<String> missing = new ArrayList<>();
        for (String field : allowedFields) {
            if (!inputMap.containsKey(field)) {
                missing.add(field);
            }
        }
        Collections.sort(missing);
        return Collections.unmodifiableList(missing);
    }
}
