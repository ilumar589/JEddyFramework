package dev.jeddy.editor;

import dev.jeddy.app.AppContext;
import dev.jeddy.app.world.LevelLoader;
import dev.jeddy.app.world.World;
import dev.jeddy.asset.format.LevelData;
import dev.jeddy.core.collections.IntList;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.editor.command.AddObjectsCommand;
import dev.jeddy.editor.command.Command;
import dev.jeddy.editor.command.DeleteObjectsCommand;
import dev.jeddy.input.Input;
import dev.jeddy.input.Modifiers;
import dev.jeddy.input.Scancode;
import dev.jeddy.math.IsoCamera;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.Widgets;

import java.nio.file.Path;

/**
 * The level editor as a controller a game drives (DESIGN §19, D88): the host creates one, {@link
 * #attach}es its world and level loader, {@link #open}s the level it is showing, and while the
 * editor is {@link #setShown shown} calls {@link #update} from its {@code update}, {@link #declare}
 * when it declares its UI and {@link #extract} when it fills the render packet, using {@link
 * #camera} as the view. Everything the editor does to the level goes through a {@link Command} on
 * the {@link UndoStack}; the {@link LevelLoader} mirrors the changes into the world at once.
 *
 * <p>The per-frame path (the viewport's hover tests, the panels, the gizmo lines) allocates
 * nothing; commands, saving and loading are cold.
 *
 * <p>Thread ownership: the game thread.
 */
public final class Editor {
    private final EditorContext ctx;
    private final Viewport viewport;
    private boolean shown;
    private Path file;
    private LevelFiles.Save saving;
    private final ViewportInput viewportInput = new ViewportInput();
    private final IntList highlighted = new IntList(64);
    private int highlightedVersion = -1;
    private int levelVersionSeen = -1;

    /** Creates an editor with no level. */
    public Editor() {
        ctx = new EditorContext(null, 256);
        viewport = new Viewport(ctx);
    }

    /** Returns the context (level, selection, history, settings, console). */
    public EditorContext context() {
        return ctx;
    }

    /** Returns the viewport (camera control, picking and gizmos). */
    public Viewport viewport() {
        return viewport;
    }

    /** Connects the host's world: placements edited here move there through {@code loader}. */
    public void attach(World world, LevelLoader loader) {
        ctx.setWorld(world, loader);
    }

    /**
     * Starts editing {@code level}, saved to {@code file} (may be {@code null} until "save as").
     * The loader given to {@link #attach} should already show it (or be loading it).
     */
    public void open(LevelData level, Path file) {
        close();
        ctx.setLevel(level);
        this.file = file;
        ctx.settings().translateStep = level.settings.gridStep;
        ctx.undo().clear();
        ctx.selection().clear();
        levelVersionSeen = level.version();
        ctx.console().info("opened " + (file == null ? "a new level" : file.toString()));
    }

    /** Stops editing (the level stays in the world). */
    public void close() {
        if (ctx.level() == null) {
            return;
        }
        clearHighlights();
        ctx.selection().clear();
        ctx.undo().clear();
        ctx.setLevel(null);
        file = null;
    }

    /** Returns whether a level is being edited. */
    public boolean isOpen() {
        return ctx.level() != null;
    }

    /** Returns the file the level is saved to, or {@code null}. */
    public Path file() {
        return file;
    }

    /** Shows or hides the editor (the F10 toggle); hidden, it costs nothing per frame. */
    public void setShown(boolean shown) {
        if (this.shown == shown) {
            return;
        }
        this.shown = shown;
        if (!shown) {
            clearHighlights();
            viewport.cancel();
        } else {
            highlightedVersion = -1;
        }
    }

    /** Returns whether the editor is shown. */
    public boolean isShown() {
        return shown;
    }

    /** Returns the editor's camera: the host renders with it while the editor is shown. */
    public IsoCamera camera() {
        return ctx.camera();
    }

    /** Points the editor camera where {@code gameCamera} looks (called when the editor opens). */
    public void takeCameraFrom(IsoCamera gameCamera) {
        IsoCamera c = ctx.camera();
        c.target.set(gameCamera.target.x, gameCamera.target.y, gameCamera.target.z);
        c.yaw = gameCamera.yaw;
        c.pitch = gameCamera.pitch;
        c.distance = gameCamera.distance;
        c.fovY = gameCamera.fovY;
        c.near = gameCamera.near;
        c.orthographic = gameCamera.orthographic;
        c.orthoHeight = gameCamera.orthoHeight;
    }

    // ---- Per frame ----------------------------------------------------------------------------

    /**
     * One frame of editor input while shown: keyboard shortcuts (unless {@code ui} has the
     * keyboard), then the viewport (camera, picking, gizmos) unless {@code ui} has the pointer.
     * Allocates nothing on an idle frame.
     */
    public void update(AppContext app, Ui ui, float dt) {
        if (!shown) {
            return;
        }
        pollSave();
        Input in = app.input();
        viewportInput.copyFrom(in, ui);
        if (ctx.level() == null) {
            viewport.update(viewportInput, dt);
            return;
        }
        pruneSelection();
        if (!ui.wantsKeyboard()) {
            shortcuts(in);
        }
        viewport.update(viewportInput, dt);
        syncHighlights();
    }

    /** Declares the editor's panels into {@code ui} (nothing while hidden). */
    public void declare(Ui ui, Widgets widgets) {
        if (!shown) {
            return;
        }
        ctx.setUi(ui, widgets);
        viewport.setRegion(0, 0, ui.width(), ui.height());
        viewport.declare(ui);
        ctx.setUi(null, null);
    }

    /** Adds the editor's lines (grid, markers, gizmo) to the packet while shown. */
    public void extract(RenderPacket packet) {
        if (!shown) {
            return;
        }
        viewport.extract(packet);
    }

    private void shortcuts(Input in) {
        int mods = in.modifiers();
        boolean ctrl = (mods & Modifiers.CTRL) != 0;
        boolean shift = (mods & Modifiers.SHIFT) != 0;
        if (ctrl && in.wasKeyPressed(Scancode.Z)) {
            if (shift) {
                redo();
            } else {
                undo();
            }
        } else if (ctrl && in.wasKeyPressed(Scancode.Y)) {
            redo();
        } else if (ctrl && in.wasKeyPressed(Scancode.S)) {
            save();
        } else if (ctrl && in.wasKeyPressed(Scancode.D)) {
            duplicateSelection();
        } else if (ctrl && in.wasKeyPressed(Scancode.A)) {
            selectAll();
        } else if (in.wasKeyPressed(Scancode.DELETE) || in.wasKeyPressed(Scancode.BACKSPACE)) {
            deleteSelection();
        } else if (in.wasKeyPressed(Scancode.ESCAPE)) {
            if (viewport.isDragging()) {
                viewport.cancel();
            } else {
                ctx.selection().clear();
            }
        } else if (!ctrl && in.wasKeyPressed(Scancode.W)) {
            viewport.setMode(Viewport.MODE_TRANSLATE);
        } else if (!ctrl && in.wasKeyPressed(Scancode.E)) {
            viewport.setMode(Viewport.MODE_ROTATE);
        } else if (!ctrl && in.wasKeyPressed(Scancode.R)) {
            viewport.setMode(Viewport.MODE_SCALE);
        } else if (!ctrl && in.wasKeyPressed(Scancode.F)) {
            viewport.frameSelection();
        } else if (!ctrl && in.wasKeyPressed(Scancode.G)) {
            ctx.settings().showGrid = !ctx.settings().showGrid;
        } else if (!ctrl && in.wasKeyPressed(Scancode.X)) {
            ctx.settings().snap = !ctx.settings().snap;
        }
    }

    /** Drops selected ids whose objects no longer exist (after an undo of an add). */
    private void pruneSelection() {
        LevelData level = ctx.level();
        if (level.version() == levelVersionSeen) {
            return;
        }
        levelVersionSeen = level.version();
        Selection sel = ctx.selection();
        for (int i = sel.size() - 1; i >= 0; i--) {
            if (level.kindOf(sel.get(i)) == LevelData.KIND_NONE) {
                sel.remove(sel.get(i));
            }
        }
    }

    /** Outlines the selected placements' groups in the world; clears the outlines of the rest. */
    private void syncHighlights() {
        Selection sel = ctx.selection();
        if (sel.version() == highlightedVersion || ctx.loader() == null) {
            return;
        }
        highlightedVersion = sel.version();
        clearHighlights();
        LevelData level = ctx.level();
        World world = ctx.world();
        LevelLoader loader = ctx.loader();
        for (int i = 0; i < sel.size(); i++) {
            int id = sel.get(i);
            if (level.kindOf(id) != LevelData.KIND_PLACEMENT) {
                continue;
            }
            int root = loader.rootOf(id);
            if (root != Handles.NULL) {
                world.setGroupHighlight(
                        root,
                        id == sel.primary()
                                ? RenderPacket.HIGHLIGHT_SELECTED
                                : RenderPacket.HIGHLIGHT_HOVER);
                highlighted.add(id);
            }
        }
    }

    private void clearHighlights() {
        LevelLoader loader = ctx.loader();
        if (loader != null) {
            for (int i = 0; i < highlighted.size(); i++) {
                int root = loader.rootOf(highlighted.get(i));
                if (root != Handles.NULL) {
                    ctx.world().setGroupHighlight(root, 0);
                }
            }
        }
        highlighted.clear();
        highlightedVersion = -1;
    }

    // ---- Actions (menus, shortcuts and panels call these) -------------------------------------

    /** Applies {@code command} and records it; {@code merge} folds it into the previous entry. */
    public void push(Command command, boolean merge) {
        ctx.undo().push(command, merge);
    }

    /** Reverts the last command. */
    public void undo() {
        if (ctx.undo().undo()) {
            ctx.console().info("undo " + ctx.undo().redoName());
        }
    }

    /** Re-applies the last undone command. */
    public void redo() {
        if (ctx.undo().redo()) {
            ctx.console().info("redo " + ctx.undo().undoName());
        }
    }

    /** Deletes the selected objects. */
    public void deleteSelection() {
        Selection sel = ctx.selection();
        if (sel.isEmpty()) {
            return;
        }
        push(new DeleteObjectsCommand(ctx.level(), sel.ids().array(), sel.size()), false);
        sel.clear();
    }

    /** Duplicates the selected objects one grid step along +X and selects the copies. */
    public void duplicateSelection() {
        Selection sel = ctx.selection();
        if (sel.isEmpty()) {
            return;
        }
        AddObjectsCommand add =
                AddObjectsCommand.duplicate(
                        ctx.level(),
                        sel.ids().array(),
                        sel.size(),
                        ctx.settings().translateStep,
                        0,
                        0);
        push(add, false);
        sel.clear();
        for (int i = 0; i < add.count(); i++) {
            sel.add(add.id(i));
        }
    }

    /** Selects every object of the level. */
    public void selectAll() {
        LevelData level = ctx.level();
        Selection sel = ctx.selection();
        sel.clear();
        for (int i = 0; i < level.placementCount(); i++) {
            sel.add(level.placementId(i));
        }
        for (int i = 0; i < level.lightCount(); i++) {
            sel.add(level.lightId(i));
        }
        for (int i = 0; i < level.spawnCount(); i++) {
            sel.add(level.spawnId(i));
        }
        for (int i = 0; i < level.triggerCount(); i++) {
            sel.add(level.triggerId(i));
        }
    }

    // ---- Files --------------------------------------------------------------------------------

    /** Returns whether the level has changes since it was opened or saved. */
    public boolean isDirty() {
        return ctx.undo().isDirty();
    }

    /** Writes the level to its file (nothing without one; use {@link #saveAs}). */
    public void save() {
        if (file != null) {
            saveAs(file);
        } else {
            ctx.console().warn("no file to save to");
        }
    }

    /** Writes the level to {@code path}, which becomes its file. */
    public void saveAs(Path path) {
        if (ctx.level() == null) {
            return;
        }
        if (saving != null && !saving.isDone()) {
            ctx.console().warn("a save is still in progress");
            return;
        }
        file = path;
        saving = LevelFiles.save(ctx.level(), path);
        ctx.undo().markSaved();
    }

    /** Returns whether a save is in progress. */
    public boolean isSaving() {
        return saving != null && !saving.isDone();
    }

    private void pollSave() {
        if (saving != null && saving.isDone()) {
            Throwable error = saving.error();
            if (error == null) {
                ctx.console().info("saved " + saving.path());
            } else {
                ctx.console().error("save failed: " + error);
            }
            saving = null;
        }
    }
}
