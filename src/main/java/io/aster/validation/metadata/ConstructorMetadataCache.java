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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
        // 审计 #19 Low：record 用 getDeclaredConstructors()——package-private / 嵌套 record 的
        // canonical constructor 未必 public，getConstructors()（仅 public）会空、误抛"未找到公共
        // 构造函数"，尽管其规范构造器可解析。非 record 仍要求 public 构造器（沿用既有语义）。
        Constructor<?>[] constructors =
            clazz.isRecord() ? clazz.getDeclaredConstructors() : clazz.getConstructors();
        if (constructors.length == 0) {
            throw new IllegalArgumentException(
                clazz.isRecord()
                    ? "未找到构造函数: " + clazz.getName()
                    : "未找到公共构造函数: " + clazz.getName());
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
        int maxArity = Arrays.stream(constructors).mapToInt(Constructor::getParameterCount).max().orElse(0);
        List<Constructor<?>> candidates = Arrays.stream(constructors)
            .filter(c -> c.getParameterCount() == maxArity)
            .toList();
        return candidates.size() == 1 ? candidates.get(0) : breakArityTie(clazz, candidates);
    }

    /**
     * 参数个数并列时的确定性选择。
     *
     * <p>{@link Class#getConstructors()} 的返回顺序在 Javadoc 中明确为未指定，若并列时任取其一，
     * field->parameter 映射（进而 STRICT 模式的未知/缺失字段判定）会随 JVM 实现而变。
     * 以「参数名 ∩ 声明字段名」最大者为准；仍并列则拒绝，与
     * {@code PolicyMetadataLoader.findPolicyMethod} 对同名重载的策略一致。
     *
     * <p>参数名不可用（非 record 且未带 -parameters）时映射本身就不可靠，
     * 由 {@link UnreliableMappingPolicy} 统一处理，此处不额外拒绝。
     */
    private Constructor<?> breakArityTie(Class<?> clazz, List<Constructor<?>> candidates) {
        boolean namesPresent = candidates.stream()
            .flatMap(c -> Arrays.stream(c.getParameters()))
            .allMatch(Parameter::isNamePresent);
        if (!namesPresent) {
            return candidates.get(0);
        }

        Set<String> fieldNames = Arrays.stream(clazz.getDeclaredFields())
            .map(Field::getName)
            .collect(Collectors.toSet());
        Map<Constructor<?>, Long> overlap = new HashMap<>();
        for (Constructor<?> candidate : candidates) {
            overlap.put(candidate, Arrays.stream(candidate.getParameters())
                .map(Parameter::getName)
                .filter(fieldNames::contains)
                .count());
        }
        long best = Collections.max(overlap.values());
        List<Constructor<?>> winners = candidates.stream()
            .filter(c -> overlap.get(c) == best)
            .toList();
        if (winners.size() == 1) {
            return winners.get(0);
        }

        StringBuilder signatures = new StringBuilder();
        for (Constructor<?> winner : winners) {
            if (signatures.length() > 0) {
                signatures.append(", ");
            }
            signatures.append(winner);
        }
        throw new IllegalArgumentException(
            "类存在多个参数个数相同且与字段名重合度相同的公共构造器，无法确定 field->parameter 映射: "
            + clazz.getName() + " (候选: " + signatures + ")。"
            + "请只保留一个参数最多的公共构造器，或让其参数名与字段名一一对应。");
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
