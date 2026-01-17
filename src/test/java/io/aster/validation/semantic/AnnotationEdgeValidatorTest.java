package io.aster.validation.semantic;

import io.aster.validation.constraints.NotEmpty;
import io.aster.validation.constraints.Pattern;
import io.aster.validation.constraints.Range;
import io.aster.validation.metadata.ConstructorMetadataCache;
import io.aster.validation.semantic.SemanticValidationException.ConstraintViolation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注解约束的边界测试用例。
 * 测试各种边界条件、特殊字符、极值等场景。
 */
class AnnotationEdgeValidatorTest {

    private SemanticValidator semanticValidator;
    private ConstructorMetadataCache constructorMetadataCache;

    @BeforeEach
    void setUp() {
        constructorMetadataCache = new ConstructorMetadataCache();
        semanticValidator = new SemanticValidator(constructorMetadataCache);
    }

    // =====================================================
    // @Range 边界测试
    // =====================================================

    @Test
    void testRange_exactMinValue() {
        RangeBoundaryHolder instance = new RangeBoundaryHolder(0);

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testRange_exactMaxValue() {
        RangeBoundaryHolder instance = new RangeBoundaryHolder(100);

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testRange_oneLessThanMin() {
        RangeBoundaryHolder instance = new RangeBoundaryHolder(-1);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .containsExactly("value");
            });
    }

    @Test
    void testRange_oneMoreThanMax() {
        RangeBoundaryHolder instance = new RangeBoundaryHolder(101);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .containsExactly("value");
            });
    }

    @Test
    void testRange_integerMaxValue() {
        LargeRangeHolder instance = new LargeRangeHolder(Integer.MAX_VALUE);

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testRange_integerMinValue() {
        LargeRangeHolder instance = new LargeRangeHolder(Integer.MIN_VALUE);

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testRange_negativeRange() {
        NegativeRangeHolder instance = new NegativeRangeHolder(-50);

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testRange_negativeRange_violation() {
        NegativeRangeHolder instance = new NegativeRangeHolder(-150);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .containsExactly("temperature");
            });
    }

    // =====================================================
    // @Range Double 精度边界测试
    // =====================================================

    @Test
    void testRangeDouble_exactMinValue() {
        DoubleRangeBoundaryHolder instance = new DoubleRangeBoundaryHolder(0.1);

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testRangeDouble_exactMaxValue() {
        DoubleRangeBoundaryHolder instance = new DoubleRangeBoundaryHolder(100.0);

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testRangeDouble_slightlyBelowMin() {
        DoubleRangeBoundaryHolder instance = new DoubleRangeBoundaryHolder(0.09999);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .containsExactly("percentage");
            });
    }

    @Test
    void testRangeDouble_slightlyAboveMax() {
        DoubleRangeBoundaryHolder instance = new DoubleRangeBoundaryHolder(100.00001);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .containsExactly("percentage");
            });
    }

    @Test
    void testRangeDouble_verySmallPrecision() {
        PrecisionRangeHolder instance = new PrecisionRangeHolder(0.0000001);

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testRangeDouble_zero() {
        DoubleRangeBoundaryHolder instance = new DoubleRangeBoundaryHolder(0.0);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class);
    }

    // =====================================================
    // @NotEmpty 边界测试
    // =====================================================

    @Test
    void testNotEmpty_whitespaceOnly() {
        WhitespaceHolder instance = new WhitespaceHolder("   ");

        // @NotEmpty 只检查 null 和空字符串，不检查空白字符
        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testNotEmpty_tab() {
        WhitespaceHolder instance = new WhitespaceHolder("\t");

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testNotEmpty_newline() {
        WhitespaceHolder instance = new WhitespaceHolder("\n");

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testNotEmpty_mixedWhitespace() {
        WhitespaceHolder instance = new WhitespaceHolder(" \t\n\r ");

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testNotEmpty_singleCharacter() {
        WhitespaceHolder instance = new WhitespaceHolder("a");

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testNotEmpty_unicodeEmoji() {
        WhitespaceHolder instance = new WhitespaceHolder("😀");

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testNotEmpty_chineseCharacters() {
        WhitespaceHolder instance = new WhitespaceHolder("中文");

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testNotEmpty_zeroWidthSpace() {
        // Unicode zero-width space (U+200B)
        WhitespaceHolder instance = new WhitespaceHolder("\u200B");

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    // =====================================================
    // @Pattern 边界测试
    // =====================================================

    @Test
    void testPattern_exactMatch() {
        PatternHolder instance = new PatternHolder("home");

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testPattern_caseSensitive() {
        PatternHolder instance = new PatternHolder("HOME");

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .containsExactly("category");
            });
    }

    @Test
    void testPattern_partialMatch() {
        PatternHolder instance = new PatternHolder("home123");

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class);
    }

    @Test
    void testPattern_emptyString() {
        PatternHolder instance = new PatternHolder("");

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class);
    }

    @Test
    void testPattern_complexRegex_email() {
        EmailHolder instance = new EmailHolder("user@example.com");

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testPattern_complexRegex_invalidEmail() {
        EmailHolder instance = new EmailHolder("invalid-email");

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class);
    }

    @Test
    void testPattern_unicodePattern() {
        UnicodePatternHolder instance = new UnicodePatternHolder("你好世界");

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testPattern_unicodePattern_invalid() {
        UnicodePatternHolder instance = new UnicodePatternHolder("Hello");

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class);
    }

    @Test
    void testPattern_specialCharacters() {
        SpecialCharPatternHolder instance = new SpecialCharPatternHolder("test-value_123");

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testPattern_specialCharacters_invalid() {
        SpecialCharPatternHolder instance = new SpecialCharPatternHolder("test@value");

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class);
    }

    // =====================================================
    // 多重约束组合测试
    // =====================================================

    @Test
    void testMultipleConstraints_allSatisfied() {
        MultiConstraintHolder instance = new MultiConstraintHolder("home", 50);

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testMultipleConstraints_notEmptyViolation() {
        MultiConstraintHolder instance = new MultiConstraintHolder("", 50);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .contains("category");
            });
    }

    @Test
    void testMultipleConstraints_patternViolation() {
        MultiConstraintHolder instance = new MultiConstraintHolder("invalid", 50);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .contains("category");
            });
    }

    @Test
    void testMultipleConstraints_rangeViolation() {
        MultiConstraintHolder instance = new MultiConstraintHolder("home", 150);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .contains("score");
            });
    }

    @Test
    void testMultipleConstraints_allViolations() {
        MultiConstraintHolder instance = new MultiConstraintHolder(null, 150);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .contains("category", "score");
            });
    }

    @Test
    void testMultipleFields_multipleConstraints() {
        ComplexHolder instance = new ComplexHolder("user-123", "user@example.com", 25, 75.5);

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testMultipleFields_mixedViolations() {
        ComplexHolder instance = new ComplexHolder("", "invalid-email", 150, 120.0);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .contains("userId", "email", "age", "score");
            });
    }

    // =====================================================
    // Null 值处理测试
    // =====================================================

    @Test
    void testNullHandling_rangeSkipsNull() {
        NullableRangeHolder instance = new NullableRangeHolder(null);

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testNullHandling_notEmptyFailsOnNull() {
        NullableNotEmptyHolder instance = new NullableNotEmptyHolder(null);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class);
    }

    @Test
    void testNullHandling_patternSkipsNull() {
        NullablePatternHolder instance = new NullablePatternHolder(null);

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    // =====================================================
    // 继承场景边界测试
    // =====================================================

    @Test
    void testInheritance_parentConstraintViolation() {
        ChildClass instance = new ChildClass(-10, "valid");

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .contains("parentScore");
            });
    }

    @Test
    void testInheritance_childConstraintViolation() {
        ChildClass instance = new ChildClass(50, "");

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .contains("childName");
            });
    }

    @Test
    void testInheritance_bothConstraintsViolation() {
        ChildClass instance = new ChildClass(-10, null);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .contains("parentScore", "childName");
            });
    }

    // =====================================================
    // 测试数据类型定义
    // =====================================================

    public static class RangeBoundaryHolder {
        @Range(min = 0, max = 100)
        private final Integer value;

        public RangeBoundaryHolder(Integer value) {
            this.value = value;
        }
    }

    public static class LargeRangeHolder {
        @Range(min = Integer.MIN_VALUE, max = Integer.MAX_VALUE)
        private final Integer value;

        public LargeRangeHolder(Integer value) {
            this.value = value;
        }
    }

    public static class NegativeRangeHolder {
        @Range(min = -100, max = -10)
        private final Integer temperature;

        public NegativeRangeHolder(Integer temperature) {
            this.temperature = temperature;
        }
    }

    public static class DoubleRangeBoundaryHolder {
        @Range(minDouble = 0.1, maxDouble = 100.0)
        private final Double percentage;

        public DoubleRangeBoundaryHolder(Double percentage) {
            this.percentage = percentage;
        }
    }

    public static class PrecisionRangeHolder {
        @Range(minDouble = 0.0000001, maxDouble = 0.9999999)
        private final Double value;

        public PrecisionRangeHolder(Double value) {
            this.value = value;
        }
    }

    public static class WhitespaceHolder {
        @NotEmpty
        private final String text;

        public WhitespaceHolder(String text) {
            this.text = text;
        }
    }

    public static class PatternHolder {
        @Pattern(regexp = "^(home|car|education|business)$")
        private final String category;

        public PatternHolder(String category) {
            this.category = category;
        }
    }

    public static class EmailHolder {
        @Pattern(regexp = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        private final String email;

        public EmailHolder(String email) {
            this.email = email;
        }
    }

    public static class UnicodePatternHolder {
        @Pattern(regexp = "^[\u4e00-\u9fa5]+$")
        private final String chineseText;

        public UnicodePatternHolder(String chineseText) {
            this.chineseText = chineseText;
        }
    }

    public static class SpecialCharPatternHolder {
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$")
        private final String identifier;

        public SpecialCharPatternHolder(String identifier) {
            this.identifier = identifier;
        }
    }

    public static class MultiConstraintHolder {
        @NotEmpty
        @Pattern(regexp = "^(home|car|education|business)$")
        private final String category;

        @Range(min = 0, max = 100)
        private final Integer score;

        public MultiConstraintHolder(String category, Integer score) {
            this.category = category;
            this.score = score;
        }
    }

    public static class ComplexHolder {
        @NotEmpty
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$")
        private final String userId;

        @NotEmpty
        @Pattern(regexp = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        private final String email;

        @Range(min = 18, max = 120)
        private final Integer age;

        @Range(minDouble = 0.0, maxDouble = 100.0)
        private final Double score;

        public ComplexHolder(String userId, String email, Integer age, Double score) {
            this.userId = userId;
            this.email = email;
            this.age = age;
            this.score = score;
        }
    }

    public static class NullableRangeHolder {
        @Range(min = 0, max = 100)
        private final Integer value;

        public NullableRangeHolder(Integer value) {
            this.value = value;
        }
    }

    public static class NullableNotEmptyHolder {
        @NotEmpty
        private final String value;

        public NullableNotEmptyHolder(String value) {
            this.value = value;
        }
    }

    public static class NullablePatternHolder {
        @Pattern(regexp = "^[a-z]+$")
        private final String value;

        public NullablePatternHolder(String value) {
            this.value = value;
        }
    }

    public static class ParentClass {
        @Range(min = 0, max = 100)
        private final Integer parentScore;

        public ParentClass(Integer parentScore) {
            this.parentScore = parentScore;
        }
    }

    public static class ChildClass extends ParentClass {
        @NotEmpty
        private final String childName;

        public ChildClass(Integer parentScore, String childName) {
            super(parentScore);
            this.childName = childName;
        }
    }
}
