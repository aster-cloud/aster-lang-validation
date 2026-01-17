package io.aster.validation.schema;

import io.aster.validation.metadata.ConstructorMetadata;
import io.aster.validation.metadata.ConstructorMetadataCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema 验证器，负责在构造领域对象前校验输入字段是否与目标类型匹配。
 */
public class SchemaValidator {

    private static final Logger logger = LoggerFactory.getLogger(SchemaValidator.class);

    private final ConstructorMetadataCache constructorMetadataCache;

    public SchemaValidator(ConstructorMetadataCache constructorMetadataCache) {
        this.constructorMetadataCache = constructorMetadataCache;
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

        if (!missingFields.isEmpty()) {
            logger.warn("类型{}缺失字段：{}，将按默认值填充。", targetClass.getName(), missingFields);
        }

        if (!unknownFields.isEmpty()) {
            throw new SchemaValidationException(unknownFields, missingFields);
        }
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
