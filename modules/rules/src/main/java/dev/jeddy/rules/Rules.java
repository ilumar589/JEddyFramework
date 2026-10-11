package dev.jeddy.rules;

import dev.jeddy.asset.format.RulesSchema;
import dev.jeddy.core.collections.IntList;
import dev.jeddy.math.Dice;
import dev.jeddy.math.Rng;

/**
 * The rules engine (DESIGN §20, D98, D99): builds creatures from content, applies effect
 * operations, resolves attacks, damage, healing, saving throws, spells and items, keeps conditions
 * and their durations, and writes every outcome to the {@link Events} ring. All randomness comes
 * from one {@link Rng}, so a fight with a given seed replays exactly.
 *
 * <p>Everything here is a cold path by game standards (a few calls per creature per round); the
 * per-frame work of a game is elsewhere. Still, nothing allocates after construction except {@link
 * #buildCharacter} and {@link #buildMonster} (they grow the stores).
 *
 * <p>Thread ownership: the game thread.
 */
public final class Rules {
    /** Saving throws, as {@link RulesSchema#SAVES} and the {@code saveKind} parameters: 1, 2, 3. */
    public static final int SAVE_FORTITUDE = 1;

    public static final int SAVE_REFLEX = 2;
    public static final int SAVE_WILL = 3;

    /** The result of an attack roll. */
    public static final int MISS = 0;

    public static final int HIT = 1;
    public static final int CRIT = 2;

    /** Attack flags. */
    public static final int ATTACK_OPPORTUNITY = 1;

    public static final int ATTACK_TOUCH = 2;
    public static final int ATTACK_RANGED = 4;

    private final Content content;
    private final Creatures creatures;
    private final Modifiers modifiers;
    private final Conditions conditions = new Conditions();
    private final Events events;
    private final Rng rng;
    private final int flatFootedRow;
    private final int dyingRow;
    private final int deadRow;
    private final int unconsciousRow;
    private final int[] touchScratch = new int[BonusType.COUNT];
    private int lastNatural;
    private int lastTotal;

    /** Creates an engine over {@code content} with room for {@code capacity} creatures. */
    public Rules(Content content, int capacity, long seed) {
        this.content = content;
        this.modifiers = new Modifiers();
        this.creatures = new Creatures(modifiers, capacity);
        this.events = new Events(1024);
        this.rng = new Rng(seed);
        flatFootedRow = content.find(RulesSchema.CONDITIONS, "flatFooted");
        dyingRow = content.find(RulesSchema.CONDITIONS, "dying");
        deadRow = content.find(RulesSchema.CONDITIONS, "dead");
        unconsciousRow = content.find(RulesSchema.CONDITIONS, "unconscious");
    }

    public Content content() {
        return content;
    }

    public Creatures creatures() {
        return creatures;
    }

    public Modifiers modifiers() {
        return modifiers;
    }

    public Conditions conditions() {
        return conditions;
    }

    public Events events() {
        return events;
    }

    /** The engine's random source (an encounter may reseed it for replays). */
    public Rng rng() {
        return rng;
    }

    /** The natural d20 of the last check or attack. */
    public int lastNatural() {
        return lastNatural;
    }

    /** The total of the last check or attack. */
    public int lastTotal() {
        return lastTotal;
    }

    // ---- Building creatures -------------------------------------------------------------------

    /**
     * Creates a creature from a character template: ancestry adjustments, class progressions at its
     * level (hit points: the hit die at level 1, its average plus one after, plus Constitution per
     * level), class features up to the level, feats, equipment and known spells.
     */
    public int buildCharacter(int characterRow, int team) {
        int c = creatures.create(content.name(RulesSchema.CHARACTERS, characterRow), team);
        creatures.setCharacterRow(c, characterRow);
        for (int a = 0; a < 6; a++) {
            creatures.setBaseAbility(c, a, content.characterAbility(characterRow, a));
        }
        int ancestry = content.characterAncestry(characterRow);
        if (ancestry >= 0) {
            applyAncestry(c, ancestry);
        }
        int level = content.characterLevel(characterRow);
        int cls = content.characterClass(characterRow);
        creatures.setLevel(c, level);
        if (cls >= 0) {
            applyClass(c, cls, level);
        }
        int feats = content.characterFeatsStart(characterRow);
        for (int i = 0; i < content.characterFeatsCount(characterRow); i++) {
            learnFeat(c, content.pool(feats + i));
        }
        equip(c, Creatures.SLOT_WEAPON, content.characterWeapon(characterRow));
        equip(c, Creatures.SLOT_ARMOR, content.characterArmor(characterRow));
        equip(c, Creatures.SLOT_SHIELD, content.characterShield(characterRow));
        int spells = content.characterSpellsStart(characterRow);
        for (int i = 0; i < content.characterSpellsCount(characterRow); i++) {
            creatures.spells(c).add(content.pool(spells + i));
        }
        applyEffects(
                c,
                content.characterEffectsStart(characterRow),
                content.characterEffectsCount(characterRow),
                Modifiers.source(Modifiers.SOURCE_CHARACTER, characterRow),
                Modifiers.PERMANENT,
                level,
                c,
                false);
        creatures.setHp(c, creatures.stat(c, Stat.MAX_HP));
        return c;
    }

    /** Creates a creature from a monster stat block; it dies at 0 hit points. */
    public int buildMonster(int monsterRow, int team) {
        int c = creatures.create(content.name(RulesSchema.MONSTERS, monsterRow), team);
        creatures.setMonsterRow(c, monsterRow);
        for (int a = 0; a < 6; a++) {
            creatures.setBaseAbility(c, a, content.monsterAbility(monsterRow, a));
        }
        int ancestry = content.monsterAncestry(monsterRow);
        if (ancestry >= 0) {
            applyAncestry(c, ancestry);
        }
        creatures.setSize(c, content.monsterSize(monsterRow));
        creatures.setLevel(c, content.monsterLevel(monsterRow));
        creatures.setBaseAttackBonus(c, content.monsterBab(monsterRow));
        for (int s = 0; s < 3; s++) {
            creatures.setBaseSave(c, s, content.monsterSave(monsterRow, s));
        }
        creatures.setBaseMaxHp(c, content.monsterHp(monsterRow));
        creatures.setBaseSpeed(c, content.monsterSpeed(monsterRow));
        int natural = content.monsterNaturalArmor(monsterRow);
        if (natural != 0) {
            creatures.addModifier(
                    c,
                    Stat.AC,
                    BonusType.NATURAL,
                    natural,
                    Modifiers.source(Modifiers.SOURCE_MONSTER, monsterRow),
                    Modifiers.PERMANENT);
        }
        int feats = content.monsterFeatsStart(monsterRow);
        for (int i = 0; i < content.monsterFeatsCount(monsterRow); i++) {
            learnFeat(c, content.pool(feats + i));
        }
        creatures.setMonsterAttacks(
                c,
                content.monsterAttacksStart(monsterRow),
                content.monsterAttacksCount(monsterRow));
        applyEffects(
                c,
                content.monsterEffectsStart(monsterRow),
                content.monsterEffectsCount(monsterRow),
                Modifiers.source(Modifiers.SOURCE_MONSTER, monsterRow),
                Modifiers.PERMANENT,
                creatures.level(c),
                c,
                false);
        creatures.setDiesAtZero(c, true);
        creatures.setHp(c, creatures.stat(c, Stat.MAX_HP));
        return c;
    }

    private void applyAncestry(int c, int row) {
        creatures.setAncestryRow(c, row);
        for (int a = 0; a < 6; a++) {
            creatures.setBaseAbility(
                    c, a, creatures.baseAbility(c, a) + content.ancestryAbility(row, a));
        }
        creatures.setSize(c, content.ancestrySize(row));
        creatures.setBaseSpeed(c, content.ancestrySpeed(row));
        applyEffects(
                c,
                content.ancestryEffectsStart(row),
                content.ancestryEffectsCount(row),
                Modifiers.source(Modifiers.SOURCE_ANCESTRY, row),
                Modifiers.PERMANENT,
                1,
                c,
                false);
    }

    private void applyClass(int c, int row, int level) {
        creatures.setClassRow(c, row);
        creatures.setBaseAttackBonus(c, Content.baseAttack(content.classBab(row), level));
        for (int s = 0; s < 3; s++) {
            creatures.setBaseSave(c, s, Content.baseSave(content.classSave(row, s), level));
        }
        int die = content.classHitDie(row);
        int con = creatures.abilityModifier(c, Stat.CON);
        int hp = die + (level - 1) * (die / 2 + 1) + Math.max(1 - level, con * level);
        creatures.setBaseMaxHp(c, Math.max(level, hp));
        creatures.setCasting(c, content.classCasting(row), content.classCastingAbility(row));
        int features = content.classFeaturesStart(row);
        for (int i = 0; i < content.classFeaturesCount(row); i++) {
            int f = features + i;
            if (content.featureLevel(f) <= level) {
                applyEffects(
                        c,
                        content.featureEffectsStart(f),
                        content.featureEffectsCount(f),
                        Modifiers.source(Modifiers.SOURCE_CLASS, f),
                        Modifiers.PERMANENT,
                        level,
                        c,
                        false);
            }
        }
    }

    /** Teaches {@code c} feat {@code row} (its effects apply while known); no-op if known. */
    public void learnFeat(int c, int row) {
        if (row < 0 || creatures.feats(c).contains(row)) {
            return;
        }
        creatures.feats(c).add(row);
        int source = Modifiers.source(Modifiers.SOURCE_FEAT, row);
        applyEffects(
                c,
                content.featEffectsStart(row),
                content.featEffectsCount(row),
                source,
                Modifiers.PERMANENT,
                creatures.level(c),
                c,
                false);
        if ((content.featFlags(row) & Content.FEAT_REFLEXES) != 0) {
            int dex = creatures.abilityModifier(c, Stat.DEX);
            if (dex > 0) {
                creatures.addModifier(
                        c, Stat.AOO, BonusType.UNTYPED, dex, source, Modifiers.PERMANENT);
            }
        }
    }

    /** Returns whether {@code c} meets feat {@code row}'s prerequisites. */
    public boolean meetsPrerequisites(int c, int row) {
        if (creatures.baseAttackBonus(c) < content.featMinBab(row)) {
            return false;
        }
        for (int a = 0; a < 6; a++) {
            if (creatures.ability(c, a) < content.featMinAbility(row, a)) {
                return false;
            }
        }
        int req = content.featRequiresStart(row);
        for (int i = 0; i < content.featRequiresCount(row); i++) {
            if (!creatures.feats(c).contains(content.pool(req + i))) {
                return false;
            }
        }
        return true;
    }

    /** Returns whether {@code c} has feat {@code row}. */
    public boolean hasFeat(int c, int row) {
        return row >= 0 && creatures.feats(c).contains(row);
    }

    /** Returns whether {@code c} has a feat with {@code flag} ({@code Content.FEAT_*}). */
    public boolean hasFeatFlag(int c, int flag) {
        IntList feats = creatures.feats(c);
        for (int i = 0; i < feats.size(); i++) {
            if ((content.featFlags(feats.get(i)) & flag) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts item {@code row} (or -1 for nothing) in {@code slot}: armor and shields add their AC
     * bonus as armor and shield modifiers and cap the Dexterity bonus, a weapon sets whether it is
     * finesse and two-handed, and the item's effects apply while equipped.
     */
    public void equip(int c, int slot, int row) {
        int source = Modifiers.source(Modifiers.SOURCE_ITEM, slot);
        creatures.removeModifiers(c, source);
        creatures.setEquipmentRow(c, slot, row);
        if (row >= 0) {
            int kind = content.itemKind(row);
            if (kind == Content.ITEM_ARMOR) {
                creatures.addModifier(
                        c,
                        Stat.AC,
                        BonusType.ARMOR,
                        content.itemArmor(row),
                        source,
                        Modifiers.PERMANENT);
            } else if (kind == Content.ITEM_SHIELD) {
                creatures.addModifier(
                        c,
                        Stat.AC,
                        BonusType.SHIELD,
                        content.itemArmor(row),
                        source,
                        Modifiers.PERMANENT);
            }
            applyEffects(
                    c,
                    content.itemEffectsStart(row),
                    content.itemEffectsCount(row),
                    source,
                    Modifiers.PERMANENT,
                    creatures.level(c),
                    c,
                    false);
        }
        int weapon = creatures.equipment(c, Creatures.SLOT_WEAPON);
        int armor = creatures.equipment(c, Creatures.SLOT_ARMOR);
        creatures.setWeaponTraits(
                c,
                weapon >= 0 && content.itemFinesse(weapon),
                weapon >= 0 && content.itemHands(weapon) >= 2,
                armor >= 0 ? content.itemMaxDex(armor) : 99);
    }

    // ---- Effects ------------------------------------------------------------------------------

    /**
     * Interprets operations {@code [start, start + count)} on {@code target}: modifiers get {@code
     * source} and the op's rounds (or {@code rounds} when the op has none); damage and healing roll
     * their dice ({@code halfDamage} halves damage: a successful save); conditions last the op's
     * rounds or {@code rounds}; {@code casterLevel} scales nothing yet (dice are written out per
     * spell level in the content) but is kept for the log. {@code actor} is who did it, for the
     * events.
     */
    public void applyEffects(
            int target,
            int start,
            int count,
            int source,
            int rounds,
            int casterLevel,
            int actor,
            boolean halfDamage) {
        for (int i = start; i < start + count; i++) {
            int op = content.op(i, RulesSchema.OP_OP);
            int value = content.op(i, RulesSchema.OP_VALUE);
            int dice = content.op(i, RulesSchema.OP_DICE);
            int opRounds = content.op(i, RulesSchema.OP_ROUNDS);
            int duration = opRounds > 0 ? opRounds : rounds;
            switch (op) {
                case Content.OP_MODIFY ->
                        creatures.addModifier(
                                target,
                                content.op(i, RulesSchema.OP_STAT),
                                content.op(i, RulesSchema.OP_TYPE),
                                value,
                                source,
                                duration);
                case Content.OP_DAMAGE -> {
                    int amount = Dice.roll(dice, rng);
                    if (halfDamage) {
                        amount /= 2;
                    }
                    damage(
                            target,
                            amount,
                            content.op(i, RulesSchema.OP_DAMAGE_TYPE),
                            actor,
                            source);
                }
                case Content.OP_HEAL -> heal(target, Dice.roll(dice, rng), actor, source);
                case Content.OP_TEMP_HP -> {
                    int amount = Dice.roll(dice, rng);
                    if (amount > creatures.tempHp(target)) {
                        creatures.setTempHp(target, amount, duration);
                        events.add(Events.TEMP_HP, actor, target, 0, 0, 0, amount, source);
                    }
                }
                case Content.OP_CONDITION ->
                        applyCondition(
                                target,
                                Content.refRow(content.op(i, RulesSchema.OP_REF)),
                                duration > 0 ? duration : Conditions.INDEFINITE);
                case Content.OP_REMOVE_CONDITION ->
                        removeCondition(target, Content.refRow(content.op(i, RulesSchema.OP_REF)));
                case Content.OP_GRANT_FEAT ->
                        learnFeat(target, Content.refRow(content.op(i, RulesSchema.OP_REF)));
                case Content.OP_EXTRA_ATTACK ->
                        creatures.addModifier(
                                target,
                                Stat.EXTRA_ATTACKS,
                                BonusType.UNTYPED,
                                value,
                                source,
                                duration);
                case Content.OP_SET_SPEED -> creatures.setBaseSpeed(target, value);
                default -> {}
            }
        }
    }

    /** Removes every effect {@code source} put on {@code c}. */
    public void removeEffects(int c, int source) {
        creatures.removeModifiers(c, source);
    }

    // ---- Conditions ---------------------------------------------------------------------------

    /**
     * Puts condition {@code row} on {@code c} for {@code rounds} ({@link Conditions#INDEFINITE}
     * until removed); a condition already present keeps the longer duration. Returns the instance.
     */
    public int applyCondition(int c, int row, int rounds) {
        IntList list = creatures.conditionHandles(c);
        for (int i = 0; i < list.size(); i++) {
            int h = list.get(i);
            if (conditions.condition(h) == row) {
                int left = conditions.rounds(h);
                if (rounds == Conditions.INDEFINITE
                        || (left != Conditions.INDEFINITE && rounds > left)) {
                    conditions.setRounds(h, rounds);
                }
                return h;
            }
        }
        int h = conditions.add(c, row, rounds);
        list.add(h);
        applyEffects(
                c,
                content.conditionEffectsStart(row),
                content.conditionEffectsCount(row),
                Modifiers.source(Modifiers.SOURCE_CONDITION, h),
                Modifiers.PERMANENT,
                creatures.level(c),
                c,
                false);
        refreshFlags(c);
        events.add(Events.CONDITION_ON, c, c, 0, 0, 0, rounds, row);
        return h;
    }

    /** Removes condition {@code row} from {@code c} if present. */
    public void removeCondition(int c, int row) {
        IntList list = creatures.conditionHandles(c);
        for (int i = 0; i < list.size(); i++) {
            int h = list.get(i);
            if (conditions.condition(h) == row) {
                endCondition(c, i);
                return;
            }
        }
    }

    private void endCondition(int c, int index) {
        IntList list = creatures.conditionHandles(c);
        int h = list.get(index);
        int row = conditions.condition(h);
        creatures.removeModifiers(c, Modifiers.source(Modifiers.SOURCE_CONDITION, h));
        conditions.remove(h);
        list.removeAt(index);
        refreshFlags(c);
        events.add(Events.CONDITION_OFF, c, c, 0, 0, 0, 0, row);
    }

    /** Returns whether {@code c} has condition {@code row}. */
    public boolean hasCondition(int c, int row) {
        IntList list = creatures.conditionHandles(c);
        for (int i = 0; i < list.size(); i++) {
            if (conditions.condition(list.get(i)) == row) {
                return true;
            }
        }
        return false;
    }

    private void refreshFlags(int c) {
        int f = 0;
        IntList list = creatures.conditionHandles(c);
        for (int i = 0; i < list.size(); i++) {
            f |= content.conditionFlags(conditions.condition(list.get(i)));
        }
        creatures.setFlags(c, f);
    }

    /** Returns whether {@code c} can take actions (alive, conscious, not stunned). */
    public boolean canAct(int c) {
        return creatures.isAlive(c) && !creatures.hasFlag(c, Content.FLAG_CANNOT_ACT);
    }

    /** Returns whether {@code c} is dead. */
    public boolean isDead(int c) {
        return !creatures.isAlive(c) || creatures.hasFlag(c, Content.FLAG_DEAD);
    }

    // ---- Damage, healing, death ---------------------------------------------------------------

    /**
     * Deals {@code amount} damage of {@code type} to {@code target}: temporary hit points absorb
     * first; at 0 or below the target is dying (unconscious, losing a point a round) or, when it
     * dies at zero or is at negative Constitution, dead. Returns the hit points actually lost.
     */
    public int damage(int target, int amount, int type, int actor, int ref) {
        if (amount <= 0 || isDead(target)) {
            return 0;
        }
        int temp = creatures.tempHp(target);
        if (temp > 0) {
            int absorbed = Math.min(temp, amount);
            creatures.setTempHp(target, temp - absorbed, creatures.tempHpRounds(target));
            amount -= absorbed;
        }
        int before = creatures.hp(target);
        creatures.setHp(target, before - amount);
        events.add(Events.DAMAGE, actor, target, 0, 0, type, amount, ref);
        checkDeath(target, actor);
        return amount;
    }

    /** Heals {@code amount} up to the maximum; a dying creature above zero stops dying. */
    public int heal(int target, int amount, int actor, int ref) {
        if (amount <= 0 || isDead(target)) {
            return 0;
        }
        int max = creatures.stat(target, Stat.MAX_HP);
        int before = creatures.hp(target);
        int after = Math.min(max, before + amount);
        creatures.setHp(target, after);
        events.add(Events.HEAL, actor, target, 0, 0, 0, after - before, ref);
        if (after > 0) {
            if (dyingRow >= 0) {
                removeCondition(target, dyingRow);
            }
            if (unconsciousRow >= 0) {
                removeCondition(target, unconsciousRow);
            }
        }
        return after - before;
    }

    private void checkDeath(int target, int actor) {
        int hp = creatures.hp(target);
        if (hp > 0) {
            return;
        }
        boolean dies = creatures.diesAtZero(target) || hp <= -creatures.ability(target, Stat.CON);
        if (dies) {
            if (deadRow >= 0 && !hasCondition(target, deadRow)) {
                if (dyingRow >= 0) {
                    removeCondition(target, dyingRow);
                }
                applyCondition(target, deadRow, Conditions.INDEFINITE);
            }
            events.add(Events.DEATH, actor, target, 0, 0, 0, hp, 0);
        } else if (dyingRow >= 0 && !hasCondition(target, dyingRow)) {
            applyCondition(target, dyingRow, Conditions.INDEFINITE);
            events.add(Events.DYING, actor, target, 0, 0, 0, hp, 0);
        }
    }

    // ---- Checks -------------------------------------------------------------------------------

    /**
     * A d20 check: {@code d20 + modifier >= dc}, a natural 20 succeeds and a natural 1 fails. The
     * natural roll and total are left in {@link #lastNatural} and {@link #lastTotal}.
     */
    public boolean check(int modifier, int dc) {
        lastNatural = rng.d20();
        lastTotal = lastNatural + modifier;
        return lastNatural == 20 || (lastNatural != 1 && lastTotal >= dc);
    }

    /** A saving throw of {@code saveKind} (1 fortitude, 2 reflex, 3 will) against {@code dc}. */
    public boolean save(int c, int saveKind, int dc, int actor, int ref) {
        int stat =
                switch (saveKind) {
                    case SAVE_FORTITUDE -> Stat.FORTITUDE;
                    case SAVE_REFLEX -> Stat.REFLEX;
                    default -> Stat.WILL;
                };
        boolean ok = check(creatures.stat(c, stat), dc);
        events.add(
                ok ? Events.SAVE_SUCCESS : Events.SAVE_FAIL,
                actor,
                c,
                lastNatural,
                lastTotal,
                dc,
                saveKind,
                ref);
        return ok;
    }

    /**
     * The number of attacks a full attack gives: iteratives from the base attack bonus plus extras.
     */
    public int fullAttackCount(int c) {
        int bab = creatures.baseAttackBonus(c);
        int n = 1 + Math.max(0, (bab - 1) / 5);
        return Math.min(6, n + creatures.stat(c, Stat.EXTRA_ATTACKS));
    }

    /** The attack bonus of attack {@code index} of a full attack: {@code -5} per iterative. */
    public int attackBonus(int c, int index) {
        int bab = creatures.baseAttackBonus(c);
        int iteratives = 1 + Math.max(0, (bab - 1) / 5);
        int penalty = index < iteratives ? 5 * index : 0; // extras from effects come at full bonus
        return creatures.stat(c, Stat.ATTACK) - penalty;
    }

    /**
     * The armor class an attack with {@code flags} faces: touch attacks ignore armor, shield and
     * natural bonuses; a flat-footed or helpless target loses its Dexterity bonus (handled by the
     * condition flags in {@link Creatures#stat}).
     */
    public int armorClass(int target, int flags) {
        int ac = creatures.stat(target, Stat.AC);
        if ((flags & ATTACK_TOUCH) != 0) {
            IntList h = creatures.modifierHandles(target);
            ac -=
                    modifiers.totalOfType(h, Stat.AC, BonusType.ARMOR)
                            + modifiers.totalOfType(h, Stat.AC, BonusType.SHIELD)
                            + modifiers.totalOfType(h, Stat.AC, BonusType.NATURAL);
        }
        return ac;
    }

    /**
     * One attack roll of {@code attacker} against {@code target} with the equipped weapon (or
     * monster attack {@code attackIndex % attacks}), the {@code index}th of a full attack, with
     * {@code bonus} added (a flanking or charge bonus the game decides). On a hit the damage is
     * rolled and dealt (dice and Strength, multiplied on a confirmed critical). Returns {@link
     * #MISS}, {@link #HIT} or {@link #CRIT}.
     */
    public int attack(int attacker, int target, int index, int bonus, int flags) {
        if (!canAct(attacker) || isDead(target)) {
            return MISS;
        }
        int weapon = creatures.equipment(attacker, Creatures.SLOT_WEAPON);
        int monsterAttack = creatures.monsterAttack(attacker);
        int dice;
        int threat;
        int multiplier;
        int type;
        int attackBonus;
        int damageBonus = creatures.stat(attacker, Stat.DAMAGE);
        if (monsterAttack >= 0 && creatures.monsterAttackCount(attacker) > 0) {
            int row = monsterAttack + index % creatures.monsterAttackCount(attacker);
            dice = content.attackDamage(row);
            threat = content.attackThreat(row);
            multiplier = content.attackCritical(row);
            type = content.attackDamageType(row);
            // A stat block's attack bonus already includes Strength and size: use it plus
            // modifiers.
            attackBonus =
                    content.attackBonus(row)
                            + creatures.bonus(attacker, Stat.ATTACK)
                            - (index > 0 ? 5 * index : 0);
            damageBonus = creatures.bonus(attacker, Stat.DAMAGE); // the dice carry Strength
        } else if (weapon >= 0) {
            dice = content.itemDamage(weapon);
            threat = content.itemThreat(weapon);
            multiplier = content.itemCritical(weapon);
            type = content.itemDamageType(weapon);
            attackBonus = attackBonus(attacker, index);
        } else {
            dice = Dice.pack(1, 3, 0); // an unarmed strike
            threat = 20;
            multiplier = 2;
            type = 0;
            attackBonus = attackBonus(attacker, index);
        }
        attackBonus += bonus;
        int ac = armorClass(target, flags);
        boolean hit = check(attackBonus, ac);
        int natural = lastNatural;
        int total = lastTotal;
        if (!hit) {
            events.add(Events.ATTACK_MISS, attacker, target, natural, total, ac, index, weapon);
            return MISS;
        }
        int result = HIT;
        if (natural >= threat && natural != 1) {
            // Confirm: a second roll against the same AC.
            if (check(attackBonus, ac)) {
                result = CRIT;
            }
        }
        events.add(
                result == CRIT ? Events.CRITICAL : Events.ATTACK_HIT,
                attacker,
                target,
                natural,
                total,
                ac,
                index,
                weapon);
        int rolled = Dice.roll(dice, rng) + damageBonus;
        if (result == CRIT) {
            for (int k = 1; k < multiplier; k++) {
                rolled += Dice.roll(dice, rng) + damageBonus;
            }
        }
        damage(target, Math.max(1, rolled), type, attacker, weapon);
        return result;
    }

    /** The DC of {@code caster}'s spell {@code row}: {@code 10 + spell level + casting ability}. */
    public int spellDc(int caster, int row) {
        int level = Math.max(0, content.spellLevel(row, creatures.casting(caster)));
        return 10 + level + creatures.abilityModifier(caster, creatures.castingAbility(caster));
    }

    /**
     * Returns whether {@code caster} has a slot left for spell {@code row} (or casts for free
     * without a class).
     */
    public boolean hasSlot(int caster, int row) {
        int cls = creatures.classRow(caster);
        if (cls < 0) {
            return true;
        }
        int level = content.spellLevel(row, creatures.casting(caster));
        if (level < 0) {
            return false;
        }
        int slots = content.classSlots(cls, creatures.level(caster), level);
        return creatures.slotsUsed(caster, level) < slots;
    }

    /**
     * Casts spell {@code row} on {@code targets[0, n)} (the game resolved range and area): spends a
     * slot, and for each target makes the spell's attack roll (touch) if any, its saving throw if
     * any (negating or halving), then applies its effects for the spell's duration. Returns {@code
     * false} when the caster cannot act or has no slot.
     */
    public boolean castSpell(int caster, int row, int[] targets, int n) {
        if (!canAct(caster) || !hasSlot(caster, row)) {
            return false;
        }
        int cls = creatures.classRow(caster);
        int level = content.spellLevel(row, creatures.casting(caster));
        if (cls >= 0 && level >= 0) {
            creatures.setSlotsUsed(caster, level, creatures.slotsUsed(caster, level) + 1);
        }
        int casterLevel = creatures.level(caster);
        int dc = spellDc(caster, row);
        int rounds = content.spellRounds(row, casterLevel);
        int source = Modifiers.source(Modifiers.SOURCE_SPELL, row);
        events.add(Events.SPELL, caster, n > 0 ? targets[0] : caster, 0, 0, dc, n, row);
        int attackKind = content.spellAttack(row);
        int save = content.spellSave(row);
        boolean halves = content.spellSaveResult(row) == Content.SAVE_HALF;
        for (int i = 0; i < n; i++) {
            int t = targets[i];
            if (!creatures.isAlive(t)) {
                continue;
            }
            if (attackKind != Content.SPELL_ATTACK_NONE) {
                int ac = armorClass(t, ATTACK_TOUCH);
                boolean hit = check(creatures.stat(caster, Stat.ATTACK), ac);
                events.add(
                        hit ? Events.ATTACK_HIT : Events.ATTACK_MISS,
                        caster,
                        t,
                        lastNatural,
                        lastTotal,
                        ac,
                        0,
                        row);
                if (!hit) {
                    continue;
                }
            }
            boolean half = false;
            if (save != Content.SPELL_SAVE_NONE) {
                boolean saved = save(t, save, dc, caster, row);
                if (saved) {
                    if (!halves) {
                        continue; // negated
                    }
                    half = true;
                }
            }
            creatures.removeModifiers(t, source); // recasting replaces the previous instance
            applyEffects(
                    t,
                    content.spellEffectsStart(row),
                    content.spellEffectsCount(row),
                    source,
                    rounds > 0 ? rounds : Modifiers.PERMANENT,
                    casterLevel,
                    caster,
                    half);
        }
        return true;
    }

    /**
     * Uses consumable item {@code row} on {@code target}: its {@code use} effects apply at once.
     */
    public void useItem(int user, int row, int target) {
        events.add(Events.ITEM, user, target, 0, 0, 0, 0, row);
        applyEffects(
                target,
                content.itemUseStart(row),
                content.itemUseCount(row),
                Modifiers.source(Modifiers.SOURCE_ITEM, 0xFFFF),
                Modifiers.PERMANENT,
                creatures.level(user),
                user,
                false);
    }

    // ---- Turns --------------------------------------------------------------------------------

    /**
     * Marks the start of {@code c}'s turn: it is no longer flat-footed, its opportunity attacks
     * reset.
     */
    public void startTurn(int c) {
        creatures.setActed(c, true);
        creatures.setAooUsed(c, 0);
        if (flatFootedRow >= 0) {
            removeCondition(c, flatFootedRow);
        }
        events.add(Events.TURN, c, c, 0, 0, 0, 0, 0);
    }

    /**
     * Ends {@code c}'s turn: durations tick (modifiers and conditions that reach zero end,
     * temporary hit points expire), and a dying creature loses a hit point or, on a natural 20,
     * stabilizes.
     */
    public void endTurn(int c) {
        modifiers.tick(creatures.modifierHandles(c));
        IntList list = creatures.conditionHandles(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            int h = list.get(i);
            int r = conditions.rounds(h);
            if (r > 0) {
                conditions.setRounds(h, r - 1);
                if (r - 1 == 0) {
                    endCondition(c, i);
                }
            }
        }
        int tr = creatures.tempHpRounds(c);
        if (creatures.tempHp(c) > 0 && tr > 0) {
            if (tr == 1) {
                creatures.setTempHp(c, 0, 0);
            } else {
                creatures.setTempHp(c, creatures.tempHp(c), tr - 1);
            }
        }
        if (dyingRow >= 0 && hasCondition(c, dyingRow) && !isDead(c)) {
            if (rng.d20() == 20) {
                removeCondition(c, dyingRow);
                if (unconsciousRow >= 0) {
                    applyCondition(c, unconsciousRow, Conditions.INDEFINITE);
                }
                events.add(Events.STABLE, c, c, 20, 0, 0, 0, 0);
            } else {
                creatures.setHp(c, creatures.hp(c) - 1);
                events.add(Events.DAMAGE, c, c, 0, 0, 10, 1, 0);
                checkDeath(c, c);
            }
        }
    }

    /** Puts {@code c} in the flat-footed state (an encounter's start, before its first turn). */
    public void setFlatFooted(int c) {
        if (flatFootedRow >= 0) {
            applyCondition(c, flatFootedRow, Conditions.INDEFINITE);
        }
    }

    /**
     * Returns how many attacks of opportunity {@code c} still has this round; none while
     * flat-footed (before its first turn of the fight) or unable to act.
     */
    public int opportunityAttacksLeft(int c) {
        if (!canAct(c) || creatures.hasFlag(c, Content.FLAG_FLAT_FOOTED)) {
            return 0;
        }
        return Math.max(0, creatures.stat(c, Stat.AOO) - creatures.aooUsed(c));
    }

    /**
     * Resolves an attack of opportunity by {@code attacker} on {@code provoker} if one is left this
     * round; returns the attack result or {@link #MISS} when none was available.
     */
    public int attackOfOpportunity(int attacker, int provoker) {
        if (opportunityAttacksLeft(attacker) == 0 || !canAct(attacker)) {
            return MISS;
        }
        creatures.setAooUsed(attacker, creatures.aooUsed(attacker) + 1);
        events.add(Events.OPPORTUNITY, attacker, provoker, 0, 0, 0, 0, 0);
        return attack(attacker, provoker, 0, 0, ATTACK_OPPORTUNITY);
    }

    /** Rolls initiative for {@code c}: {@code d20 + initiative}. */
    public int rollInitiative(int c) {
        return rng.d20() + creatures.stat(c, Stat.INITIATIVE);
    }
}
