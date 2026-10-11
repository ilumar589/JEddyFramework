package dev.jeddy.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.jeddy.core.concurrent.SpscIntRing;
import dev.jeddy.sdl.SdlEventType;

import org.junit.jupiter.api.Test;

/**
 * {@link Input#uiScale}: UI logical pixels per window coordinate unit, from the window records the
 * pump writes (no SDL needed).
 */
class InputScaleTest {
    private final SpscIntRing ring = new SpscIntRing(64 * InputRecord.INTS);
    private final Input input = new Input(ring, 1280, 720);
    private final int[] rec = new int[InputRecord.INTS];

    private void window(int type, int data1, int data2) {
        rec[InputRecord.KIND] = InputRecord.WINDOW;
        rec[InputRecord.A] = type;
        rec[InputRecord.B] = data1;
        rec[InputRecord.C] = data2;
        ring.offer(rec, 0, InputRecord.INTS);
    }

    @Test
    void plainDisplay() {
        input.update();
        assertEquals(1f, input.uiScale(), 1e-6f);
    }

    @Test
    void retinaWindowCoordinatesAreAlreadyLogical() {
        // macOS: points for the window, twice the pixels, a display scale of 2.
        window(SdlEventType.WINDOW_PIXEL_SIZE_CHANGED, 2560, 1440);
        window(SdlEventType.WINDOW_DISPLAY_SCALE_CHANGED, Float.floatToRawIntBits(2f), 0);
        input.update();
        assertEquals(1f, input.uiScale(), 1e-6f);
    }

    @Test
    void windowsAt150PercentSeesTwoThirdsOfItsPixels() {
        // Windows: window coordinates are pixels, the scale is the DPI setting.
        window(SdlEventType.WINDOW_RESIZED, 1600, 900);
        window(SdlEventType.WINDOW_PIXEL_SIZE_CHANGED, 1600, 900);
        window(SdlEventType.WINDOW_DISPLAY_SCALE_CHANGED, Float.floatToRawIntBits(1.5f), 0);
        input.update();
        assertEquals(1 / 1.5f, input.uiScale(), 1e-6f);
        assertEquals(1600 / 1.5f, input.windowWidth() * input.uiScale(), 1e-3f);
    }
}
