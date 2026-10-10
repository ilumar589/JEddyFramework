package dev.jeddy.input;

import dev.jeddy.core.concurrent.SpscIntRing;
import dev.jeddy.sdl.SdlEventType;

/** Writes event records the way {@link EventPump} does, without SDL, for pure-Java tests. */
final class FakeEvents {
    final SpscIntRing ring = new SpscIntRing(1 << 14);
    final Input input = new Input(ring, 1280, 720);
    private final int[] rec = new int[InputRecord.INTS];
    private long time = 1_000_000;

    private void emit(int kind, int a, int b, int c, int d, int e) {
        time += 1_000_000;
        rec[InputRecord.KIND] = kind;
        rec[InputRecord.TIME_LO] = (int) time;
        rec[InputRecord.TIME_HI] = (int) (time >>> 32);
        rec[InputRecord.A] = a;
        rec[InputRecord.B] = b;
        rec[InputRecord.C] = c;
        rec[InputRecord.D] = d;
        rec[InputRecord.E] = e;
        if (!ring.offer(rec, 0, InputRecord.INTS)) {
            throw new IllegalStateException("ring full");
        }
    }

    /** SDL modifier state to send with key events (SDL_KMOD bits). */
    int sdlMods;

    FakeEvents key(int scancode, boolean down) {
        emit(InputRecord.KEY, scancode, 0, sdlMods, down ? InputRecord.KEY_DOWN : 0, 0);
        return this;
    }

    FakeEvents shift(boolean held) {
        sdlMods = held ? sdlMods | 0x0001 : sdlMods & ~0x0001;
        return key(Scancode.LSHIFT, held);
    }

    FakeEvents move(float x, float y) {
        float dx = x - input.mouseX();
        float dy = y - input.mouseY();
        emit(
                InputRecord.MOUSE_MOTION,
                Float.floatToRawIntBits(x),
                Float.floatToRawIntBits(y),
                Float.floatToRawIntBits(dx),
                Float.floatToRawIntBits(dy),
                input.mouseButtons());
        return this;
    }

    FakeEvents button(int button, boolean down, int clicks, float x, float y) {
        emit(
                InputRecord.MOUSE_BUTTON,
                button,
                down ? 1 : 0,
                clicks,
                Float.floatToRawIntBits(x),
                Float.floatToRawIntBits(y));
        return this;
    }

    FakeEvents wheel(float y) {
        emit(
                InputRecord.MOUSE_WHEEL,
                0,
                Float.floatToRawIntBits(y),
                0,
                Float.floatToRawIntBits(input.mouseX()),
                Float.floatToRawIntBits(input.mouseY()));
        return this;
    }

    FakeEvents window(int type, int data1, int data2) {
        emit(InputRecord.WINDOW, type, data1, data2, 1, 0);
        return this;
    }

    FakeEvents focus(boolean gained) {
        return window(
                gained ? SdlEventType.WINDOW_FOCUS_GAINED : SdlEventType.WINDOW_FOCUS_LOST, 0, 0);
    }

    FakeEvents padAdded(int slot) {
        emit(InputRecord.PAD_ADDED, slot, 100 + slot, 1, 0, 0);
        return this;
    }

    FakeEvents padRemoved(int slot) {
        emit(InputRecord.PAD_REMOVED, slot, 100 + slot, 0, 0, 0);
        return this;
    }

    FakeEvents padButton(int slot, int button, boolean down) {
        emit(InputRecord.PAD_BUTTON, slot, button, down ? 1 : 0, 0, 0);
        return this;
    }

    FakeEvents padAxis(int slot, int axis, float value) {
        emit(InputRecord.PAD_AXIS, slot, axis, Math.round(value * 32767), 0, 0);
        return this;
    }

    /** Applies everything queued so far as one frame. */
    Input frame() {
        input.update();
        return input;
    }
}
