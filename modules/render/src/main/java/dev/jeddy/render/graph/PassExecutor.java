package dev.jeddy.render.graph;

/**
 * The work of a pass. Registered once; called every frame the pass is live, so it must not
 * allocate.
 *
 * <p>Thread ownership: the GL thread.
 */
@FunctionalInterface
public interface PassExecutor {
    /** Records the pass's GL work. The graph has bound its framebuffer and viewport. */
    void execute(PassContext context);
}
