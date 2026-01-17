package io.aster.validation.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * 策略元数据加载器，负责动态加载策略类并缓存反射信息。
 */
public class PolicyMetadataLoader {

    private static final Logger logger = LoggerFactory.getLogger(PolicyMetadataLoader.class);

    private final ConcurrentHashMap<String, PolicyMetadata> metadataCache = new ConcurrentHashMap<>();

    /**
     * 根据策略限定名加载元数据信息，并进行缓存。
     *
     * @param qualifiedName 策略限定名（形如 module.function）
     * @return 策略元数据缓存对象
     */
    public PolicyMetadata loadPolicyMetadata(String qualifiedName) {
        return metadataCache.computeIfAbsent(qualifiedName, this::createMetadata);
    }

    /**
     * 清空所有已缓存的元数据信息。
     */
    public void clear() {
        metadataCache.clear();
    }

    private PolicyMetadata createMetadata(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == qualifiedName.length() - 1) {
            throw new IllegalArgumentException("非法策略标识: " + qualifiedName);
        }

        String policyModule = qualifiedName.substring(0, lastDot);
        String policyFunction = qualifiedName.substring(lastDot + 1);

        try {
            String className = policyModule + "." + policyFunction + "_fn";
            Class<?> policyClass = Class.forName(className);

            Method functionMethod = findPolicyMethod(policyClass, policyFunction);
            MethodHandle handle = MethodHandles.publicLookup().unreflect(functionMethod);
            MethodHandle spreadInvoker = handle.asSpreader(Object[].class, functionMethod.getParameterCount());

            return new PolicyMetadata(
                policyClass,
                functionMethod,
                handle,
                spreadInvoker,
                functionMethod.getParameters()
            );
        } catch (Throwable e) {
            throw new RuntimeException("Failed to load policy metadata: " + qualifiedName, e);
        }
    }

    private Method findPolicyMethod(Class<?> policyClass, String functionName) {
        for (Method method : policyClass.getDeclaredMethods()) {
            if (method.getName().equals(functionName) &&
                java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        throw new IllegalArgumentException("未找到策略方法: " + functionName);
    }

    public void preloadPolicies(Collection<String> qualifiedNames) {
        if (qualifiedNames == null || qualifiedNames.isEmpty()) {
            return;
        }
        for (String qualifiedName : qualifiedNames) {
            if (qualifiedName == null || qualifiedName.isBlank()) {
                continue;
            }
            try {
                loadPolicyMetadata(qualifiedName);
            } catch (RuntimeException ex) {
                logger.warn("预加载策略元数据失败: {} - {}", qualifiedName, ex.getMessage());
            }
        }
    }

    public List<String> discoverPolicyFunctionsFromJar(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return List.of();
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = PolicyMetadataLoader.class.getClassLoader();
        }
        try (InputStream inputStream = cl.getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                logger.warn("未找到策略资源 JAR: {}", resourceName);
                return List.of();
            }
            List<String> qualifiedNames = new ArrayList<>();
            try (JarInputStream jarStream = new JarInputStream(inputStream)) {
                JarEntry entry;
                while ((entry = jarStream.getNextJarEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    if (name == null || !name.endsWith("_fn.class")) {
                        continue;
                    }
                    String className = name.replace('/', '.').replace(".class", "");
                    if (!className.endsWith("_fn")) {
                        continue;
                    }
                    int lastDot = className.lastIndexOf('.');
                    if (lastDot <= 0) {
                        continue;
                    }
                    String module = className.substring(0, lastDot);
                    String functionWithSuffix = className.substring(lastDot + 1);
                    String functionName = functionWithSuffix.substring(0, functionWithSuffix.length() - 3);
                    qualifiedNames.add(module + "." + functionName);
                }
            }
            return qualifiedNames;
        } catch (IOException e) {
            logger.warn("扫描策略资源 JAR 失败: {}", resourceName, e);
            return List.of();
        }
    }
}
