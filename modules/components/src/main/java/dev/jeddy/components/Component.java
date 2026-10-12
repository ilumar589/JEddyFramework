package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code record} as a component (DESIGN §22, D105). The processor generates, in the same
 * package, {@code <Name>Store} (a sparse-set store with one array per field), {@code <Name>View} (a
 * read-only flyweight over one row) and {@code <Name>Mut} (the view with setters). The record is
 * the cold form: given to {@code add}, returned by {@code get}, edited in the editor.
 *
 * <p>Record components may be {@code int}, {@code long}, {@code float}, {@code boolean}, an {@code
 * enum}, {@code dev.jeddy.math.Vec2f} or {@code Vec3f}; an {@code int} may be an {@link EntityRef}
 * or an {@link AssetRef}. Defaults come from {@link Default}; {@link Range}, {@link Angle}, {@link
 * Color} and {@link Doc} are hints for the editor.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Component {
    String doc() default "";
}
