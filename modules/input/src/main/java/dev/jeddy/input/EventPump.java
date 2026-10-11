package dev.jeddy.input;

import static dev.jeddy.sdl.ffi.SDL3.*;

import dev.jeddy.core.concurrent.SpscIntRing;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.core.memory.CStrings;
import dev.jeddy.sdl.SdlEventType;
import dev.jeddy.sdl.SdlThread;
import dev.jeddy.sdl.ffi.SDL_CommonEvent;
import dev.jeddy.sdl.ffi.SDL_Event;
import dev.jeddy.sdl.ffi.SDL_GamepadAxisEvent;
import dev.jeddy.sdl.ffi.SDL_GamepadButtonEvent;
import dev.jeddy.sdl.ffi.SDL_GamepadDeviceEvent;
import dev.jeddy.sdl.ffi.SDL_KeyboardEvent;
import dev.jeddy.sdl.ffi.SDL_MouseButtonEvent;
import dev.jeddy.sdl.ffi.SDL_MouseMotionEvent;
import dev.jeddy.sdl.ffi.SDL_MouseWheelEvent;
import dev.jeddy.sdl.ffi.SDL_TextEditingEvent;
import dev.jeddy.sdl.ffi.SDL_TextInputEvent;
import dev.jeddy.sdl.ffi.SDL_WindowEvent;
import dev.jeddy.window.MainThreadQueue;
import dev.jeddy.window.Window;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Polls SDL events on the main thread and forwards them to the game thread as fixed 8-{@code int}
 * records in an {@link SpscIntRing} (DESIGN §12.2, TASKS M2-04).
 *
 * <p>One preallocated {@code SDL_Event} segment is reused for every poll, fields are read at {@code
 * static final} offsets, and text is decoded from UTF-8 straight out of SDL's buffer into code
 * points, so {@link #pump()} allocates nothing. Window events also update the {@link Window} on the
 * spot (main-thread state); gamepads are opened and closed here, and rumble or LED requests from
 * the game thread arrive through the {@link MainThreadQueue} (opcodes 64–127).
 *
 * <p>If the ring is full (the game thread stalled for a long time) events are dropped and counted.
 *
 * <p>Thread ownership: main thread only.
 */
public final class EventPump implements MainThreadQueue.Handler, AutoCloseable {
    private static final Logger LOG = Log.logger("input");

    /** Gamepad slots; a ninth controller is ignored until one disconnects. */
    public static final int MAX_GAMEPADS = 8;

    /**
     * Rumble: {@code a} = slot, {@code b} = low-frequency strength, {@code c} = high (0-65535),
     * {@code d} = ms.
     */
    public static final int OP_RUMBLE = 64;

    /**
     * Trigger rumble: {@code a} = slot, {@code b} = left, {@code c} = right (0-65535), {@code d} =
     * ms.
     */
    public static final int OP_RUMBLE_TRIGGERS = 65;

    /** Light bar color: {@code a} = slot, {@code b} = {@code 0xRRGGBB}. */
    public static final int OP_SET_LED = 66;

    /** Player index (LEDs on some pads): {@code a} = slot, {@code b} = index or -1. */
    public static final int OP_SET_PLAYER_INDEX = 67;

    private static final long TYPE = SDL_CommonEvent.type$offset();
    private static final long TIMESTAMP = SDL_CommonEvent.timestamp$offset();
    private static final long KEY_SCANCODE = SDL_KeyboardEvent.scancode$offset();
    private static final long KEY_KEYCODE = SDL_KeyboardEvent.key$offset();
    private static final long KEY_MOD = SDL_KeyboardEvent.mod$offset();
    private static final long KEY_DOWN = SDL_KeyboardEvent.down$offset();
    private static final long KEY_REPEAT = SDL_KeyboardEvent.repeat$offset();
    private static final long TEXT = SDL_TextInputEvent.text$offset();
    private static final long EDIT_TEXT = SDL_TextEditingEvent.text$offset();
    private static final long EDIT_START = SDL_TextEditingEvent.start$offset();
    private static final long MOTION_STATE = SDL_MouseMotionEvent.state$offset();
    private static final long MOTION_X = SDL_MouseMotionEvent.x$offset();
    private static final long MOTION_Y = SDL_MouseMotionEvent.y$offset();
    private static final long MOTION_XREL = SDL_MouseMotionEvent.xrel$offset();
    private static final long MOTION_YREL = SDL_MouseMotionEvent.yrel$offset();
    private static final long BUTTON_BUTTON = SDL_MouseButtonEvent.button$offset();
    private static final long BUTTON_DOWN = SDL_MouseButtonEvent.down$offset();
    private static final long BUTTON_CLICKS = SDL_MouseButtonEvent.clicks$offset();
    private static final long BUTTON_X = SDL_MouseButtonEvent.x$offset();
    private static final long BUTTON_Y = SDL_MouseButtonEvent.y$offset();
    private static final long WHEEL_X = SDL_MouseWheelEvent.x$offset();
    private static final long WHEEL_Y = SDL_MouseWheelEvent.y$offset();
    private static final long WHEEL_DIRECTION = SDL_MouseWheelEvent.direction$offset();
    private static final long WHEEL_MOUSE_X = SDL_MouseWheelEvent.mouse_x$offset();
    private static final long WHEEL_MOUSE_Y = SDL_MouseWheelEvent.mouse_y$offset();
    private static final long WINDOW_ID = SDL_WindowEvent.windowID$offset();
    private static final long WINDOW_DATA1 = SDL_WindowEvent.data1$offset();
    private static final long WINDOW_DATA2 = SDL_WindowEvent.data2$offset();
    private static final long PAD_WHICH = SDL_GamepadDeviceEvent.which$offset();
    private static final long PAD_BUTTON = SDL_GamepadButtonEvent.button$offset();
    private static final long PAD_BUTTON_DOWN = SDL_GamepadButtonEvent.down$offset();
    private static final long PAD_AXIS = SDL_GamepadAxisEvent.axis$offset();
    private static final long PAD_AXIS_VALUE = SDL_GamepadAxisEvent.value$offset();

    private static final int WHEEL_FLIPPED = SDL_MOUSEWHEEL_FLIPPED();

    /** All of memory, for reading text that SDL owns without creating a segment per event. */
    private static final MemorySegment RAW = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);

    private final Arena arena;
    private final MemorySegment event;
    private final SpscIntRing out;
    private final Window window;
    private final int[] rec = new int[InputRecord.INTS];
    private final MemorySegment[] pads = new MemorySegment[MAX_GAMEPADS];
    private final int[] padIds = new int[MAX_GAMEPADS];
    private final KeyLabels keyLabels = new KeyLabels();
    private boolean quitRequested;
    private long polled;
    private long dropped;

    /**
     * Creates the pump.
     *
     * @param window the window whose events update its cached state, or {@code null}
     * @param out the ring to the game thread; size it for a few frames of events (for example 8192
     *     records, {@code 8192 * }{@link #recordInts()} {@code int}s)
     */
    public EventPump(Window window, SpscIntRing out) {
        SdlThread.assertMainThread("EventPump");
        this.arena = Arena.ofConfined();
        this.event = arena.allocate(SDL_Event.layout());
        this.out = out;
        this.window = window;
        keyLabels.refresh();
    }

    /** Returns the number of {@code int}s per event record, for sizing the ring. */
    public static int recordInts() {
        return InputRecord.INTS;
    }

    /** Returns the current keyboard layout's key labels (safe to read from any thread). */
    public KeyLabels keyLabels() {
        return keyLabels;
    }

    /**
     * Polls every pending SDL event, updates the window and forwards the events. Call once per
     * frame at the start of the main loop. Allocates nothing.
     *
     * @return the number of SDL events polled
     */
    public int pump() {
        SdlThread.assertMainThread("SDL_PollEvent");
        MemorySegment e = event;
        int count = 0;
        while (SDL_PollEvent(e)) {
            count++;
            dispatch(e);
        }
        polled += count;
        return count;
    }

    private void dispatch(MemorySegment e) {
        int type = e.get(ValueLayout.JAVA_INT, TYPE);
        long time = e.get(ValueLayout.JAVA_LONG, TIMESTAMP);
        switch (type) {
            case SdlEventType.QUIT -> {
                quitRequested = true;
                emit(InputRecord.QUIT, time, 0, 0, 0, 0, 0);
            }
            case SdlEventType.KEY_DOWN, SdlEventType.KEY_UP -> {
                int flags =
                        (e.get(ValueLayout.JAVA_BOOLEAN, KEY_DOWN) ? InputRecord.KEY_DOWN : 0)
                                | (e.get(ValueLayout.JAVA_BOOLEAN, KEY_REPEAT)
                                        ? InputRecord.KEY_REPEAT
                                        : 0);
                emit(
                        InputRecord.KEY,
                        time,
                        e.get(ValueLayout.JAVA_INT, KEY_SCANCODE),
                        e.get(ValueLayout.JAVA_INT, KEY_KEYCODE),
                        Short.toUnsignedInt(e.get(ValueLayout.JAVA_SHORT, KEY_MOD)),
                        flags,
                        0);
            }
            case SdlEventType.TEXT_INPUT ->
                    emitText(InputRecord.TEXT, time, e.get(ValueLayout.JAVA_LONG, TEXT));
            case SdlEventType.TEXT_EDITING -> {
                emit(
                        InputRecord.TEXT_EDIT,
                        time,
                        -1,
                        e.get(ValueLayout.JAVA_INT, EDIT_START),
                        0,
                        0,
                        0);
                emitText(InputRecord.TEXT_EDIT, time, e.get(ValueLayout.JAVA_LONG, EDIT_TEXT));
            }
            case SdlEventType.MOUSE_MOTION ->
                    emit(
                            InputRecord.MOUSE_MOTION,
                            time,
                            e.get(ValueLayout.JAVA_INT, MOTION_X),
                            e.get(ValueLayout.JAVA_INT, MOTION_Y),
                            e.get(ValueLayout.JAVA_INT, MOTION_XREL),
                            e.get(ValueLayout.JAVA_INT, MOTION_YREL),
                            e.get(ValueLayout.JAVA_INT, MOTION_STATE));
            case SdlEventType.MOUSE_BUTTON_DOWN, SdlEventType.MOUSE_BUTTON_UP ->
                    emit(
                            InputRecord.MOUSE_BUTTON,
                            time,
                            Byte.toUnsignedInt(e.get(ValueLayout.JAVA_BYTE, BUTTON_BUTTON)),
                            e.get(ValueLayout.JAVA_BOOLEAN, BUTTON_DOWN) ? 1 : 0,
                            Byte.toUnsignedInt(e.get(ValueLayout.JAVA_BYTE, BUTTON_CLICKS)),
                            e.get(ValueLayout.JAVA_INT, BUTTON_X),
                            e.get(ValueLayout.JAVA_INT, BUTTON_Y));
            case SdlEventType.MOUSE_WHEEL -> {
                float x = e.get(ValueLayout.JAVA_FLOAT, WHEEL_X);
                float y = e.get(ValueLayout.JAVA_FLOAT, WHEEL_Y);
                if (e.get(ValueLayout.JAVA_INT, WHEEL_DIRECTION) == WHEEL_FLIPPED) {
                    x = -x;
                    y = -y;
                }
                emit(
                        InputRecord.MOUSE_WHEEL,
                        time,
                        Float.floatToRawIntBits(x),
                        Float.floatToRawIntBits(y),
                        0,
                        e.get(ValueLayout.JAVA_INT, WHEEL_MOUSE_X),
                        e.get(ValueLayout.JAVA_INT, WHEEL_MOUSE_Y));
            }
            case SdlEventType.CLIPBOARD_UPDATE -> window.clipboard().refresh();
            case SdlEventType.KEYMAP_CHANGED -> {
                keyLabels.refresh();
                emit(InputRecord.KEYMAP_CHANGED, time, 0, 0, 0, 0, 0);
            }
            case SdlEventType.GAMEPAD_ADDED ->
                    padAdded(e.get(ValueLayout.JAVA_INT, PAD_WHICH), time);
            case SdlEventType.GAMEPAD_REMOVED ->
                    padRemoved(e.get(ValueLayout.JAVA_INT, PAD_WHICH), time);
            case SdlEventType.GAMEPAD_BUTTON_DOWN, SdlEventType.GAMEPAD_BUTTON_UP -> {
                int slot = slotOf(e.get(ValueLayout.JAVA_INT, PAD_WHICH));
                if (slot >= 0) {
                    emit(
                            InputRecord.PAD_BUTTON,
                            time,
                            slot,
                            Byte.toUnsignedInt(e.get(ValueLayout.JAVA_BYTE, PAD_BUTTON)),
                            e.get(ValueLayout.JAVA_BOOLEAN, PAD_BUTTON_DOWN) ? 1 : 0,
                            0,
                            0);
                }
            }
            case SdlEventType.GAMEPAD_AXIS_MOTION -> {
                int slot = slotOf(e.get(ValueLayout.JAVA_INT, PAD_WHICH));
                if (slot >= 0) {
                    emit(
                            InputRecord.PAD_AXIS,
                            time,
                            slot,
                            Byte.toUnsignedInt(e.get(ValueLayout.JAVA_BYTE, PAD_AXIS)),
                            e.get(ValueLayout.JAVA_SHORT, PAD_AXIS_VALUE),
                            0,
                            0);
                }
            }
            default -> {
                if (SdlEventType.isWindowEvent(type)) {
                    int windowId = e.get(ValueLayout.JAVA_INT, WINDOW_ID);
                    int data1 = e.get(ValueLayout.JAVA_INT, WINDOW_DATA1);
                    int data2 = e.get(ValueLayout.JAVA_INT, WINDOW_DATA2);
                    if (window != null && windowId == window.id()) {
                        window.onWindowEvent(type, data1, data2);
                        if (type == SdlEventType.WINDOW_DISPLAY_SCALE_CHANGED) {
                            data1 = Float.floatToRawIntBits(window.displayScale());
                        }
                    }
                    emit(InputRecord.WINDOW, time, type, data1, data2, windowId, 0);
                }
            }
        }
    }

    /** Decodes NUL-terminated UTF-8 at {@code address} into one record per code point. */
    private void emitText(int kind, long time, long address) {
        if (address == 0) {
            return;
        }
        long p = address;
        while (true) {
            int b0 = RAW.get(ValueLayout.JAVA_BYTE, p) & 0xFF;
            if (b0 == 0) {
                return;
            }
            int cp;
            int extra;
            if (b0 < 0x80) {
                cp = b0;
                extra = 0;
            } else if (b0 >= 0xF0) {
                cp = b0 & 0x07;
                extra = 3;
            } else if (b0 >= 0xE0) {
                cp = b0 & 0x0F;
                extra = 2;
            } else if (b0 >= 0xC0) {
                cp = b0 & 0x1F;
                extra = 1;
            } else {
                cp = 0xFFFD; // stray continuation byte
                extra = 0;
            }
            p++;
            for (int i = 0; i < extra; i++) {
                int b = RAW.get(ValueLayout.JAVA_BYTE, p) & 0xFF;
                if ((b & 0xC0) != 0x80) {
                    cp = 0xFFFD; // truncated sequence; resume at this byte
                    extra = 0;
                    break;
                }
                cp = (cp << 6) | (b & 0x3F);
                p++;
            }
            emit(kind, time, cp, 0, 0, 0, 0);
        }
    }

    private void padAdded(int joystickId, long time) {
        if (slotOf(joystickId) >= 0) {
            return;
        }
        int slot = -1;
        for (int i = 0; i < MAX_GAMEPADS; i++) {
            if (pads[i] == null) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            LOG.warn("ignoring gamepad {}: all {} slots are in use", joystickId, MAX_GAMEPADS);
            return;
        }
        MemorySegment pad = SDL_OpenGamepad(joystickId);
        if (pad.address() == 0) {
            LOG.warn("cannot open gamepad {}", joystickId);
            return;
        }
        pads[slot] = pad;
        padIds[slot] = joystickId;
        int type = SDL_GetGamepadType(pad);
        LOG.info(
                "gamepad {} connected in slot {}: {}",
                joystickId,
                slot,
                CStrings.read(SDL_GetGamepadName(pad)));
        emit(InputRecord.PAD_ADDED, time, slot, joystickId, type, 0, 0);
    }

    private void padRemoved(int joystickId, long time) {
        int slot = slotOf(joystickId);
        if (slot < 0) {
            return;
        }
        SDL_CloseGamepad(pads[slot]);
        pads[slot] = null;
        padIds[slot] = 0;
        LOG.info("gamepad {} in slot {} disconnected", joystickId, slot);
        emit(InputRecord.PAD_REMOVED, time, slot, joystickId, 0, 0, 0);
    }

    private int slotOf(int joystickId) {
        for (int i = 0; i < MAX_GAMEPADS; i++) {
            if (pads[i] != null && padIds[i] == joystickId) {
                return i;
            }
        }
        return -1;
    }

    private void emit(int kind, long time, int a, int b, int c, int d, int e) {
        int[] r = rec;
        r[InputRecord.KIND] = kind;
        r[InputRecord.TIME_LO] = (int) time;
        r[InputRecord.TIME_HI] = (int) (time >>> 32);
        r[InputRecord.A] = a;
        r[InputRecord.B] = b;
        r[InputRecord.C] = c;
        r[InputRecord.D] = d;
        r[InputRecord.E] = e;
        if (!out.offer(r, 0, InputRecord.INTS)) {
            dropped++;
        }
    }

    /** Executes gamepad requests from the game thread ({@link #OP_RUMBLE} ...). */
    @Override
    public boolean handle(int opcode, int a, int b, int c, int d, int e) {
        if (opcode < OP_RUMBLE || opcode > OP_SET_PLAYER_INDEX) {
            return false;
        }
        MemorySegment pad = a >= 0 && a < MAX_GAMEPADS ? pads[a] : null;
        if (pad == null) {
            return true; // disconnected meanwhile: nothing to do
        }
        switch (opcode) {
            case OP_RUMBLE -> SDL_RumbleGamepad(pad, (short) b, (short) c, d);
            case OP_RUMBLE_TRIGGERS -> SDL_RumbleGamepadTriggers(pad, (short) b, (short) c, d);
            case OP_SET_LED ->
                    SDL_SetGamepadLED(pad, (byte) (b >>> 16), (byte) (b >>> 8), (byte) b);
            case OP_SET_PLAYER_INDEX -> SDL_SetGamepadPlayerIndex(pad, b);
            default -> {}
        }
        return true;
    }

    /**
     * Requests rumble on the pad in {@code slot} (game thread; executed on the main thread).
     *
     * @param low low-frequency (left) motor strength, 0 to 1
     * @param high high-frequency (right) motor strength, 0 to 1
     * @param millis duration
     */
    public static boolean postRumble(
            MainThreadQueue queue, int slot, float low, float high, int millis) {
        return queue.post(OP_RUMBLE, slot, motor(low), motor(high), millis, 0);
    }

    /** Requests trigger rumble (Xbox impulse triggers), strengths 0 to 1. */
    public static boolean postTriggerRumble(
            MainThreadQueue queue, int slot, float left, float right, int millis) {
        return queue.post(OP_RUMBLE_TRIGGERS, slot, motor(left), motor(right), millis, 0);
    }

    /** Requests a light-bar color ({@code 0xRRGGBB}) on pads that have one. */
    public static boolean postLed(MainThreadQueue queue, int slot, int rgb) {
        return queue.post(OP_SET_LED, slot, rgb, 0, 0, 0);
    }

    private static int motor(float strength) {
        return Math.round(Math.max(0f, Math.min(1f, strength)) * 0xFFFF);
    }

    /** Returns {@code true} once SDL reported a quit request. */
    public boolean isQuitRequested() {
        return quitRequested;
    }

    /** Returns the number of SDL events polled so far. */
    public long polled() {
        return polled;
    }

    /** Returns the number of records dropped because the ring was full. */
    public long dropped() {
        return dropped;
    }

    /** Closes open gamepads and frees the event buffer. */
    @Override
    public void close() {
        SdlThread.assertMainThread("EventPump.close");
        for (int i = 0; i < MAX_GAMEPADS; i++) {
            if (pads[i] != null) {
                SDL_CloseGamepad(pads[i]);
                pads[i] = null;
            }
        }
        arena.close();
    }
}
