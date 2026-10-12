package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** On a behaviour method: entities that have any of these components are skipped. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface Without {
    Class<?>[] value();
}
