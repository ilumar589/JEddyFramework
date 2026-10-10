package dev.jeddy.core.job;

import java.io.Serial;

/**
 * Thrown by {@link JobSystem#wait(int)} when a job's kernel, or a job it depended on, threw.
 *
 * <p>Thread ownership: immutable.
 */
public final class JobFailedException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    /** Wraps the kernel's failure. */
    public JobFailedException(Throwable cause) {
        super("job failed: " + cause, cause);
    }
}
