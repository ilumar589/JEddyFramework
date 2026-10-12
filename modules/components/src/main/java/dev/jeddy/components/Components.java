package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a {@code package-info.java}: generates the game's registry class {@code name} in that package
 * (DESIGN §22, D108) with a field per store, the singletons as constructor parameters, and the
 * entry points {@code fixedUpdate}, {@code update}, {@code spawn}, {@code despawn}, {@code
 * trigger}, {@code dispatch}, {@code turn}, {@code signal}, {@code load} and the snapshot calls.
 * One registry per game; every {@link Component} and {@link Behaviour} the compilation sees belongs
 * to it.
 */
@Target(ElementType.PACKAGE)
@Retention(RetentionPolicy.SOURCE)
public @interface Components {
    String name();

    /** Initial rows per store (they grow past it, allocating). */
    int capacity() default 256;
}
