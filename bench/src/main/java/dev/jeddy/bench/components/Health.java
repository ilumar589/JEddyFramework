package dev.jeddy.bench.components;

import dev.jeddy.components.Component;

/** The benchmark's component: two ints, as a gameplay component usually is. */
@Component
public record Health(int current, int max) {}
