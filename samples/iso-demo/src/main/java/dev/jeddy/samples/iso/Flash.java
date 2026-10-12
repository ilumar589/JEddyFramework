package dev.jeddy.samples.iso;

import dev.jeddy.components.Component;

/**
 * The hostile outline of a skeleton: {@code flash} counts down after a hit, {@code style} is the
 * highlight the world was last told.
 */
@Component(doc = "The hit flash and the outline style")
public record Flash(float flash, int style) {}
