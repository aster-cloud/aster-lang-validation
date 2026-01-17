package io.aster.validation.schema;

import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/**
 * Schema 验证异常，用于标记输入数据与目标类型字段不匹配的情况。
 */
public class SchemaValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> unknownFields;
    private final transient List<String> missingFields;

    public SchemaValidationException(List<String> unknownFields, List<String> missingFields) {
        super(buildMessage(unknownFields, missingFields));
        this.unknownFields = unknownFields == null ? Collections.emptyList() : List.copyOf(unknownFields);
        this.missingFields = missingFields == null ? Collections.emptyList() : List.copyOf(missingFields);
    }

    public List<String> getUnknownFields() {
        return unknownFields;
    }

    public List<String> getMissingFields() {
        return missingFields;
    }

    private static String buildMessage(List<String> unknown, List<String> missing) {
        StringJoiner joiner = new StringJoiner("，", "Schema 验证失败：", "");

        if (unknown != null && !unknown.isEmpty()) {
            joiner.add("未知字段 " + unknown);
        }
        if (missing != null && !missing.isEmpty()) {
            joiner.add("缺失字段 " + missing);
        }

        String message = joiner.toString();
        return message.endsWith("：") ? "Schema 验证失败" : message;
    }
}
