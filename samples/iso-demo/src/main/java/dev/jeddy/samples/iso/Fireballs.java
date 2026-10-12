package dev.jeddy.samples.iso;

import dev.jeddy.app.world.World;
import dev.jeddy.components.Behaviour;
import dev.jeddy.components.OnFixedUpdate;

/**
 * The fireballs' behaviour (DESIGN §22): a fireball flies for {@link Spells#TRAVEL} seconds,
 * explodes once (the overlap query and the rules' saves happen in {@link Spells#explode}), and is
 * despawned when the explosion has faded. Stage 2: after the crowd and the collision world moved,
 * so the explosion catches the bodies where they are now.
 *
 * <p>Thread ownership: the game thread.
 */
@Behaviour
public final class Fireballs {
    private Fireballs() {}

    @OnFixedUpdate(stage = 2)
    public static void fly(
            FireballMut f, float dt, int entity, Spells spells, CryptGame game, World world) {
        f.setAge(f.age() + dt);
        if (!f.exploded() && f.age() >= Spells.TRAVEL) {
            f.setExploded(true);
            f.setBurstPending(true);
            spells.explode(f);
        }
        if (f.age() >= Spells.TRAVEL + Spells.EXPLOSION) {
            game.despawn(entity); // removing the visited row is safe: the loop walks backwards
            world.entities().destroy(entity);
        }
    }
}
