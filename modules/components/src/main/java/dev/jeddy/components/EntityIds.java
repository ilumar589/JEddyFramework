package dev.jeddy.components;

/**
 * The game's translation between entity handles and the stable ids a save stores (DESIGN §21,
 * D114). A handle (an index and a generation, D4) is valid for one run of the program; a save must
 * name an entity in a way the next run can resolve. An id is a {@code long}: a <em>kind</em> in the
 * top byte and a <em>value</em> in the low 56 bits. Kind {@link #OBJECT} is a level object, whose
 * value is its object id ({@code LevelLoader.placementOf} on save, {@code rootOf} on load); kinds
 * from {@link #FIRST_GAME_KIND} up are the game's own (a party member by index, a monster by its
 * spawn order, a projectile by a counter), because only the game knows what makes an entity "the
 * same" across runs.
 *
 * <p>{@link Store#write(dev.jeddy.core.io.ByteWriter, EntityIds)} asks {@link #idOf} for every row
 * and every {@code @EntityRef} column; {@link Store#read(dev.jeddy.core.io.ByteReader, EntityIds)}
 * asks {@link #entityOf} for each and drops a row whose id resolves to {@link #NONE}: a save from a
 * level that lost an object still loads. {@link #HANDLES} is the identity mapping for tests and
 * tools that save and load within one run.
 *
 * <p>Thread ownership: the game thread, during a capture or a load.
 */
public interface EntityIds {
    /** The id of nothing: a null handle on save, an unresolvable id on load. */
    long NONE = 0;

    /** Kind 1: a level object by its object id. */
    int OBJECT = 1;

    /** The first kind a game may define. */
    int FIRST_GAME_KIND = 2;

    /** Translates handles to themselves (as ids) and back: for saves read in the same run. */
    EntityIds HANDLES =
            new EntityIds() {
                @Override
                public long idOf(int entity) {
                    return entity == 0 ? NONE : entity & 0xFFFFFFFFL;
                }

                @Override
                public int entityOf(long id) {
                    return (int) id;
                }
            };

    /** Builds an id from a kind (1..255) and a value (0..2^56 − 1). */
    static long id(int kind, long value) {
        if (kind <= 0 || kind > 255) {
            throw new IllegalArgumentException("kind " + kind);
        }
        if (value < 0 || value >>> 56 != 0) {
            throw new IllegalArgumentException("value " + value);
        }
        return (long) kind << 56 | value;
    }

    /** The id of level object {@code objectId}. */
    static long object(int objectId) {
        return id(OBJECT, objectId);
    }

    /** The kind of {@code id} (0 for {@link #NONE}). */
    static int kind(long id) {
        return (int) (id >>> 56);
    }

    /** The value of {@code id}. */
    static long value(long id) {
        return id & 0x00FF_FFFF_FFFF_FFFFL;
    }

    /** The stable id of {@code entity}, or {@link #NONE} for an entity the save cannot name. */
    long idOf(int entity);

    /**
     * The live entity for {@code id}, created if the game wants it so, or {@code 0} when nothing
     * answers to it.
     */
    int entityOf(long id);
}
