package dev.jeddy.rules;

import dev.jeddy.core.collections.IntList;

/**
 * A fight (DESIGN §20, D100): the participants in initiative order, rounds and turns, each
 * creature's {@link ActionBudget}, and attacks of opportunity when the game reports a provoking
 * action. The encounter is a state machine the drivers step ({@link TurnBasedDriver} waits for
 * orders, {@link RealTimeDriver} runs a clock), so a fight switches between the modes without
 * losing anything: the order, the round, whose turn it is and what is left of it live here.
 *
 * <p>Turn order: initiative is {@code d20 + initiative modifier} rolled at {@link #start} (or when
 * a creature {@link #join}s), ties go to the higher Dexterity, then to whoever joined first.
 * Everyone starts flat-footed until their first turn. {@link #nextTurn} ends the current turn
 * (durations tick) and starts the next; at the end of the order a new round begins. Creatures that
 * cannot act still get their turn so their durations tick and the dying roll to stabilize; the
 * drivers skip asking for their orders.
 *
 * <p>Thread ownership: the game thread. Allocates only when a list grows.
 */
public final class Encounter {
    private final Rules rules;
    private final ActionBudget budget = new ActionBudget();
    private final IntList order = new IntList(16); // creatures in initiative order
    private final IntList initiative = new IntList(16); // parallel to order
    private final IntList joined = new IntList(16); // parallel: join sequence, the last tie-break
    private int joinCounter;
    private int round;
    private int turnIndex = -1;
    private boolean active;
    private boolean currentLeft; // the creature at turnIndex left; do not end its turn again
    private int[] teamScratch = new int[8];

    /** Creates an empty encounter over {@code rules}. */
    public Encounter(Rules rules) {
        this.rules = rules;
    }

    public Rules rules() {
        return rules;
    }

    public ActionBudget budget() {
        return budget;
    }

    /** Returns whether {@link #start} was called and {@link #end} was not. */
    public boolean isActive() {
        return active;
    }

    /** The current round, from 1; 0 before the fight starts. */
    public int round() {
        return round;
    }

    /** The index in the order of the creature whose turn it is, or -1 before the first turn. */
    public int turnIndex() {
        return turnIndex;
    }

    /** The creature whose turn it is, or -1. */
    public int current() {
        return turnIndex >= 0 && turnIndex < order.size() ? order.get(turnIndex) : -1;
    }

    /** The number of participants. */
    public int size() {
        return order.size();
    }

    /** The {@code i}th creature in initiative order. */
    public int participant(int i) {
        return order.get(i);
    }

    /** The initiative total of the {@code i}th creature in the order. */
    public int initiativeAt(int i) {
        return initiative.get(i);
    }

    /** The position of {@code creature} in the order, or -1 if it is not in the fight. */
    public int indexOf(int creature) {
        return order.indexOf(creature);
    }

    /** Returns whether {@code creature} is in the fight. */
    public boolean contains(int creature) {
        return order.indexOf(creature) >= 0;
    }

    /**
     * Adds {@code creature} before the fight starts; it rolls initiative at {@link #start}. Adding
     * during the fight is {@link #join}.
     */
    public void add(int creature) {
        if (active) {
            join(creature);
            return;
        }
        if (!contains(creature)) {
            order.add(creature);
            initiative.add(0);
            joined.add(joinCounter++);
        }
    }

    /**
     * Rolls initiative for everyone, sorts the order, makes everyone flat-footed, starts round 1.
     */
    public void start() {
        for (int i = 0; i < order.size(); i++) {
            int c = order.get(i);
            initiative.set(i, rules.rollInitiative(c));
            rules.setFlatFooted(c);
            budget.clear(c);
        }
        sort();
        round = 1;
        turnIndex = -1;
        currentLeft = false;
        active = true;
        rules.events().add(Events.ROUND, -1, -1, 0, 0, 0, round, 0);
    }

    /**
     * Brings {@code creature} into a running fight: it rolls initiative and slots into the order
     * (acting this round if its place is still to come, otherwise next round), flat-footed until
     * its first turn.
     */
    public void join(int creature) {
        if (contains(creature)) {
            return;
        }
        int init = rules.rollInitiative(creature);
        int seq = joinCounter++;
        int at = order.size();
        for (int i = 0; i < order.size(); i++) {
            if (before(init, rules.creatures().ability(creature, Stat.DEX), seq, i)) {
                at = i;
                break;
            }
        }
        order.insert(at, creature);
        initiative.insert(at, init);
        joined.insert(at, seq);
        if (at <= turnIndex) {
            turnIndex++; // the current creature keeps its turn
        }
        rules.setFlatFooted(creature);
        budget.clear(creature);
    }

    /**
     * Takes {@code creature} out of the fight (it keeps its state in the rules). If it was its
     * turn, the next call to {@link #nextTurn} starts the creature that followed it.
     */
    public void leave(int creature) {
        int i = order.indexOf(creature);
        if (i < 0) {
            return;
        }
        order.removeAt(i);
        initiative.removeAt(i);
        joined.removeAt(i);
        if (i == turnIndex) {
            currentLeft = true; // its turn is not ended again by nextTurn
        }
        if (i <= turnIndex) {
            turnIndex--;
        }
    }

    /** Ends the fight: no more turns; conditions and budgets stay as they are. */
    public void end() {
        active = false;
    }

    /**
     * Ends the current turn (durations tick) and starts the next one, beginning a new round after
     * the last creature of the order. Returns the creature whose turn it now is, or -1 when the
     * fight is not active or has no participants.
     */
    public int nextTurn() {
        if (!active || order.isEmpty()) {
            return -1;
        }
        int c = current();
        if (c >= 0 && !currentLeft) {
            rules.endTurn(c);
        }
        currentLeft = false;
        turnIndex++;
        if (turnIndex >= order.size()) {
            turnIndex = 0;
            round++;
            rules.events().add(Events.ROUND, -1, -1, 0, 0, 0, round, 0);
        }
        c = order.get(turnIndex);
        rules.startTurn(c);
        if (rules.canAct(c)) {
            budget.reset(c);
        } else {
            budget.clear(c);
        }
        return c;
    }

    // ---- Actions ------------------------------------------------------------------------------

    /** Returns whether {@code creature} still has {@code action} ({@code ActionBudget} bits). */
    public boolean hasAction(int creature, int action) {
        return rules.canAct(creature) && budget.has(creature, action);
    }

    /** Spends {@code action} of {@code creature}'s budget; returns whether it could. */
    public boolean useAction(int creature, int action) {
        return rules.canAct(creature) && budget.use(creature, action);
    }

    /** Records that {@code creature} moved on the ground (no 5-foot step this turn). */
    public void reportMovement(int creature) {
        budget.movedThisTurn(creature);
    }

    /**
     * Resolves the attacks of opportunity {@code provoker} draws from {@code threateners[0, n)}
     * (the game reports who threatens the square it left or the action it took): each threatening
     * enemy that can act and has an attack of opportunity left this round makes one. Returns how
     * many attacks were made.
     */
    public int provoke(int provoker, int[] threateners, int n) {
        int made = 0;
        int team = rules.creatures().team(provoker);
        for (int i = 0; i < n; i++) {
            int t = threateners[i];
            if (t == provoker || rules.creatures().team(t) == team || !rules.canAct(t)) {
                continue;
            }
            if (rules.opportunityAttacksLeft(t) > 0) {
                rules.attackOfOpportunity(t, provoker);
                made++;
            }
        }
        return made;
    }

    // ---- Outcome ------------------------------------------------------------------------------

    /** The number of teams with at least one creature that can still act. */
    public int teamsStanding() {
        int n = 0;
        for (int i = 0; i < order.size(); i++) {
            int c = order.get(i);
            if (!rules.canAct(c)) {
                continue;
            }
            int team = rules.creatures().team(c);
            boolean seen = false;
            for (int k = 0; k < n; k++) {
                if (teamScratch[k] == team) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                if (n == teamScratch.length) {
                    teamScratch = java.util.Arrays.copyOf(teamScratch, n * 2);
                }
                teamScratch[n++] = team;
            }
        }
        return n;
    }

    /** Returns whether at most one team can still act. */
    public boolean isOver() {
        return teamsStanding() <= 1;
    }

    /** The team left standing when the fight is over, or -1 (nobody, or not over). */
    public int winner() {
        if (teamsStanding() != 1) {
            return -1;
        }
        for (int i = 0; i < order.size(); i++) {
            if (rules.canAct(order.get(i))) {
                return rules.creatures().team(order.get(i));
            }
        }
        return -1;
    }

    // ---- Order --------------------------------------------------------------------------------

    /** Returns whether ({@code init}, {@code dex}, {@code seq}) sorts before entry {@code i}. */
    private boolean before(int init, int dex, int seq, int i) {
        int oi = initiative.get(i);
        if (init != oi) {
            return init > oi;
        }
        int od = rules.creatures().ability(order.get(i), Stat.DEX);
        if (dex != od) {
            return dex > od;
        }
        return seq < joined.get(i);
    }

    /** Insertion sort of the three parallel lists (small fights; no allocation). */
    private void sort() {
        for (int i = 1; i < order.size(); i++) {
            int c = order.get(i);
            int init = initiative.get(i);
            int seq = joined.get(i);
            int dex = rules.creatures().ability(c, Stat.DEX);
            int j = i - 1;
            while (j >= 0 && before(init, dex, seq, j)) {
                order.set(j + 1, order.get(j));
                initiative.set(j + 1, initiative.get(j));
                joined.set(j + 1, joined.get(j));
                j--;
            }
            order.set(j + 1, c);
            initiative.set(j + 1, init);
            joined.set(j + 1, seq);
        }
    }
}
