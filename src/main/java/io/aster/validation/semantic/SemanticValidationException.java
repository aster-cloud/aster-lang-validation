package io.aster.validation.semantic;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Layer 2 语义验证异常，携带全部违规明细。
 */
public class SemanticValidationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient List<ConstraintViolation> violations;

    public SemanticValidationException(List<ConstraintViolation> violations) {
        super(buildMessage(violations));
        this.violations = Collections.unmodifiableList(violations == null ? List.of() : List.copyOf(violations));
    }

    public List<ConstraintViolation> getViolations() {
        return violations;
    }

    private static String buildMessage(List<ConstraintViolation> violations) {
        if (violations == null || violations.isEmpty()) {
            return "语义验证失败：未提供违规详情";
        }
        StringJoiner joiner = new StringJoiner("\n", "语义验证失败：\n", "");
        for (ConstraintViolation violation : violations) {
            joiner.add("- " + violation.fieldName() + ": " + violation.message());
        }
        return joiner.toString();
    }

    /**
     * 单条语义约束违规记录。
     */
    public static final class ConstraintViolation implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String fieldName;
        private final transient Object actualValue;
        private final String constraintType;
        private final String message;

        public ConstraintViolation(String fieldName, Object actualValue, String constraintType, String message) {
            this.fieldName = Objects.requireNonNull(fieldName, "字段名不能为空");
            this.actualValue = actualValue;
            this.constraintType = Objects.requireNonNull(constraintType, "约束类型不能为空");
            this.message = Objects.requireNonNull(message, "违规消息不能为空");
        }

        public String fieldName() {
            return fieldName;
        }

        public Object actualValue() {
            return actualValue;
        }

        public String constraintType() {
            return constraintType;
        }

        public String message() {
            return message;
        }
    }
}
