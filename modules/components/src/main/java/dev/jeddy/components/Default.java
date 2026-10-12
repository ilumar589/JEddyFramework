package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The default value of a record component as text, parsed by the processor for the field's type:
 * {@code "10"}, {@code "0.5"}, {@code "true"}, an enum constant's name, or {@code "1, 2, 3"} for a
 * vector. Without it a field defaults to zero, {@code false}, the first constant or the zero
 * vector. Also the value the editor shows for a new row.
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.SOURCE)
public @interface Default {
    String value();
}
