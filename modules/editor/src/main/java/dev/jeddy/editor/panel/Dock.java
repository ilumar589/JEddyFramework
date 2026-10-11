package dev.jeddy.editor.panel;

import dev.jeddy.editor.EditorContext;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.Widgets;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the panels in three edge zones over the scene (DESIGN §19, D88): a left and a right column
 * and a bottom strip, each a tab bar over the visible panel and a splitter that resizes the zone.
 * What is left in the middle is the viewport region ({@link #regionX} ...), which the editor hands
 * to its {@code Viewport}. A panel is in one zone or hidden; the View menu toggles them.
 *
 * <p>The layout is one string ({@link #layout}, {@link #setLayout}) the host keeps in its settings:
 * {@code left=300:Outliner,Assets|right=320:Inspector|bottom=200:Console}.
 *
 * <p>Thread ownership: the game thread.
 */
public final class Dock {
    /** Zones. */
    public static final int LEFT = 0;

    public static final int RIGHT = 1;
    public static final int BOTTOM = 2;
    public static final int HIDDEN = -1;

    private static final long ZONE_ID = 0xD0C1L;
    private static final long TABS_ID = 0x7AB5L;
    private static final long SPLIT_ID = 0x5B11L;
    private static final long BODY_ID = 0xB0D1L;
    private static final float MIN_SIZE = 120;

    private final List<Panel> panels = new ArrayList<>();
    private final List<Integer> zoneOf = new ArrayList<>();
    private final float[] size = {300, 340, 220};
    private final int[] selected = new int[3];
    private final CharSequence[][] labels = new CharSequence[3][];
    private final Panel[][] zonePanels = new Panel[3][];
    private final int[] zoneCount = new int[3];
    private float regionX, regionY, regionW, regionH;
    private int layoutVersion;
    private int builtVersion = -1;

    /** Creates an empty dock. */
    public Dock() {}

    /**
     * Adds {@code panel} to {@code zone} ({@code LEFT}, {@code RIGHT}, {@code BOTTOM}, {@code
     * HIDDEN}).
     */
    public void add(Panel panel, int zone) {
        panels.add(panel);
        zoneOf.add(zone);
        layoutVersion++;
    }

    /** Returns the panels in registration order. */
    public List<Panel> panels() {
        return panels;
    }

    /** Returns the zone of panel {@code i}, or {@code HIDDEN}. */
    public int zone(int i) {
        return zoneOf.get(i);
    }

    /** Moves panel {@code i} to {@code zone} (and makes it the zone's visible tab). */
    public void setZone(int i, int zone) {
        zoneOf.set(i, zone);
        layoutVersion++;
        if (zone != HIDDEN) {
            rebuild();
            Panel p = panels.get(i);
            for (int k = 0; k < zoneCount[zone]; k++) {
                if (zonePanels[zone][k] == p) {
                    selected[zone] = k;
                }
            }
        }
    }

    /** Shows panel {@code i} if hidden (in its last zone, else the right one), else hides it. */
    public void toggle(int i) {
        setZone(i, zoneOf.get(i) == HIDDEN ? lastZone(i) : HIDDEN);
    }

    private final int[] lastZoneOf = new int[16];

    private int lastZone(int i) {
        return i < lastZoneOf.length && lastZoneOf[i] >= 0 ? lastZoneOf[i] : RIGHT;
    }

    /** Returns the size (width or height) of {@code zone} in points. */
    public float size(int zone) {
        return size[zone];
    }

    /** Sets the size of {@code zone}. */
    public void setSize(int zone, float s) {
        size[zone] = Math.max(MIN_SIZE, s);
    }

    /** The viewport region left free by the zones, after {@link #declare}. */
    public float regionX() {
        return regionX;
    }

    public float regionY() {
        return regionY;
    }

    public float regionW() {
        return regionW;
    }

    public float regionH() {
        return regionH;
    }

    private void rebuild() {
        for (int z = 0; z < 3; z++) {
            int n = 0;
            for (int i = 0; i < panels.size(); i++) {
                if (zoneOf.get(i) == z) {
                    n++;
                }
            }
            if (zonePanels[z] == null || zonePanels[z].length != n) {
                zonePanels[z] = new Panel[n];
                labels[z] = new CharSequence[n];
            }
            int k = 0;
            for (int i = 0; i < panels.size(); i++) {
                if (zoneOf.get(i) == z) {
                    zonePanels[z][k] = panels.get(i);
                    labels[z][k] = panels.get(i).name();
                    if (i < lastZoneOf.length) {
                        lastZoneOf[i] = z;
                    }
                    k++;
                }
            }
            zoneCount[z] = n;
            selected[z] = Math.min(selected[z], Math.max(0, n - 1));
        }
        builtVersion = layoutVersion;
    }

    /**
     * Declares the zones below a menu bar {@code top} points tall, over a window of {@code w} ×
     * {@code h}; the panels draw inside. Must be called inside the editor's root column.
     */
    public void declare(EditorContext ctx, Widgets w, float top, float width, float height) {
        if (builtVersion != layoutVersion) {
            rebuild();
        }
        Ui ui = w.ui;
        float split = w.theme.splitterWidth;
        boolean left = zoneCount[LEFT] > 0;
        boolean right = zoneCount[RIGHT] > 0;
        boolean bottom = zoneCount[BOTTOM] > 0;
        float bottomH = bottom ? size[BOTTOM] + split : 0;
        float middleH = Math.max(0, height - top - bottomH);
        // The middle row: left zone | splitter | free space | splitter | right zone.
        ui.box().row().widthGrow().height(middleH).noHit().open();
        if (left) {
            zone(ctx, w, LEFT, size[LEFT], middleH);
            ui.pushId(LEFT);
            setSize(LEFT, size[LEFT] + w.splitter(SPLIT_ID, true));
            ui.popId();
        }
        ui.box().grow().noHit(); // the viewport shows through here
        if (right) {
            ui.pushId(RIGHT);
            setSize(RIGHT, size[RIGHT] - w.splitter(SPLIT_ID, true));
            ui.popId();
            zone(ctx, w, RIGHT, size[RIGHT], middleH);
        }
        ui.close();
        if (bottom) {
            ui.pushId(BOTTOM);
            setSize(BOTTOM, size[BOTTOM] - w.splitter(SPLIT_ID, false));
            ui.popId();
            zone(ctx, w, BOTTOM, width, size[BOTTOM]);
        }
        float lw = left ? size[LEFT] + split : 0;
        float rw = right ? size[RIGHT] + split : 0;
        regionX = lw;
        regionY = top;
        regionW = Math.max(1, width - lw - rw);
        regionH = Math.max(1, middleH);
    }

    private void zone(EditorContext ctx, Widgets w, int z, float zw, float zh) {
        Ui ui = w.ui;
        ui.pushId(z);
        ui.box(ZONE_ID)
                .column()
                .size(zw, zh)
                .gap(0)
                .background(w.theme.panel)
                .border(w.theme.panelBorder, 1)
                .clip()
                .hit()
                .open();
        selected[z] = w.tabs(TABS_ID, selected[z], labels[z]);
        ui.box().widthGrow().height(1).background(w.theme.separator).noHit();
        w.scrollColumn(BODY_ID).widthGrow().heightGrow().padding(w.theme.padding * 0.75f).open();
        if (zoneCount[z] > 0) {
            Panel p = zonePanels[z][selected[z]];
            ui.pushId(panels.indexOf(p));
            p.draw(ctx, w);
            ui.popId();
        }
        ui.close();
        ui.close();
        ui.popId();
    }

    /** Tells every panel the level changed. */
    public void levelChanged(EditorContext ctx) {
        for (Panel p : panels) {
            p.levelChanged(ctx);
        }
    }

    // ---- Persistence --------------------------------------------------------------------------

    /**
     * Returns the layout as {@code left=300:A,B|right=320:C|bottom=200:D} (hidden panels left out).
     */
    public String layout() {
        StringBuilder sb = new StringBuilder();
        String[] names = {"left", "right", "bottom"};
        for (int z = 0; z < 3; z++) {
            if (z > 0) {
                sb.append('|');
            }
            sb.append(names[z]).append('=').append(Math.round(size[z])).append(':');
            boolean first = true;
            for (int i = 0; i < panels.size(); i++) {
                if (zoneOf.get(i) == z) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append(panels.get(i).name());
                    first = false;
                }
            }
        }
        return sb.toString();
    }

    /** Applies a layout string from {@link #layout}; unknown panel names are ignored. */
    public void setLayout(String layout) {
        if (layout == null || layout.isEmpty()) {
            return;
        }
        for (int i = 0; i < panels.size(); i++) {
            zoneOf.set(i, HIDDEN);
        }
        for (String part : layout.split("\\|")) {
            int eq = part.indexOf('=');
            int colon = part.indexOf(':');
            if (eq < 0 || colon < eq) {
                continue;
            }
            int z =
                    switch (part.substring(0, eq).trim()) {
                        case "left" -> LEFT;
                        case "right" -> RIGHT;
                        case "bottom" -> BOTTOM;
                        default -> -1;
                    };
            if (z < 0) {
                continue;
            }
            try {
                setSize(z, Float.parseFloat(part.substring(eq + 1, colon).trim()));
            } catch (NumberFormatException e) {
                // keep the current size
            }
            for (String name : part.substring(colon + 1).split(",")) {
                String n = name.trim();
                for (int i = 0; i < panels.size(); i++) {
                    if (panels.get(i).name().equals(n)) {
                        zoneOf.set(i, z);
                    }
                }
            }
        }
        layoutVersion++;
    }
}
