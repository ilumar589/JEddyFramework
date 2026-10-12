package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Phase: runs every frame for every matching entity, after interpolation; takes {@code float dt}
 * and {@code float alpha}. Stages as in {@link OnFixedUpdate}: {@code update(dt, alpha)} runs them
 * all, {@code updateStage(stage, dt, alpha)} one.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface OnUpdate {
    int stage() default 0;
}
