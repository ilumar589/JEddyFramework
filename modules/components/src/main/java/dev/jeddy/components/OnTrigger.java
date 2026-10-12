package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Phase: runs when a physics trigger event of the given kind ({@link #ENTER} or {@link #EXIT})
 * names the entity; takes {@code int trigger} (the trigger id) and {@code int kind}. The values
 * equal {@code physics.trigger.Triggers.ENTER} and {@code EXIT}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface OnTrigger {
    int ENTER = 1;

    int EXIT = 2;

    int value();
}
