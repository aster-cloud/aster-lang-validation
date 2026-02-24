# aster-lang-validation -- Aster 两层验证框架

提供领域对象的两层验证机制：Schema 验证（构造前字段校验）和 Semantic 验证（实例业务规则校验），确保数据从输入到业务逻辑全链路的正确性。

## 架构

### Layer 1 -- SchemaValidator（构造前校验）

在构造领域对象之前，校验输入字段（通常为 `Map<String, Object>`）是否与目标类型的构造器参数匹配。

- 检查必填字段是否缺失
- 检查是否存在未知字段
- 基于 `ConstructorMetadataCache` 反射解析目标类型的构造器元数据

### Layer 2 -- SemanticValidator（业务规则校验）

对已构造的领域对象实例执行约束注解驱动的业务规则验证。

- 遍历类型及其父类的全部字段，保障继承场景完整校验
- 字段值为 `null` 时默认跳过（仅 `@NotEmpty` 对 null 判定违规）
- 收集全部违规后一次性抛出 `SemanticValidationException`，避免逐个暴露

## 约束注解

| 注解 | 说明 |
|------|------|
| `@Range` | 数值范围约束（最小值、最大值） |
| `@NotEmpty` | 非空约束，适用于字符串和集合 |
| `@Pattern` | 正则表达式匹配约束 |

## 元数据支持

- **ConstructorMetadata** -- 构造器参数名与索引的映射
- **ConstructorMetadataCache** -- 构造器元数据缓存，避免重复反射
- **PolicyMetadata / PolicyMetadataLoader** -- 策略元数据加载，支持外部规则配置

## 异常体系

- `SchemaValidationException` -- Schema 层校验失败时抛出
- `SemanticValidationException` -- Semantic 层校验失败时抛出

## 依赖

- SLF4J 2.0.9（日志）
- JUnit Jupiter 5.10.0（测试）
- AssertJ 3.27.6（测试断言）
- Mockito 5.5.0（Mock 框架）
- Logback 1.5.19（测试运行时日志）

## 构建与测试

```bash
# 构建
./gradlew build

# 运行测试
./gradlew test
```

## 环境要求

- Java 25+
- Gradle
- 编译时启用 `-parameters`（已在 build.gradle.kts 中配置，保留构造器参数名供反射使用）

## 许可证

Apache License 2.0
