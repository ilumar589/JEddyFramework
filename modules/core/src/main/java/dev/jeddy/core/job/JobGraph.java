package dev.jeddy.core.job;

import java.util.Arrays;

/**
 * A reusable DAG of jobs, built once and run every frame (DESIGN §4.5, §13.2). Nodes are added in
 * dependency order; each node may depend on any earlier nodes. Running the graph allocates nothing.
 *
 * <pre>{@code
 * JobGraph g = new JobGraph();
 * int broadphase = g.add(broadphaseKernel, 1, 1);
 * int crowd = g.add(crowdKernel, agentCount, 64, broadphase);
 * ...
 * g.setCount(crowd, agentCount); // per frame, if it changed
 * g.run(jobs);
 * }</pre>
 *
 * <p>Thread ownership: build and run from one thread; kernels run on the job system's threads.
 */
public final class JobGraph {
    private JobKernel[] kernels = new JobKernel[8];
    private int[] counts = new int[8];
    private int[] grains = new int[8];
    private int[][] deps = new int[8][];
    private int[] handles = new int[8];
    private int[] depScratch = new int[4];
    private int size;

    /** Creates an empty graph. */
    public JobGraph() {}

    /**
     * Adds a node.
     *
     * @param kernel the work
     * @param count number of items (0 skips the node; dependents still run)
     * @param grain items per chunk
     * @param dependsOn earlier node ids this node waits for
     * @return the new node's id
     */
    public int add(JobKernel kernel, int count, int grain, int... dependsOn) {
        for (int d : dependsOn) {
            if (d < 0 || d >= size) {
                throw new IllegalArgumentException("dependency " + d + " is not an earlier node");
            }
        }
        if (size == kernels.length) {
            int n = size * 2;
            kernels = Arrays.copyOf(kernels, n);
            counts = Arrays.copyOf(counts, n);
            grains = Arrays.copyOf(grains, n);
            deps = Arrays.copyOf(deps, n);
            handles = Arrays.copyOf(handles, n);
        }
        kernels[size] = kernel;
        counts[size] = count;
        grains[size] = grain;
        deps[size] = dependsOn.clone();
        if (dependsOn.length > depScratch.length) {
            depScratch = new int[dependsOn.length];
        }
        return size++;
    }

    /** Returns the number of nodes. */
    public int size() {
        return size;
    }

    /** Changes the item count of node {@code node} for following runs. */
    public void setCount(int node, int count) {
        counts[node] = count;
    }

    /**
     * Submits every node and waits for all of them. Rethrows the first failure as {@link
     * JobFailedException}.
     */
    public void run(JobSystem jobs) {
        for (int i = 0; i < size; i++) {
            int[] d = deps[i];
            for (int k = 0; k < d.length; k++) {
                depScratch[k] = handles[d[k]];
            }
            handles[i] = jobs.submit(kernels[i], counts[i], grains[i], depScratch, d.length);
        }
        JobFailedException failure = null;
        for (int i = size - 1; i >= 0; i--) {
            try {
                jobs.wait(handles[i]);
            } catch (JobFailedException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
