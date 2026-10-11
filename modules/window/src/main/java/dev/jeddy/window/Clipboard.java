package dev.jeddy.window;

import static dev.jeddy.sdl.ffi.SDL3.*;

import dev.jeddy.core.memory.CStrings;
import dev.jeddy.sdl.SdlThread;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * The system clipboard as the game thread sees it (TASKS M13-05). SDL's clipboard calls belong to
 * the main thread (DESIGN §5), so the text is mirrored: the event pump {@linkplain #refresh
 * re-reads} it whenever SDL reports a clipboard change and the game thread reads the mirror without
 * a call across threads; writing {@linkplain #request posts} the text to the main thread, which
 * {@linkplain #push pushes} it. Clipboard operations are user actions, so the strings they allocate
 * are off the hot path.
 *
 * <p>Thread ownership: {@link #push} and {@link #refresh} on the main thread; {@link #text} and
 * {@link #request} from any thread.
 */
public final class Clipboard {
    private volatile String text = "";
    private volatile String pending = "";

    Clipboard() {}

    /** Returns the clipboard text as last observed (empty when there is none). */
    public String text() {
        return text;
    }

    /** Asks the main thread to put {@code s} on the clipboard; the mirror updates when it has. */
    public boolean request(MainThreadQueue queue, String s) {
        pending = s;
        return WindowCommands.postSetClipboard(queue);
    }

    /** Main thread: pushes the requested text to the OS clipboard. */
    public void push() {
        SdlThread.assertMainThread("SDL_SetClipboardText");
        String s = pending;
        try (Arena arena = Arena.ofConfined()) {
            SDL_SetClipboardText(arena.allocateFrom(s, StandardCharsets.UTF_8));
        }
        text = s;
    }

    /** Main thread: re-reads the OS clipboard into the mirror. */
    public void refresh() {
        SdlThread.assertMainThread("SDL_GetClipboardText");
        MemorySegment p = SDL_GetClipboardText();
        String s = CStrings.read(p);
        if (p != null && !p.equals(MemorySegment.NULL)) {
            SDL_free(p);
        }
        text = s == null ? "" : s;
    }
}
