package io.aster.validation.semantic;

import io.aster.validation.constraints.NotEmpty;
import io.aster.validation.constraints.Pattern;
import io.aster.validation.constraints.Range;
import io.aster.validation.metadata.ConstructorMetadataCache;
import io.aster.validation.semantic.SemanticValidationException.ConstraintViolation;
import io.aster.validation.testdata.LoanApplicationWithConstraints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class  SemanticValidatorTest {

    private SemanticValidator semanticValidator;
    private ConstructorMetadataCache constructorMetadataCache;

    @BeforeEach
    void setUp() {
        constructorMetadataCache = new ConstructorMetadataCache();
        semanticValidator = new SemanticValidator(constructorMetadataCache);
    }

    @Test
    void testValidateSemantics_allConstraintsSatisfied() {
        LoanApplicationWithConstraints instance = new LoanApplicationWithConstraints(
            "app-1",
            10_000,
            24,
            "home"
        );

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testValidateSemantics_rangeViolation_tooLow() {
        LoanApplicationWithConstraints instance = new LoanApplicationWithConstraints(
            "app-1",
            500,
            24,
            "home"
        );

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .contains("amount");
            });
    }

    @Test
    void testValidateSemantics_rangeViolation_tooHigh() {
        LoanApplicationWithConstraints instance = new LoanApplicationWithConstraints(
            "app-1",
            99_000_000,
            24,
            "home"
        );

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .contains("amount");
            });
    }

    @Test
    void testValidateSemantics_notEmptyViolation_null() {
        NotEmptyHolder instance = new NotEmptyHolder(null);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> assertThat(((SemanticValidationException) ex).getViolations())
                .extracting(ConstraintViolation::fieldName)
                .containsExactly("value"));
    }

    @Test
    void testValidateSemantics_notEmptyViolation_emptyString() {
        NotEmptyHolder instance = new NotEmptyHolder("");

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
    void testValidateSemantics_patternViolation() {
        LoanApplicationWithConstraints instance = new LoanApplicationWithConstraints(
            "app-1",
            10_000,
            24,
            "travel"
        );

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .contains("purpose");
            });
    }

    @Test
    void testValidateSemantics_multipleViolations() {
        LoanApplicationWithConstraints instance = new LoanApplicationWithConstraints(
            null,
            200,
            400,
            "invalid"
        );

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .contains("applicantId", "amount", "termMonths", "purpose");
            });
    }

    @Test
    void testValidateSemantics_nullValueSkipped() {
        RangeHolder instance = new RangeHolder(null);

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testValidateSemantics_inheritedFields() {
        SubClass instance = new SubClass(-10, "valid");

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
    void testValidateSemantics_noConstraints() {
        PlainType instance = new PlainType("anything");

        assertThatCode(() -> semanticValidator.validateSemantics(instance))
            .doesNotThrowAnyException();
    }

    @Test
    void testValidateSemantics_doubleRange() {
        DoubleRangeHolder instance = new DoubleRangeHolder(55.5);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> {
                SemanticValidationException exception = (SemanticValidationException) ex;
                assertThat(exception.getViolations())
                    .extracting(ConstraintViolation::fieldName)
                    .containsExactly("ratio");
            });
    }

    @Test
    void testValidateSemantics_floatRangeDefaults_ordinaryValuesPass() {
        // With the buggy default (Double.MIN_VALUE, ~4.9e-324), these ordinary
        // values would all be flagged as below the minimum. With the fixed
        // default (-Double.MAX_VALUE) they must pass.
        assertThatCode(() -> semanticValidator.validateSemantics(new DefaultRangeHolder(0.0, BigDecimal.ZERO)))
            .doesNotThrowAnyException();
        assertThatCode(() -> semanticValidator.validateSemantics(new DefaultRangeHolder(-5.0, new BigDecimal("-5.0"))))
            .doesNotThrowAnyException();
        assertThatCode(() -> semanticValidator.validateSemantics(new DefaultRangeHolder(3.14, new BigDecimal("3.14"))))
            .doesNotThrowAnyException();
    }

    @Test
    void testValidateSemantics_explicitFloatRange_outOfRangeFails() {
        ExplicitDoubleRangeHolder instance = new ExplicitDoubleRangeHolder(150.0);

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .satisfies(ex -> assertThat(((SemanticValidationException) ex).getViolations())
                .extracting(ConstraintViolation::fieldName)
                .contains("value"));
    }

    @Test
    void testValidateSemantics_exceptionMessage() {
        LoanApplicationWithConstraints instance = new LoanApplicationWithConstraints(
            "app-1",
            100,
            24,
            "travel"
        );

        assertThatThrownBy(() -> semanticValidator.validateSemantics(instance))
            .isInstanceOf(SemanticValidationException.class)
            .hasMessage("语义验证失败：\n- amount: 值 100 超出范围 [1000, 10000000]\n- purpose: 值不匹配模式 ^(home|car|education|business)$");
    }

    /**
     * 仅包含字符串非空约束的测试类型。
     */
    public static class NotEmptyHolder {
        @NotEmpty
        private final String value;

        public NotEmptyHolder(String value) {
            this.value = value;
        }
    }

    /**
     * 测试 @Range 在 null 值场景会跳过校验。
     */
    public static class RangeHolder {
        @Range(min = 10, max = 100)
        private final Integer value;

        public RangeHolder(Integer value) {
            this.value = value;
        }
    }

    /**
     * 父类包含约束，验证继承场景。
     */
    public static class BaseClass {
        @Range(min = 0, max = 100)
        private final Integer score;

        public BaseClass(Integer score) {
            this.score = score;
        }
    }

    /**
     * 子类扩展额外字段，验证继承校验。
     */
    public static class SubClass extends BaseClass {

        @NotEmpty
        private final String description;

        public SubClass(Integer score, String description) {
            super(score);
            this.description = description;
        }
    }

    /**
     * 不包含任何约束的类型。
     */
    public static class PlainType {
        private final String value;

        public PlainType(String value) {
            this.value = value;
        }
    }

    /**
     * Double 类型范围校验示例。
     */
    public static class DoubleRangeHolder {
        @Range(minDouble = 0.1, maxDouble = 50.0)
        private final Double ratio;

        public DoubleRangeHolder(Double ratio) {
            this.ratio = ratio;
        }
    }

    /**
     * 浮点类型 @Range 未覆盖 minDouble 的默认下界场景。
     * 普通数值（0、负数、正数）都应通过校验。
     */
    public static class DefaultRangeHolder {
        @Range(maxDouble = 1000.0)
        private final Double ratio;

        @Range(maxDouble = 1000.0)
        private final BigDecimal amount;

        public DefaultRangeHolder(Double ratio, BigDecimal amount) {
            this.ratio = ratio;
            this.amount = amount;
        }
    }

    /**
     * 显式设置浮点上下界，验证越界仍被拦截。
     */
    public static class ExplicitDoubleRangeHolder {
        @Range(minDouble = 0.0, maxDouble = 100.0)
        private final Double value;

        public ExplicitDoubleRangeHolder(Double value) {
            this.value = value;
        }
    }
}
