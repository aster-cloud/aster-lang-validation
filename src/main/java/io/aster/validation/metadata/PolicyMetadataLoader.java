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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * 策略元数据加载器，负责动态加载策略类并缓存反射信息。
 *
 * <p>安全约束：
 * <ol>
 *   <li>仅加载以 {@code _fn} 结尾的策略类（编译器约定），其他类一律拒绝</li>
 *   <li>包名必须通过 {@link #setPackageAllowlist(Set)} 显式声明，默认空集 =
 *       拒绝所有反射加载（fail-closed）；调用方在初始化阶段提供策略包前缀</li>
 *   <li>仅解析 {@code public static} 方法，避免反射调用任意实例方法</li>
 * </ol>
 *
 * <p>修复了 R2 codex 后端审查 #10：未受控的 {@code Class.forName} 在多租户
 * 环境下可被滥用为反射攻击面。
 */
public class PolicyMetadataLoader {

    private static final Logger logger = LoggerFactory.getLogger(PolicyMetadataLoader.class);
    private static final String POLICY_CLASS_SUFFIX = "_fn";

    /**
     * System property / environment variable name from which the default
     * allowlist is loaded. Comma-separated package prefixes. This lets
     * deployments configure the allowlist without code changes and avoids
     * the previous "default = empty = reject everything" behavior that
     * would have broken existing callers on upgrade.
     *
     * Example: {@code -Daster.policy.packages=io.aster.policy,com.example.rules}
     */
    public static final String POLICY_PACKAGES_PROPERTY = "aster.policy.packages";
    public static final String POLICY_PACKAGES_ENV = "ASTER_POLICY_PACKAGES";

    private final ConcurrentHashMap<String, PolicyMetadata> metadataCache = new ConcurrentHashMap<>();
    private volatile Set<String> packageAllowlist;

    /**
     * Default constructor: reads allowlist from system property or env var.
     * Use {@link #PolicyMetadataLoader(Set)} for programmatic configuration.
     */
    public PolicyMetadataLoader() {
        this.packageAllowlist = loadAllowlistFromEnvironment();
    }

    /**
     * Programmatic constructor: caller provides the package allowlist directly.
     * Pass {@code null} or empty set for strictest fail-closed (reject every load).
     */
    public PolicyMetadataLoader(Set<String> packageAllowlist) {
        this.packageAllowlist = packageAllowlist == null ? Set.of() : Set.copyOf(packageAllowlist);
    }

    private static Set<String> loadAllowlistFromEnvironment() {
        String value = System.getProperty(POLICY_PACKAGES_PROPERTY);
        if (value == null || value.isBlank()) value = System.getenv(POLICY_PACKAGES_ENV);
        if (value == null || value.isBlank()) {
            logger.info(
                "PolicyMetadataLoader allowlist is empty (no {} or {} set). " +
                "All loadPolicyMetadata() calls will be rejected as SecurityException. " +
                "Configure the allowlist before loading policies.",
                POLICY_PACKAGES_PROPERTY, POLICY_PACKAGES_ENV
            );
            return Set.of();
        }
        Set<String> packages = java.util.Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        logger.info("PolicyMetadataLoader allowlist loaded from {}: {}",
            System.getProperty(POLICY_PACKAGES_PROPERTY) != null ? POLICY_PACKAGES_PROPERTY : POLICY_PACKAGES_ENV,
            packages);
        return packages;
    }

    /**
     * 配置允许反射加载的包前缀集合。空集表示拒绝所有加载（fail-closed）。
     * 应在应用启动期间一次性调用；运行时变更允许，会刷新缓存外的新查询。
     */
    public void setPackageAllowlist(Set<String> packages) {
        this.packageAllowlist = packages == null ? Set.of() : Set.copyOf(packages);
        // Security-critical: tightening the allowlist must not leave already-cached
        // policies from now-forbidden packages invocable. computeIfAbsent skips the
        // allowlist check on a cache hit, so a stale entry would remain a bypass.
        // Evict every cached policy whose module is no longer allowed.
        evictForbiddenFromCache();
    }

    /**
     * Drop cached metadata whose module no longer passes the current allowlist.
     * Called after any allowlist mutation so cache hits can never outlive the
     * permission that admitted them.
     */
    private void evictForbiddenFromCache() {
        metadataCache.keySet().removeIf(qualifiedName -> {
            int lastDot = qualifiedName.lastIndexOf('.');
            if (lastDot <= 0) {
                return true; // malformed key: cannot prove it is allowed → evict
            }
            String module = qualifiedName.substring(0, lastDot);
            return !isPackageAllowed(module);
        });
    }

    public Set<String> getPackageAllowlist() {
        return packageAllowlist;
    }

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

        // Allowlist 强制 — 默认空集就是拒绝所有加载。
        // 此校验必须在 Class.forName 之前进行；否则任意类名都会被尝试解析。
        if (!isPackageAllowed(policyModule)) {
            throw new SecurityException(
                "Policy package not in allowlist: " + policyModule +
                " (allowed: " + packageAllowlist + "). " +
                "Call PolicyMetadataLoader.setPackageAllowlist(...) before loading policies."
            );
        }

        try {
            String className = policyModule + "." + policyFunction + POLICY_CLASS_SUFFIX;
            Class<?> policyClass = Class.forName(className);

            // 后置校验：即使包名允许，类名必须以 _fn 结尾（编译器约定的策略类）。
            if (!policyClass.getName().endsWith(POLICY_CLASS_SUFFIX)) {
                throw new SecurityException("Loaded class does not have _fn suffix: " + policyClass.getName());
            }

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
        } catch (SecurityException se) {
            // 不要把安全拒绝包装成 RuntimeException — 调用方需要能识别这是
            // 安全策略拒绝，而不是 ClassNotFoundException 之类的运行时故障。
            throw se;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to load policy metadata: " + qualifiedName, e);
        }
    }

    private boolean isPackageAllowed(String packageName) {
        if (packageAllowlist.isEmpty()) {
            return false;
        }
        for (String allowed : packageAllowlist) {
            if (packageName.equals(allowed) || packageName.startsWith(allowed + ".")) {
                return true;
            }
        }
        return false;
    }

    private Method findPolicyMethod(Class<?> policyClass, String functionName) {
        List<Method> matches = new ArrayList<>();
        for (Method method : policyClass.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (method.getName().equals(functionName)
                && java.lang.reflect.Modifier.isStatic(modifiers)
                && java.lang.reflect.Modifier.isPublic(modifiers)) {
                matches.add(method);
            }
        }

        if (matches.isEmpty()) {
            throw new IllegalArgumentException("未找到策略方法: " + functionName
                + " (类: " + policyClass.getName() + ")");
        }
        if (matches.size() > 1) {
            // Multiple public-static overloads share the name. Picking the first is
            // nondeterministic (declared-method order is unspecified), so reject
            // rather than silently bind to an arbitrary overload. Policy classes are
            // expected to declare exactly one public-static entry point per name.
            StringBuilder signatures = new StringBuilder();
            for (Method m : matches) {
                if (signatures.length() > 0) {
                    signatures.append(", ");
                }
                signatures.append(m.getParameterCount()).append("-arg ").append(m);
            }
            throw new IllegalArgumentException(
                "策略方法存在多个 public static 重载，无法确定调用目标: " + functionName
                + " (类: " + policyClass.getName() + ", 候选: " + signatures + ")。"
                + " 策略类应为每个名称仅声明一个 public static 入口。");
        }
        return matches.get(0);
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
            } catch (SecurityException se) {
                // Security denials must propagate — they indicate a misconfigured
                // allowlist, not a transient/per-policy failure. Wrapping them as
                // a generic warning would hide a deployment-level mistake.
                logger.error("预加载策略元数据被安全策略拒绝: {} - {}", qualifiedName, se.getMessage());
                throw se;
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
