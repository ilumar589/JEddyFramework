package dev.jeddy.samples.iso;

import dev.jeddy.components.Component;
import dev.jeddy.components.Default;

/**
 * Whether the party can see a skeleton and whether its model is hidden (fog of war): the parallel
 * behaviour writes {@code visible}, the sequential one applies it to the world.
 */
@Component(doc = "Seen by the party, and hidden in the world")
public record Sight(@Default("true") boolean visible, boolean hidden) {}
