package dev.jeddy.samples.iso;

import dev.jeddy.asset.format.RulesSchema;
import dev.jeddy.core.collections.IntList;
import dev.jeddy.core.io.ByteReader;
import dev.jeddy.core.io.ByteWriter;
import dev.jeddy.physics.crowd.Crowd;
import dev.jeddy.physics.nav.TurnBasedNav;
import dev.jeddy.rules.ActionBudget;
import dev.jeddy.rules.Content;
import dev.jeddy.rules.Creatures;
import dev.jeddy.rules.Encounter;
import dev.jeddy.rules.Events;
import dev.jeddy.rules.RealTimeDriver;
import dev.jeddy.rules.Rules;
import dev.jeddy.rules.Stat;
import dev.jeddy.rules.TurnBasedDriver;
import dev.jeddy.rules.TurnListener;

/**
 * The fight as the rules see it (TASKS M15-07): every party member and skeleton is a creature built
 * from the content (classes, feats, weapons, armor; monster stat blocks), one {@link Encounter}
 * holds whoever is engaged, and the two drivers play it: in real time the {@link RealTimeDriver}
 * hands each creature its attacks for the round as its turn comes up and the animations spend them;
 * in turn-based mode ({@code T}) the {@link TurnBasedDriver} waits for the player's orders for
 * party members (move within the budget, attack, end turn) and runs a small AI for the skeletons'
 * turns. Outcomes come back through the {@link Events} ring, which this class turns into hit
 * flashes, deaths, revivals and combat-log lines.
 *
 * <p>Distances: the rules speak in feet, the crowd in meters; a 5-foot square is {@link
 * #METERS_PER_FOOT} × 5 ≈ 1.5 m here (the crypt's scale), reach is the demo's {@link
 * Enemies#REACH}.
 *
 * <p>Thread ownership: the game thread. Allocates nothing per tick after construction (the stores
 * grow at most when skeletons are rebuilt).
 */
final class Combat implements TurnListener {
    /** The crypt's 5-foot square is 1.5 m. */
    static final float METERS_PER_FOOT = 0.3f;

    static final int TEAM_PARTY = 0;
    static final int TEAM_UNDEAD = 1;

    /** Seconds a skeleton's turn may take walking before it strikes or passes. */
    private static final float AI_MOVE_SECONDS = 3f;

    final Rules rules;
    final Content content;
    final Creatures creatures;
    final Encounter encounter;
    final RealTimeDriver realTime;
    final TurnBasedDriver turnBased;
    final CombatLog log;

    /** Whether the player plays turn by turn ({@code T}). */
    boolean turnBasedMode;

    /** Fireballs without spell slots (the perf scene throws them all day). */
    boolean unlimitedSlots;

    /** The party cannot fall (the perf scene measures, it does not play): hits still land. */
    boolean invulnerableParty;

    /** Rounds fought since the first engagement, for the HUD. */
    int rounds;

    private final Party party;
    private final Enemies enemies;
    private final Animator animator;
    private final Crowd crowd;
    private final int[] partyCreature = new int[Party.SIZE];
    private final int[] enemyCreature;
    private final int[] monsterRow;
    private int[] agentOf = new int[64];
    private int[] memberOf = new int[64];
    private int[] enemyOf = new int[64];
    private final int[] attacksLeft;
    private final int[] attackIndex;
    private final float[] moveLeft;
    private final int fireballRow;
    private final int flatFootedRow;
    private final int deadRow;
    private final int dyingRow;
    private final int[] spellTargets;
    private final int[] threatScratch;
    private final float[] threatCircles;
    private final int[] crossingEnemy = new int[16];
    private final float[] crossingDistance = new float[16];
    private final int[] crossingKind = new int[16];
    private final float[] segment = new float[6];
    private final int[] one = new int[1];
    private long eventsRead;
    private int engaged;
    private float regenTimer;
    // The skeleton AI's turn in turn-based mode.
    private int aiCreature = -1;
    private int aiPhase;
    private float aiTimer;
    private float aiTargetX;
    private float aiTargetZ;
    private int aiVictim = -1;

    Combat(
            Content content,
            long seed,
            Party party,
            Enemies enemies,
            Animator animator,
            Crowd crowd,
            CombatLog log) {
        this.content = content;
        this.party = party;
        this.enemies = enemies;
        this.animator = animator;
        this.crowd = crowd;
        this.log = log;
        int capacity = Party.SIZE + enemies.count + 8;
        rules = new Rules(content, capacity, seed);
        creatures = rules.creatures();
        encounter = new Encounter(rules);
        realTime = new RealTimeDriver(encounter);
        turnBased = new TurnBasedDriver(encounter);
        realTime.setListener(this);
        turnBased.setListener(this);
        attacksLeft = new int[capacity * 2];
        attackIndex = new int[capacity * 2];
        moveLeft = new float[capacity * 2];
        enemyCreature = new int[enemies.count];
        monsterRow = new int[Characters.ENEMIES.length];
        spellTargets = new int[capacity];
        threatScratch = new int[capacity];
        threatCircles = new float[capacity * 3];
        for (int i = 0; i < Party.SIZE; i++) {
            int row = content.find(RulesSchema.CHARACTERS, Characters.PARTY_IDS[i]);
            if (row < 0) {
                throw new IllegalStateException("no character " + Characters.PARTY_IDS[i]);
            }
            int c = rules.buildCharacter(row, TEAM_PARTY);
            partyCreature[i] = c;
            bind(c, party.agent[i], i, -1);
        }
        for (int m = 0; m < monsterRow.length; m++) {
            monsterRow[m] = content.find(RulesSchema.MONSTERS, Characters.ENEMY_IDS[m]);
            if (monsterRow[m] < 0) {
                throw new IllegalStateException("no monster " + Characters.ENEMY_IDS[m]);
            }
        }
        for (int i = 0; i < enemies.count; i++) {
            enemyCreature[i] = -1;
            spawnEnemy(i);
        }
        fireballRow = content.find(RulesSchema.SPELLS, "fireball");
        flatFootedRow = content.find(RulesSchema.CONDITIONS, "flatFooted");
        deadRow = content.find(RulesSchema.CONDITIONS, "dead");
        dyingRow = content.find(RulesSchema.CONDITIONS, "dying");
        eventsRead = rules.events().next();
    }

    private void bind(int c, int agent, int member, int enemy) {
        if (c >= agentOf.length) {
            int n = Math.max(c + 1, agentOf.length * 2);
            agentOf = java.util.Arrays.copyOf(agentOf, n);
            memberOf = java.util.Arrays.copyOf(memberOf, n);
            enemyOf = java.util.Arrays.copyOf(enemyOf, n);
        }
        agentOf[c] = agent;
        memberOf[c] = member;
        enemyOf[c] = enemy;
    }

    /** (Re)builds skeleton {@code i}'s creature from its model's stat block (spawn and respawn). */
    void spawnEnemy(int i) {
        if (enemyCreature[i] >= 0) {
            creatures.destroy(enemyCreature[i]);
        }
        int c = rules.buildMonster(monsterRow[enemies.model(i)], TEAM_UNDEAD);
        enemyCreature[i] = c;
        bind(c, enemies.agent(i), -1, i);
    }

    // ---- Lookups ------------------------------------------------------------------------------

    int partyCreature(int member) {
        return partyCreature[member];
    }

    int enemyCreature(int i) {
        return enemyCreature[i];
    }

    /** The party member of creature {@code c}, or -1. */
    int memberOf(int c) {
        return c >= 0 && c < memberOf.length ? memberOf[c] : -1;
    }

    /** The skeleton of creature {@code c}, or -1. */
    int enemyOf(int c) {
        return c >= 0 && c < enemyOf.length ? enemyOf[c] : -1;
    }

    int hp(int c) {
        return creatures.hp(c);
    }

    int maxHp(int c) {
        return creatures.stat(c, Stat.MAX_HP);
    }

    /** Whether creature {@code c} can act (not dying, dead, stunned or unconscious). */
    boolean canAct(int c) {
        return rules.canAct(c);
    }

    /** Whether a fight is on. */
    boolean active() {
        return encounter.isActive();
    }

    // ---- Engagement ---------------------------------------------------------------------------

    /** Skeleton {@code i} noticed the party: it joins the fight (starting one if none is on). */
    void engage(int i) {
        int c = enemyCreature[i];
        if (encounter.contains(c)) {
            return;
        }
        if (!encounter.isActive()) {
            for (int m = 0; m < Party.SIZE; m++) {
                encounter.add(partyCreature[m]);
            }
            encounter.add(c);
            encounter.start();
            realTime.sync();
            rounds = 1;
            log.start().add("Combat begins").end();
        } else {
            encounter.join(c);
        }
        engaged++;
    }

    /** Skeleton {@code i} lost interest or fell: it leaves the fight. */
    void disengage(int i) {
        int c = enemyCreature[i];
        if (!encounter.contains(c)) {
            return;
        }
        boolean wasCurrent = encounter.current() == c;
        encounter.leave(c);
        engaged--;
        if (wasCurrent && aiCreature == c) {
            aiCreature = -1;
            if (turnBasedMode) {
                turnBased.endTurn();
            }
        }
        if (engaged == 0 && encounter.isActive()) {
            endCombat();
        }
    }

    private void endCombat() {
        encounter.end();
        aiCreature = -1;
        log.start().add("Combat ends after ").add(rounds).add(" rounds").end();
        // The crypt is forgiving: the fallen come to with one hit point, slots refill.
        for (int m = 0; m < Party.SIZE; m++) {
            int c = partyCreature[m];
            if (rules.isDead(c)) {
                rules.removeCondition(c, deadRow);
                log.start().add(creatures.name(c)).add(" rises again").end();
            }
            if (creatures.hp(c) < 1) {
                rules.heal(c, 1 - creatures.hp(c), c, 0);
            }
            for (int level = 0; level < Creatures.SPELL_LEVELS; level++) {
                creatures.setSlotsUsed(c, level, 0);
            }
        }
    }

    // ---- Ticking ------------------------------------------------------------------------------

    /** Advances the real-time clock (or the skeleton AI's turn), then applies the events. */
    void tick(float dt) {
        if (encounter.isActive()) {
            if (turnBasedMode) {
                if (encounter.current() < 0) {
                    turnBased.endTurn(); // the first turn
                }
                aiTick(dt);
            } else {
                realTime.advance(dt);
            }
            rounds = encounter.round();
        } else {
            regenTimer += dt;
            if (regenTimer >= 2) {
                regenTimer = 0;
                for (int m = 0; m < Party.SIZE; m++) {
                    int c = partyCreature[m];
                    if (creatures.hp(c) < maxHp(c) && !rules.isDead(c)) {
                        rules.heal(c, 1, c, 0);
                    }
                }
            }
        }
        drainEvents();
    }

    @Override
    public void onTurn(Encounter e, int c) {
        attacksLeft[c] = rules.fullAttackCount(c);
        attackIndex[c] = 0;
        moveLeft[c] = creatures.stat(c, Stat.SPEED) * METERS_PER_FOOT;
        if (turnBasedMode) {
            int member = memberOf(c);
            if (member >= 0) {
                party.select(member, false);
            } else {
                aiCreature = c;
                aiPhase = 0;
                aiTimer = 0;
            }
        }
    }

    // ---- Strikes (both modes) -----------------------------------------------------------------

    /** Whether creature {@code c} may swing now: in a fight, able, with an attack left. */
    boolean canStrike(int c) {
        if (!encounter.isActive() || !rules.canAct(c) || !encounter.contains(c)) {
            return false;
        }
        if (turnBasedMode) {
            return encounter.current() == c
                    && (attacksLeft[c] > 0 || encounter.hasAction(c, ActionBudget.STANDARD));
        }
        return attacksLeft[c] > 0;
    }

    /**
     * Creature {@code c} swings at {@code target}: in real time one of its round's attacks; in
     * turn-based mode a full attack when it has not moved, else one attack for its standard action.
     * Returns the rules' result ({@code Rules.MISS}, {@code HIT}, {@code CRIT}).
     */
    int strike(int c, int target) {
        if (!canStrike(c)) {
            return Rules.MISS;
        }
        if (attackIndex[c] == 0) {
            if (turnBasedMode) {
                boolean full =
                        !encounter.budget().moved(c)
                                && encounter.useAction(c, ActionBudget.FULL_ROUND);
                if (!full) {
                    if (!encounter.useAction(c, ActionBudget.STANDARD)) {
                        return Rules.MISS;
                    }
                    attacksLeft[c] = 1;
                }
            } else {
                encounter.useAction(c, ActionBudget.FULL_ROUND);
            }
        }
        if (attacksLeft[c] <= 0) {
            return Rules.MISS;
        }
        attacksLeft[c]--;
        return rules.attack(c, target, attackIndex[c]++, 0, 0);
    }

    /**
     * The caster throws a fireball that caught the crowd agents in {@code hit[0, n)}: spends the
     * slot, rolls each target's Reflex save and deals the damage. Returns {@code false} when the
     * caster has no slot left (or cannot act).
     */
    boolean fireball(int caster, IntList hit, int n) {
        if (unlimitedSlots) {
            int level = content.spellLevel(fireballRow, creatures.casting(caster));
            if (level >= 0) {
                creatures.setSlotsUsed(caster, level, 0);
            }
        }
        int targets = 0;
        for (int i = 0; i < n && targets < spellTargets.length; i++) {
            int c = creatureOfAgent(hit.get(i));
            if (c >= 0) {
                spellTargets[targets++] = c;
            }
        }
        if (turnBasedMode && encounter.isActive() && encounter.current() == caster) {
            encounter.useAction(caster, ActionBudget.STANDARD);
        }
        return rules.castSpell(caster, fireballRow, spellTargets, targets);
    }

    /** Whether the caster still has a fireball. */
    boolean hasFireball(int caster) {
        return unlimitedSlots || rules.hasSlot(caster, fireballRow);
    }

    private int creatureOfAgent(int agent) {
        int m = party.memberOf(agent);
        if (m >= 0) {
            return partyCreature[m];
        }
        int e = enemies.enemyOf(agent);
        return e >= 0 ? enemyCreature[e] : -1;
    }

    // ---- Turn-based play ----------------------------------------------------------------------

    /** Switches modes; the encounter keeps its state, the real-time clock resyncs. */
    void setTurnBased(boolean on) {
        if (on == turnBasedMode) {
            return;
        }
        turnBasedMode = on;
        aiCreature = -1;
        if (on) {
            int c = encounter.current();
            if (encounter.isActive() && c >= 0) {
                onTurn(encounter, c); // the current creature gets the turn's budget again
            }
        } else {
            realTime.sync();
        }
    }

    /** The creature whose turn it is, or -1. */
    int current() {
        return encounter.isActive() ? encounter.current() : -1;
    }

    /** Meters the current creature may still walk this turn (one more move action if unused). */
    float moveLeft(int c) {
        return c >= 0 && c < moveLeft.length ? moveLeft[c] : 0;
    }

    /**
     * Meters creature {@code c} could walk at most this turn: what is left of its move plus a
     * second move action out of its standard action, if that is still free.
     */
    float movementBudget(int c) {
        if (c < 0 || !turnBasedMode || !encounter.isActive() || !rules.canAct(c)) {
            return 0;
        }
        float extra =
                encounter.hasAction(c, ActionBudget.STANDARD)
                        ? creatures.stat(c, Stat.SPEED) * METERS_PER_FOOT
                        : 0;
        return moveLeft(c) + extra;
    }

    /** Ends the current turn (the player's End Turn). */
    void endTurn() {
        if (turnBasedMode && encounter.isActive()) {
            turnBased.endTurn();
        }
    }

    /**
     * Orders creature {@code c} to walk {@code cost} meters to {@code (x, y, z)} in its turn:
     * spends movement (a second move action out of the standard one when the first is used up),
     * draws the attacks of opportunity of the skeletons it walks away from, and starts the walk.
     * Returns {@code false} when the budget does not reach.
     */
    boolean move(int c, float x, float y, float z, float cost) {
        if (!turnBasedMode || encounter.current() != c || !rules.canAct(c) || cost < 0) {
            return false;
        }
        if (cost > moveLeft[c]) {
            if (!encounter.useAction(c, ActionBudget.MOVE)) {
                return false;
            }
            moveLeft[c] += creatures.stat(c, Stat.SPEED) * METERS_PER_FOOT;
            if (cost > moveLeft[c]) {
                return false;
            }
        } else if (!encounter.budget().moved(c)) {
            encounter.useAction(c, ActionBudget.MOVE);
        }
        moveLeft[c] -= cost;
        encounter.reportMovement(c);
        provokeAlongPath(c, x, z);
        crowd.requestMoveTarget(agentOf[c], x, y, z);
        return true;
    }

    /** Attacks of opportunity for walking out of the reach of engaged enemies. */
    private void provokeAlongPath(int c, float toX, float toZ) {
        int agent = agentOf[c];
        segment[0] = crowd.x(agent);
        segment[1] = 0;
        segment[2] = crowd.z(agent);
        segment[3] = toX;
        segment[4] = 0;
        segment[5] = toZ;
        int team = creatures.team(c);
        int n = 0;
        for (int i = 0; i < encounter.size() && n < threatScratch.length; i++) {
            int other = encounter.participant(i);
            int a = agentOf[other];
            if (a < 0 || creatures.team(other) == team || !rules.canAct(other)) {
                continue;
            }
            threatScratch[n] = other;
            threatCircles[n * 3] = crowd.x(a);
            threatCircles[n * 3 + 1] = crowd.z(a);
            threatCircles[n * 3 + 2] = Enemies.REACH + 0.4f;
            n++;
        }
        int crossings =
                TurnBasedNav.threatCrossings(
                        segment,
                        2,
                        threatCircles,
                        n,
                        crossingEnemy,
                        crossingDistance,
                        crossingKind);
        for (int k = 0; k < crossings; k++) {
            if (crossingKind[k] == TurnBasedNav.EXIT) {
                one[0] = threatScratch[crossingEnemy[k]];
                if (encounter.provoke(c, one, 1) > 0) {
                    int e = enemyOf(one[0]);
                    if (e >= 0) {
                        animator.action(
                                enemies.character(e), Characters.ENEMY_ATTACK[enemies.model(e)]);
                    }
                }
            }
        }
    }

    /** The skeleton whose turn it is walks to the nearest party member and strikes. */
    private void aiTick(float dt) {
        int c = aiCreature;
        if (c < 0 || encounter.current() != c) {
            return;
        }
        if (!rules.canAct(c)) {
            aiCreature = -1;
            turnBased.endTurn();
            return;
        }
        int e = enemyOf(c);
        int agent = agentOf[c];
        aiTimer += dt;
        if (aiPhase == 0) {
            // Pick the nearest party member that is still up.
            int best = -1;
            float bestD2 = Float.MAX_VALUE;
            for (int m = 0; m < Party.SIZE; m++) {
                int pc = partyCreature[m];
                if (!rules.canAct(pc)) {
                    continue;
                }
                int pa = party.agent[m];
                float dx = crowd.x(pa) - crowd.x(agent);
                float dz = crowd.z(pa) - crowd.z(agent);
                float d2 = dx * dx + dz * dz;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = m;
                }
            }
            if (best < 0) {
                aiCreature = -1;
                turnBased.endTurn();
                return;
            }
            aiVictim = best;
            float d = (float) Math.sqrt(bestD2);
            if (d <= Enemies.REACH) {
                aiPhase = 2;
            } else {
                float budget = moveLeft[c];
                float walk = Math.min(d - Enemies.REACH * 0.7f, budget);
                int pa = party.agent[best];
                float t = walk / d;
                aiTargetX = crowd.x(agent) + (crowd.x(pa) - crowd.x(agent)) * t;
                aiTargetZ = crowd.z(agent) + (crowd.z(pa) - crowd.z(agent)) * t;
                moveLeft[c] -= walk;
                encounter.useAction(c, ActionBudget.MOVE);
                encounter.reportMovement(c);
                crowd.requestMoveTarget(agent, aiTargetX, crowd.y(agent), aiTargetZ);
                aiPhase = 1;
                aiTimer = 0;
            }
        }
        if (aiPhase == 1) {
            float dx = crowd.x(agent) - aiTargetX;
            float dz = crowd.z(agent) - aiTargetZ;
            float vx = crowd.velocityX(agent);
            float vz = crowd.velocityZ(agent);
            boolean arrived =
                    dx * dx + dz * dz < 0.36f || (aiTimer > 0.5f && vx * vx + vz * vz < 0.01f);
            if (arrived || aiTimer > AI_MOVE_SECONDS) {
                crowd.resetMoveTarget(agent);
                aiPhase = 2;
            } else {
                return;
            }
        }
        if (aiPhase == 2) {
            int pa = party.agent[aiVictim];
            float dx = crowd.x(pa) - crowd.x(agent);
            float dz = crowd.z(pa) - crowd.z(agent);
            if (dx * dx + dz * dz <= (Enemies.REACH + 0.3f) * (Enemies.REACH + 0.3f)) {
                int pc = partyCreature[aiVictim];
                int swings = 0;
                while (canStrike(c) && swings < 6) {
                    strike(c, pc);
                    swings++;
                }
                if (swings > 0 && e >= 0) {
                    enemies.face(e, crowd.x(pa), crowd.z(pa));
                    animator.action(
                            enemies.character(e), Characters.ENEMY_ATTACK[enemies.model(e)]);
                }
            }
            aiCreature = -1;
            turnBased.endTurn();
        }
    }

    // ---- Events -------------------------------------------------------------------------------

    /** Turns the rules' events since the last tick into animation, state and log lines. */
    private void drainEvents() {
        Events ev = rules.events();
        long end = ev.next();
        if (eventsRead < ev.oldest()) {
            eventsRead = ev.oldest();
        }
        for (long s = eventsRead; s < end; s++) {
            int kind = ev.kind(s);
            int actor = ev.actor(s);
            int target = ev.target(s);
            switch (kind) {
                case Events.ATTACK_HIT, Events.CRITICAL -> {
                    log.start()
                            .add(creatures.name(actor))
                            .add(kind == Events.CRITICAL ? " crits " : " hits ")
                            .add(creatures.name(target))
                            .add(" (")
                            .add(ev.total(s))
                            .add(" vs AC ")
                            .add(ev.against(s))
                            .add(")")
                            .end();
                }
                case Events.ATTACK_MISS ->
                        log.start()
                                .add(creatures.name(actor))
                                .add(" misses ")
                                .add(creatures.name(target))
                                .add(" (")
                                .add(ev.total(s))
                                .add(" vs AC ")
                                .add(ev.against(s))
                                .add(")")
                                .end();
                case Events.DAMAGE -> {
                    if (ev.ref(s) != 0 || actor != target) { // not the dying's point a round
                        log.start()
                                .add("  ")
                                .add(creatures.name(target))
                                .add(" takes ")
                                .add(ev.amount(s))
                                .add(" ")
                                .add(
                                        RulesSchema.DAMAGE_TYPES[
                                                Math.clamp(
                                                        ev.against(s),
                                                        0,
                                                        RulesSchema.DAMAGE_TYPES.length - 1)])
                                .add(" (")
                                .add(Math.max(0, creatures.hp(target)))
                                .add(" left)")
                                .end();
                    }
                    int e = enemyOf(target);
                    if (e >= 0) {
                        enemies.flinch(e);
                    }
                }
                case Events.HEAL -> {
                    int m = memberOf(target);
                    if (m >= 0 && creatures.hp(target) > 0 && rules.canAct(target)) {
                        animator.revive(party.character[m]);
                    }
                }
                case Events.SAVE_SUCCESS, Events.SAVE_FAIL ->
                        log.start()
                                .add(creatures.name(target))
                                .add(kind == Events.SAVE_SUCCESS ? " saves (" : " fails the save (")
                                .add(ev.total(s))
                                .add(" vs DC ")
                                .add(ev.against(s))
                                .add(")")
                                .end();
                case Events.CONDITION_ON, Events.CONDITION_OFF -> {
                    if (ev.ref(s) != flatFootedRow) { // everyone starts a fight flat-footed
                        log.start()
                                .add(creatures.name(target))
                                .add(kind == Events.CONDITION_ON ? " is " : " is no longer ")
                                .add(content.name(RulesSchema.CONDITIONS, ev.ref(s)))
                                .end();
                    }
                }
                case Events.DYING, Events.DEATH -> {
                    int m = memberOf(target);
                    int e = enemyOf(target);
                    if (m >= 0 && invulnerableParty) {
                        rules.removeCondition(target, content.find(RulesSchema.CONDITIONS, "dead"));
                        rules.removeCondition(
                                target, content.find(RulesSchema.CONDITIONS, "dying"));
                        creatures.setHp(target, maxHp(target));
                    } else if (m >= 0) {
                        animator.die(party.character[m]);
                        log.start()
                                .add(creatures.name(target))
                                .add(kind == Events.DEATH ? " dies" : " falls")
                                .end();
                    } else if (e >= 0) {
                        enemies.kill(e);
                        disengage(e);
                        log.start()
                                .add(creatures.name(target))
                                .add(" is destroyed (")
                                .add(enemies.alive)
                                .add(" left)")
                                .end();
                    }
                }
                case Events.STABLE ->
                        log.start().add(creatures.name(target)).add(" stabilizes").end();
                case Events.ROUND -> {
                    if (turnBasedMode) {
                        log.start().add("Round ").add(ev.amount(s)).end();
                    }
                }
                case Events.SPELL ->
                        log.start()
                                .add(creatures.name(actor))
                                .add(" casts ")
                                .add(content.name(RulesSchema.SPELLS, ev.ref(s)))
                                .add(" (DC ")
                                .add(ev.against(s))
                                .add(")")
                                .end();
                case Events.OPPORTUNITY ->
                        log.start()
                                .add(creatures.name(actor))
                                .add(" gets an attack of opportunity on ")
                                .add(creatures.name(target))
                                .end();
                default -> {}
            }
        }
        eventsRead = end;
    }

    // ---- Saves (DESIGN §21) -------------------------------------------------------------------

    /**
     * Writes the rules (creatures by content id, the generator), the encounter, the real-time clock
     * and this class's own round state: the mode, the rounds, who is engaged, each creature's
     * attacks and movement left this turn, and the skeleton AI's turn. Allocates nothing.
     */
    void write(ByteWriter w) {
        rules.write(w);
        encounter.write(w);
        w.float32(realTime.clock());
        w.bool(turnBasedMode);
        w.int32(rounds);
        w.int32(engaged);
        w.float32(regenTimer);
        int n = creatures.highWaterMark();
        w.ints(attacksLeft, Math.min(n, attacksLeft.length));
        w.ints(attackIndex, Math.min(n, attackIndex.length));
        w.floats(moveLeft, Math.min(n, moveLeft.length));
        w.int32(aiCreature);
        w.int32(aiPhase);
        w.float32(aiTimer);
        w.float32(aiTargetX);
        w.float32(aiTargetZ);
        w.int32(aiVictim);
    }

    /**
     * Reads what {@link #write} wrote: the creatures come back at their handles (the party at
     * {@code 0..5}, skeleton {@code i} at {@code 6 + i}, as they were built), so {@link
     * #partyCreature} and {@link #enemyCreature} need no translation; the agent of each skeleton is
     * re-read from the stores, since a respawn may have moved it to another slot. The events the
     * rebuild raised are dropped: nothing happened in play.
     */
    void read(ByteReader r) {
        rules.read(r);
        encounter.read(r);
        realTime.setClock(r.float32());
        turnBasedMode = r.bool();
        rounds = r.int32();
        engaged = r.int32();
        regenTimer = r.float32();
        int n = r.intsInto(attacksLeft);
        java.util.Arrays.fill(attacksLeft, n, attacksLeft.length, 0);
        n = r.intsInto(attackIndex);
        java.util.Arrays.fill(attackIndex, n, attackIndex.length, 0);
        n = r.floatsInto(moveLeft);
        java.util.Arrays.fill(moveLeft, n, moveLeft.length, 0);
        aiCreature = r.int32();
        aiPhase = r.int32();
        aiTimer = r.float32();
        aiTargetX = r.float32();
        aiTargetZ = r.float32();
        aiVictim = r.int32();
        for (int i = 0; i < enemies.count; i++) {
            bind(enemyCreature[i], enemies.agent(i), -1, i);
        }
        eventsRead = rules.events().next();
    }
}
