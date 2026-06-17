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
                && Optional.class.equals(parameters[index].getType())) {
                continue; // optional parameter, may be omitted
            }
            required.add(field);
        }
        Collections.sort(required);
        return Collections.unmodifiableList(required);
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
