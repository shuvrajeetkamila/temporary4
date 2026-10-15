package org.junit.jupiter.params;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) public @interface ParameterizedTest { String name() default ""; }
