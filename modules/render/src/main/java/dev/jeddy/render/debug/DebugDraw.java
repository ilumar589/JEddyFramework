package dev.jeddy.render.debug;

import dev.jeddy.render.RenderPacket;

/**
 * Immediate-mode debug shapes from any thread (TASKS M5-10, DESIGN §9.5): physics, navigation and
 * gameplay add lines into their own per-thread buffer (indexed by the job system's thread index),
 * and the game thread merges them into the render packet at extract time. No locks, no allocation;
 * a full buffer drops lines and counts them.
 *
 * <p>Colors are packed {@code 0xAARRGGBB}.
 *
 * <p>Thread ownership: slot {@code t} belongs to the thread with job-system index {@code t}; {@link
 * #collect} runs on the game thread while no other thread draws.
 */
public final class DebugDraw {
    private final float[][] points; // per slot: x0 y0 z0 x1 y1 z1 per line
    private final int[][] colors;
    private final int[] counts;
    private final int capacity;
    private long dropped;
    private static final int CIRCLE_SEGMENTS = 24;
    private static final float[] COS = new float[CIRCLE_SEGMENTS + 1];
    private static final float[] SIN = new float[CIRCLE_SEGMENTS + 1];

    static {
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double a = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            COS[i] = (float) Math.cos(a);
            SIN[i] = (float) Math.sin(a);
        }
    }

    /** Creates buffers for {@code threadSlots} threads of {@code linesPerThread} lines each. */
    public DebugDraw(int threadSlots, int linesPerThread) {
        points = new float[threadSlots][linesPerThread * 6];
        colors = new int[threadSlots][linesPerThread];
        counts = new int[threadSlots];
        capacity = linesPerThread;
    }

    /** Adds a line. */
    public void line(
            int thread, float x0, float y0, float z0, float x1, float y1, float z1, int argb) {
        int n = counts[thread];
        if (n == capacity) {
            dropped++;
            return;
        }
        float[] p = points[thread];
        int o = n * 6;
        p[o] = x0;
        p[o + 1] = y0;
        p[o + 2] = z0;
        p[o + 3] = x1;
        p[o + 4] = y1;
        p[o + 5] = z1;
        colors[thread][n] = argb;
        counts[thread] = n + 1;
    }

    /** Adds the edges of an axis-aligned box. */
    public void box(
            int t,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            int argb) {
        line(t, minX, minY, minZ, maxX, minY, minZ, argb);
        line(t, maxX, minY, minZ, maxX, minY, maxZ, argb);
        line(t, maxX, minY, maxZ, minX, minY, maxZ, argb);
        line(t, minX, minY, maxZ, minX, minY, minZ, argb);
        line(t, minX, maxY, minZ, maxX, maxY, minZ, argb);
        line(t, maxX, maxY, minZ, maxX, maxY, maxZ, argb);
        line(t, maxX, maxY, maxZ, minX, maxY, maxZ, argb);
        line(t, minX, maxY, maxZ, minX, maxY, minZ, argb);
        line(t, minX, minY, minZ, minX, maxY, minZ, argb);
        line(t, maxX, minY, minZ, maxX, maxY, minZ, argb);
        line(t, maxX, minY, maxZ, maxX, maxY, maxZ, argb);
        line(t, minX, minY, maxZ, minX, maxY, maxZ, argb);
    }

    /** Adds a horizontal circle (selection rings, ranges, areas of effect). */
    public void circle(int t, float cx, float cy, float cz, float r, int argb) {
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            line(
                    t,
                    cx + COS[i] * r,
                    cy,
                    cz + SIN[i] * r,
                    cx + COS[i + 1] * r,
                    cy,
                    cz + SIN[i + 1] * r,
                    argb);
        }
    }

    /** Adds a wire sphere (three circles). */
    public void sphere(int t, float cx, float cy, float cz, float r, int argb) {
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            line(
                    t,
                    cx + COS[i] * r,
                    cy,
                    cz + SIN[i] * r,
                    cx + COS[i + 1] * r,
                    cy,
                    cz + SIN[i + 1] * r,
                    argb);
            line(
                    t,
                    cx + COS[i] * r,
                    cy + SIN[i] * r,
                    cz,
                    cx + COS[i + 1] * r,
                    cy + SIN[i + 1] * r,
                    cz,
                    argb);
            line(
                    t,
                    cx,
                    cy + COS[i] * r,
                    cz + SIN[i] * r,
                    cx,
                    cy + COS[i + 1] * r,
                    cz + SIN[i + 1] * r,
                    argb);
        }
    }

    /** Adds a vertical capsule (characters, collision shapes). */
    public void capsule(int t, float x, float y0, float y1, float z, float r, int argb) {
        circle(t, x, y0, z, r, argb);
        circle(t, x, y1, z, r, argb);
        for (int i = 0; i < 4; i++) {
            int k = i * CIRCLE_SEGMENTS / 4;
            line(t, x + COS[k] * r, y0, z + SIN[k] * r, x + COS[k] * r, y1, z + SIN[k] * r, argb);
        }
    }

    /** Adds a small three-axis cross. */
    public void cross(int t, float x, float y, float z, float size, int argb) {
        line(t, x - size, y, z, x + size, y, z, argb);
        line(t, x, y - size, z, x, y + size, z, argb);
        line(t, x, y, z - size, x, y, z + size, argb);
    }

    /** Returns how many lines were dropped because a thread's buffer was full. */
    public long dropped() {
        return dropped;
    }

    /** Moves every thread's lines into the packet and empties the buffers. */
    public void collect(RenderPacket packet) {
        int total = 0;
        for (int c : counts) {
            total += c;
        }
        packet.resizeDebugLines(total);
        int at = 0;
        for (int t = 0; t < counts.length; t++) {
            int n = counts[t];
            System.arraycopy(points[t], 0, packet.debugLines, at * 6, n * 6);
            System.arraycopy(colors[t], 0, packet.debugColors, at, n);
            at += n;
            counts[t] = 0;
        }
    }
}
