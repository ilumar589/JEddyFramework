package dev.jeddy.input;

import static dev.jeddy.sdl.ffi.SDL3.SDL_GetKeyFromScancode;
import static dev.jeddy.sdl.ffi.SDL3.SDL_GetKeyName;

import dev.jeddy.core.memory.CStrings;
import dev.jeddy.sdl.SdlThread;

/**
 * What each physical key is labelled on the user's keyboard layout, for showing bindings in the UI
 * ("Z" for {@link Scancode#W} on AZERTY). SDL's key-name functions are not thread-safe, so the main
 * thread fills a table at startup and whenever the layout changes; any thread reads it.
 *
 * <p>Thread ownership: {@link #refresh()} on the main thread; {@link #label(int)} from any thread.
 */
public final class KeyLabels {
    private volatile String[] labels = new String[Scancode.COUNT];

    KeyLabels() {}

    /** Rebuilds the table from the current layout (main thread, cold path). */
    void refresh() {
        SdlThread.assertMainThread("SDL_GetKeyName");
        String[] next = new String[Scancode.COUNT];
        for (int sc = 1; sc < Scancode.COUNT; sc++) {
            if (Scancode.name(sc) == null) {
                continue;
            }
            int key = SDL_GetKeyFromScancode(sc, (short) 0, false);
            String name = key == 0 ? null : CStrings.read(SDL_GetKeyName(key));
            next[sc] = name == null || name.isEmpty() ? Scancode.name(sc) : name;
        }
        labels = next;
    }

    /**
     * Returns the label of the key at {@code scancode} on the current layout, falling back to
     * {@link Scancode#name(int)}.
     */
    public String label(int scancode) {
        if (scancode <= 0 || scancode >= Scancode.COUNT) {
            return "";
        }
        String label = labels[scancode];
        return label != null ? label : Scancode.name(scancode);
    }
}
