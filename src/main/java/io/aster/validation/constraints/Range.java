package io.aster.validation.constraints;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数值范围约束注解，支持整数与浮点类型。
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Range {

    long min() default Long.MIN_VALUE;

    long max() default Long.MAX_VALUE;

    double minDouble() default Double.MIN_VALUE;

    double maxDouble() default Double.MAX_VALUE;

    String message() default "值必须在 {min} 到 {max} 之间";
}
