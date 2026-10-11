package dev.jeddy.asset.format;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The schema of rules content (DESIGN §20, D97): the kinds of definition (conditions, ancestries,
 * classes, feats, spells, items, monsters and their nested tables), each a list of typed fields.
 * One description serves four readers: the cooker's {@code RulesImporter} validates JSON against it
 * and writes a {@code .jrules} in its column order, {@link RulesData} reads that file, the {@code
 * rules} engine resolves column indices by name once, and the editor's Rules panel generates its
 * property grid from it. Adding a field to the content means adding it here and nowhere else.
 *
 * <p>Field types: {@code INT}, {@code BOOL} and {@code ENUM} are one int; {@code FLAGS} is an int
 * bit set of its enum names; {@code DICE} is a packed dice expression ({@code
 * dev.jeddy.math.Dice}); {@code STRING} is a string reference; {@code REF} is the row index of
 * another kind (-1 for none, an id string in JSON); {@code REF_LIST} and {@code INT_LIST} are
 * ranges into the int pool; {@code OPS} is a range of effect operations; {@code NESTED} is a range
 * of rows of a nested kind whose rows appear as an array inside the parent object in JSON.
 *
 * <p>Thread ownership: immutable, any thread.
 */
public final class RulesSchema {
    private RulesSchema() {}

    /** Field types. */
    public enum Type {
        INT,
        BOOL,
        STRING,
        ENUM,
        FLAGS,
        DICE,
        REF,
        REF_LIST,
        INT_LIST,
        OPS,
        NESTED
    }

    /** One field of a kind. */
    public record Field(
            String name,
            Type type,
            String[] names,
            String refKind,
            int defaultInt,
            String defaultString,
            String doc) {
        /** Returns how many ints the field takes in a row (2 for strings and ranges). */
        public int width() {
            return switch (type) {
                case STRING, REF_LIST, INT_LIST, OPS, NESTED -> 2;
                default -> 1;
            };
        }

        /** Returns the index of {@code name} among the enum names, or -1. */
        public int enumIndex(String value) {
            if (names == null) {
                return -1;
            }
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(value)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /** One kind of definition: a table. */
    public static final class Kind {
        private final String name;
        private final String parent;
        private final String parentField;
        private final List<Field> fields = new ArrayList<>();
        private int index;
        private int[] offsets;
        private int stride;
        private String doc;

        private Kind(String name, String parent, String parentField, String doc) {
            this.name = name;
            this.parent = parent;
            this.parentField = parentField;
            this.doc = doc;
        }

        /** The kind's name, also its JSON file ({@code rules/<name>.json}) for top-level kinds. */
        public String name() {
            return name;
        }

        /** The kind this one nests in, or {@code null} for a top-level kind. */
        public String parent() {
            return parent;
        }

        /** The parent's field holding this kind's rows, for nested kinds. */
        public String parentField() {
            return parentField;
        }

        /** Returns whether rows are keyed by an {@code id} (top-level kinds). */
        public boolean hasId() {
            return parent == null;
        }

        /** A one-line description. */
        public String doc() {
            return doc;
        }

        /** The kind's index in {@link #KINDS}. */
        public int index() {
            return index;
        }

        /** The fields in column order. */
        public List<Field> fields() {
            return Collections.unmodifiableList(fields);
        }

        /** Returns the index of field {@code name}, or -1. */
        public int field(String fieldName) {
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i).name().equals(fieldName)) {
                    return i;
                }
            }
            return -1;
        }

        /** Returns the index of field {@code name}; throws when absent. */
        public int require(String fieldName) {
            int i = field(fieldName);
            if (i < 0) {
                throw new IllegalArgumentException(name + " has no field " + fieldName);
            }
            return i;
        }

        /** The int offset of field {@code i} in a row. */
        public int offset(int i) {
            return offsets[i];
        }

        /** Ints per row. */
        public int stride() {
            return stride;
        }

        private Kind add(Field f) {
            fields.add(f);
            return this;
        }

        private Kind str(String n, String doc) {
            return add(new Field(n, Type.STRING, null, null, 0, "", doc));
        }

        private Kind integer(String n, int def, String doc) {
            return add(new Field(n, Type.INT, null, null, def, null, doc));
        }

        private Kind bool(String n, boolean def, String doc) {
            return add(new Field(n, Type.BOOL, null, null, def ? 1 : 0, null, doc));
        }

        private Kind enumeration(String n, String[] names, int def, String doc) {
            return add(new Field(n, Type.ENUM, names, null, def, null, doc));
        }

        private Kind flags(String n, String[] names, String doc) {
            return add(new Field(n, Type.FLAGS, names, null, 0, null, doc));
        }

        private Kind dice(String n, String doc) {
            return add(new Field(n, Type.DICE, null, null, 0, null, doc));
        }

        private Kind ref(String n, String kind, String doc) {
            return add(new Field(n, Type.REF, null, kind, -1, null, doc));
        }

        private Kind refList(String n, String kind, String doc) {
            return add(new Field(n, Type.REF_LIST, null, kind, 0, null, doc));
        }

        private Kind intList(String n, String doc) {
            return add(new Field(n, Type.INT_LIST, null, null, 0, null, doc));
        }

        private Kind ops(String n, String doc) {
            return add(new Field(n, Type.OPS, null, null, 0, null, doc));
        }

        private Kind nested(String n, String kind, String doc) {
            return add(new Field(n, Type.NESTED, null, kind, 0, null, doc));
        }

        private void finish(int index) {
            this.index = index;
            offsets = new int[fields.size()];
            int o = 0;
            for (int i = 0; i < fields.size(); i++) {
                offsets[i] = o;
                o += fields.get(i).width();
            }
            stride = o;
        }
    }

    // ---- Enumerations shared by fields and the engine ------------------------------------------

    /** The six abilities, in the order every ability-indexed array uses. */
    public static final String[] ABILITIES = {"str", "dex", "con", "int", "wis", "cha"};

    /** Statistics a modifier can target (the engine's {@code Stat} mirrors this order). */
    public static final String[] STATS = {
        "str",
        "dex",
        "con",
        "int",
        "wis",
        "cha",
        "ac",
        "attack",
        "damage",
        "fortitude",
        "reflex",
        "will",
        "initiative",
        "speed",
        "maxHp",
        "aoo",
        "extraAttacks"
    };

    /** Bonus types; same-type bonuses do not stack except untyped, dodge and circumstance. */
    public static final String[] BONUS_TYPES = {
        "untyped", "armor", "shield", "natural", "deflection", "dodge", "enhancement", "morale",
        "competence", "luck", "size", "insight", "sacred", "profane", "circumstance", "racial",
        "alchemical", "penalty"
    };

    /** Effect operations (D98). */
    public static final String[] OPS = {
        "modify",
        "damage",
        "heal",
        "tempHp",
        "condition",
        "removeCondition",
        "grantFeat",
        "extraAttack",
        "setSpeed"
    };

    /** Damage types. */
    public static final String[] DAMAGE_TYPES = {
        "bludgeoning",
        "piercing",
        "slashing",
        "fire",
        "cold",
        "acid",
        "electricity",
        "force",
        "negative",
        "positive",
        "untyped"
    };

    /** Creature sizes. */
    public static final String[] SIZES = {"tiny", "small", "medium", "large", "huge"};

    /** Condition flags the engine knows (effects do the rest). */
    public static final String[] CONDITION_FLAGS = {
        "cannotAct", "flatFooted", "prone", "helpless", "loseDexToAc", "noMove", "dying", "dead"
    };

    /** Feat flags the engine knows. */
    public static final String[] FEAT_FLAGS = {"reflexes", "powerAttack", "pointBlank", "cleave"};

    /** Base attack bonus progressions. */
    public static final String[] BAB = {"full", "threeQuarters", "half"};

    /** Save progressions. */
    public static final String[] SAVE = {"good", "poor"};

    /** Casting kinds. */
    public static final String[] CASTING = {"none", "arcane", "divine"};

    /** Spell schools. */
    public static final String[] SCHOOLS = {
        "abjuration",
        "conjuration",
        "divination",
        "enchantment",
        "evocation",
        "illusion",
        "necromancy",
        "transmutation"
    };

    /** Action kinds. */
    public static final String[] ACTIONS = {"standard", "move", "swift", "fullRound", "free"};

    /** Area shapes. */
    public static final String[] SHAPES = {"none", "burst", "cone", "line"};

    /** Whom a spell affects. */
    public static final String[] TARGETS = {"self", "one", "all", "enemies", "allies"};

    /** Saving throws. */
    public static final String[] SAVES = {"none", "fortitude", "reflex", "will"};

    /** What a successful save does. */
    public static final String[] SAVE_RESULTS = {"negates", "half"};

    /** Attack rolls a spell needs. */
    public static final String[] SPELL_ATTACKS = {"none", "meleeTouch", "rangedTouch"};

    /** Item kinds. */
    public static final String[] ITEM_KINDS = {"weapon", "armor", "shield", "consumable", "misc"};

    // ---- The effect operation record (8 ints) ---------------------------------------------------

    /** Ints per effect operation. */
    public static final int OP_INTS = 8;

    public static final int OP_OP = 0;
    public static final int OP_STAT = 1;
    public static final int OP_TYPE = 2;
    public static final int OP_VALUE = 3;
    public static final int OP_DICE = 4;
    public static final int OP_ROUNDS = 5;
    public static final int OP_REF = 6; // kind << 24 | row, or -1
    public static final int OP_DAMAGE_TYPE = 7;

    /** The operation's own fields, for the importer and the editor (the same types as rows). */
    public static final List<Field> OP_FIELDS =
            List.of(
                    new Field("op", Type.ENUM, OPS, null, 0, null, "what the operation does"),
                    new Field(
                            "stat",
                            Type.ENUM,
                            STATS,
                            null,
                            6,
                            null,
                            "the statistic a modify targets"),
                    new Field(
                            "type",
                            Type.ENUM,
                            BONUS_TYPES,
                            null,
                            0,
                            null,
                            "the bonus type of a modify"),
                    new Field(
                            "value",
                            Type.INT,
                            null,
                            null,
                            0,
                            null,
                            "a modify's bonus, an extraAttack's count, a setSpeed's feet"),
                    new Field(
                            "dice", Type.DICE, null, null, 0, null, "damage, heal or tempHp dice"),
                    new Field(
                            "rounds",
                            Type.INT,
                            null,
                            null,
                            0,
                            null,
                            "duration in rounds; 0 lasts while the source applies"),
                    new Field("ref", Type.STRING, null, null, 0, "", "a condition or feat id"),
                    new Field(
                            "damageType",
                            Type.ENUM,
                            DAMAGE_TYPES,
                            null,
                            10,
                            null,
                            "the damage type of a damage operation"));

    // ---- Kinds ------------------------------------------------------------------------------

    /** Every kind in file order (nested kinds follow their parent). */
    public static final List<Kind> KINDS;

    public static final Kind CONDITIONS;
    public static final Kind ANCESTRIES;
    public static final Kind CLASSES;
    public static final Kind CLASS_SLOTS;
    public static final Kind CLASS_FEATURES;
    public static final Kind FEATS;
    public static final Kind SPELLS;
    public static final Kind ITEMS;
    public static final Kind MONSTERS;
    public static final Kind MONSTER_ATTACKS;
    public static final Kind CHARACTERS;

    static {
        List<Kind> kinds = new ArrayList<>();
        CONDITIONS =
                top(
                                kinds,
                                "conditions",
                                "A state a creature can be in, with its effects while it lasts")
                        .flags("flags", CONDITION_FLAGS, "what the engine does with it")
                        .ops("effects", "modifiers while the condition is on");
        ANCESTRIES =
                top(kinds, "ancestries", "A creature's lineage: size, speed, ability adjustments")
                        .enumeration("size", SIZES, 2, "size category")
                        .integer("speed", 30, "land speed in feet")
                        .integer("str", 0, "Strength adjustment")
                        .integer("dex", 0, "Dexterity adjustment")
                        .integer("con", 0, "Constitution adjustment")
                        .integer("int", 0, "Intelligence adjustment")
                        .integer("wis", 0, "Wisdom adjustment")
                        .integer("cha", 0, "Charisma adjustment")
                        .ops("effects", "always-on effects");
        CLASSES =
                top(
                                kinds,
                                "classes",
                                "A character class: hit die, progressions, casting, features by"
                                        + " level")
                        .integer("hitDie", 8, "hit points per level: the die's sides")
                        .enumeration("bab", BAB, 0, "base attack bonus progression")
                        .enumeration("fortitude", SAVE, 1, "Fortitude progression")
                        .enumeration("reflex", SAVE, 1, "Reflex progression")
                        .enumeration("will", SAVE, 1, "Will progression")
                        .enumeration("casting", CASTING, 0, "spell list the class casts from")
                        .enumeration(
                                "castingAbility",
                                ABILITIES,
                                3,
                                "ability behind the spells' DC and bonus slots")
                        .nested("slots", "classSlots", "spell slots per class level")
                        .nested("features", "classFeatures", "features gained at levels");
        CLASS_SLOTS =
                nested(kinds, "classSlots", "classes", "slots", "spell slots of one class level")
                        .integer("level", 1, "class level")
                        .intList("slots", "slots per spell level, from level 0 up");
        CLASS_FEATURES =
                nested(kinds, "classFeatures", "classes", "features", "a feature gained at a level")
                        .integer("level", 1, "class level it is gained at")
                        .str("name", "the feature's name")
                        .ops("effects", "what it does while the character has it");
        FEATS =
                top(kinds, "feats", "A feat: prerequisites and effects while known")
                        .integer("minBab", 0, "base attack bonus required")
                        .integer("minStr", 0, "Strength required")
                        .integer("minDex", 0, "Dexterity required")
                        .integer("minCon", 0, "Constitution required")
                        .integer("minInt", 0, "Intelligence required")
                        .integer("minWis", 0, "Wisdom required")
                        .integer("minCha", 0, "Charisma required")
                        .refList("requires", "feats", "feats required first")
                        .flags("flags", FEAT_FLAGS, "behaviors the engine implements")
                        .ops("effects", "modifiers while the feat is known");
        SPELLS =
                top(
                                kinds,
                                "spells",
                                "A spell: levels, action, range and area, duration, save, effects")
                        .integer("arcaneLevel", -1, "level on the arcane list, -1 if absent")
                        .integer("divineLevel", -1, "level on the divine list, -1 if absent")
                        .enumeration("school", SCHOOLS, 4, "school of magic")
                        .enumeration("action", ACTIONS, 0, "the action casting takes")
                        .integer("range", 0, "range in feet; 0 is self, 5 touch")
                        .enumeration("shape", SHAPES, 0, "area shape")
                        .integer("radius", 0, "area radius or length in feet")
                        .enumeration("targets", TARGETS, 1, "whom it affects")
                        .integer("rounds", 0, "duration in rounds; 0 is instantaneous")
                        .integer("roundsPerLevel", 0, "extra rounds per caster level")
                        .enumeration("save", SAVES, 0, "saving throw allowed")
                        .enumeration("saveResult", SAVE_RESULTS, 0, "what a successful save does")
                        .enumeration("attack", SPELL_ATTACKS, 0, "attack roll needed")
                        .ops("effects", "what it does to each target");
        ITEMS =
                top(kinds, "items", "Weapons, armor, shields, consumables")
                        .enumeration("kind", ITEM_KINDS, 0, "what the item is")
                        .dice("damage", "weapon damage")
                        .integer("threat", 20, "lowest natural roll that threatens a critical")
                        .integer("critical", 2, "critical multiplier")
                        .enumeration("damageType", DAMAGE_TYPES, 2, "weapon damage type")
                        .integer("range", 0, "range increment in feet; 0 for melee")
                        .integer("hands", 1, "hands a weapon needs")
                        .bool("finesse", false, "Dexterity may replace Strength on attack rolls")
                        .integer("armor", 0, "armor or shield bonus to AC")
                        .integer("maxDex", 99, "maximum Dexterity bonus to AC with the armor")
                        .integer("checkPenalty", 0, "armor check penalty")
                        .integer("value", 0, "price in gold")
                        .ops("effects", "modifiers while equipped")
                        .ops("use", "what a consumable does when used");
        MONSTERS =
                top(kinds, "monsters", "A creature stat block")
                        .ref("ancestry", "ancestries", "lineage, or none")
                        .enumeration("size", SIZES, 2, "size category")
                        .integer("level", 1, "hit dice / level")
                        .integer("str", 10, "Strength")
                        .integer("dex", 10, "Dexterity")
                        .integer("con", 10, "Constitution")
                        .integer("int", 10, "Intelligence")
                        .integer("wis", 10, "Wisdom")
                        .integer("cha", 10, "Charisma")
                        .integer("naturalArmor", 0, "natural armor bonus")
                        .integer("bab", 0, "base attack bonus")
                        .integer("fortitude", 0, "base Fortitude save")
                        .integer("reflex", 0, "base Reflex save")
                        .integer("will", 0, "base Will save")
                        .integer("hp", 10, "hit points")
                        .integer("speed", 30, "land speed in feet")
                        .refList("feats", "feats", "feats known")
                        .nested("attacks", "monsterAttacks", "natural and weapon attacks")
                        .ops("effects", "always-on effects");
        MONSTER_ATTACKS =
                nested(kinds, "monsterAttacks", "monsters", "attacks", "one attack of a monster")
                        .str("name", "the attack's name")
                        .integer("bonus", 0, "attack bonus")
                        .dice("damage", "damage dice")
                        .integer("threat", 20, "lowest natural roll that threatens")
                        .integer("critical", 2, "critical multiplier")
                        .enumeration("damageType", DAMAGE_TYPES, 2, "damage type")
                        .integer("reach", 5, "reach in feet");
        CHARACTERS =
                top(
                                kinds,
                                "characters",
                                "A player character template: lineage, class, level, scores, feats,"
                                        + " gear, spells")
                        .ref("ancestry", "ancestries", "lineage")
                        .ref("characterClass", "classes", "class")
                        .integer("level", 1, "class level")
                        .integer("str", 10, "Strength")
                        .integer("dex", 10, "Dexterity")
                        .integer("con", 10, "Constitution")
                        .integer("int", 10, "Intelligence")
                        .integer("wis", 10, "Wisdom")
                        .integer("cha", 10, "Charisma")
                        .refList("feats", "feats", "feats known")
                        .ref("weapon", "items", "the wielded weapon")
                        .ref("armor", "items", "the worn armor")
                        .ref("shield", "items", "the shield, if any")
                        .refList("spells", "spells", "spells known")
                        .ops("effects", "always-on effects");
        for (int i = 0; i < kinds.size(); i++) {
            kinds.get(i).finish(i);
        }
        KINDS = Collections.unmodifiableList(kinds);
    }

    private static Kind top(List<Kind> kinds, String name, String doc) {
        Kind k = new Kind(name, null, null, doc);
        k.str("id", "the key other definitions refer to")
                .str("name", "display name")
                .str("description", "flavor and rules text");
        kinds.add(k);
        return k;
    }

    private static Kind nested(
            List<Kind> kinds, String name, String parent, String field, String doc) {
        Kind k = new Kind(name, parent, field, doc);
        kinds.add(k);
        return k;
    }

    /** Returns the kind named {@code name}, or {@code null}. */
    public static Kind kind(String name) {
        for (Kind k : KINDS) {
            if (k.name().equals(name)) {
                return k;
            }
        }
        return null;
    }

    /** Returns the index of {@code name} in {@code names}, or -1. */
    public static int indexOf(String[] names, String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
