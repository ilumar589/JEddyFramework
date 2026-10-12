/**
 * The compiler of the {@code .dlg} and {@code .quest} text formats (DESIGN §24): {@link
 * dev.jeddy.script.compile.DialogueCompiler} and {@link dev.jeddy.script.compile.QuestCompiler}
 * turn a script into {@code asset.format.DialogueData} and {@code QuestData}, with expressions as
 * stack-machine bytecode and every error reported with its line and column. Pure Java; used by the
 * cooker and by the editor's live validation.
 */
package dev.jeddy.script.compile;
