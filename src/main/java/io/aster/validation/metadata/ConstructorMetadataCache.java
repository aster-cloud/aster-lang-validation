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

    private final ConcurrentHashMap<Class<?>, ConstructorMetadata> constructorCache = new ConcurrentHashMap<>();

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

        boolean parameterNamesPresent = Arrays.stream(parameters).allMatch(Parameter::isNamePresent);
        if (parameterNamesPresent) {
            for (int i = 0; i < parameters.length; i++) {
                mapping.put(parameters[i].getName(), i);
            }
            return mapping;
        }

        if (fields != null) {
            for (int i = 0; i < fields.length && i < parameters.length; i++) {
                mapping.putIfAbsent(fields[i].getName(), i);
            }
        }

        if (mapping.isEmpty()) {
            logger.warn("类{}的构造器参数名不可用，字段数量为{}，构造器参数数量为{}，无法建立精确映射，将按索引直接回填。",
                clazz.getName(),
                fields == null ? 0 : fields.length,
                constructor.getParameterCount()
            );
            for (int i = 0; i < parameters.length; i++) {
                mapping.put(parameters[i].getName() != null ? parameters[i].getName() : "arg" + i, i);
            }
        } else {
            logger.warn("类{}的构造器参数名不可用，已按字段声明顺序建立映射，建议编译时启用-parameters。", clazz.getName());
        }

        return mapping;
    }

    private boolean shouldMarkFallback(Class<?> clazz, Parameter[] parameters) {
        if (clazz.isRecord()) {
            return false;
        }
        return !Arrays.stream(parameters).allMatch(Parameter::isNamePresent);
    }
}
