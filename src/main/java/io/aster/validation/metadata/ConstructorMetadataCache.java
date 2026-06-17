package io.aster.validation.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 构造器元数据缓存，负责缓存领域对象的构造方法信息。
 */
public class ConstructorMetadataCache {

    private static final Logger logger = LoggerFactory.getLogger(ConstructorMetadataCache.class);

    /**
     * Policy controlling what happens when a non-record class is missing reliable
     * constructor parameter names (i.e. compiled without {@code -parameters} and not
     * a record). In that situation, mapping {@code field[i] -> i} is an unsafe guess
     * because field declaration order is not guaranteed to match constructor
     * parameter order.
     */
    public enum UnreliableMappingPolicy {
        /**
         * Default. Treat the schema mapping as unavailable (empty mapping) and mark
         * {@link ConstructorMetadata#isFallbackToFieldOrder()} so callers can detect
         * that schema validation cannot be performed for this type. No silent guess.
         */
        SKIP,
        /**
         * Fail fast with a clear {@link UnreliableConstructorMappingException} rather
         * than producing a mapping that may be wrong.
         */
        THROW
    }

    private final ConcurrentHashMap<Class<?>, ConstructorMetadata> constructorCache = new ConcurrentHashMap<>();

    private final UnreliableMappingPolicy unreliableMappingPolicy;

    public ConstructorMetadataCache() {
        this(UnreliableMappingPolicy.SKIP);
    }

    public ConstructorMetadataCache(UnreliableMappingPolicy unreliableMappingPolicy) {
        this.unreliableMappingPolicy = unreliableMappingPolicy == null
            ? UnreliableMappingPolicy.SKIP
            : unreliableMappingPolicy;
    }

    public UnreliableMappingPolicy getUnreliableMappingPolicy() {
        return unreliableMappingPolicy;
    }

    /**
     * 获取目标类型的构造器元数据，若不存在则创建后缓存。
     *
     * @param clazz 目标类型
     * @return 构造器元数据
     */
    public ConstructorMetadata getConstructorMetadata(Class<?> clazz) {
        return constructorCache.computeIfAbsent(clazz, this::buildMetadata);
    }

    /**
     * 清空所有构造器元数据缓存。
     */
    public void clear() {
        constructorCache.clear();
    }

    private ConstructorMetadata buildMetadata(Class<?> clazz) {
        Constructor<?>[] constructors = clazz.getConstructors();
        if (constructors.length == 0) {
            throw new IllegalArgumentException("未找到公共构造函数: " + clazz.getName());
        }

        Constructor<?> constructor = selectConstructor(clazz, constructors);
        Parameter[] parameters = constructor.getParameters();
        Field[] fields = clazz.getDeclaredFields();
        Map<String, Integer> mapping = buildParameterMapping(clazz, constructor, parameters, fields);

        return new ConstructorMetadata(
            constructor,
            parameters,
            fields,
            Collections.unmodifiableMap(mapping),
            shouldMarkFallback(clazz, parameters)
        );
    }

    private Constructor<?> selectConstructor(Class<?> clazz, Constructor<?>[] constructors) {
        if (clazz.isRecord()) {
            RecordComponent[] components = clazz.getRecordComponents();
            if (components != null && components.length > 0) {
                Class<?>[] parameterTypes = Arrays.stream(components)
                    .map(RecordComponent::getType)
                    .toArray(Class<?>[]::new);
                try {
                    return clazz.getDeclaredConstructor(parameterTypes);
                } catch (NoSuchMethodException ex) {
                    logger.warn("记录类型{}未找到匹配的主构造器，回退至第一个公共构造器。", clazz.getName());
                }
            }
        }
        return Arrays.stream(constructors)
            .max((a, b) -> Integer.compare(a.getParameterCount(), b.getParameterCount()))
            .orElse(constructors[0]);
    }

    private Map<String, Integer> buildParameterMapping(Class<?> clazz,
                                                       Constructor<?> constructor,
                                                       Parameter[] parameters,
                                                       Field[] fields) {
        Map<String, Integer> mapping = new HashMap<>();

        if (clazz.isRecord()) {
            RecordComponent[] components = clazz.getRecordComponents();
            if (components != null) {
                for (int i = 0; i < components.length; i++) {
                    mapping.put(components[i].getName(), i);
                }
            }
            return mapping;
        }

        // No-arg constructor: nothing to map, and nothing to guess. Empty mapping
        // is correct and unambiguous, so don't treat it as unreliable.
        if (parameters.length == 0) {
            return mapping;
        }

        boolean parameterNamesPresent = Arrays.stream(parameters).allMatch(Parameter::isNamePresent);
        if (parameterNamesPresent) {
            for (int i = 0; i < parameters.length; i++) {
                mapping.put(parameters[i].getName(), i);
            }
            return mapping;
        }

        // Non-record class without reliable parameter names. We must NOT guess by
        // index: field declaration order is not guaranteed to equal constructor
        // parameter order, so a field[i] -> i mapping can silently mis-bind values
        // to the wrong constructor argument. Either skip (treat mapping as
        // unavailable) or throw, per the configured policy.
        if (unreliableMappingPolicy == UnreliableMappingPolicy.THROW) {
            throw new UnreliableConstructorMappingException(clazz,
                fields == null ? 0 : fields.length,
                constructor.getParameterCount());
        }

        logger.warn(
            "类{}既非记录类型且构造器参数名不可用（字段数量={}, 构造器参数数量={}），" +
            "无法可靠建立 field->parameter 映射；schema 映射将被视为不可用（跳过）。" +
            "建议将该类型声明为 record，或编译时启用 -parameters。",
            clazz.getName(),
            fields == null ? 0 : fields.length,
            constructor.getParameterCount()
        );
        return mapping; // intentionally empty -> mapping unavailable, no silent guess
    }

    private boolean shouldMarkFallback(Class<?> clazz, Parameter[] parameters) {
        if (clazz.isRecord()) {
            return false;
        }
        return !Arrays.stream(parameters).allMatch(Parameter::isNamePresent);
    }
}
