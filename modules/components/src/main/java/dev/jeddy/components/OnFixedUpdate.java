package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Phase: runs every simulation tick for every matching entity; takes {@code float dt}. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface OnFixedUpdate {}
