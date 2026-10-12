package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Phase: runs when an encounter turn starts for the entity ({@code turn(entity)}). */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface OnTurn {}
