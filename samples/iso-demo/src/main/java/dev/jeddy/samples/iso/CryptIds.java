package dev.jeddy.samples.iso;

import dev.jeddy.app.world.LevelLoader;
import dev.jeddy.components.EntityIds;
import dev.jeddy.core.collections.IntIntMap;
import dev.jeddy.core.collections.LongIntMap;

/**
 * The crypt's stable entity ids (DESIGN §21, D114): what the registry's stores write in place of
 * entity handles and what a load resolves back. Four kinds: a level object by its object id ({@link
 * EntityIds#OBJECT}), a party member by index, a skeleton by its spawn order, and a fireball by a
 * counter that restarts at every capture (a fireball is the same fireball only within one save).
 * Party members and skeletons exist before a load (the level is rebuilt first), so their ids are
 * looked up; a fireball's id creates a fresh entity for the row.
 *
 * <p>Thread ownership: the game thread, during a capture or a load.
 */
final class CryptIds implements EntityIds {
    static final int PARTY = FIRST_GAME_KIND;
    static final int SKELETON = FIRST_GAME_KIND + 1;
    static final int FIREBALL = FIRST_GAME_KIND + 2;

    private final LevelLoader loader;
    private final Party party;
    private final Enemies enemies;
    private final Spells spells;
    private final IntIntMap skeletonOfRoot;
    private final IntIntMap fireballOfEntity = new IntIntMap(32, -1);
    private final LongIntMap entityOfFireball = new LongIntMap(32, -1);
    private int nextFireball;

    CryptIds(LevelLoader loader, Party party, Enemies enemies, Spells spells) {
        this.loader = loader;
        this.party = party;
        this.enemies = enemies;
        this.spells = spells;
        skeletonOfRoot = new IntIntMap(Math.max(16, enemies.count * 2), -1);
        for (int i = 0; i < enemies.count; i++) {
            skeletonOfRoot.put(enemies.entity(i), i);
        }
    }

    /** Starts a capture: fireballs are numbered afresh. */
    void beginCapture() {
        fireballOfEntity.clear();
        nextFireball = 0;
    }

    /** Starts a load: the fireball entities of the previous load are forgotten. */
    void beginLoad() {
        entityOfFireball.clear();
    }

    @Override
    public long idOf(int entity) {
        if (entity == 0) {
            return NONE;
        }
        for (int i = 0; i < Party.SIZE; i++) {
            if (party.root[i] == entity) {
                return EntityIds.id(PARTY, i);
            }
        }
        int skeleton = skeletonOfRoot.get(entity);
        if (skeleton >= 0) {
            return EntityIds.id(SKELETON, skeleton);
        }
        int object = loader.placementOf(entity);
        if (object >= 0) {
            return EntityIds.object(object);
        }
        int fireball = fireballOfEntity.get(entity);
        if (fireball < 0) {
            fireball = nextFireball++;
            fireballOfEntity.put(entity, fireball);
        }
        return EntityIds.id(FIREBALL, fireball);
    }

    @Override
    public int entityOf(long id) {
        int value = (int) EntityIds.value(id);
        return switch (EntityIds.kind(id)) {
            case OBJECT -> loader.rootOf(value);
            case PARTY -> value >= 0 && value < Party.SIZE ? party.root[value] : 0;
            case SKELETON -> value >= 0 && value < enemies.count ? enemies.entity(value) : 0;
            case FIREBALL -> {
                int e = entityOfFireball.get(id);
                if (e < 0) {
                    e = spells.spawnEntity();
                    entityOfFireball.put(id, e);
                }
                yield e;
            }
            default -> 0;
        };
    }
}
