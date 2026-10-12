/**
 * Dialogue and quests: the compiler of the {@code .dlg}/{@code .quest} text to a graph with
 * bytecode conditions, the stack-machine interpreter, conversations, quests and flags. Reaches the
 * game through {@code ScriptHost}. See §24 of docs/DESIGN.md.
 */
module dev.jeddy.script {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.asset;

    exports dev.jeddy.script;
    exports dev.jeddy.script.compile;
}
