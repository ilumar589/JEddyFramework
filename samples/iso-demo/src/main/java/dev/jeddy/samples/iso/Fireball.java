package dev.jeddy.samples.iso;

import dev.jeddy.components.Component;
import dev.jeddy.components.Doc;
import dev.jeddy.math.Vec3f;

/**
 * A fireball in flight or exploding (DESIGN §22): one entity per cast, despawned when the explosion
 * has faded.
 *
 * @param from where it was thrown from (the caster's hand)
 * @param to where it lands
 * @param age seconds since the cast
 * @param exploded whether it has landed
 * @param burstPending whether the burst particles are still to be emitted (one frame)
 * @param caster the creature whose spell it is
 */
@Component(doc = "A fireball: flight, explosion, scorch")
public record Fireball(
        Vec3f from,
        Vec3f to,
        float age,
        boolean exploded,
        boolean burstPending,
        @Doc("The caster's creature") int caster) {}
