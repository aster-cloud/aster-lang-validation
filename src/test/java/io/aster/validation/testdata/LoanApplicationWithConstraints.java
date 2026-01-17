package io.aster.validation.testdata;

import io.aster.validation.constraints.NotEmpty;
import io.aster.validation.constraints.Pattern;
import io.aster.validation.constraints.Range;

/**
 * 带语义约束的贷款申请测试数据类型。
 */
public class LoanApplicationWithConstraints {

    @NotEmpty
    private final String applicantId;

    @Range(min = 1000, max = 10000000)
    private final Integer amount;

    @Range(min = 6, max = 360)
    private final Integer termMonths;

    @NotEmpty
    @Pattern(regexp = "^(home|car|education|business)$")
    private final String purpose;

    public LoanApplicationWithConstraints(String applicantId, Integer amount, Integer termMonths, String purpose) {
        this.applicantId = applicantId;
        this.amount = amount;
        this.termMonths = termMonths;
        this.purpose = purpose;
    }

    public String getApplicantId() {
        return applicantId;
    }

    public Integer getAmount() {
        return amount;
    }

    public Integer getTermMonths() {
        return termMonths;
    }

    public String getPurpose() {
        return purpose;
    }
}
