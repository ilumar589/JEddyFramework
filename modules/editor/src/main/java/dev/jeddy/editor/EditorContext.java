package dev.jeddy.editor;

import dev.jeddy.app.world.LevelLoader;
import dev.jeddy.app.world.World;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.Vfs;
import dev.jeddy.asset.format.ComponentSchema;
import dev.jeddy.asset.format.LevelData;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.editor.rules.RulesSource;
import dev.jeddy.math.IsoCamera;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.Widgets;

/**
 * What commands and panels see of the editor (DESIGN §19): the level being edited, the loader that
 * mirrors it into the running {@link World}, the selection, the history, the settings, the console
 * and the UI to declare into. One context per {@link Editor}; commands get it on {@code apply} and
 * {@code revert}, panels on {@code draw}.
 *
 * <p>The world is optional (the command tests edit a level with no world): every {@code sync*}
 * method does nothing without a loader showing this level.
 *
 * <p>Thread ownership: the game thread.
 */
public final class EditorContext {
    private LevelData level;
    private LevelLoader loader;
    private World world;
    private final Selection selection = new Selection();
    private final UndoStack undo;
    private final EditorSettings settings = new EditorSettings();
    private final Console console = new Console(256);
    private final IsoCamera camera = new IsoCamera();
    private Ui ui;
    private Widgets widgets;
    private Editor editor;
    private Viewport viewport;
    private Vfs vfs;
    private AssetType<SceneData> scenes;
    private RulesSource rules;
    private ComponentSchema schema = ComponentSchema.EMPTY;
    private ComponentSync componentSync;

    /**
     * What a running game does when the editor changes an object's component rows (DESIGN §22):
     * typically {@code despawn} the object's entity and {@code loadObject} its rows again.
     */
    public interface ComponentSync {
        /** Object {@code objectId}'s rows in {@code level} changed. */
        void componentsChanged(LevelData level, int objectId);
    }

    /** Creates a context editing {@code level} (may be {@code null} until one is opened). */
    public EditorContext(LevelData level, int undoCapacity) {
        this.level = level;
        this.undo = new UndoStack(this, undoCapacity);
    }

    /** Returns the level being edited, or {@code null}. */
    public LevelData level() {
        return level;
    }

    /** Returns the loader showing the level in the world, or {@code null}. */
    public LevelLoader loader() {
        return loader;
    }

    /** Returns the running world, or {@code null}. */
    public World world() {
        return world;
    }

    /** Returns the selection. */
    public Selection selection() {
        return selection;
    }

    /** Returns the history. */
    public UndoStack undo() {
        return undo;
    }

    /** Returns the editor settings (snapping, camera, layout). */
    public EditorSettings settings() {
        return settings;
    }

    /** Returns the console log. */
    public Console console() {
        return console;
    }

    /** Returns the editor's camera (the game's view while the editor is open). */
    public IsoCamera camera() {
        return camera;
    }

    /** Returns the UI context panels declare into (null outside a frame). */
    public Ui ui() {
        return ui;
    }

    /** Returns the widget set on the editor's theme (null outside a frame). */
    public Widgets widgets() {
        return widgets;
    }

    /** Returns the editor this context belongs to. */
    public Editor editor() {
        return editor;
    }

    /** Returns the viewport (camera, picking, gizmos). */
    public Viewport viewport() {
        return viewport;
    }

    /** Returns the virtual file system of cooked assets, or {@code null} (the Assets panel). */
    public Vfs vfs() {
        return vfs;
    }

    /** Returns the scene asset type, or {@code null} (prefab browsing). */
    public AssetType<SceneData> scenes() {
        return scenes;
    }

    /** Returns the rules content being edited (the Rules panel), or {@code null}. */
    public RulesSource rules() {
        return rules;
    }

    /** Returns the game's component schema (the inspector's Components section); never null. */
    public ComponentSchema schema() {
        return schema;
    }

    // ---- Wiring (the Editor) ------------------------------------------------------------------

    void setSchema(ComponentSchema schema, ComponentSync sync) {
        this.schema = schema == null ? ComponentSchema.EMPTY : schema;
        this.componentSync = sync;
    }

    void setRules(RulesSource rules) {
        this.rules = rules;
    }

    void setEditor(Editor editor, Viewport viewport) {
        this.editor = editor;
        this.viewport = viewport;
    }

    void setAssets(Vfs vfs, AssetType<SceneData> scenes) {
        this.vfs = vfs;
        this.scenes = scenes;
    }

    void setLevel(LevelData level) {
        this.level = level;
    }

    void setWorld(World world, LevelLoader loader) {
        this.world = world;
        this.loader = loader;
    }

    void setUi(Ui ui, Widgets widgets) {
        this.ui = ui;
        this.widgets = widgets;
    }

    private boolean showing() {
        return loader != null && loader.level() == level;
    }

    // ---- World synchronisation (called by commands after they change the level) --------------

    /** Tells the world that object {@code id}'s transform changed (placements move their group). */
    public void syncTransform(int id) {
        if (showing() && level.kindOf(id) == LevelData.KIND_PLACEMENT) {
            loader.syncTransform(level.placementIndex(id));
        }
    }

    /** Tells the world that object {@code id}'s flags changed (static, shadows, hidden). */
    public void syncFlags(int id) {
        if (showing() && level.kindOf(id) == LevelData.KIND_PLACEMENT) {
            loader.syncFlags(level.placementIndex(id));
        }
    }

    /** Tells the world that placement {@code id}'s prefab changed (it is re-instantiated). */
    public void syncPrefab(int id) {
        if (showing() && level.kindOf(id) == LevelData.KIND_PLACEMENT) {
            loader.placementReplaced(level.placementIndex(id));
        }
    }

    /**
     * Tells the game that object {@code id}'s component rows changed (when a loader is attached,
     * only while it shows this level).
     */
    public void syncComponents(int id) {
        if (componentSync != null && (loader == null || showing())) {
            componentSync.componentsChanged(level, id);
        }
    }
}
