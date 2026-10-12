package dev.jeddy.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a view parameter of a behaviour: the entity need not have the component; {@code present()} on
 * the view says whether it does (the view is positioned at no row when it does not).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.SOURCE)
public @interface Optional {}
