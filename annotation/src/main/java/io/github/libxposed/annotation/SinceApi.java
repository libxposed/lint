package io.github.libxposed.annotation;

import androidx.annotation.IntRange;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Denotes the Xposed API version where the annotated API element was introduced.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({
        ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR,
        ElementType.FIELD,
        ElementType.METHOD,
        ElementType.PACKAGE,
        ElementType.TYPE
})
public @interface SinceApi {
    /**
     * The first Xposed API version that includes the annotated element.
     *
     * @return The first Xposed API version that includes the annotated element.
     */
    @IntRange(from = 101)
    int value();
}
