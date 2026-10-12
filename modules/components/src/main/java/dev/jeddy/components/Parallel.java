package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a behaviour method: the loop runs on the job system over ranges of {@code grain} rows, and
 * consecutive parallel behaviours of a phase run concurrently when their declared access allows
 * (DESIGN §22, D107). A parallel behaviour may not change structure (add or remove components,
 * spawn or despawn) and may only take singletons its parameters mark {@link ThreadSafe}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface Parallel {
    int grain() default 64;
}
