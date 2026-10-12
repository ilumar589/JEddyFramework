package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Phase: runs once for an entity when {@code despawn(entity)} is called, before its rows go. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface OnDespawn {}
