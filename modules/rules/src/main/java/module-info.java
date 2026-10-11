/**
 * The d20 game-mechanics layer: creatures, typed modifiers, dice and checks, effects as data,
 * conditions, encounters with initiative and an action economy, real-time and turn-based drivers.
 * See §20 of docs/DESIGN.md.
 */
module dev.jeddy.rules {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;
    requires transitive dev.jeddy.asset;

    exports dev.jeddy.rules;
}
