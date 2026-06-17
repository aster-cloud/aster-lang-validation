package io.aster.validation.schema;

import io.aster.validation.metadata.ConstructorMetadataCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaValidatorTest {

    private SchemaValidator schemaValidator;
    private ConstructorMetadataCache constructorMetadataCache;

    @BeforeEach
    void setUp() {
        constructorMetadataCache = new ConstructorMetadataCache();
        schemaValidator = new SchemaValidator(constructorMetadataCache);
    }

    @Test
    void testValidateSchema_allFieldsMatch() {
        Map<String, Object> input = new HashMap<>();
        input.put("name", "Alice");
        input.put("age", 28);
        input.put("active", true);

        assertThatCode(() -> schemaValidator.validateSchema(SampleClass.class, input))
            .doesNotThrowAnyException();
    }

    @Test
    void testValidateSchema_unknownFieldSingle() {
        Map<String, Object> input = new HashMap<>();
        input.put("name", "Alice");
        input.put("age", 28);
        input.put("active", true);
        input.put("unknown", "value");

        assertThatThrownBy(() -> schemaValidator.validateSchema(SampleClass.class, input))
            .isInstanceOf(SchemaValidationException.class)
            .hasMessageContaining("未知字段 [unknown]");
    }

    @Test
    void testValidateSchema_unknownFieldMultiple() {
        Map<String, Object> input = new HashMap<>();
        input.put("name", "Alice");
        input.put("age", 28);
        input.put("active", true);
        input.put("extraA", "value");
        input.put("extraB", "value");

        assertThatThrownBy(() -> schemaValidator.validateSchema(SampleClass.class, input))
            .isInstanceOf(SchemaValidationException.class)
            .satisfies(ex -> {
                SchemaValidationException validationException = (SchemaValidationException) ex;
                assertThat(validationException.getUnknownFields()).containsExactly("extraA", "extraB");
            });
    }

    @Test
    void testValidateSchema_missingFieldWithDefault_lenient() {
        SchemaValidator lenient = new SchemaValidator(
            constructorMetadataCache, SchemaValidator.MissingFieldPolicy.LENIENT);
        Map<String, Object> input = new HashMap<>();
        input.put("name", "Alice");
        input.put("age", 28);

        assertThatCode(() -> lenient.validateSchema(SampleClass.class, input))
            .doesNotThrowAnyException();
    }

    @Test
    void testValidateSchema_emptyMap_lenient() {
        SchemaValidator lenient = new SchemaValidator(
            constructorMetadataCache, SchemaValidator.MissingFieldPolicy.LENIENT);
        Map<String, Object> input = new HashMap<>();

        assertThatCode(() -> lenient.validateSchema(SampleClass.class, input))
            .doesNotThrowAnyException();
    }

    @Test
    void testValidateSchema_nullValue() {
        Map<String, Object> input = new HashMap<>();
        input.put("name", null);
        input.put("age", 28);
        input.put("active", null);

        assertThatCode(() -> schemaValidator.validateSchema(SampleClass.class, input))
            .doesNotThrowAnyException();
    }

    @Test
    void testValidateSchema_recordClass() {
        Map<String, Object> input = new HashMap<>();
        input.put("code", "R-1");
        input.put("level", 3);

        assertThatCode(() -> schemaValidator.validateSchema(SampleRecord.class, input))
            .doesNotThrowAnyException();
    }

    @Test
    void testValidateSchema_regularClass() {
        Map<String, Object> input = new HashMap<>();
        input.put("identifier", "ID-1");
        input.put("score", 80);

        assertThatCode(() -> schemaValidator.validateSchema(RegularClass.class, input))
            .doesNotThrowAnyException();
    }

    @Test
    void testValidateSchema_exceptionMessage() {
        Map<String, Object> input = new HashMap<>();
        input.put("unknownField", "value");

        assertThatThrownBy(() -> schemaValidator.validateSchema(SampleClass.class, input))
            .isInstanceOf(SchemaValidationException.class)
            .hasMessage("Schema 验证失败：未知字段 [unknownField]，缺失字段 [active, age, name]");
    }

    @Test
    void testValidateSchema_caseInsensitive() {
        Map<String, Object> input = new HashMap<>();
        input.put("Name", "Alice");
        input.put("age", 28);
        input.put("active", true);

        assertThatThrownBy(() -> schemaValidator.validateSchema(SampleClass.class, input))
            .isInstanceOf(SchemaValidationException.class)
            .satisfies(ex -> {
                SchemaValidationException validationException = (SchemaValidationException) ex;
                assertThat(validationException.getUnknownFields()).containsExactly("Name");
                assertThat(validationException.getMissingFields()).contains("name");
            });
    }

    @Test
    void testValidateSchema_strictRejectsMissingRequired() {
        // Default validator is STRICT.
        Map<String, Object> input = new HashMap<>();
        input.put("name", "Alice");
        input.put("age", 28);
        // "active" missing -> required -> STRICT must reject

        assertThatThrownBy(() -> schemaValidator.validateSchema(SampleClass.class, input))
            .isInstanceOf(SchemaValidationException.class)
            .satisfies(ex -> {
                SchemaValidationException validationException = (SchemaValidationException) ex;
                assertThat(validationException.getMissingFields()).containsExactly("active");
            });
    }

    @Test
    void testValidateSchema_strictAllowsMissingOptionalParam() {
        // "note" is backed by an Optional<String> constructor parameter, so it may
        // be omitted even under STRICT. "title" is required and present.
        Map<String, Object> input = new HashMap<>();
        input.put("title", "T");

        assertThatCode(() -> schemaValidator.validateSchema(WithOptionalParam.class, input))
            .doesNotThrowAnyException();
    }

    @Test
    void testValidateSchema_strictRejectsMissingRequiredEvenWithOptionalPresent() {
        // "title" (required) missing -> reject; "note" (Optional) absent is fine.
        Map<String, Object> input = new HashMap<>();

        assertThatThrownBy(() -> schemaValidator.validateSchema(WithOptionalParam.class, input))
            .isInstanceOf(SchemaValidationException.class)
            .satisfies(ex -> {
                SchemaValidationException validationException = (SchemaValidationException) ex;
                assertThat(validationException.getMissingFields()).containsExactly("title");
            });
    }

    @Test
    void testValidateSchema_lenientPassesThroughMissingRequired() {
        SchemaValidator lenient = new SchemaValidator(
            constructorMetadataCache, SchemaValidator.MissingFieldPolicy.LENIENT);
        Map<String, Object> input = new HashMap<>();
        input.put("name", "Alice");
        // age + active missing, but LENIENT only warns

        assertThatCode(() -> lenient.validateSchema(SampleClass.class, input))
            .doesNotThrowAnyException();
    }

    /**
     * 用于测试 Optional 构造器参数（必填字段过滤）的样例。
     */
    public static class WithOptionalParam {
        private final String title;
        private final java.util.Optional<String> note;

        public WithOptionalParam(String title, java.util.Optional<String> note) {
            this.title = title;
            this.note = note;
        }
    }

    /**
     * 用于测试的普通类。
     */
    public static class SampleClass {
        private final String name;
        private final int age;
        private final boolean active;

        public SampleClass(String name, int age, boolean active) {
            this.name = name;
            this.age = age;
            this.active = active;
        }
    }

    /**
     * 用于测试缺省值的普通类。
     */
    public static class RegularClass {
        private final String identifier;
        private final Integer score;

        public RegularClass(String identifier, Integer score) {
            this.identifier = identifier;
            this.score = score;
        }
    }

    /**
     * 用于测试记录类型的样例。
     */
    public record SampleRecord(String code, Integer level) {
    }
}
