package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a final class whose {@code public static} methods are behaviours (DESIGN §22, D106): each
 * method carries one phase annotation ({@link OnSpawn}, {@link OnDespawn}, {@link OnFixedUpdate},
 * {@link OnUpdate}, {@link OnTrigger}, {@link OnTurn}, {@link OnEvent}); its component view
 * parameters are the query, {@code float dt}-style parameters are the phase's values, and any other
 * parameter is a singleton the generated registry takes in its constructor.
 *
 * <p>Within a phase, behaviours run in {@link #order()} then declaration order.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Behaviour {
    int order() default 0;
}
