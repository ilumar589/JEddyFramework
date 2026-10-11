package dev.jeddy.editor.command;

import dev.jeddy.asset.format.LevelData;
import dev.jeddy.editor.EditorContext;

/**
 * Replaces the level's settings block (navmesh and occlusion parameters, sun, exposure,
 * environment, grid step) with a copy. Merges with the next settings change so a dragged slider is
 * one entry.
 *
 * <p>Thread ownership: the game thread.
 */
public final class SetSettingsCommand implements Command {
    private final LevelData.Settings before = new LevelData.Settings();
    private final LevelData.Settings after = new LevelData.Settings();

    /** Captures {@code level}'s settings as before and {@code newSettings} as after. */
    public SetSettingsCommand(LevelData level, LevelData.Settings newSettings) {
        before.copyFrom(level.settings);
        after.copyFrom(newSettings);
    }

    @Override
    public void apply(EditorContext ctx) {
        ctx.level().settings.copyFrom(after);
        ctx.level().touch();
    }

    @Override
    public void revert(EditorContext ctx) {
        ctx.level().settings.copyFrom(before);
        ctx.level().touch();
    }

    @Override
    public String name() {
        return "Change settings";
    }

    @Override
    public boolean mergeWith(Command next) {
        if (next instanceof SetSettingsCommand s) {
            after.copyFrom(s.after);
            return true;
        }
        return false;
    }
}
