package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a singleton parameter of a {@link Parallel} behaviour, or on one of the game's own singleton
 * types: the author asserts that what the behaviour does with it is safe from several job threads
 * at once (it only reads, or the type synchronizes). Engine types are never marked; the claim is
 * made at the parameter, where the use is.
 */
@Target({ElementType.PARAMETER, ElementType.TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface ThreadSafe {}
