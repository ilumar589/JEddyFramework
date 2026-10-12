package dev.jeddy.components.sample;

import dev.jeddy.components.Component;
import dev.jeddy.components.Default;
import dev.jeddy.components.Doc;
import dev.jeddy.components.Range;

/** Hit points. */
@Component(doc = "Hit points")
public record Health(
        @Default("10") @Range(min = 0, max = 100) @Doc("Current") int current,
        @Default("10") int max) {}
