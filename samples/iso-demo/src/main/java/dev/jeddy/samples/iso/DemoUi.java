package dev.jeddy.samples.iso;

import dev.jeddy.app.AppContext;
import dev.jeddy.app.ui.UiBridge;
import dev.jeddy.app.ui.UiFonts;
import dev.jeddy.asset.AssetManager;
import dev.jeddy.asset.AssetType;
import dev.jeddy.core.id.StrId;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.material.GpuTexture;
import dev.jeddy.ui.Box;
import dev.jeddy.ui.Theme;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.UiKey;
import dev.jeddy.ui.Widgets;
import dev.jeddy.window.VSync;
import dev.jeddy.window.WindowCommands;
import dev.jeddy.window.WindowMode;

/**
 * The demo's screens (TASKS M13-07), built with the immediate-mode UI every frame: a status panel,
 * party portraits with health bars, an inventory with drag and drop and tooltips, a combat log, a
 * pause menu with options, the loading screen and the centre messages. {@link DemoGame} fills the
 * public fields before {@link #declare} and reads back what the player did (selection clicks,
 * option changes) from the same fields.
 *
 * <p>The game thread builds the UI ({@link #beginFrame}, {@link #declare}) and lays it out into the
 * packet in {@link #extract}; the two times are reported by the status panel and the perf capture.
 * Allocates nothing per frame after the fonts load.
 *
 * <p>Thread ownership: the game thread.
 */
final class DemoUi {
    private static final long STATUS = StrId.of("status");
    private static final long PORTRAIT = StrId.of("portrait");
    private static final long INVENTORY = StrId.of("inventory");
    private static final long SLOT = StrId.of("slot");
    private static final long SORT = StrId.of("sort");
    private static final long CLOSE = StrId.of("close");
    private static final long LOG = StrId.of("log");
    private static final long PAUSE = StrId.of("pause");
    private static final long RESUME = StrId.of("resume");
    private static final long OPTIONS = StrId.of("options");
    private static final long QUIT = StrId.of("quit");
    private static final long VOLUME = StrId.of("volume");
    private static final long AMBIENCE = StrId.of("ambience");
    private static final long VSYNC = StrId.of("vsync");
    private static final long MODE = StrId.of("mode");
    private static final long PROFILER = StrId.of("profiler");
    private static final long FORMATION = StrId.of("formation");
    private static final long TURNS = StrId.of("turns");
    private static final long HELP = StrId.of("help");

    private static final int DRAG_ITEM = 1;
    private static final int SLOT_SIZE = 44;
    private static final int COLUMNS = 6;
    private static final int ROWS = 4;

    /** Inventory items: name, description, badge color. */
    private static final String[] ITEM_NAMES = {
        "Healing Potion", "Iron Sword", "Oak Shield", "Torch", "Lockpicks", "Spellbook",
        "Rope", "Silver Key", "Dried Rations", "Scroll of Fire", "Amulet", "Gold Coins"
    };

    private static final String[] ITEM_HINTS = {
        "Restores 20 hit points when drunk.",
        "A plain blade. 1d8 slashing.",
        "Heavy, dented, still useful. +2 armor.",
        "Lights the way for an hour.",
        "For the quiet approach.",
        "Fireball and three lesser cantrips.",
        "Twenty meters of hemp rope.",
        "Opens one of the crypt's locked doors.",
        "Tastes like dust. Keeps you going.",
        "One use. Best read from a distance.",
        "Faintly warm to the touch.",
        "Forty-two pieces, give or take."
    };

    private static final int[] ITEM_COLORS = {
        0xFFE05A5A,
        0xFFB0B8C8,
        0xFF8C6A3F,
        0xFFF0A040,
        0xFF9AA0A6,
        0xFF7A5CC8,
        0xFFC8A060,
        0xFFD8D8E8,
        0xFFA07A40,
        0xFFE07830,
        0xFF50C0A0,
        0xFFF0C040
    };

    private static final CharSequence[] MODES = {"Windowed", "Borderless", "Fullscreen"};
    private static final String HELP_TEXT =
            "LMB select / move · RMB move · drag box · 1-6 Tab · G formation · F fireball"
                    + " · T range · I inventory · Space pause · Esc menu · F3 profiler";

    final Ui ui = new Ui();
    final Theme theme = Theme.dark();
    final Widgets w;
    final UiBridge bridge;
    final UiFonts fonts;
    final CombatLog log = new CombatLog(64, 96);

    // Filled by the game each frame.
    boolean loading;
    float loadProgress;
    boolean simulationPaused;
    boolean targeting;
    boolean turnBased;
    float moveCost = -1;
    float moveBudget;
    boolean perf;
    boolean measuring;
    float perfRemaining;
    int selectedCount;
    int enemiesAlive;
    int enemiesVisible;
    int formation;
    int instances;
    int lights;
    double gameMs;
    double mainMs;
    boolean boxSelecting;
    float boxX0;
    float boxY0;
    float boxX1;
    float boxY1;
    boolean profiler;
    boolean vsync;
    int windowMode;
    boolean shift;

    // Read back by the game.
    boolean menuOpen;
    boolean inventoryOpen;
    boolean quitRequested;
    int clickedMember = -1;
    boolean formationChanged;
    boolean profilerChanged;
    boolean turnBasedChanged;
    float masterVolume = 1;
    boolean ambience = true;
    boolean volumeChanged;

    private final int[] slots = new int[COLUMNS * ROWS];
    private int logVersion = -1;
    private long buildStart;
    private long beginNanos;
    private long buildNanos;
    private long layoutNanos;
    private final AppContext ctx;

    /**
     * Creates the demo UI; {@code textures} may be {@code null} (headless), in which case the
     * metrics-only font lays text out and nothing is drawn.
     */
    DemoUi(AppContext ctx, AssetManager assets, AssetType<GpuTexture> textures) {
        this.ctx = ctx;
        w = new Widgets(ui, theme);
        bridge = new UiBridge(ctx, ui);
        w.clipboard = bridge.clipboard();
        if (textures != null) {
            fonts = new UiFonts(ui, assets, textures);
            fonts.load("engine/fonts/inter");
        } else {
            fonts = null;
        }
        for (int i = 0; i < slots.length; i++) {
            slots[i] = i < ITEM_NAMES.length ? i : -1;
        }
    }

    /** Copies the input and begins the frame; call before the game reads the pointer. */
    void beginFrame(float dt) {
        long t0 = System.nanoTime();
        if (fonts != null) {
            fonts.update();
        }
        bridge.beginFrame(dt);
        clickedMember = -1;
        formationChanged = false;
        profilerChanged = false;
        turnBasedChanged = false;
        volumeChanged = false;
        shift = (ui.input().modifiers & UiKey.MOD_SHIFT) != 0;
        beginNanos = System.nanoTime() - t0;
    }

    /** Declares every screen for this frame. */
    void declare(Party party) {
        buildStart = System.nanoTime();
        if (loading) {
            loadingScreen();
            buildNanos = beginNanos + System.nanoTime() - buildStart;
            return;
        }
        statusPanel();
        messages();
        portraits(party);
        combatLog();
        if (inventoryOpen) {
            inventory();
        }
        if (menuOpen) {
            pauseMenu();
        }
        if (boxSelecting) {
            ui.box()
                    .floatingAt(boxX0, boxY0)
                    .unclamped()
                    .size(boxX1 - boxX0, boxY1 - boxY0)
                    .background(0x2050FF80)
                    .border(0xFF50FF80, 1)
                    .noHit();
        }
        ui.box(HELP).floatingOnScreen().anchor(Ui.BOTTOM, Ui.BOTTOM).offset(0, -4).noHit().open();
        w.small(HELP_TEXT).color(theme.textDim);
        ui.close();
        buildNanos = beginNanos + System.nanoTime() - buildStart;
    }

    /** Lays the frame out into the packet. */
    void extract(RenderPacket p) {
        long t0 = System.nanoTime();
        ui.endFrame(p.ui);
        layoutNanos = System.nanoTime() - t0;
    }

    /** Nanoseconds the last frame's input copy, interaction resolution and declaration took. */
    long buildNanos() {
        return buildNanos;
    }

    /** Nanoseconds the last frame's layout and draw-list emission took. */
    long layoutNanos() {
        return layoutNanos;
    }

    // ---- Screens ----------------------------------------------------------------------------

    private void loadingScreen() {
        ui.box()
                .floatingOnScreen()
                .anchor(Ui.MIDDLE, Ui.MIDDLE)
                .column()
                .gap(8)
                .padding(16)
                .width(280)
                .background(theme.panel)
                .border(theme.panelBorder, 1)
                .radius(6)
                .open();
        w.heading("Loading the crypt");
        w.label()
                .append("Assets ")
                .append(Math.round(loadProgress * 100))
                .append('%')
                .color(theme.textDim);
        w.progress(loadProgress);
        ui.close();
    }

    private void statusPanel() {
        ui.box(STATUS)
                .floatingOnScreen()
                .anchor(Ui.TOP_RIGHT, Ui.TOP_RIGHT)
                .offset(-12, 12)
                .column()
                .gap(2)
                .padding(10, 8)
                .width(300)
                .background(theme.panel)
                .border(theme.panelBorder, 1)
                .radius(theme.radius)
                .open();
        value("Party selected").append(selectedCount).append(" / ").append(Party.SIZE);
        ui.close();
        value("Skeletons").append(enemiesAlive).append("  seen ").append(enemiesVisible);
        ui.close();
        value("Formation").append(Party.FORMATION_NAMES[formation]);
        ui.close();
        value("Instances").append(instances).append("  lights ").append(lights);
        ui.close();
        value("FPS")
                .append(mainMs > 0 ? (int) Math.round(1000 / mainMs) : 0)
                .append("  main ")
                .append((float) mainMs, 1)
                .append(" ms  game ")
                .append((float) gameMs, 1)
                .append(" ms");
        ui.close();
        value("UI build")
                .append(buildNanos * 1e-6f, 2)
                .append(" ms  layout ")
                .append(layoutNanos * 1e-6f, 2)
                .append(" ms");
        ui.close();
        if (perf) {
            w.label()
                    .append(measuring ? "Perf measuring " : "Perf warm-up ")
                    .append((int) Math.max(0, perfRemaining))
                    .append(" s")
                    .color(theme.accent);
        }
        ui.close();
    }

    /** Opens a label/value row and returns the value text; the caller appends and closes. */
    private Box value(String label) {
        w.row().widthGrow().open();
        w.small(label).color(theme.textDim);
        w.spacer();
        return w.small("");
    }

    private void messages() {
        ui.box()
                .floatingOnScreen()
                .anchor(Ui.TOP, Ui.TOP)
                .offset(0, 16)
                .column()
                .gap(4)
                .align(Ui.CENTER, Ui.START)
                .noHit()
                .open();
        if (simulationPaused && !menuOpen) {
            w.heading("PAUSED").color(theme.accent);
        }
        if (targeting) {
            w.label("Fireball: click to cast, right click cancels").color(theme.accent);
        }
        if (turnBased) {
            if (moveCost >= 0) {
                w.label()
                        .append("Move cost ")
                        .append(moveCost, 1)
                        .append(" / ")
                        .append(moveBudget, 1)
                        .append(" m");
            } else {
                w.label("Out of range").color(theme.textDim);
            }
        }
        ui.close();
    }

    private void portraits(Party party) {
        ui.box()
                .floatingOnScreen()
                .anchor(Ui.BOTTOM_LEFT, Ui.BOTTOM_LEFT)
                .offset(12, -28)
                .row()
                .gap(6)
                .noHit()
                .open();
        for (int i = 0; i < Party.SIZE; i++) {
            ui.pushId(i);
            boolean selected = party.selected[i];
            boolean hovered = ui.hovered(PORTRAIT) || party.hovered == i;
            ui.box(PORTRAIT)
                    .column()
                    .gap(4)
                    .padding(6)
                    .width(84)
                    .align(Ui.CENTER, Ui.START)
                    .background(hovered ? theme.widgetHover : theme.panel)
                    .border(selected ? theme.accent : theme.panelBorder, selected ? 2 : 1)
                    .radius(theme.radius)
                    .hit()
                    .open();
            ui.box()
                    .size(40, 40)
                    .radius(20)
                    .background(Characters.PARTY_COLORS[i])
                    .align(Ui.CENTER, Ui.CENTER)
                    .noHit()
                    .open();
            w.heading(Characters.PARTY_INITIALS[i]).color(0xFF101114);
            ui.close();
            w.small(Characters.PARTY_NAMES[i]);
            float hp = party.hp[i] / Party.MAX_HP;
            w.progress(hp, hp > 0.5f ? 0xFF4CC26A : hp > 0.25f ? 0xFFE0A030 : 0xFFE05A5A, 6);
            w.small("")
                    .append((int) party.hp[i])
                    .append(" / ")
                    .append((int) Party.MAX_HP)
                    .color(theme.textDim);
            ui.close();
            if (ui.clicked(PORTRAIT)) {
                clickedMember = i;
            }
            w.tooltip(PORTRAIT, Characters.PARTY_NAMES[i]);
            ui.popId();
        }
        ui.close();
    }

    private void combatLog() {
        boolean scrollDown = log.version() != logVersion;
        logVersion = log.version();
        if (scrollDown) {
            ui.setScroll(LOG, 1, Float.MAX_VALUE);
        }
        ui.box()
                .floatingOnScreen()
                .anchor(Ui.BOTTOM_RIGHT, Ui.BOTTOM_RIGHT)
                .offset(-12, -28)
                .column()
                .gap(4)
                .padding(8)
                .width(340)
                .height(150)
                .background(theme.panel)
                .border(theme.panelBorder, 1)
                .radius(theme.radius)
                .open();
        w.small("Combat log").color(theme.textDim);
        w.scrollColumn(LOG).gap(1).widthGrow().heightGrow().open();
        int n = log.count();
        for (int i = 0; i < n; i++) {
            ui.box().row().gap(6).widthGrow().open();
            w.small("")
                    .append((int) log.time(i))
                    .append('s')
                    .color(theme.textDim)
                    .width(36)
                    .textAlign(Ui.END);
            w.small("").append(log.chars(i), 0, log.length(i)).wrap().widthGrow();
            ui.close();
        }
        ui.close();
        ui.close();
    }

    private void inventory() {
        w.beginWindow(INVENTORY, "Inventory", 120, 80, 0, 0);
        w.row().widthGrow().open();
        w.labelDim("Drag items between slots; hover for details.");
        w.spacer();
        if (w.button(SORT, "Sort")) {
            sortSlots();
        }
        if (w.button(CLOSE, "Close")) {
            inventoryOpen = false;
        }
        ui.close();
        ui.box().column().gap(4).open();
        for (int r = 0; r < ROWS; r++) {
            ui.box().row().gap(4).open();
            for (int c = 0; c < COLUMNS; c++) {
                int s = r * COLUMNS + c;
                ui.pushId(s);
                slot(s);
                ui.popId();
            }
            ui.close();
        }
        ui.close();
        w.endWindow();
    }

    private void slot(int s) {
        int item = slots[s];
        boolean over = ui.dropHover(SLOT);
        boolean draggingThis = ui.dragging() && ui.dragKind() == DRAG_ITEM && ui.dragValue() == s;
        Box b =
                ui.box(SLOT)
                        .size(SLOT_SIZE, SLOT_SIZE)
                        .radius(theme.radius)
                        .align(Ui.CENTER, Ui.CENTER)
                        .background(over ? theme.widgetHover : theme.inset)
                        .border(over ? theme.accent : theme.panelBorder, 1)
                        .dropTarget();
        if (item >= 0) {
            b.dragSource(DRAG_ITEM, s);
        }
        b.open();
        if (item >= 0 && !draggingThis) {
            badge(item, 32);
        }
        ui.close();
        if (item >= 0 && !ui.dragging()) {
            tooltip(item);
        }
        if (ui.dropped(SLOT) && ui.dragKind() == DRAG_ITEM) {
            int from = ui.dragValue();
            int moved = slots[from];
            slots[from] = slots[s];
            slots[s] = moved;
        }
        if (draggingThis) {
            // The dragged item follows the pointer, on top of everything.
            ui.box()
                    .floatingAt(ui.input().mouseX - 16, ui.input().mouseY - 16)
                    .z(2000)
                    .noHit()
                    .open();
            badge(item, 32);
            ui.close();
        }
    }

    private void badge(int item, float size) {
        ui.box()
                .size(size, size)
                .radius(6)
                .background(ITEM_COLORS[item])
                .align(Ui.CENTER, Ui.CENTER)
                .noHit()
                .open();
        w.label().append(ITEM_NAMES[item], 0, 1).color(0xFF101114);
        ui.close();
    }

    private void tooltip(int item) {
        if (!ui.hovered(SLOT) || ui.hoverTime() < theme.tooltipDelay) {
            return;
        }
        ui.box()
                .floatingOn(SLOT)
                .anchor(Ui.BOTTOM_LEFT, Ui.TOP_LEFT)
                .offset(0, 4)
                .z(1000)
                .column()
                .gap(2)
                .padding(8, 6)
                .maxWidth(220)
                .background(theme.tooltip)
                .border(theme.panelBorder, 1)
                .radius(theme.radius)
                .noHit()
                .open();
        w.label(ITEM_NAMES[item]);
        w.small(ITEM_HINTS[item]).color(theme.textDim).wrap();
        ui.close();
    }

    private void sortSlots() {
        // Items first, in table order, then the empty slots (insertion sort: a dozen entries).
        for (int i = 1; i < slots.length; i++) {
            int v = slots[i];
            int j = i - 1;
            while (j >= 0 && key(slots[j]) > key(v)) {
                slots[j + 1] = slots[j];
                j--;
            }
            slots[j + 1] = v;
        }
    }

    private static int key(int item) {
        return item < 0 ? Integer.MAX_VALUE : item;
    }

    private void pauseMenu() {
        ui.box(PAUSE)
                .floatingOnScreen()
                .anchor(Ui.MIDDLE, Ui.MIDDLE)
                .z(500)
                .column()
                .gap(10)
                .padding(16)
                .width(340)
                .background(theme.panel)
                .border(theme.panelBorder, 1)
                .radius(6)
                .hit()
                .open();
        w.heading("Paused");
        w.separator();
        if (w.primaryButton(RESUME, "Resume")) {
            menuOpen = false;
        }
        w.labelDim("Options");
        w.row().widthGrow().open();
        w.label("Master volume").minWidth(120);
        float v = w.slider(VOLUME, masterVolume, 0, 1);
        if (v != masterVolume) {
            masterVolume = v;
            volumeChanged = true;
        }
        ui.close();
        boolean a = w.checkbox(AMBIENCE, "Crypt ambience", ambience);
        if (a != ambience) {
            ambience = a;
            volumeChanged = true;
        }
        boolean vs = w.checkbox(VSYNC, "Vertical sync", vsync);
        if (vs != vsync) {
            vsync = vs;
            WindowCommands.postVSync(ctx.mainThread(), vsync ? VSync.ADAPTIVE : VSync.OFF);
        }
        w.row().widthGrow().open();
        w.label("Window").minWidth(120);
        int m = w.dropdown(MODE, windowMode, MODES);
        if (m != windowMode) {
            windowMode = m;
            WindowCommands.postWindowMode(ctx.mainThread(), WindowMode.values()[m], 0, 0, 0);
        }
        ui.close();
        w.row().widthGrow().open();
        w.label("Formation").minWidth(120);
        int f = w.dropdown(FORMATION, formation, Party.FORMATION_NAMES);
        if (f != formation) {
            formation = f;
            formationChanged = true;
        }
        ui.close();
        boolean t = w.checkbox(TURNS, "Turn-based movement range", turnBased);
        if (t != turnBased) {
            turnBased = t;
            turnBasedChanged = true;
        }
        boolean p = w.checkbox(PROFILER, "Profiler overlay (F3)", profiler);
        if (p != profiler) {
            profiler = p;
            profilerChanged = true;
        }
        w.separator();
        w.row().widthGrow().gap(8).open();
        if (w.button(OPTIONS, "Inventory")) {
            inventoryOpen = !inventoryOpen;
        }
        w.spacer();
        if (w.button(QUIT, "Quit to desktop")) {
            quitRequested = true;
        }
        ui.close();
        ui.close();
    }
}
