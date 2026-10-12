package dev.jeddy.save;

import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.LockSupport;

/**
 * The writing half of a save (DESIGN §21, D118). {@link #submit} copies a {@link SaveWriter}'s
 * capture into buffers this job keeps (so the writer is free again at once and the copy allocates
 * nothing after the first save of that size), then wakes the job's <em>worker thread</em>, which
 * encodes the file (zstd, checksums), writes it to a temporary file beside the target and moves it
 * over the target atomically: a crash or a full disk halfway leaves the previous save intact. The
 * game polls {@link #isDone} and {@link #error} from its frame; nothing calls back into the game
 * thread.
 *
 * <p>One job writes one file at a time: a {@link #submit} while the previous write is still running
 * waits for it first (a player who saves faster than the disk writes gets a short wait rather than
 * a lost save). A game keeps one job per purpose (the player's saves, the autosave) or one for all.
 *
 * <p>Thread ownership: {@link #submit}, {@link #isDone}, {@link #error}, {@link #await} and {@link
 * #close} on the game thread (or any one thread); the encoding and the write on the job's own
 * daemon thread, which parks between saves.
 */
public final class SaveJob {
    private static final Logger LOG = Log.logger("save");

    private byte[] meta = new byte[256];
    private int metaSize;
    private byte[] payload = new byte[64 * 1024];
    private int payloadSize;
    private int chunkCount;
    private int level = SaveFile.DEFAULT_LEVEL;
    // The worker: one daemon thread for the life of the job, parked between writes, so a submit
    // allocates nothing (a thread per save would: its object, its stack, its name). A platform
    // thread, not a virtual one: unparking a virtual thread submits its continuation to the
    // scheduler's pool, which wraps it in a task object (24 B per save, measured by the test).
    private Thread worker;
    private volatile Path target; // the request: set by submit, cleared by the worker when done
    private volatile boolean closed;
    private volatile Throwable error;
    private volatile long bytesWritten;
    private volatile long writeNanos;
    private long captureNanos;

    /** A job with buffers that grow to the first capture's size. */
    public SaveJob() {}

    /** The zstd level the next submits use ({@link SaveFile#DEFAULT_LEVEL}; 0 for none). */
    public SaveJob level(int level) {
        this.level = level;
        return this;
    }

    /**
     * Takes {@code writer}'s capture and writes it to {@code target} in the background; {@code
     * captureNanos} is what the capture took, for {@link #captureNanos}. Waits for a write still in
     * progress. Allocates only when the capture outgrows the kept buffers (and, once, the worker
     * thread).
     */
    public void submit(SaveWriter writer, Path target, long captureNanos) {
        if (closed) {
            throw new IllegalStateException("job closed");
        }
        await();
        writer.finish();
        this.captureNanos = captureNanos;
        metaSize = writer.metaSize();
        if (meta.length < metaSize) {
            meta = new byte[Math.max(metaSize, meta.length * 2)];
        }
        System.arraycopy(writer.metaBuffer(), 0, meta, 0, metaSize);
        payloadSize = writer.payloadSize();
        if (payload.length < payloadSize) {
            payload = new byte[Math.max(payloadSize, payload.length * 2)];
        }
        System.arraycopy(writer.payloadBuffer(), 0, payload, 0, payloadSize);
        chunkCount = writer.chunkCount();
        error = null;
        bytesWritten = 0;
        if (worker == null) {
            worker = Thread.ofPlatform().daemon(true).name("save-job").start(this::loop);
        }
        this.target = target; // the volatile write publishes the buffers to the worker
        LockSupport.unpark(worker);
    }

    /** {@link #submit(SaveWriter, Path, long)} without a capture time. */
    public void submit(SaveWriter writer, Path target) {
        submit(writer, target, 0);
    }

    private void loop() {
        while (!closed) {
            Path t = target;
            if (t == null) {
                LockSupport.park(this);
                continue;
            }
            long t0 = System.nanoTime();
            try {
                byte[] file =
                        SaveFile.encode(meta, metaSize, payload, payloadSize, chunkCount, level);
                write(file, t);
                bytesWritten = file.length;
            } catch (Throwable e) {
                error = e;
                LOG.warn("save to " + t + " failed: " + e);
            } finally {
                writeNanos = System.nanoTime() - t0;
                target = null; // done: the volatile write publishes the results
            }
        }
    }

    /**
     * Writes {@code file} to {@code target} through a temporary file in the same directory and an
     * atomic move, so the target is either the old file or the whole new one.
     */
    public static void write(byte[] file, Path target) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, file);
        try {
            Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // A file system without atomic replace (rare: some network shares): the plain move
            // still replaces, with a window where the target is missing.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Whether no write is in progress (also before the first submit). */
    public boolean isDone() {
        return target == null;
    }

    /** Waits for the write in progress, if any (parks in short naps; a cold path). */
    public void await() {
        while (target != null) {
            LockSupport.parkNanos(this, 100_000);
        }
    }

    /** Stops the worker after the write in progress; the job takes no more submits. */
    public void close() {
        closed = true;
        await();
        Thread w = worker;
        if (w != null) {
            LockSupport.unpark(w);
        }
    }

    /** What the last write failed with, or {@code null}. */
    public Throwable error() {
        return error;
    }

    /** Bytes of the last file written (0 while in progress or after a failure). */
    public long bytesWritten() {
        return bytesWritten;
    }

    /** What the last encode and write took on the worker thread. */
    public long writeNanos() {
        return writeNanos;
    }

    /** What the last capture took on the game thread, as the submitter reported it. */
    public long captureNanos() {
        return captureNanos;
    }

    /** Bytes the kept payload buffer can hold before growing. */
    public int payloadCapacity() {
        return payload.length;
    }

    @Override
    public String toString() {
        return "SaveJob[" + (isDone() ? "idle" : "writing") + ", " + bytesWritten + " B]";
    }
}
