package dev.jeddy.samples.iso;

import dev.jeddy.components.Component;
import dev.jeddy.components.Doc;
import dev.jeddy.math.Vec3f;

/**
 * A skeleton's AI state (DESIGN §22): what it does, its timers and where it came from.
 *
 * @param state idle at its post, chasing, striking, or dead
 * @param timer seconds until the next decision (idle), the next path request (chase) or the respawn
 *     (dead)
 * @param cooldown seconds until it may swing again
 * @param corpse seconds the body stays visible after death
 * @param home its post: where it idles and returns to when it gives up
 */
@Component(doc = "A skeleton's state machine and timers")
public record Mind(
        MonsterState state,
        @Doc("Seconds to the next decision, path request or respawn") float timer,
        float cooldown,
        float corpse,
        Vec3f home) {}
