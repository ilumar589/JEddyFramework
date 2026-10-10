package dev.jeddy.samples.iso;

import dev.jeddy.app.world.World;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.render.RenderPacket;

/**
 * The KayKit character models of the demo and how they are placed: animated scenes (skinned bodies,
 * weapons following their hands, TASKS M12-07), scaled to the crypt, with one weapon set shown out
 * of the alternatives each model carries.
 *
 * <p>Thread ownership: the game thread.
 */
final class Characters {
    static final String[] PARTY = {"knight", "barbarian", "mage", "rogue", "rogue_hooded", "mage"};
    static final String[] ENEMIES = {
        "skeleton_warrior", "skeleton_minion", "skeleton_mage", "skeleton_rogue"
    };

    /** The models are about 2.3 m tall; the crypt's 4 m tiles read better with 1.85 m people. */
    static final float SCALE = 0.8f;

    static final int FLAGS = RenderPacket.FLAG_CASTS_SHADOW;

    /** Yaw that turns a model (facing +Z) toward +X. */
    static final float FACE_PLUS_X = (float) (Math.PI / 2);

    private static final World.NodeFlags EQUIPMENT = Characters::equipment;

    private Characters() {}

    static String path(String model) {
        return "characters/" + model + ".jscene";
    }

    /** The rig's attack per party model (see {@link #PARTY}). */
    static final int[] PARTY_ATTACK = {
        Animator.ATTACK_1H,
        Animator.ATTACK_1H,
        Animator.CAST,
        Animator.ATTACK_DUAL,
        Animator.ATTACK_DUAL,
        Animator.CAST
    };

    /** The rig's attack per enemy model (see {@link #ENEMIES}). */
    static final int[] ENEMY_ATTACK = {
        Animator.ATTACK_1H, Animator.ATTACK_1H, Animator.CAST, Animator.ATTACK_1H
    };

    /**
     * Places an animated character facing {@code yaw}; returns its root entity (the group to hide,
     * and the key of its animation instance).
     */
    static int spawn(World world, SceneData scene, float x, float y, float z, float yaw) {
        int root = world.instantiateAnimated(scene, x, y, z, yaw, FLAGS, EQUIPMENT);
        world.transforms().setScale(root, SCALE, SCALE, SCALE);
        return root;
    }

    /** Hides the alternative weapons, shields and props of the KayKit adventurers. */
    private static int equipment(String name, int flags) {
        if (name.contains("Offhand") && !name.startsWith("Knife")) {
            return -1;
        }
        if (name.startsWith("2H_") && !name.equals("2H_Staff")) {
            return -1;
        }
        return switch (name) {
            case "Badge_Shield",
                    "Rectangle_Shield",
                    "Spike_Shield",
                    "Mug",
                    "Spellbook",
                    "Spellbook_open",
                    "1H_Wand",
                    "1H_Crossbow",
                    "Throwable" ->
                    -1;
            default -> flags;
        };
    }
}
