package dev.jeddy.components.sample;

import dev.jeddy.components.Angle;
import dev.jeddy.components.Component;
import dev.jeddy.components.Default;
import dev.jeddy.math.Vec3f;

/** Where an entity is. */
@Component
public record Position(@Default("1, 2, 3") Vec3f pos, @Angle float yaw) {}
