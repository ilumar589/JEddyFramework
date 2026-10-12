package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Phase: runs when the game raises a signal of the given kind for the entity ({@code signal(kind,
 * entity, a, b)}); takes {@code int a} and {@code int b}. Signals are how the rules, animation and
 * dialogue event rings reach behaviours: the game drains a ring and raises one signal per event it
 * cares about, with the kinds it defines as constants.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface OnEvent {
    int value();
}
