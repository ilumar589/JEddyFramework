/**
 * The dialogue and quest runtime (DESIGN §24): {@link dev.jeddy.script.Interpreter} runs the
 * bytecode conditions and actions of a cooked dialogue over a {@link dev.jeddy.script.ScriptHost},
 * {@link dev.jeddy.script.Conversation} is one dialogue in progress, {@link
 * dev.jeddy.script.Quests} the stages and the journal, {@link dev.jeddy.script.Flags} the named
 * values. Everything runs on the game thread and allocates nothing in play.
 */
package dev.jeddy.script;
