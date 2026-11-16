package dev.resilience;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Resilient {
    String value();

    RetrySafety safety() default RetrySafety.UNSAFE;
}
