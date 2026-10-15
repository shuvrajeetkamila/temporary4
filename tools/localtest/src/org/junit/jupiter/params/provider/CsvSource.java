package org.junit.jupiter.params.provider;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) public @interface CsvSource { String[] value(); }
