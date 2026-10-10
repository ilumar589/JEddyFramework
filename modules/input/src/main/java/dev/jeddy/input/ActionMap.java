package dev.jeddy.input;

import dev.jeddy.core.config.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Maps physical inputs to game actions through prioritized contexts (DESIGN §12.2, TASKS M2-06).
 *
 * <p><b>Actions</b> are {@code int} IDs: buttons ({@link #wasPressed}, {@link #isDown}) or axes
 * ({@link #value}). <b>Contexts</b> such as UI, Dialogue, Targeting, World and Camera hold the
 * bindings; active contexts are evaluated from the highest priority down. An input that drives a
 * binding in a higher context is consumed and lower contexts do not see it; a <em>blocking</em>
 * context (a modal dialog) hides every input from the contexts below it.
 *
 * <p><b>Chords:</b> a binding can require modifiers ({@code Shift+Mouse:Left} to queue orders).
 * When several bindings in one context share an input, the one with the most matching modifiers
 * wins, so Shift+Click does not also trigger the plain Click action.
 *
 * <p>Setup (registering, binding, loading) is a cold path. {@link #update(Input)} runs once per
 * game frame after {@link Input#update()} and allocates nothing.
 *
 * <pre>{@code
 * ActionMap map = new ActionMap();
 * int world = map.context("world", 10, false);
 * int move = map.button("move");
 * int queueMove = map.button("move.queue");
 * map.bind(world, move, Binding.mouse(MouseButton.LEFT));
 * map.bind(world, queueMove, Binding.mouse(MouseButton.LEFT, Modifiers.SHIFT));
 * map.setActive(world, true);
 * map.markDefaults();
 * }</pre>
 *
 * <p>Thread ownership: game thread only.
 */
public final class ActionMap {
    private static final int PAD_SLOTS = EventPump.MAX_GAMEPADS;
    private static final float AXIS_BUTTON_THRESHOLD = 0.5f;

    // Actions
    private String[] actionNames = new String[16];
    private boolean[] actionIsAxis = new boolean[16];
    private int actionCount;
    private boolean[] down = new boolean[16];
    private boolean[] prevDown = new boolean[16];
    private boolean[] pressedNow = new boolean[16];
    private int[] pressedFrame = new int[16];
    private int[] releasedFrame = new int[16];
    private float[] value = new float[16];

    // Contexts, kept sorted by priority (highest first) in byPriority
    private String[] contextNames = new String[8];
    private int[] contextPriority = new int[8];
    private boolean[] contextBlocking = new boolean[8];
    private boolean[] contextActive = new boolean[8];
    private int[] byPriority = new int[0];
    private int contextCount;

    // Bindings (structure of arrays)
    private int[] bContext = new int[32];
    private int[] bAction = new int[32];
    private int[] bKind = new int[32];
    private int[] bCode = new int[32];
    private int[] bCode2 = new int[32];
    private int[] bMods = new int[32];
    private float[] bScale = new float[32];
    private boolean[] bPrevDown = new boolean[32];
    private int bindingCount;

    // Derived index, rebuilt after binding changes: bindings per context, and siblings sharing
    // the same physical input (for chord resolution).
    private int[] contextFirst = new int[0];
    private int[] contextNext = new int[0];
    private int[] siblingNext = new int[0];
    private boolean indexDirty = true;

    // Per-frame consumption stamps: frame and context that used the input.
    private final int[] keyConsumedFrame = new int[Scancode.COUNT];
    private final int[] keyConsumedBy = new int[Scancode.COUNT];
    private final int[] mouseConsumedFrame = new int[MouseButton.COUNT];
    private final int[] mouseConsumedBy = new int[MouseButton.COUNT];
    private final int[] padConsumedFrame = new int[GamepadButton.COUNT + GamepadAxis.COUNT];
    private final int[] padConsumedBy = new int[GamepadButton.COUNT + GamepadAxis.COUNT];
    private final int[] otherConsumedFrame = new int[8];
    private final int[] otherConsumedBy = new int[8];

    private float padDeadZone = 0.2f;
    private int frame;
    private List<Binding[]> defaults;
    private int[] defaultContexts;
    private int[] defaultActions;

    /** Creates an empty map. */
    public ActionMap() {}

    // ---- Registration (cold) -----------------------------------------------------------------

    /** Registers a button action and returns its ID. */
    public int button(String name) {
        return addAction(name, false);
    }

    /** Registers an axis action and returns its ID. */
    public int axis(String name) {
        return addAction(name, true);
    }

    private int addAction(String name, boolean isAxis) {
        if (actionId(name) >= 0) {
            throw new IllegalArgumentException("action '" + name + "' already exists");
        }
        if (actionCount == actionNames.length) {
            int n = actionCount * 2;
            actionNames = Arrays.copyOf(actionNames, n);
            actionIsAxis = Arrays.copyOf(actionIsAxis, n);
            down = Arrays.copyOf(down, n);
            prevDown = Arrays.copyOf(prevDown, n);
            pressedNow = Arrays.copyOf(pressedNow, n);
            pressedFrame = Arrays.copyOf(pressedFrame, n);
            releasedFrame = Arrays.copyOf(releasedFrame, n);
            value = Arrays.copyOf(value, n);
        }
        actionNames[actionCount] = name;
        actionIsAxis[actionCount] = isAxis;
        return actionCount++;
    }

    /** Returns the ID of the named action, or -1. */
    public int actionId(String name) {
        for (int i = 0; i < actionCount; i++) {
            if (actionNames[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** Returns the name of an action. */
    public String actionName(int action) {
        return actionNames[action];
    }

    /** Returns {@code true} for axis actions. */
    public boolean isAxis(int action) {
        return actionIsAxis[action];
    }

    /** Returns the number of actions. */
    public int actionCount() {
        return actionCount;
    }

    /**
     * Registers a context (inactive until {@link #setActive}).
     *
     * @param name name used in settings files
     * @param priority higher priorities see input first (for example UI 50, Dialogue 40, Targeting
     *     30, World 20, Camera 10)
     * @param blocking hide all input from lower-priority contexts while active
     * @return the context ID
     */
    public int context(String name, int priority, boolean blocking) {
        if (contextId(name) >= 0) {
            throw new IllegalArgumentException("context '" + name + "' already exists");
        }
        if (contextCount == contextNames.length) {
            int n = contextCount * 2;
            contextNames = Arrays.copyOf(contextNames, n);
            contextPriority = Arrays.copyOf(contextPriority, n);
            contextBlocking = Arrays.copyOf(contextBlocking, n);
            contextActive = Arrays.copyOf(contextActive, n);
        }
        int id = contextCount++;
        contextNames[id] = name;
        contextPriority[id] = priority;
        contextBlocking[id] = blocking;
        Integer[] order = new Integer[contextCount];
        for (int i = 0; i < contextCount; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(contextPriority[b], contextPriority[a]));
        byPriority = new int[contextCount];
        for (int i = 0; i < contextCount; i++) {
            byPriority[i] = order[i];
        }
        indexDirty = true;
        return id;
    }

    /** Returns the ID of the named context, or -1. */
    public int contextId(String name) {
        for (int i = 0; i < contextCount; i++) {
            if (contextNames[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** Returns the name of a context. */
    public String contextName(int context) {
        return contextNames[context];
    }

    /** Turns a context on or off. Takes effect at the next {@link #update(Input)}. */
    public void setActive(int context, boolean active) {
        contextActive[context] = active;
    }

    /** Returns whether a context is on. */
    public boolean isActive(int context) {
        return contextActive[context];
    }

    /** Sets the gamepad stick dead zone (default 0.2). */
    public void setPadDeadZone(float deadZone) {
        this.padDeadZone = deadZone;
    }

    // ---- Bindings (cold) ---------------------------------------------------------------------

    /** Adds a binding of {@code action} in {@code context}. */
    public void bind(int context, int action, Binding binding) {
        if (bindingCount == bKind.length) {
            int n = bindingCount * 2;
            bContext = Arrays.copyOf(bContext, n);
            bAction = Arrays.copyOf(bAction, n);
            bKind = Arrays.copyOf(bKind, n);
            bCode = Arrays.copyOf(bCode, n);
            bCode2 = Arrays.copyOf(bCode2, n);
            bMods = Arrays.copyOf(bMods, n);
            bScale = Arrays.copyOf(bScale, n);
            bPrevDown = Arrays.copyOf(bPrevDown, n);
        }
        int i = bindingCount++;
        bContext[i] = context;
        bAction[i] = action;
        bKind[i] = binding.kind();
        bCode[i] = binding.code();
        bCode2[i] = binding.code2();
        bMods[i] = binding.modifiers();
        bScale[i] = binding.scale();
        bPrevDown[i] = false;
        indexDirty = true;
    }

    /** Parses and adds a binding ({@link Binding#parse(String)}). */
    public void bind(int context, int action, String binding) {
        bind(context, action, Binding.parse(binding));
    }

    /** Removes every binding of {@code action} in {@code context}. */
    public void unbind(int context, int action) {
        int w = 0;
        for (int r = 0; r < bindingCount; r++) {
            if (bContext[r] == context && bAction[r] == action) {
                continue;
            }
            if (w != r) {
                bContext[w] = bContext[r];
                bAction[w] = bAction[r];
                bKind[w] = bKind[r];
                bCode[w] = bCode[r];
                bCode2[w] = bCode2[r];
                bMods[w] = bMods[r];
                bScale[w] = bScale[r];
                bPrevDown[w] = bPrevDown[r];
            }
            w++;
        }
        bindingCount = w;
        indexDirty = true;
    }

    /** Replaces all bindings of {@code action} in {@code context}. */
    public void rebind(int context, int action, List<Binding> bindings) {
        unbind(context, action);
        for (Binding b : bindings) {
            bind(context, action, b);
        }
    }

    /** Returns the bindings of {@code action} in {@code context} (cold path). */
    public List<Binding> bindings(int context, int action) {
        List<Binding> out = new ArrayList<>();
        for (int i = 0; i < bindingCount; i++) {
            if (bContext[i] == context && bAction[i] == action) {
                out.add(new Binding(bKind[i], bCode[i], bCode2[i], bMods[i], bScale[i]));
            }
        }
        return out;
    }

    /** Returns the number of bindings. */
    public int bindingCount() {
        return bindingCount;
    }

    /** Remembers the current bindings as the defaults for {@link #resetToDefaults()}. */
    public void markDefaults() {
        defaults = new ArrayList<>();
        defaultContexts = new int[contextCount * actionCount];
        defaultActions = new int[contextCount * actionCount];
        int n = 0;
        for (int c = 0; c < contextCount; c++) {
            for (int a = 0; a < actionCount; a++) {
                List<Binding> bs = bindings(c, a);
                if (!bs.isEmpty()) {
                    defaultContexts[n] = c;
                    defaultActions[n] = a;
                    defaults.add(bs.toArray(Binding[]::new));
                    n++;
                }
            }
        }
    }

    /** Restores the bindings saved by {@link #markDefaults()}. */
    public void resetToDefaults() {
        if (defaults == null) {
            return;
        }
        bindingCount = 0;
        for (int i = 0; i < defaults.size(); i++) {
            for (Binding b : defaults.get(i)) {
                bind(defaultContexts[i], defaultActions[i], b);
            }
        }
        indexDirty = true;
    }

    // ---- Persistence (cold) ------------------------------------------------------------------

    /** Settings key for the bindings of an action in a context. */
    private String key(int context, int action) {
        return "input.bindings." + contextNames[context] + "." + actionNames[action];
    }

    /**
     * Writes every (context, action) pair that has bindings or defaults as a list of {@link
     * Binding} strings under {@code input.bindings.<context>.<action>}.
     */
    public void save(Settings settings) {
        for (int c = 0; c < contextCount; c++) {
            for (int a = 0; a < actionCount; a++) {
                List<Binding> bs = bindings(c, a);
                if (bs.isEmpty() && !hasDefault(c, a)) {
                    continue;
                }
                List<String> text = new ArrayList<>(bs.size());
                for (Binding b : bs) {
                    text.add(b.toString());
                }
                settings.set(key(c, a), text);
            }
        }
    }

    /**
     * Replaces the bindings of every (context, action) pair found in {@code settings}; pairs that
     * are missing keep their current bindings. Unparsable entries are skipped and returned.
     *
     * @return the entries that could not be parsed
     */
    public List<String> load(Settings settings) {
        List<String> errors = new ArrayList<>();
        for (int c = 0; c < contextCount; c++) {
            for (int a = 0; a < actionCount; a++) {
                String key = key(c, a);
                if (!settings.contains(key)) {
                    continue;
                }
                List<Binding> parsed = new ArrayList<>();
                for (String text : settings.getStringList(key)) {
                    try {
                        parsed.add(Binding.parse(text));
                    } catch (IllegalArgumentException e) {
                        errors.add(key + ": " + e.getMessage());
                    }
                }
                rebind(c, a, parsed);
            }
        }
        return errors;
    }

    private boolean hasDefault(int context, int action) {
        if (defaults == null) {
            return false;
        }
        for (int i = 0; i < defaults.size(); i++) {
            if (defaultContexts[i] == context && defaultActions[i] == action) {
                return true;
            }
        }
        return false;
    }

    private void rebuildIndex() {
        contextFirst = new int[contextCount];
        Arrays.fill(contextFirst, -1);
        contextNext = new int[bindingCount];
        siblingNext = new int[bindingCount];
        Arrays.fill(siblingNext, -1);
        for (int i = bindingCount - 1; i >= 0; i--) {
            contextNext[i] = contextFirst[bContext[i]];
            contextFirst[bContext[i]] = i;
        }
        // Siblings: bindings in the same context on the same physical input, as a ring.
        for (int i = 0; i < bindingCount; i++) {
            int prev = i;
            for (int j = i + 1; j < bindingCount; j++) {
                if (bContext[j] == bContext[i]
                        && bKind[j] == bKind[i]
                        && bCode[j] == bCode[i]
                        && siblingNext[j] == -1) {
                    siblingNext[prev] = j;
                    prev = j;
                }
            }
            if (prev != i && siblingNext[prev] == -1) {
                siblingNext[prev] = i;
            }
        }
        indexDirty = false;
    }

    // ---- Per frame -----------------------------------------------------------------------------

    /**
     * Marks a key as used by something outside the map this frame (a focused text field), so no
     * binding sees it. Call after {@link Input#update()} and before {@link #update(Input)}.
     */
    public void consumeKey(int scancode, Input input) {
        keyConsumedFrame[scancode] = input.frame();
        keyConsumedBy[scancode] = -1;
    }

    /** Marks a mouse button as used outside the map this frame (UI clicked a widget). */
    public void consumeMouse(int button, Input input) {
        mouseConsumedFrame[button] = input.frame();
        mouseConsumedBy[button] = -1;
    }

    /** Marks the wheel as used outside the map this frame (UI scrolled a list). */
    public void consumeWheel(Input input) {
        otherConsumedFrame[0] = input.frame();
        otherConsumedBy[0] = -1;
        otherConsumedFrame[1] = input.frame();
        otherConsumedBy[1] = -1;
    }

    /** Evaluates every active context against this frame's input. Allocates nothing. */
    public void update(Input input) {
        if (indexDirty) {
            rebuildIndex();
        }
        frame = input.frame();
        int n = actionCount;
        for (int a = 0; a < n; a++) {
            prevDown[a] = down[a];
            down[a] = false;
            pressedNow[a] = false;
            value[a] = 0;
        }
        int held = input.modifiers();
        int[] order = byPriority;
        for (int k = 0; k < order.length; k++) {
            int ctx = order[k];
            if (!contextActive[ctx]) {
                continue;
            }
            for (int b = contextFirst[ctx]; b >= 0; b = contextNext[b]) {
                evaluate(b, ctx, input, held);
            }
            if (contextBlocking[ctx]) {
                break;
            }
        }
        for (int a = 0; a < n; a++) {
            if (pressedNow[a] || (down[a] && !prevDown[a])) {
                pressedFrame[a] = frame;
            }
            if (!down[a] && (prevDown[a] || pressedNow[a])) {
                releasedFrame[a] = frame;
            }
        }
    }

    private void evaluate(int b, int ctx, Input input, int held) {
        int mods = bMods[b];
        if ((held & mods) != mods || supersededBySibling(b, held)) {
            bPrevDown[b] = false;
            return;
        }
        int code = bCode[b];
        int action = bAction[b];
        boolean isDown = false;
        boolean pressed = false;
        float v = 0;
        switch (bKind[b]) {
            case Binding.KIND_KEY -> {
                if (consumed(keyConsumedFrame, keyConsumedBy, code, ctx)) {
                    return;
                }
                isDown = input.isKeyDown(code);
                pressed = input.wasKeyPressed(code);
                if (isDown || pressed) {
                    consume(keyConsumedFrame, keyConsumedBy, code, ctx);
                }
            }
            case Binding.KIND_MOUSE -> {
                if (consumed(mouseConsumedFrame, mouseConsumedBy, code, ctx)) {
                    return;
                }
                isDown = input.isMouseDown(code);
                pressed = input.wasMousePressed(code);
                if (isDown || pressed) {
                    consume(mouseConsumedFrame, mouseConsumedBy, code, ctx);
                }
            }
            case Binding.KIND_KEY_AXIS -> {
                int pos = bCode2[b];
                boolean negConsumed = consumed(keyConsumedFrame, keyConsumedBy, code, ctx);
                boolean posConsumed = consumed(keyConsumedFrame, keyConsumedBy, pos, ctx);
                if (!negConsumed && input.isKeyDown(code)) {
                    v -= 1;
                    consume(keyConsumedFrame, keyConsumedBy, code, ctx);
                }
                if (!posConsumed && input.isKeyDown(pos)) {
                    v += 1;
                    consume(keyConsumedFrame, keyConsumedBy, pos, ctx);
                }
                v *= bScale[b];
                isDown = v != 0;
            }
            case Binding.KIND_WHEEL -> {
                int slot = code == 1 || code >= 4 ? 1 : 0;
                if (consumed(otherConsumedFrame, otherConsumedBy, slot, ctx)) {
                    return;
                }
                float wheel = slot == 1 ? input.wheelX() : input.wheelY();
                switch (code) {
                    case 0, 1 -> v = wheel * bScale[b];
                    case 2, 5 -> pressed = wheel > 0;
                    default -> pressed = wheel < 0;
                }
                isDown = pressed;
                if (v != 0 || pressed) {
                    consume(otherConsumedFrame, otherConsumedBy, slot, ctx);
                }
            }
            case Binding.KIND_MOUSE_AXIS -> {
                v = (code == 0 ? input.mouseDeltaX() : input.mouseDeltaY()) * bScale[b];
                isDown = v != 0;
            }
            case Binding.KIND_PAD_BUTTON -> {
                if (consumed(padConsumedFrame, padConsumedBy, code, ctx)) {
                    return;
                }
                for (int s = 0; s < PAD_SLOTS; s++) {
                    if (input.isPadConnected(s)) {
                        isDown |= input.isPadDown(s, code);
                        pressed |= input.wasPadPressed(s, code);
                    }
                }
                if (isDown || pressed) {
                    consume(padConsumedFrame, padConsumedBy, code, ctx);
                }
            }
            case Binding.KIND_PAD_AXIS -> {
                int slotIndex = GamepadButton.COUNT + code;
                if (consumed(padConsumedFrame, padConsumedBy, slotIndex, ctx)) {
                    return;
                }
                float raw = strongestPadAxis(input, code);
                int direction = bCode2[b];
                if (direction == 0) {
                    v = applyDeadZone(raw, code) * bScale[b];
                    isDown = v != 0;
                } else {
                    isDown = raw * direction > AXIS_BUTTON_THRESHOLD;
                    pressed = isDown && !bPrevDown[b];
                }
                if (isDown) {
                    consume(padConsumedFrame, padConsumedBy, slotIndex, ctx);
                }
            }
            default -> {}
        }
        bPrevDown[b] = isDown;
        down[action] |= isDown;
        pressedNow[action] |= pressed;
        value[action] += actionIsAxis[action] ? v : (isDown || pressed ? 1 : 0);
        if (!actionIsAxis[action] && value[action] > 1) {
            value[action] = 1;
        }
    }

    private boolean supersededBySibling(int b, int held) {
        int mods = bMods[b];
        for (int s = siblingNext[b]; s >= 0 && s != b; s = siblingNext[s]) {
            int sm = bMods[s];
            if (sm != mods && (sm & mods) == mods && (held & sm) == sm) {
                return true;
            }
        }
        return false;
    }

    private boolean consumed(int[] frames, int[] by, int index, int ctx) {
        return frames[index] == frame && by[index] != ctx;
    }

    private void consume(int[] frames, int[] by, int index, int ctx) {
        frames[index] = frame;
        by[index] = ctx;
    }

    private static float strongestPadAxis(Input input, int axis) {
        float best = 0;
        for (int s = 0; s < PAD_SLOTS; s++) {
            if (input.isPadConnected(s)) {
                float v = input.padAxis(s, axis);
                if (Math.abs(v) > Math.abs(best)) {
                    best = v;
                }
            }
        }
        return best;
    }

    private float applyDeadZone(float v, int axis) {
        float dz = GamepadAxis.isTrigger(axis) ? 0.05f : padDeadZone;
        float a = Math.abs(v);
        if (a <= dz) {
            return 0;
        }
        return Math.copySign((a - dz) / (1 - dz), v);
    }

    // ---- Queries -----------------------------------------------------------------------------

    /** Returns {@code true} while any binding of the button action is held. */
    public boolean isDown(int action) {
        return down[action];
    }

    /** Returns {@code true} if the action was triggered this frame. */
    public boolean wasPressed(int action) {
        return pressedFrame[action] == frame;
    }

    /** Returns {@code true} if the action ended this frame. */
    public boolean wasReleased(int action) {
        return releasedFrame[action] == frame;
    }

    /**
     * Returns an axis action's value this frame (sum of its bindings: keys and sticks -1..1, wheel
     * and mouse motion unbounded), or 1/0 for a button action.
     */
    public float value(int action) {
        return value[action];
    }
}
