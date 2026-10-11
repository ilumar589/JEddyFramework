package dev.jeddy.editor.panel;

import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.Vfs;
import dev.jeddy.asset.format.LevelData;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.editor.EditorContext;
import dev.jeddy.editor.Text;
import dev.jeddy.editor.command.AddObjectsCommand;
import dev.jeddy.math.IsoCamera;
import dev.jeddy.ui.TextBuffer;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.Widgets;

import java.util.ArrayList;
import java.util.List;

/**
 * The cooked assets (DESIGN §19): every path the {@link Vfs} knows, filtered by text, in a list;
 * choosing a scene loads it and lists its root nodes, each with a <em>Place</em> button that adds a
 * placement of that prefab at the camera target (on layer {@code prop}, static, casting shadows).
 * The level's own prefabs are listed first for quick placing. Listing the VFS allocates (a
 * refresh), drawing does not.
 *
 * <p>Thread ownership: the game thread.
 */
public final class AssetsPanel extends Panel {
    private static final long FILTER = 1;
    private static final long REFRESH = 2;
    private static final long PREFABS = 3;
    private static final long PLACE = 4;
    private static final long FILES = 5;
    private static final long ROW = 6;
    private static final long NODES = 7;
    private static final long NODE_ROW = 8;
    private static final int MAX_ROWS = 300;

    private final TextBuffer filter = new TextBuffer(64);
    private final List<String> paths = new ArrayList<>();
    private boolean listed;
    private String chosenScene;
    private int sceneHandle = -1;
    private final float[] at = new float[3];

    /** Creates the panel. */
    public AssetsPanel() {
        super("Assets");
    }

    @Override
    public void levelChanged(EditorContext ctx) {
        listed = false;
    }

    @Override
    public void draw(EditorContext ctx, Widgets w) {
        Ui ui = w.ui;
        LevelData level = ctx.level();
        Vfs vfs = ctx.vfs();
        if (!listed && vfs != null) {
            refresh(vfs);
        }
        // The level's prefabs.
        if (level != null && w.treeNode(PREFABS, "Level prefabs", true)) {
            for (int p = 0; p < level.prefabCount(); p++) {
                ui.pushId(p);
                w.row().open();
                String node = level.prefabNode(p);
                ui.text(node.isEmpty() ? level.prefabScene(p) : node);
                ui.text()
                        .append(level.prefabUses(p))
                        .append(" used")
                        .fontSize(w.theme.fontSizeSmall)
                        .color(w.theme.textDim);
                w.spacer();
                if (w.button(PLACE, "Place")) {
                    place(ctx, level, p);
                }
                ui.close();
                ui.popId();
            }
            w.treeEnd();
        }
        w.separator();
        w.row().open();
        w.textField(FILTER, filter, "Filter cooked files");
        if (w.button(REFRESH, "Refresh") && vfs != null) {
            refresh(vfs);
        }
        ui.close();
        if (vfs == null) {
            w.labelDim("No asset system attached");
            return;
        }
        ui.box(FILES)
                .column()
                .widthGrow()
                .height(220)
                .gap(1)
                .padding(2)
                .background(w.theme.inset)
                .radius(w.theme.radius)
                .scroll(false, true)
                .open();
        int shown = 0;
        for (int i = 0; i < paths.size() && shown < MAX_ROWS; i++) {
            String path = paths.get(i);
            if (filter.length() > 0 && !Text.containsIgnoreCase(path, filter)) {
                continue;
            }
            ui.pushId(i);
            boolean chosen = path.equals(chosenScene);
            if (w.selectableRow(ROW, path, chosen, 0) && path.endsWith(".jscene")) {
                choose(ctx, path);
            }
            ui.popId();
            shown++;
        }
        ui.close();
        // The chosen scene's root nodes.
        if (chosenScene != null && level != null) {
            AssetType<SceneData> scenes = ctx.scenes();
            if (scenes == null || sceneHandle < 0) {
                return;
            }
            w.heading(chosenScene);
            if (!scenes.isReady(sceneHandle)) {
                w.labelDim("loading ...");
                return;
            }
            SceneData scene = scenes.get(sceneHandle);
            ui.box(NODES).column().widthGrow().gap(1).open();
            for (int n = 0; n < scene.nodeCount(); n++) {
                if (scene.parent(n) >= 0) {
                    continue;
                }
                ui.pushId(n);
                w.row().open();
                ui.text(scene.nodeName(n));
                w.spacer();
                if (w.button(NODE_ROW, "Place")) {
                    place(ctx, level, level.prefab(chosenScene, scene.nodeName(n)));
                }
                ui.close();
                ui.popId();
            }
            ui.close();
        }
    }

    private void refresh(Vfs vfs) {
        paths.clear();
        vfs.paths(paths);
        paths.sort(null);
        listed = true;
    }

    private void choose(EditorContext ctx, String path) {
        AssetType<SceneData> scenes = ctx.scenes();
        if (scenes == null) {
            return;
        }
        if (sceneHandle >= 0) {
            scenes.release(sceneHandle);
        }
        chosenScene = path;
        sceneHandle = scenes.load(path);
    }

    /** Adds a placement of prefab {@code p} at the camera target and selects it. */
    private void place(EditorContext ctx, LevelData level, int p) {
        IsoCamera cam = ctx.camera();
        at[0] = cam.target.x;
        at[1] = cam.target.y;
        at[2] = cam.target.z;
        String node = level.prefabNode(p);
        AddObjectsCommand add =
                AddObjectsCommand.placement(
                        level,
                        p,
                        node.isEmpty() ? "" : node,
                        "prop",
                        LevelData.PLACEMENT_STATIC | LevelData.PLACEMENT_CASTS_SHADOW,
                        at[0],
                        at[1],
                        at[2]);
        ctx.undo().push(add);
        ctx.selection().select(add.id(0));
        ctx.console().info("placed " + (node.isEmpty() ? level.prefabScene(p) : node));
    }
}
