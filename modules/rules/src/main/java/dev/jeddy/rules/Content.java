package dev.jeddy.rules;

import dev.jeddy.asset.format.RulesData;
import dev.jeddy.asset.format.RulesSchema;
import dev.jeddy.asset.format.RulesSchema.Kind;

/**
 * Typed access to cooked rules content (DESIGN §20): resolves the field indices the engine needs
 * from {@link RulesSchema} by name once, so every accessor is an array read. Rows are the
 * definitions' indices in their kind ({@link #find}); the engine stores those rows in creatures.
 *
 * <p>Thread ownership: immutable; any thread.
 */
public final class Content {
    /** Condition flags, in the order of {@link RulesSchema#CONDITION_FLAGS}. */
    public static final int FLAG_CANNOT_ACT = 1;

    public static final int FLAG_FLAT_FOOTED = 2;
    public static final int FLAG_PRONE = 4;
    public static final int FLAG_HELPLESS = 8;
    public static final int FLAG_LOSE_DEX = 16;
    public static final int FLAG_NO_MOVE = 32;
    public static final int FLAG_DYING = 64;
    public static final int FLAG_DEAD = 128;

    /** Feat flags, in the order of {@link RulesSchema#FEAT_FLAGS}. */
    public static final int FEAT_REFLEXES = 1;

    public static final int FEAT_POWER_ATTACK = 2;
    public static final int FEAT_POINT_BLANK = 4;
    public static final int FEAT_CLEAVE = 8;

    /** Effect operations, in the order of {@link RulesSchema#OPS}. */
    public static final int OP_MODIFY = 0;

    public static final int OP_DAMAGE = 1;
    public static final int OP_HEAL = 2;
    public static final int OP_TEMP_HP = 3;
    public static final int OP_CONDITION = 4;
    public static final int OP_REMOVE_CONDITION = 5;
    public static final int OP_GRANT_FEAT = 6;
    public static final int OP_EXTRA_ATTACK = 7;
    public static final int OP_SET_SPEED = 8;

    /** Base attack bonus progressions, in the order of {@link RulesSchema#BAB}. */
    public static final int BAB_FULL = 0;

    public static final int BAB_THREE_QUARTERS = 1;
    public static final int BAB_HALF = 2;

    /** Save progressions. */
    public static final int SAVE_GOOD = 0;

    public static final int SAVE_POOR = 1;

    /** Casting kinds. */
    public static final int CASTING_NONE = 0;

    public static final int CASTING_ARCANE = 1;
    public static final int CASTING_DIVINE = 2;

    /** Spell saves, in the order of {@link RulesSchema#SAVES}: none, fortitude, reflex, will. */
    public static final int SPELL_SAVE_NONE = 0;

    /** Save results. */
    public static final int SAVE_NEGATES = 0;

    public static final int SAVE_HALF = 1;

    /** Spell attack rolls. */
    public static final int SPELL_ATTACK_NONE = 0;

    public static final int SPELL_ATTACK_MELEE_TOUCH = 1;
    public static final int SPELL_ATTACK_RANGED_TOUCH = 2;

    /** Spell target kinds, in the order of {@link RulesSchema#TARGETS}. */
    public static final int TARGETS_SELF = 0;

    public static final int TARGETS_ONE = 1;
    public static final int TARGETS_ALL = 2;
    public static final int TARGETS_ENEMIES = 3;
    public static final int TARGETS_ALLIES = 4;

    /** Item kinds. */
    public static final int ITEM_WEAPON = 0;

    public static final int ITEM_ARMOR = 1;
    public static final int ITEM_SHIELD = 2;
    public static final int ITEM_CONSUMABLE = 3;

    /** Sizes, in the order of {@link RulesSchema#SIZES}; the modifier is {@code 2 - size}... */
    public static final int SIZE_MEDIUM = 2;

    private final RulesData data;

    // Conditions
    private final int cFlags;
    private final int cEffects;
    // Ancestries
    private final int aSize;
    private final int aSpeed;
    private final int aAbility; // str; the six are consecutive
    private final int aEffects;
    // Classes
    private final int clHitDie;
    private final int clBab;
    private final int clSave; // fortitude; reflex and will follow
    private final int clCasting;
    private final int clCastingAbility;
    private final int clSlots;
    private final int clFeatures;
    private final int csLevel;
    private final int csSlots;
    private final int cfLevel;
    private final int cfName;
    private final int cfEffects;
    // Feats
    private final int fFlags;
    private final int fEffects;
    private final int fRequires;
    private final int fMinBab;
    private final int fMinAbility;
    // Spells
    private final int sArcane;
    private final int sDivine;
    private final int sAction;
    private final int sRange;
    private final int sShape;
    private final int sRadius;
    private final int sTargets;
    private final int sRounds;
    private final int sRoundsPerLevel;
    private final int sSave;
    private final int sSaveResult;
    private final int sAttack;
    private final int sEffects;
    // Items
    private final int iKind;
    private final int iDamage;
    private final int iThreat;
    private final int iCritical;
    private final int iDamageType;
    private final int iRange;
    private final int iHands;
    private final int iFinesse;
    private final int iArmor;
    private final int iMaxDex;
    private final int iCheckPenalty;
    private final int iEffects;
    private final int iUse;
    // Monsters
    private final int mAncestry;
    private final int mSize;
    private final int mLevel;
    private final int mAbility;
    private final int mNatural;
    private final int mBab;
    private final int mSave;
    private final int mHp;
    private final int mSpeed;
    private final int mFeats;
    private final int mAttacks;
    private final int mEffects;
    private final int maName;
    private final int maBonus;
    private final int maDamage;
    private final int maThreat;
    private final int maCritical;
    private final int maDamageType;
    private final int maReach;
    // Characters
    private final int chAncestry;
    private final int chClass;
    private final int chLevel;
    private final int chAbility;
    private final int chFeats;
    private final int chWeapon;
    private final int chArmor;
    private final int chShield;
    private final int chSpells;
    private final int chEffects;

    /** Wraps cooked content. */
    public Content(RulesData data) {
        this.data = data;
        Kind c = RulesSchema.CONDITIONS;
        cFlags = c.require("flags");
        cEffects = c.require("effects");
        Kind a = RulesSchema.ANCESTRIES;
        aSize = a.require("size");
        aSpeed = a.require("speed");
        aAbility = a.require("str");
        aEffects = a.require("effects");
        Kind cl = RulesSchema.CLASSES;
        clHitDie = cl.require("hitDie");
        clBab = cl.require("bab");
        clSave = cl.require("fortitude");
        clCasting = cl.require("casting");
        clCastingAbility = cl.require("castingAbility");
        clSlots = cl.require("slots");
        clFeatures = cl.require("features");
        csLevel = RulesSchema.CLASS_SLOTS.require("level");
        csSlots = RulesSchema.CLASS_SLOTS.require("slots");
        cfLevel = RulesSchema.CLASS_FEATURES.require("level");
        cfName = RulesSchema.CLASS_FEATURES.require("name");
        cfEffects = RulesSchema.CLASS_FEATURES.require("effects");
        Kind f = RulesSchema.FEATS;
        fFlags = f.require("flags");
        fEffects = f.require("effects");
        fRequires = f.require("requires");
        fMinBab = f.require("minBab");
        fMinAbility = f.require("minStr");
        Kind s = RulesSchema.SPELLS;
        sArcane = s.require("arcaneLevel");
        sDivine = s.require("divineLevel");
        sAction = s.require("action");
        sRange = s.require("range");
        sShape = s.require("shape");
        sRadius = s.require("radius");
        sTargets = s.require("targets");
        sRounds = s.require("rounds");
        sRoundsPerLevel = s.require("roundsPerLevel");
        sSave = s.require("save");
        sSaveResult = s.require("saveResult");
        sAttack = s.require("attack");
        sEffects = s.require("effects");
        Kind i = RulesSchema.ITEMS;
        iKind = i.require("kind");
        iDamage = i.require("damage");
        iThreat = i.require("threat");
        iCritical = i.require("critical");
        iDamageType = i.require("damageType");
        iRange = i.require("range");
        iHands = i.require("hands");
        iFinesse = i.require("finesse");
        iArmor = i.require("armor");
        iMaxDex = i.require("maxDex");
        iCheckPenalty = i.require("checkPenalty");
        iEffects = i.require("effects");
        iUse = i.require("use");
        Kind m = RulesSchema.MONSTERS;
        mAncestry = m.require("ancestry");
        mSize = m.require("size");
        mLevel = m.require("level");
        mAbility = m.require("str");
        mNatural = m.require("naturalArmor");
        mBab = m.require("bab");
        mSave = m.require("fortitude");
        mHp = m.require("hp");
        mSpeed = m.require("speed");
        mFeats = m.require("feats");
        mAttacks = m.require("attacks");
        mEffects = m.require("effects");
        Kind ma = RulesSchema.MONSTER_ATTACKS;
        maName = ma.require("name");
        maBonus = ma.require("bonus");
        maDamage = ma.require("damage");
        maThreat = ma.require("threat");
        maCritical = ma.require("critical");
        maDamageType = ma.require("damageType");
        maReach = ma.require("reach");
        Kind ch = RulesSchema.CHARACTERS;
        chAncestry = ch.require("ancestry");
        chClass = ch.require("characterClass");
        chLevel = ch.require("level");
        chAbility = ch.require("str");
        chFeats = ch.require("feats");
        chWeapon = ch.require("weapon");
        chArmor = ch.require("armor");
        chShield = ch.require("shield");
        chSpells = ch.require("spells");
        chEffects = ch.require("effects");
    }

    /** The underlying tables. */
    public RulesData data() {
        return data;
    }

    /** Returns the row of {@code id} in {@code kind}, or -1. */
    public int find(Kind kind, String id) {
        return data.find(kind, id);
    }

    /** Returns the id of row {@code row} of a top-level kind. */
    public String id(Kind kind, int row) {
        return data.getString(kind, row, 0);
    }

    /** Returns the display name of row {@code row} of a top-level kind. */
    public String name(Kind kind, int row) {
        return data.getString(kind, row, 1);
    }

    // ---- Conditions ---------------------------------------------------------------------------

    public int conditionCount() {
        return data.rows(RulesSchema.CONDITIONS);
    }

    public int conditionFlags(int row) {
        return data.getInt(RulesSchema.CONDITIONS, row, cFlags);
    }

    public int conditionEffectsStart(int row) {
        return data.start(RulesSchema.CONDITIONS, row, cEffects);
    }

    public int conditionEffectsCount(int row) {
        return data.count(RulesSchema.CONDITIONS, row, cEffects);
    }

    // ---- Ancestries ---------------------------------------------------------------------------

    public int ancestrySize(int row) {
        return data.getInt(RulesSchema.ANCESTRIES, row, aSize);
    }

    public int ancestrySpeed(int row) {
        return data.getInt(RulesSchema.ANCESTRIES, row, aSpeed);
    }

    /** Ability adjustment {@code ability} (0 str .. 5 cha). */
    public int ancestryAbility(int row, int ability) {
        return data.getInt(RulesSchema.ANCESTRIES, row, aAbility + ability);
    }

    public int ancestryEffectsStart(int row) {
        return data.start(RulesSchema.ANCESTRIES, row, aEffects);
    }

    public int ancestryEffectsCount(int row) {
        return data.count(RulesSchema.ANCESTRIES, row, aEffects);
    }

    // ---- Classes ------------------------------------------------------------------------------

    public int classHitDie(int row) {
        return data.getInt(RulesSchema.CLASSES, row, clHitDie);
    }

    public int classBab(int row) {
        return data.getInt(RulesSchema.CLASSES, row, clBab);
    }

    /** Save progression {@code save} (0 fortitude, 1 reflex, 2 will). */
    public int classSave(int row, int save) {
        return data.getInt(RulesSchema.CLASSES, row, clSave + save);
    }

    public int classCasting(int row) {
        return data.getInt(RulesSchema.CLASSES, row, clCasting);
    }

    public int classCastingAbility(int row) {
        return data.getInt(RulesSchema.CLASSES, row, clCastingAbility);
    }

    /** Returns the slots of spell level {@code spellLevel} at class level {@code level}, or 0. */
    public int classSlots(int row, int level, int spellLevel) {
        int start = data.start(RulesSchema.CLASSES, row, clSlots);
        int n = data.count(RulesSchema.CLASSES, row, clSlots);
        Kind k = RulesSchema.CLASS_SLOTS;
        for (int i = 0; i < n; i++) {
            if (data.getInt(k, start + i, csLevel) == level) {
                int ls = data.start(k, start + i, csSlots);
                int lc = data.count(k, start + i, csSlots);
                return spellLevel < lc ? data.pool(ls + spellLevel) : 0;
            }
        }
        return 0;
    }

    public int classFeaturesStart(int row) {
        return data.start(RulesSchema.CLASSES, row, clFeatures);
    }

    public int classFeaturesCount(int row) {
        return data.count(RulesSchema.CLASSES, row, clFeatures);
    }

    public int featureLevel(int row) {
        return data.getInt(RulesSchema.CLASS_FEATURES, row, cfLevel);
    }

    public String featureName(int row) {
        return data.getString(RulesSchema.CLASS_FEATURES, row, cfName);
    }

    public int featureEffectsStart(int row) {
        return data.start(RulesSchema.CLASS_FEATURES, row, cfEffects);
    }

    public int featureEffectsCount(int row) {
        return data.count(RulesSchema.CLASS_FEATURES, row, cfEffects);
    }

    // ---- Feats --------------------------------------------------------------------------------

    public int featCount() {
        return data.rows(RulesSchema.FEATS);
    }

    public int featFlags(int row) {
        return data.getInt(RulesSchema.FEATS, row, fFlags);
    }

    public int featEffectsStart(int row) {
        return data.start(RulesSchema.FEATS, row, fEffects);
    }

    public int featEffectsCount(int row) {
        return data.count(RulesSchema.FEATS, row, fEffects);
    }

    public int featRequiresStart(int row) {
        return data.start(RulesSchema.FEATS, row, fRequires);
    }

    public int featRequiresCount(int row) {
        return data.count(RulesSchema.FEATS, row, fRequires);
    }

    public int featMinBab(int row) {
        return data.getInt(RulesSchema.FEATS, row, fMinBab);
    }

    public int featMinAbility(int row, int ability) {
        return data.getInt(RulesSchema.FEATS, row, fMinAbility + ability);
    }

    // ---- Spells -------------------------------------------------------------------------------

    public int spellCount() {
        return data.rows(RulesSchema.SPELLS);
    }

    /** The spell's level on the {@code casting} list (arcane or divine), or -1. */
    public int spellLevel(int row, int casting) {
        return data.getInt(RulesSchema.SPELLS, row, casting == CASTING_DIVINE ? sDivine : sArcane);
    }

    public int spellAction(int row) {
        return data.getInt(RulesSchema.SPELLS, row, sAction);
    }

    public int spellRange(int row) {
        return data.getInt(RulesSchema.SPELLS, row, sRange);
    }

    public int spellShape(int row) {
        return data.getInt(RulesSchema.SPELLS, row, sShape);
    }

    public int spellRadius(int row) {
        return data.getInt(RulesSchema.SPELLS, row, sRadius);
    }

    public int spellTargets(int row) {
        return data.getInt(RulesSchema.SPELLS, row, sTargets);
    }

    /** The duration in rounds at {@code casterLevel} (0 for instantaneous). */
    public int spellRounds(int row, int casterLevel) {
        return data.getInt(RulesSchema.SPELLS, row, sRounds)
                + data.getInt(RulesSchema.SPELLS, row, sRoundsPerLevel) * casterLevel;
    }

    public int spellSave(int row) {
        return data.getInt(RulesSchema.SPELLS, row, sSave);
    }

    public int spellSaveResult(int row) {
        return data.getInt(RulesSchema.SPELLS, row, sSaveResult);
    }

    public int spellAttack(int row) {
        return data.getInt(RulesSchema.SPELLS, row, sAttack);
    }

    public int spellEffectsStart(int row) {
        return data.start(RulesSchema.SPELLS, row, sEffects);
    }

    public int spellEffectsCount(int row) {
        return data.count(RulesSchema.SPELLS, row, sEffects);
    }

    // ---- Items --------------------------------------------------------------------------------

    public int itemKind(int row) {
        return data.getInt(RulesSchema.ITEMS, row, iKind);
    }

    public int itemDamage(int row) {
        return data.getInt(RulesSchema.ITEMS, row, iDamage);
    }

    public int itemThreat(int row) {
        return data.getInt(RulesSchema.ITEMS, row, iThreat);
    }

    public int itemCritical(int row) {
        return data.getInt(RulesSchema.ITEMS, row, iCritical);
    }

    public int itemDamageType(int row) {
        return data.getInt(RulesSchema.ITEMS, row, iDamageType);
    }

    public int itemRange(int row) {
        return data.getInt(RulesSchema.ITEMS, row, iRange);
    }

    public int itemHands(int row) {
        return data.getInt(RulesSchema.ITEMS, row, iHands);
    }

    public boolean itemFinesse(int row) {
        return data.getBool(RulesSchema.ITEMS, row, iFinesse);
    }

    public int itemArmor(int row) {
        return data.getInt(RulesSchema.ITEMS, row, iArmor);
    }

    public int itemMaxDex(int row) {
        return data.getInt(RulesSchema.ITEMS, row, iMaxDex);
    }

    public int itemCheckPenalty(int row) {
        return data.getInt(RulesSchema.ITEMS, row, iCheckPenalty);
    }

    public int itemEffectsStart(int row) {
        return data.start(RulesSchema.ITEMS, row, iEffects);
    }

    public int itemEffectsCount(int row) {
        return data.count(RulesSchema.ITEMS, row, iEffects);
    }

    public int itemUseStart(int row) {
        return data.start(RulesSchema.ITEMS, row, iUse);
    }

    public int itemUseCount(int row) {
        return data.count(RulesSchema.ITEMS, row, iUse);
    }

    // ---- Monsters -----------------------------------------------------------------------------

    public int monsterAncestry(int row) {
        return data.getInt(RulesSchema.MONSTERS, row, mAncestry);
    }

    public int monsterSize(int row) {
        return data.getInt(RulesSchema.MONSTERS, row, mSize);
    }

    public int monsterLevel(int row) {
        return data.getInt(RulesSchema.MONSTERS, row, mLevel);
    }

    public int monsterAbility(int row, int ability) {
        return data.getInt(RulesSchema.MONSTERS, row, mAbility + ability);
    }

    public int monsterNaturalArmor(int row) {
        return data.getInt(RulesSchema.MONSTERS, row, mNatural);
    }

    public int monsterBab(int row) {
        return data.getInt(RulesSchema.MONSTERS, row, mBab);
    }

    public int monsterSave(int row, int save) {
        return data.getInt(RulesSchema.MONSTERS, row, mSave + save);
    }

    public int monsterHp(int row) {
        return data.getInt(RulesSchema.MONSTERS, row, mHp);
    }

    public int monsterSpeed(int row) {
        return data.getInt(RulesSchema.MONSTERS, row, mSpeed);
    }

    public int monsterFeatsStart(int row) {
        return data.start(RulesSchema.MONSTERS, row, mFeats);
    }

    public int monsterFeatsCount(int row) {
        return data.count(RulesSchema.MONSTERS, row, mFeats);
    }

    public int monsterAttacksStart(int row) {
        return data.start(RulesSchema.MONSTERS, row, mAttacks);
    }

    public int monsterAttacksCount(int row) {
        return data.count(RulesSchema.MONSTERS, row, mAttacks);
    }

    public int monsterEffectsStart(int row) {
        return data.start(RulesSchema.MONSTERS, row, mEffects);
    }

    public int monsterEffectsCount(int row) {
        return data.count(RulesSchema.MONSTERS, row, mEffects);
    }

    public String attackName(int row) {
        return data.getString(RulesSchema.MONSTER_ATTACKS, row, maName);
    }

    public int attackBonus(int row) {
        return data.getInt(RulesSchema.MONSTER_ATTACKS, row, maBonus);
    }

    public int attackDamage(int row) {
        return data.getInt(RulesSchema.MONSTER_ATTACKS, row, maDamage);
    }

    public int attackThreat(int row) {
        return data.getInt(RulesSchema.MONSTER_ATTACKS, row, maThreat);
    }

    public int attackCritical(int row) {
        return data.getInt(RulesSchema.MONSTER_ATTACKS, row, maCritical);
    }

    public int attackDamageType(int row) {
        return data.getInt(RulesSchema.MONSTER_ATTACKS, row, maDamageType);
    }

    public int attackReach(int row) {
        return data.getInt(RulesSchema.MONSTER_ATTACKS, row, maReach);
    }

    // ---- Characters ---------------------------------------------------------------------------

    public int characterCount() {
        return data.rows(RulesSchema.CHARACTERS);
    }

    public int characterAncestry(int row) {
        return data.getInt(RulesSchema.CHARACTERS, row, chAncestry);
    }

    public int characterClass(int row) {
        return data.getInt(RulesSchema.CHARACTERS, row, chClass);
    }

    public int characterLevel(int row) {
        return data.getInt(RulesSchema.CHARACTERS, row, chLevel);
    }

    public int characterAbility(int row, int ability) {
        return data.getInt(RulesSchema.CHARACTERS, row, chAbility + ability);
    }

    public int characterFeatsStart(int row) {
        return data.start(RulesSchema.CHARACTERS, row, chFeats);
    }

    public int characterFeatsCount(int row) {
        return data.count(RulesSchema.CHARACTERS, row, chFeats);
    }

    public int characterWeapon(int row) {
        return data.getInt(RulesSchema.CHARACTERS, row, chWeapon);
    }

    public int characterArmor(int row) {
        return data.getInt(RulesSchema.CHARACTERS, row, chArmor);
    }

    public int characterShield(int row) {
        return data.getInt(RulesSchema.CHARACTERS, row, chShield);
    }

    public int characterSpellsStart(int row) {
        return data.start(RulesSchema.CHARACTERS, row, chSpells);
    }

    public int characterSpellsCount(int row) {
        return data.count(RulesSchema.CHARACTERS, row, chSpells);
    }

    public int characterEffectsStart(int row) {
        return data.start(RulesSchema.CHARACTERS, row, chEffects);
    }

    public int characterEffectsCount(int row) {
        return data.count(RulesSchema.CHARACTERS, row, chEffects);
    }

    // ---- Operations and lists -----------------------------------------------------------------

    /** Int {@code k} ({@code RulesSchema.OP_*}) of operation {@code i}. */
    public int op(int i, int k) {
        return data.op(i, k);
    }

    /** Int {@code i} of the list pool. */
    public int pool(int i) {
        return data.pool(i);
    }

    /** The row of a reference stored as {@code kind << 24 | row} in an operation. */
    public static int refRow(int ref) {
        return ref & 0xFFFFFF;
    }

    /** The kind index of a reference stored in an operation. */
    public static int refKind(int ref) {
        return ref >>> 24;
    }

    /**
     * Base attack bonus of a class at {@code level}: full {@code level}, three quarters {@code
     * floor(3 level / 4)}, half {@code floor(level / 2)}.
     */
    public static int baseAttack(int progression, int level) {
        return switch (progression) {
            case BAB_FULL -> level;
            case BAB_THREE_QUARTERS -> level * 3 / 4;
            default -> level / 2;
        };
    }

    /**
     * Base save of a progression at {@code level}: good {@code 2 + level / 2}, poor {@code level /
     * 3}.
     */
    public static int baseSave(int progression, int level) {
        return progression == SAVE_GOOD ? 2 + level / 2 : level / 3;
    }

    /** The attack and AC modifier of a size (medium 0, small +1, tiny +2, large −1, huge −2). */
    public static int sizeModifier(int size) {
        return SIZE_MEDIUM - size;
    }
}
