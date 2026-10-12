package dev.jeddy.components.sample;

import dev.jeddy.components.AssetRef;
import dev.jeddy.components.Component;
import dev.jeddy.components.Default;
import dev.jeddy.components.EntityRef;

/** One of every other field type. */
@Component
public record Tagged(
        @Default("UNDEAD") Team team,
        @EntityRef int target,
        @AssetRef("mesh") int mesh,
        @Default("true") boolean hostile,
        @Default("1234567890123") long seen) {}
