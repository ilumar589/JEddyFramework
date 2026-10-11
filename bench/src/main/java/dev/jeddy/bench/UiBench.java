package dev.jeddy.bench;

import dev.jeddy.asset.format.FontData;
import dev.jeddy.asset.format.FontWriter;
import dev.jeddy.ui.Theme;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.UiDrawList;
import dev.jeddy.ui.UiInput;
import dev.jeddy.ui.Widgets;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

/**
 * One frame of UI (TASKS M13-07, AC: 2,000 elements built, laid out and emitted ≤ 0.3 ms, 0
 * B/frame): a scrolling list of 400 rows, each a focusable row with a formatted label, a wrapping
 * text, a spacer and a drag source (2,002 elements, about 800 text elements), with the pointer
 * moving over it. {@code frame} measures everything the game thread does per frame; {@code widgets}
 * declares a panel of every built-in widget to see the cost of the compositions.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class UiBench {
    private static final long LIST = 1;
    private static final long ROW = 2;
    private static final long NAME = 3;
    private static final long BUTTON = 10;
    private static final long CHECK = 11;
    private static final long SLIDER = 12;
    private static final long FIELD = 13;
    private static final long DROP = 14;
    private static final long TREE = 15;
    private static final long LEAF = 16;
    private static final long TABS = 17;
    private static final long DRAG = 18;
    private static final CharSequence[] OPTIONS = {"One", "Two", "Three"};

    private Ui ui;
    private Widgets widgets;
    private UiInput in;
    private UiDrawList out;
    private int frame;
    private boolean checked;
    private float volume = 0.5f;
    private dev.jeddy.ui.TextBuffer text;
    private int choice;
    private int tab;
    private float dragged;

    /** A context with a font whose atlas is "loaded", so glyph quads are emitted. */
    @Setup(Level.Trial)
    public void setup() throws Exception {
        ui = new Ui();
        FontWriter w = new FontWriter().metrics(48, 40, 10, 58, 8).atlas(1024, 1024);
        for (int cp = 0x20; cp < 0x7F; cp++) {
            w.glyph(cp, 24 + cp % 7, 1, 36, 28, 40, (cp % 32) * 32, (cp / 32) * 48);
        }
        FontData font = FontData.read(MemorySegment.ofArray(w.toBytes()));
        ui.addFont(font, 1);
        widgets = new Widgets(ui, Theme.dark());
        in = new UiInput();
        in.width = 1920;
        in.height = 1080;
        in.dt = 1f / 60;
        out = new UiDrawList(8192, 64);
        text = new dev.jeddy.ui.TextBuffer("hello world");
    }

    /** 2,002 elements: declaration, interaction, six layout passes and the draw list. */
    @Benchmark
    public int frame() {
        frame++;
        in.mouseX = 100 + frame % 300;
        in.mouseY = 100 + frame % 500;
        in.wheelY = frame % 60 == 0 ? -1 : 0;
        ui.beginFrame(in);
        ui.box(LIST)
                .width(500)
                .heightGrow()
                .column()
                .padding(8)
                .gap(4)
                .background(0xF0202228)
                .scroll(false, true)
                .open();
        for (int i = 0; i < 400; i++) {
            ui.pushId(i);
            ui.box(ROW)
                    .widthGrow()
                    .height(24)
                    .padding(4)
                    .gap(6)
                    .background(ui.hovered(ROW) ? 0xFF444852 : 0xFF34373F)
                    .focusable()
                    .open();
            ui.text(NAME).append("Item ").append(i).append(' ').append(i * 0.25f, 2);
            ui.box().widthGrow();
            ui.text().append("aaa bbb ccc ddd eee").wrap().widthFit(0, 60);
            ui.box().size(16, 16).background(0xFF4C8DFF).dragSource(1, i);
            ui.close();
            ui.popId();
        }
        ui.close();
        ui.endFrame(out);
        in.clearEvents();
        return out.quadCount();
    }

    /** A panel of every built-in widget (about 60 elements). */
    @Benchmark
    public int widgets() {
        frame++;
        in.mouseX = 20 + frame % 300;
        in.mouseY = 20 + frame % 400;
        ui.beginFrame(in);
        Widgets w = widgets;
        w.panel().width(320).open();
        if (w.button(BUTTON, "Click")) {
            frame++;
        }
        w.tooltip(BUTTON, "A tooltip");
        checked = w.checkbox(CHECK, "Check", checked);
        w.radio(LEAF, "Radio", checked);
        volume = w.slider(SLIDER, volume, 0, 1);
        w.textField(FIELD, text, "name");
        choice = w.dropdown(DROP, choice, OPTIONS);
        if (w.treeNode(TREE, "Node", true)) {
            w.treeLeaf(LEAF + 1, "Leaf", true);
            w.treeEnd();
        }
        tab = w.tabs(TABS, tab, OPTIONS);
        dragged = w.dragValue(DRAG, dragged, 0.5f, 0, 100, 1);
        w.separator();
        w.progress(0.4f);
        w.paragraph("some wrapping text in a paragraph of the panel that goes on a bit");
        ui.close();
        ui.endFrame(out);
        in.clearEvents();
        return out.quadCount();
    }
}
