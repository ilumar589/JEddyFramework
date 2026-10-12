package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Phase: runs once for an entity when the registry's {@code spawn(entity)} is called. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface OnSpawn {}
