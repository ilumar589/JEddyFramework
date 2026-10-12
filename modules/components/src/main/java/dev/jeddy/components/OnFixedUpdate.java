package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Phase: runs every simulation tick for every matching entity; takes {@code float dt}. {@code
 * fixedUpdate(dt)} on the registry runs every stage in ascending order; a game that interleaves the
 * behaviours with systems of its own (the crowd, the fog of war) runs one stage at a time with
 * {@code fixedUpdateStage(stage, dt)}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface OnFixedUpdate {
    int stage() default 0;
}
