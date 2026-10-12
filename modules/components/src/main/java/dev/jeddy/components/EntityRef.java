package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The {@code int} field holds an entity handle ({@code Handles.NULL} when none). The editor shows a
 * picker over the level's objects and the loader resolves the object id to the entity.
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.SOURCE)
public @interface EntityRef {}
