package dev.jeddy.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.config.Settings;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/** Actions, chords, contexts, persistence and rebinding (TASKS M2-06). */
class ActionMapTest {
    private FakeEvents ev;
    private ActionMap map;
    private int ui;
    private int dialogue;
    private int world;
    private int camera;
    private int move;
    private int queueMove;
    private int select;
    private int pause;
    private int pan;
    private int zoom;
    private int confirm;

    @BeforeEach
    void setUp() {
        ev = new FakeEvents();
        map = new ActionMap();
        ui = map.context("ui", 50, false);
        dialogue = map.context("dialogue", 40, true);
        world = map.context("world", 20, false);
        camera = map.context("camera", 10, false);
        move = map.button("move");
        queueMove = map.button("move.queue");
        select = map.button("select");
        pause = map.button("pause");
        pan = map.axis("camera.pan.x");
        zoom = map.axis("camera.zoom");
        confirm = map.button("confirm");

        map.bind(world, move, Binding.mouse(MouseButton.LEFT));
        map.bind(world, queueMove, Binding.parse("Shift+Mouse:Left"));
        map.bind(world, pause, "Key:SPACE");
        map.bind(world, pause, Binding.pad(GamepadButton.START));
        map.bind(camera, pan, "Keys:A/D");
        map.bind(camera, pan, Binding.padAxis(GamepadAxis.LEFT_X, 1));
        map.bind(camera, zoom, "Wheel:Y");
        map.bind(ui, select, Binding.mouse(MouseButton.LEFT));
        map.bind(dialogue, confirm, "Key:SPACE");
        map.setActive(world, true);
        map.setActive(camera, true);
        map.markDefaults();
    }

    private void frame() {
        ev.frame();
        map.update(ev.input);
    }

    @Test
    void buttonsReportEdges() {
        ev.key(Scancode.SPACE, true);
        frame();
        assertTrue(map.wasPressed(pause));
        assertTrue(map.isDown(pause));
        frame();
        assertFalse(map.wasPressed(pause));
        assertTrue(map.isDown(pause));
        ev.key(Scancode.SPACE, false);
        frame();
        assertTrue(map.wasReleased(pause));
        assertFalse(map.isDown(pause));

        // A tap within one frame is not lost.
        ev.key(Scancode.SPACE, true).key(Scancode.SPACE, false);
        frame();
        assertTrue(map.wasPressed(pause));
        assertTrue(map.wasReleased(pause));
    }

    @Test
    void theMostSpecificChordWins() {
        ev.button(MouseButton.LEFT, true, 1, 10, 10);
        frame();
        assertTrue(map.wasPressed(move));
        assertFalse(map.wasPressed(queueMove));
        ev.button(MouseButton.LEFT, false, 1, 10, 10);
        frame();

        ev.shift(true).button(MouseButton.LEFT, true, 1, 10, 10);
        frame();
        assertTrue(map.wasPressed(queueMove), "Shift+Click queues");
        assertFalse(map.wasPressed(move), "and does not also move");
    }

    @Test
    void axesCombineKeysSticksAndWheel() {
        ev.key(Scancode.D, true);
        frame();
        assertEquals(1f, map.value(pan));
        ev.key(Scancode.A, true);
        frame();
        assertEquals(0f, map.value(pan), "both keys cancel out");
        ev.key(Scancode.A, false).key(Scancode.D, false);
        ev.padAdded(0).padAxis(0, GamepadAxis.LEFT_X, -0.6f);
        frame();
        assertEquals(-0.5f, map.value(pan), 1e-3f, "dead zone 0.2 remapped to 0..1");
        ev.padAxis(0, GamepadAxis.LEFT_X, 0.1f);
        frame();
        assertEquals(0f, map.value(pan), "inside the dead zone");
        ev.wheel(2);
        frame();
        assertEquals(2f, map.value(zoom));
        frame();
        assertEquals(0f, map.value(zoom));
    }

    @Test
    void higherContextsConsumeInput() {
        map.setActive(ui, true);
        ev.button(MouseButton.LEFT, true, 1, 5, 5);
        frame();
        assertTrue(map.wasPressed(select), "UI sees the click first");
        assertFalse(map.wasPressed(move), "and the world does not");
        map.setActive(ui, false);
        ev.button(MouseButton.LEFT, false, 1, 5, 5);
        frame();

        // External consumption: a widget handled the click.
        ev.button(MouseButton.LEFT, true, 1, 5, 5);
        ev.frame();
        map.consumeMouse(MouseButton.LEFT, ev.input);
        map.update(ev.input);
        assertFalse(map.wasPressed(move));
    }

    @Test
    void blockingContextsHideEverythingBelow() {
        map.setActive(dialogue, true);
        ev.key(Scancode.SPACE, true).key(Scancode.D, true);
        frame();
        assertTrue(map.wasPressed(confirm));
        assertFalse(map.wasPressed(pause), "world is below the modal dialogue");
        assertEquals(0f, map.value(pan), "camera too");
        map.setActive(dialogue, false);
        frame();
        assertEquals(1f, map.value(pan));
    }

    @Test
    void gamepadButtonsAndHotplug() {
        ev.padAdded(2).padButton(2, GamepadButton.START, true);
        frame();
        assertTrue(map.wasPressed(pause));
        assertEquals(Input.DEVICE_GAMEPAD, ev.input.lastDevice());
        ev.padRemoved(2);
        frame();
        assertFalse(map.isDown(pause), "unplugging releases the pad's buttons");
        assertTrue(map.wasReleased(pause));
    }

    @Test
    void bindingsRoundTripThroughText() {
        for (String text :
                List.of(
                        "Key:W",
                        "Shift+Ctrl+Key:S",
                        "Mouse:Right",
                        "Alt+Mouse:X1",
                        "Keys:A/D",
                        "Wheel:Y",
                        "Wheel:Down",
                        "MouseAxis:X*0.25",
                        "Pad:South",
                        "PadAxis:RightX",
                        "PadAxis:LeftTrigger+")) {
            assertEquals(text, Binding.parse(text).toString());
        }
        assertEquals(Binding.key(Scancode.W), Binding.parse("key:w"));
        assertThrows(IllegalArgumentException.class, () -> Binding.parse("Key:NotAKey"));
        assertThrows(IllegalArgumentException.class, () -> Binding.parse("Hyper+Key:A"));
        assertTrue(Binding.parse("Keys:A/D").isAxis());
        assertFalse(Binding.parse("PadAxis:LeftX-").isAxis());
    }

    @Test
    void bindingsPersistAndReset() {
        map.rebind(world, pause, List.of(Binding.key(Scancode.P)));
        Settings settings = new Settings();
        map.save(settings);
        String toml = settings.toToml();
        assertTrue(toml.contains("Key:P"), toml);

        ActionMap fresh = new ActionMap();
        int w = fresh.context("world", 20, false);
        fresh.context("camera", 10, false);
        int p = fresh.button("pause");
        fresh.bind(w, p, "Key:SPACE");
        List<String> errors = fresh.load(Settings.parse(toml));
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(List.of(Binding.key(Scancode.P)), fresh.bindings(w, p));

        map.resetToDefaults();
        assertEquals(
                List.of(Binding.key(Scancode.SPACE), Binding.pad(GamepadButton.START)),
                map.bindings(world, pause));
    }

    @Test
    void rebinderCapturesChordsModifiersAndCancels() {
        Rebinder r = new Rebinder();
        r.start(false);
        frame();
        assertFalse(r.poll(ev.input)); // the frame that opened the prompt is ignored
        ev.shift(true);
        frame();
        assertFalse(r.poll(ev.input));
        ev.key(Scancode.F, true);
        frame();
        assertTrue(r.poll(ev.input));
        assertEquals(Binding.key(Scancode.F, Modifiers.SHIFT), r.result());

        r.start(false);
        ev.key(Scancode.F, false).shift(false);
        frame();
        r.poll(ev.input);
        ev.shift(true);
        frame();
        r.poll(ev.input);
        ev.shift(false);
        frame();
        assertTrue(r.poll(ev.input));
        assertEquals(Binding.key(Scancode.LSHIFT), r.result(), "a modifier on its own");

        r.start(false);
        frame();
        r.poll(ev.input);
        ev.key(Scancode.ESCAPE, true);
        frame();
        assertTrue(r.poll(ev.input));
        assertNull(r.result());

        r.start(true);
        frame();
        r.poll(ev.input);
        ev.key(Scancode.Q, true);
        frame();
        assertFalse(r.poll(ev.input));
        ev.key(Scancode.E, true);
        frame();
        assertTrue(r.poll(ev.input));
        assertEquals(Binding.keyAxis(Scancode.Q, Scancode.E), r.result());
    }

    @Test
    void updatingAllocatesNothing() {
        map.setActive(ui, true);
        ev.padAdded(0);
        frame();
        Allocations.assertNoAllocation(
                "ActionMap.update",
                () -> {
                    ev.key(Scancode.SPACE, true)
                            .key(Scancode.D, true)
                            .button(MouseButton.LEFT, true, 1, 3, 3)
                            .padAxis(0, GamepadAxis.LEFT_X, 0.7f)
                            .wheel(1);
                    frame();
                    ev.key(Scancode.SPACE, false)
                            .key(Scancode.D, false)
                            .button(MouseButton.LEFT, false, 1, 3, 3);
                    frame();
                });
    }
}
