package dev.jeddy.rules;

import dev.jeddy.core.collections.IntList;

import java.util.Arrays;

/**
 * Every creature in play as struct-of-arrays (DESIGN §20, D96): base ability scores, level, class
 * and ancestry (or monster) rows, base attack bonus and base saves, hit points (maximum, current,
 * temporary), size, speed, equipment, the feats and spells known, spell slots used, a team, and the
 * handles of the creature's {@link Modifiers} and condition instances. {@link #stat} resolves any
 * {@link Stat} from the bases and the stacked modifiers; nothing here rolls dice.
 *
 * <p>Creatures are plain indices with an alive flag and a free list; the game keeps its own map
 * from agents to creatures.
 *
 * <p>Thread ownership: the game thread. Allocates only when the store grows.
 */
public final class Creatures {
    /** Equipment slots. */
    public static final int SLOT_WEAPON = 0;

    public static final int SLOT_ARMOR = 1;
    public static final int SLOT_SHIELD = 2;
    public static final int SLOTS = 3;

    /** Spell levels tracked. */
    public static final int SPELL_LEVELS = 10;

    private final Modifiers modifiers;
    private final Conditions conditionStore;
    private int capacity;
    private int count;
    private boolean[] alive;
    private final IntList free = new IntList(16);
    private int liveCount;

    private String[] name;
    private int[] team;
    private int[] characterRow; // -1 for monsters
    private int[] monsterRow; // -1 for characters
    private int[] ability; // 6 per creature, base scores
    private int[] level;
    private int[] classRow;
    private int[] ancestryRow;
    private int[] bab;
    private int[] baseSave; // 3 per creature
    private int[] baseMaxHp;
    private int[] hp;
    private int[] tempHp;
    private int[] tempHpRounds;
    private int[] size;
    private int[] baseSpeed;
    private int[] equipment; // SLOTS per creature, item rows or -1
    private int[] monsterAttack; // first attack row, or -1
    private int[] monsterAttackCount;
    private int[] casting;
    private int[] castingAbility;
    private int[] slotsUsed; // SPELL_LEVELS per creature
    private boolean[] diesAtZero;
    private IntList[] feats;
    private IntList[] spells;
    private IntList[] mods;
    private IntList[] conditions; // condition instance handles into conditionStore
    private int[] flags; // the union of the active conditions' flags, kept by Rules
    private int[] aooUsed;
    private boolean[] acted;
    private final int[] scratch = new int[BonusType.COUNT];

    /**
     * Creates a store for {@code capacity} creatures (it grows past it) whose modifiers and
     * condition instances live in the two stores given, so that destroying a creature releases
     * both: a leaked condition instance would make {@code Conditions} grow during a long fight.
     */
    public Creatures(Modifiers modifiers, Conditions conditionStore, int capacity) {
        this.modifiers = modifiers;
        this.conditionStore = conditionStore;
        this.capacity = Math.max(1, capacity);
        allocate(this.capacity);
    }

    private void allocate(int n) {
        alive = Arrays.copyOf(alive == null ? new boolean[0] : alive, n);
        name = Arrays.copyOf(name == null ? new String[0] : name, n);
        team = grow(team, n);
        characterRow = grow(characterRow, n);
        monsterRow = grow(monsterRow, n);
        ability = grow(ability, n * 6);
        level = grow(level, n);
        classRow = grow(classRow, n);
        ancestryRow = grow(ancestryRow, n);
        bab = grow(bab, n);
        baseSave = grow(baseSave, n * 3);
        baseMaxHp = grow(baseMaxHp, n);
        hp = grow(hp, n);
        tempHp = grow(tempHp, n);
        tempHpRounds = grow(tempHpRounds, n);
        size = grow(size, n);
        baseSpeed = grow(baseSpeed, n);
        equipment = grow(equipment, n * SLOTS);
        monsterAttack = grow(monsterAttack, n);
        monsterAttackCount = grow(monsterAttackCount, n);
        casting = grow(casting, n);
        castingAbility = grow(castingAbility, n);
        slotsUsed = grow(slotsUsed, n * SPELL_LEVELS);
        diesAtZero = Arrays.copyOf(diesAtZero == null ? new boolean[0] : diesAtZero, n);
        feats = growLists(feats, n, 8);
        spells = growLists(spells, n, 8);
        mods = growLists(mods, n, 16); // ancestry, class, feats, items and a fight's conditions
        conditions = growLists(conditions, n, 8);
        flags = grow(flags, n);
        aooUsed = grow(aooUsed, n);
        acted = Arrays.copyOf(acted == null ? new boolean[0] : acted, n);
        int old = maxDexToAc == null ? 0 : maxDexToAc.length;
        maxDexToAc = grow(maxDexToAc, n);
        Arrays.fill(maxDexToAc, old, n, 99);
        finesse = Arrays.copyOf(finesse == null ? new boolean[0] : finesse, n);
        twoHanded = Arrays.copyOf(twoHanded == null ? new boolean[0] : twoHanded, n);
        capacity = n;
    }

    private static int[] grow(int[] a, int n) {
        return a == null ? new int[n] : Arrays.copyOf(a, n);
    }

    private static IntList[] growLists(IntList[] a, int n, int each) {
        IntList[] out = a == null ? new IntList[n] : Arrays.copyOf(a, n);
        for (int i = a == null ? 0 : a.length; i < n; i++) {
            out[i] = new IntList(each);
        }
        return out;
    }

    /** Creature handles are below this (the store grows past it, allocating). */
    public int capacity() {
        return capacity;
    }

    /** Returns the number of live creatures. */
    public int liveCount() {
        return liveCount;
    }

    /** Returns the highest index ever used plus one (iterate with {@link #isAlive}). */
    public int highWaterMark() {
        return count;
    }

    /** Returns whether {@code c} is a live creature. */
    public boolean isAlive(int c) {
        return c >= 0 && c < count && alive[c];
    }

    /** Returns the modifiers store. */
    public Modifiers modifiers() {
        return modifiers;
    }

    /**
     * Creates a creature named {@code name} on {@code team} with every base at a neutral default
     * (scores 10, level 1, 10 hit points, medium, 30 feet); {@code Rules.buildCharacter} and {@code
     * buildMonster} fill it from content.
     */
    public int create(String name, int team) {
        int c;
        if (!free.isEmpty()) {
            c = free.removeLast();
        } else {
            if (count == capacity) {
                allocate(capacity * 2);
            }
            c = count++;
        }
        alive[c] = true;
        liveCount++;
        this.name[c] = name;
        this.team[c] = team;
        characterRow[c] = -1;
        monsterRow[c] = -1;
        Arrays.fill(ability, c * 6, c * 6 + 6, 10);
        level[c] = 1;
        classRow[c] = -1;
        ancestryRow[c] = -1;
        bab[c] = 0;
        Arrays.fill(baseSave, c * 3, c * 3 + 3, 0);
        baseMaxHp[c] = 10;
        hp[c] = 10;
        tempHp[c] = 0;
        tempHpRounds[c] = 0;
        size[c] = Content.SIZE_MEDIUM;
        baseSpeed[c] = 30;
        Arrays.fill(equipment, c * SLOTS, c * SLOTS + SLOTS, -1);
        monsterAttack[c] = -1;
        monsterAttackCount[c] = 0;
        casting[c] = Content.CASTING_NONE;
        castingAbility[c] = Stat.INT;
        Arrays.fill(slotsUsed, c * SPELL_LEVELS, c * SPELL_LEVELS + SPELL_LEVELS, 0);
        diesAtZero[c] = false;
        feats[c].clear();
        spells[c].clear();
        release(c);
        flags[c] = 0;
        aooUsed[c] = 0;
        acted[c] = false;
        maxDexToAc[c] = 99;
        finesse[c] = false;
        twoHanded[c] = false;
        return c;
    }

    /** Removes creature {@code c}, its modifiers and its condition instances. */
    public void destroy(int c) {
        if (!isAlive(c)) {
            return;
        }
        release(c);
        alive[c] = false;
        liveCount--;
        free.add(c);
    }

    /** Gives {@code c}'s modifiers and condition instances back to their stores. */
    private void release(int c) {
        for (int i = 0; i < mods[c].size(); i++) {
            modifiers.remove(mods[c].get(i));
        }
        mods[c].clear();
        for (int i = 0; i < conditions[c].size(); i++) {
            conditionStore.remove(conditions[c].get(i));
        }
        conditions[c].clear();
    }

    // ---- Plain fields -------------------------------------------------------------------------

    public String name(int c) {
        return name[c];
    }

    public void setName(int c, String n) {
        name[c] = n;
    }

    public int team(int c) {
        return team[c];
    }

    public void setTeam(int c, int t) {
        team[c] = t;
    }

    public int characterRow(int c) {
        return characterRow[c];
    }

    public void setCharacterRow(int c, int row) {
        characterRow[c] = row;
    }

    public int monsterRow(int c) {
        return monsterRow[c];
    }

    public void setMonsterRow(int c, int row) {
        monsterRow[c] = row;
    }

    /** Base ability score {@code a} (0 str .. 5 cha), before modifiers. */
    public int baseAbility(int c, int a) {
        return ability[c * 6 + a];
    }

    public void setBaseAbility(int c, int a, int score) {
        ability[c * 6 + a] = score;
    }

    public int level(int c) {
        return level[c];
    }

    public void setLevel(int c, int l) {
        level[c] = l;
    }

    public int classRow(int c) {
        return classRow[c];
    }

    public void setClassRow(int c, int row) {
        classRow[c] = row;
    }

    public int ancestryRow(int c) {
        return ancestryRow[c];
    }

    public void setAncestryRow(int c, int row) {
        ancestryRow[c] = row;
    }

    public int baseAttackBonus(int c) {
        return bab[c];
    }

    public void setBaseAttackBonus(int c, int v) {
        bab[c] = v;
    }

    /** Base save {@code s} (0 fortitude, 1 reflex, 2 will). */
    public int baseSave(int c, int s) {
        return baseSave[c * 3 + s];
    }

    public void setBaseSave(int c, int s, int v) {
        baseSave[c * 3 + s] = v;
    }

    public int baseMaxHp(int c) {
        return baseMaxHp[c];
    }

    public void setBaseMaxHp(int c, int v) {
        baseMaxHp[c] = v;
    }

    public int hp(int c) {
        return hp[c];
    }

    public void setHp(int c, int v) {
        hp[c] = v;
    }

    public int tempHp(int c) {
        return tempHp[c];
    }

    public void setTempHp(int c, int v, int rounds) {
        tempHp[c] = v;
        tempHpRounds[c] = rounds;
    }

    public int tempHpRounds(int c) {
        return tempHpRounds[c];
    }

    public int size(int c) {
        return size[c];
    }

    public void setSize(int c, int s) {
        size[c] = s;
    }

    public int baseSpeed(int c) {
        return baseSpeed[c];
    }

    public void setBaseSpeed(int c, int v) {
        baseSpeed[c] = v;
    }

    /** The item row in {@code slot}, or -1. */
    public int equipment(int c, int slot) {
        return equipment[c * SLOTS + slot];
    }

    void setEquipmentRow(int c, int slot, int item) {
        equipment[c * SLOTS + slot] = item;
    }

    public int monsterAttack(int c) {
        return monsterAttack[c];
    }

    public int monsterAttackCount(int c) {
        return monsterAttackCount[c];
    }

    public void setMonsterAttacks(int c, int first, int n) {
        monsterAttack[c] = first;
        monsterAttackCount[c] = n;
    }

    public int casting(int c) {
        return casting[c];
    }

    public int castingAbility(int c) {
        return castingAbility[c];
    }

    public void setCasting(int c, int kind, int abilityIndex) {
        casting[c] = kind;
        castingAbility[c] = abilityIndex;
    }

    public int slotsUsed(int c, int spellLevel) {
        return slotsUsed[c * SPELL_LEVELS + spellLevel];
    }

    public void setSlotsUsed(int c, int spellLevel, int used) {
        slotsUsed[c * SPELL_LEVELS + spellLevel] = used;
    }

    public boolean diesAtZero(int c) {
        return diesAtZero[c];
    }

    public void setDiesAtZero(int c, boolean v) {
        diesAtZero[c] = v;
    }

    /** The feats known (rows), to read; {@code Rules.learnFeat} adds. */
    public IntList feats(int c) {
        return feats[c];
    }

    /** The spells known (rows). */
    public IntList spells(int c) {
        return spells[c];
    }

    /** The creature's modifier handles. */
    public IntList modifierHandles(int c) {
        return mods[c];
    }

    /** The creature's condition instance handles. */
    public IntList conditionHandles(int c) {
        return conditions[c];
    }

    /** The union of the active conditions' flags ({@code Content.FLAG_*}). */
    public int flags(int c) {
        return flags[c];
    }

    void setFlags(int c, int f) {
        flags[c] = f;
    }

    public boolean hasFlag(int c, int flag) {
        return (flags[c] & flag) != 0;
    }

    public int aooUsed(int c) {
        return aooUsed[c];
    }

    public void setAooUsed(int c, int n) {
        aooUsed[c] = n;
    }

    public boolean acted(int c) {
        return acted[c];
    }

    public void setActed(int c, boolean v) {
        acted[c] = v;
    }

    // ---- Resolution ---------------------------------------------------------------------------

    /** Adds a modifier to {@code c}; returns its handle. */
    public int addModifier(int c, int stat, int type, int value, int source, int rounds) {
        int h = modifiers.add(c, stat, type, value, source, rounds);
        mods[c].add(h);
        return h;
    }

    /** Removes every modifier of {@code c} from {@code source}. */
    public void removeModifiers(int c, int source) {
        modifiers.removeBySource(mods[c], source);
    }

    /** The stacked modifier total for {@code stat}. */
    public int bonus(int c, int stat) {
        return modifiers.total(mods[c], stat, scratch);
    }

    /** The current ability score {@code a}: base plus modifiers. */
    public int ability(int c, int a) {
        return ability[c * 6 + a] + bonus(c, a);
    }

    /** The current ability modifier. */
    public int abilityModifier(int c, int a) {
        return Stat.modifier(ability(c, a));
    }

    /**
     * Resolves {@code stat}: an ability is its score; AC is {@code 10 + size + Dex (unless lost) +
     * modifiers} (armor, shield and natural bonuses are modifiers from equipment and content);
     * attack is {@code base attack + Strength (or Dexterity with a finesse weapon) + size +
     * modifiers}; damage is the Strength bonus plus modifiers; saves are their base plus the
     * ability plus modifiers; initiative is Dexterity plus modifiers; speed and maximum hit points
     * their base plus modifiers; attacks of opportunity per round is {@code 1 + modifiers}.
     */
    public int stat(int c, int stat) {
        switch (stat) {
            case Stat.STR, Stat.DEX, Stat.CON, Stat.INT, Stat.WIS, Stat.CHA -> {
                return ability(c, stat);
            }
            case Stat.AC -> {
                int dex =
                        hasFlag(c, Content.FLAG_LOSE_DEX)
                                ? 0
                                : Math.max(0, abilityModifier(c, Stat.DEX));
                return 10
                        + Content.sizeModifier(size[c])
                        + Math.min(dex, maxDexToAc[c])
                        + bonus(c, Stat.AC);
            }
            case Stat.ATTACK -> {
                int str = abilityModifier(c, Stat.STR);
                int dex = abilityModifier(c, Stat.DEX);
                int best = finesse[c] ? Math.max(str, dex) : str;
                return bab[c] + best + Content.sizeModifier(size[c]) + bonus(c, Stat.ATTACK);
            }
            case Stat.DAMAGE -> {
                int str = abilityModifier(c, Stat.STR);
                return (twoHanded[c] ? str + str / 2 : str) + bonus(c, Stat.DAMAGE);
            }
            case Stat.FORTITUDE -> {
                return baseSave[c * 3] + abilityModifier(c, Stat.CON) + bonus(c, Stat.FORTITUDE);
            }
            case Stat.REFLEX -> {
                return baseSave[c * 3 + 1] + abilityModifier(c, Stat.DEX) + bonus(c, Stat.REFLEX);
            }
            case Stat.WILL -> {
                return baseSave[c * 3 + 2] + abilityModifier(c, Stat.WIS) + bonus(c, Stat.WILL);
            }
            case Stat.INITIATIVE -> {
                return abilityModifier(c, Stat.DEX) + bonus(c, Stat.INITIATIVE);
            }
            case Stat.SPEED -> {
                return Math.max(0, baseSpeed[c] + bonus(c, Stat.SPEED));
            }
            case Stat.MAX_HP -> {
                return Math.max(1, baseMaxHp[c] + bonus(c, Stat.MAX_HP));
            }
            case Stat.AOO -> {
                return 1 + bonus(c, Stat.AOO);
            }
            case Stat.EXTRA_ATTACKS -> {
                return bonus(c, Stat.EXTRA_ATTACKS);
            }
            default -> throw new IllegalArgumentException("stat " + stat);
        }
    }

    // Weapon-dependent switches kept by Rules.equip: the largest Dexterity bonus the armor allows,
    // whether the weapon is finesse and whether it is wielded in two hands.
    private int[] maxDexToAc;
    private boolean[] finesse;
    private boolean[] twoHanded;

    void setWeaponTraits(int c, boolean finesseWeapon, boolean twoHandedWeapon, int maxDex) {
        finesse[c] = finesseWeapon;
        twoHanded[c] = twoHandedWeapon;
        maxDexToAc[c] = maxDex;
    }

    int maxDexToAc(int c) {
        return maxDexToAc[c];
    }
}
