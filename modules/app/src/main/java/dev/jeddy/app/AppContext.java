package dev.jeddy.app;

import dev.jeddy.core.config.Settings;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.core.time.FixedStepper;
import dev.jeddy.input.ActionMap;
import dev.jeddy.input.CrpgGestures;
import dev.jeddy.input.Input;
import dev.jeddy.input.KeyLabels;
import dev.jeddy.input.VirtualCursor;
import dev.jeddy.window.MainThreadQueue;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The game thread's view of a running app (DESIGN §13.2): input, actions, gestures, the job system,
 * simulation time and the queue for main-thread requests.
 *
 * <p>Thread ownership: game thread only, except {@link #quit()}, which any thread may call.
 */
public final class AppContext {
    final Input input;
    final ActionMap actions = new ActionMap();
    final CrpgGestures gestures = new CrpgGestures();
    final VirtualCursor virtualCursor = new VirtualCursor();
    final MainThreadQueue queue;
    final JobSystem jobs;
    final FixedStepper stepper;
    final KeyLabels keyLabels;
    final Settings settings;
    final Path settingsFile;
    final JeddyApp app;
    long frame;
    long frameNanos;

    AppContext(
            JeddyApp app,
            Input input,
            MainThreadQueue queue,
            JobSystem jobs,
            FixedStepper stepper,
            KeyLabels keyLabels,
            Settings settings,
            Path settingsFile) {
        this.app = app;
        this.input = input;
        this.queue = queue;
        this.jobs = jobs;
        this.stepper = stepper;
        this.keyLabels = keyLabels;
        this.settings = settings;
        this.settingsFile = settingsFile;
        gestures.virtualCursor(virtualCursor);
    }

    /** Returns this frame's device state. */
    public Input input() {
        return input;
    }

    /** Returns the action map, updated each frame before {@link Game#fixedUpdate}. */
    public ActionMap actions() {
        return actions;
    }

    /** Returns the CRPG gestures, updated each frame before {@link Game#fixedUpdate}. */
    public CrpgGestures gestures() {
        return gestures;
    }

    /** Returns the gamepad virtual cursor (driven by the gestures while a pad is in use). */
    public VirtualCursor virtualCursor() {
        return virtualCursor;
    }

    /** Returns the queue for main-thread requests ({@code WindowCommands}, rumble). */
    public MainThreadQueue mainThread() {
        return queue;
    }

    /** Returns the job system shared by both threads. */
    public JobSystem jobs() {
        return jobs;
    }

    /** Returns the simulation clock: pause, time scale, tick count. */
    public FixedStepper simulation() {
        return stepper;
    }

    /** Returns key labels for the current keyboard layout (binding prompts). */
    public KeyLabels keyLabels() {
        return keyLabels;
    }

    /** Returns the user settings (empty if no settings file was configured or found). */
    public Settings settings() {
        return settings;
    }

    /** Writes {@link #settings()} back to the configured file (cold path). */
    public void saveSettings() throws IOException {
        if (settingsFile != null) {
            settings.save(settingsFile);
        }
    }

    /** Returns the number of game frames started. */
    public long frame() {
        return frame;
    }

    /** Returns the duration of the previous game frame in nanoseconds. */
    public long frameNanos() {
        return frameNanos;
    }

    /** Ends the app after the current frame. Any thread. */
    public void quit() {
        app.requestStop();
    }
}
