package dev.jeddy.editor;

import dev.jeddy.app.AppContext;
import dev.jeddy.app.world.LevelLoader;
import dev.jeddy.app.world.World;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.Vfs;
import dev.jeddy.asset.format.LevelData;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.core.collections.IntList;
import dev.jeddy.core.config.Settings;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.editor.command.AddObjectsCommand;
import dev.jeddy.editor.command.Command;
import dev.jeddy.editor.command.DeleteObjectsCommand;
import dev.jeddy.editor.panel.AssetsPanel;
import dev.jeddy.editor.panel.ConsolePanel;
import dev.jeddy.editor.panel.Dock;
import dev.jeddy.editor.panel.EntitiesPanel;
import dev.jeddy.editor.panel.HistoryPanel;
import dev.jeddy.editor.panel.InspectorPanel;
import dev.jeddy.editor.panel.OutlinerPanel;
import dev.jeddy.editor.panel.Panel;
import dev.jeddy.editor.panel.RulesPanel;
import dev.jeddy.editor.panel.SettingsPanel;
import dev.jeddy.editor.rules.RulesSource;
import dev.jeddy.input.Input;
import dev.jeddy.input.Modifiers;
import dev.jeddy.input.Scancode;
import dev.jeddy.math.IsoCamera;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.Widgets;

import java.io.IOException;
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
    private static final long ROOT = 0xED17L;
    private static final long MENU_BAR = 1;
    private static final long MENU_FILE = 2;
    private static final long MENU_EDIT = 3;
    private static final long MENU_VIEW = 4;
    private static final long MENU_MODE = 5;
    private static final long ITEM = 100;
    private static final long PANEL_ITEM = 200;
    private static final CharSequence[] MODE_NAMES = {"Move (W)", "Rotate (E)", "Scale (R)"};

    private final EditorContext ctx;
    private final Viewport viewport;
    private final Dock dock = new Dock();
    private final ConsolePanel consolePanel = new ConsolePanel();
    private boolean shown;
    private Path file;
    private String fileName = ""; // cached: Path.getFileName allocates
    private LevelFiles.Save saving;
    private final ViewportInput viewportInput = new ViewportInput();
    private final IntList highlighted = new IntList(64);
    private int highlightedVersion = -1;
    private int levelVersionSeen = -1;

    /** Creates an editor with no level. */
    public Editor() {
        ctx = new EditorContext(null, 256);
        viewport = new Viewport(ctx);
        ctx.setEditor(this, viewport);
        dock.add(new OutlinerPanel(), Dock.LEFT);
        dock.add(new AssetsPanel(), Dock.LEFT);
        dock.add(new InspectorPanel(), Dock.RIGHT);
        dock.add(new SettingsPanel(), Dock.RIGHT);
        dock.add(new EntitiesPanel(), Dock.HIDDEN);
        dock.add(new RulesPanel(), Dock.HIDDEN);
        dock.add(consolePanel, Dock.BOTTOM);
        dock.add(new HistoryPanel(), Dock.HIDDEN);
    }

    /** Returns the dock holding the panels. */
    public Dock dock() {
        return dock;
    }

    /** Runs a console command line ({@code help} lists them). */
    public void runCommand(String line) {
        consolePanel.run(ctx, line);
    }

    /** Connects the cooked asset tree and scene loading for the Assets panel. */
    public void attachAssets(Vfs vfs, AssetType<SceneData> scenes) {
        ctx.setAssets(vfs, scenes);
    }

    /**
     * Gives the inspector the game's component schema (DESIGN §22, D109) and the hook a running
     * game uses to re-apply an object's rows when they change; either may be null.
     */
    public void attachSchema(
            dev.jeddy.asset.format.ComponentSchema schema, EditorContext.ComponentSync sync) {
        ctx.setSchema(schema, sync);
    }

    /**
     * Loads a component schema from the game's class output or jar ({@code
     * META-INF/jeddy/components.json} through {@code owner}'s class loader), or returns an empty
     * schema when the game has none.
     */
    public static dev.jeddy.asset.format.ComponentSchema schemaOf(Class<?> owner) {
        try (java.io.InputStream in =
                owner.getResourceAsStream("/" + dev.jeddy.asset.format.ComponentSchema.RESOURCE)) {
            return in == null
                    ? dev.jeddy.asset.format.ComponentSchema.EMPTY
                    : dev.jeddy.asset.format.ComponentSchema.parse(in.readAllBytes());
        } catch (java.io.IOException | RuntimeException e) {
            dev.jeddy.core.log.Log.logger("editor").warn("components.json: " + e.getMessage());
            return dev.jeddy.asset.format.ComponentSchema.EMPTY;
        }
    }

    /**
     * Loads the rules content of {@code dir} ({@code assets/rules}: one JSON file per kind) for the
     * Rules panel; edits go through the history and {@link #saveRules} writes the files back.
     * Returns whether it loaded.
     */
    public boolean attachRules(Path dir) {
        try {
            RulesSource rules = RulesSource.load(dir);
            ctx.setRules(rules);
            ctx.console().info("rules " + dir.toAbsolutePath());
            return true;
        } catch (IOException | RuntimeException e) {
            ctx.console().error("rules " + dir + ": " + e.getMessage());
            return false;
        }
    }

    /** Writes the rules content back to its JSON files; the cooker watcher re-cooks them. */
    public boolean saveRules() {
        RulesSource rules = ctx.rules();
        if (rules == null) {
            return false;
        }
        try {
            rules.save(null);
            ctx.console().info("saved rules to " + rules.directory());
            return true;
        } catch (IOException e) {
            ctx.console().error("saving rules failed: " + e);
            return false;
        }
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
        setFile(file);
        ctx.settings().translateStep = level.settings.gridStep;
        ctx.undo().clear();
        ctx.selection().clear();
        levelVersionSeen = level.version();
        dock.levelChanged(ctx);
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
        setFile(null);
        dock.levelChanged(ctx);
    }

    /** Returns whether a level is being edited. */
    public boolean isOpen() {
        return ctx.level() != null;
    }

    private void setFile(Path path) {
        file = path;
        fileName = path == null || path.getFileName() == null ? "" : path.getFileName().toString();
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

    /**
     * Declares the editor's menu bar, dock and panels into {@code ui} (nothing while hidden): a
     * full-window floating root that lets the pointer through where no panel is, so the viewport
     * underneath gets the clicks.
     */
    public void declare(Ui ui, Widgets widgets) {
        if (!shown) {
            return;
        }
        ctx.setUi(ui, widgets);
        float width = ui.width();
        float height = ui.height();
        ui.box(ROOT)
                .floatingOnScreen()
                .anchor(Ui.TOP_LEFT, Ui.TOP_LEFT)
                .size(width, height)
                .column()
                .gap(0)
                .z(50)
                .noHit()
                .open();
        menuBar(widgets);
        dock.declare(ctx, widgets, widgets.theme.widgetHeight, width, height);
        ui.close();
        viewport.setRegion(dock.regionX(), dock.regionY(), dock.regionW(), dock.regionH());
        viewport.declare(ui);
        ctx.setUi(null, null);
    }

    private void menuBar(Widgets w) {
        Ui ui = w.ui;
        LevelData level = ctx.level();
        w.beginMenuBar(MENU_BAR);
        if (w.menu(MENU_FILE, "File")) {
            if (w.menuItem(ITEM + 1, "Save", "Ctrl+S", level != null && file != null)) {
                save();
            }
            if (w.menuItem(
                    ITEM + 2, "Dump JSON next to the level", null, level != null && file != null)) {
                runCommand("dump");
            }
            if (w.menuItem(ITEM + 4, "Save rules", null, ctx.rules() != null)) {
                saveRules();
            }
            if (w.menuItem(ITEM + 3, "Close editor", "F10", true)) {
                setShown(false);
            }
            w.endMenu();
        }
        if (w.menu(MENU_EDIT, "Edit")) {
            UndoStack undo = ctx.undo();
            if (w.menuItem(ITEM + 10, "Undo", "Ctrl+Z", undo.canUndo())) {
                undo();
            }
            if (w.menuItem(ITEM + 11, "Redo", "Ctrl+Y", undo.canRedo())) {
                redo();
            }
            boolean any = !ctx.selection().isEmpty();
            if (w.menuItem(ITEM + 12, "Duplicate", "Ctrl+D", any)) {
                duplicateSelection();
            }
            if (w.menuItem(ITEM + 13, "Delete", "Del", any)) {
                deleteSelection();
            }
            if (w.menuItem(ITEM + 14, "Select all", "Ctrl+A", level != null)) {
                selectAll();
            }
            if (w.menuItem(ITEM + 15, "Frame selection", "F", level != null)) {
                viewport.frameSelection();
            }
            w.endMenu();
        }
        if (w.menu(MENU_VIEW, "View")) {
            EditorSettings s = ctx.settings();
            if (w.menuCheck(ITEM + 20, "Grid", s.showGrid)) {
                s.showGrid = !s.showGrid;
            }
            if (w.menuCheck(ITEM + 21, "Markers", s.showMarkers)) {
                s.showMarkers = !s.showMarkers;
            }
            if (w.menuCheck(ITEM + 22, "Snap", s.snap)) {
                s.snap = !s.snap;
            }
            w.separator();
            for (int i = 0; i < dock.panels().size(); i++) {
                Panel p = dock.panels().get(i);
                if (w.menuCheck(PANEL_ITEM + i, p.name(), dock.zone(i) != Dock.HIDDEN)) {
                    dock.toggle(i);
                }
            }
            w.endMenu();
        }
        if (w.menu(MENU_MODE, MODE_NAMES[viewport.mode()])) {
            for (int m = 0; m < 3; m++) {
                if (w.menuCheck(ITEM + 30 + m, MODE_NAMES[m], viewport.mode() == m)) {
                    viewport.setMode(m);
                }
            }
            w.endMenu();
        }
        w.spacer();
        // Status: file, dirty mark, selection, snapping.
        if (level != null) {
            ui.text(file == null ? "unsaved level" : fileName)
                    .fontSize(w.theme.fontSizeSmall)
                    .color(w.theme.textDim);
            if (isDirty()) {
                ui.text("*").fontSize(w.theme.fontSizeSmall).color(w.theme.focus);
            }
            ui.text()
                    .append(ctx.selection().size())
                    .append(" selected")
                    .fontSize(w.theme.fontSizeSmall)
                    .color(w.theme.textDim);
        }
        ui.text(ctx.settings().snap ? "snap on" : "snap off")
                .fontSize(w.theme.fontSizeSmall)
                .color(w.theme.textDim);
        w.endMenuBar();
    }

    // ---- Settings persistence -----------------------------------------------------------------

    /** Writes the dock layout and the editor settings into {@code settings} ({@code editor.*}). */
    public void saveSettings(Settings settings) {
        EditorSettings s = ctx.settings();
        settings.set("editor.layout", dock.layout());
        settings.set("editor.snap", s.snap);
        settings.set("editor.translateStep", s.translateStep);
        settings.set("editor.rotateStep", s.rotateStepDegrees);
        settings.set("editor.scaleStep", s.scaleStep);
        settings.set("editor.grid", s.showGrid);
        settings.set("editor.markers", s.showMarkers);
        settings.set("editor.gizmoPixels", s.gizmoPixels);
        settings.set("editor.orbitSpeed", s.orbitSpeed);
    }

    /** Reads what {@link #saveSettings} wrote (missing keys keep the defaults). */
    public void loadSettings(Settings settings) {
        EditorSettings s = ctx.settings();
        dock.setLayout(settings.getString("editor.layout", ""));
        s.snap = settings.getBoolean("editor.snap", s.snap);
        s.translateStep = settings.getFloat("editor.translateStep", s.translateStep);
        s.rotateStepDegrees = settings.getFloat("editor.rotateStep", s.rotateStepDegrees);
        s.scaleStep = settings.getFloat("editor.scaleStep", s.scaleStep);
        s.showGrid = settings.getBoolean("editor.grid", s.showGrid);
        s.showMarkers = settings.getBoolean("editor.markers", s.showMarkers);
        s.gizmoPixels = settings.getFloat("editor.gizmoPixels", s.gizmoPixels);
        s.orbitSpeed = settings.getFloat("editor.orbitSpeed", s.orbitSpeed);
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
        setFile(path);
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
