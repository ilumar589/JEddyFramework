package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The {@code int} field holds an asset handle of the given kind ({@code "mesh"}, {@code "scene"},
 * {@code "sound"}, ...). The editor shows the asset browser and the loader resolves the path.
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.SOURCE)
public @interface AssetRef {
    String value();
}
