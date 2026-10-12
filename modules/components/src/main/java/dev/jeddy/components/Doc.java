package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A one-line description shown by the editor next to the field. */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.SOURCE)
public @interface Doc {
    String value();
}
