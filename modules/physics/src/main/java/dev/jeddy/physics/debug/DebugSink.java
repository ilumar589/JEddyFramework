package dev.jeddy.physics.debug;

/**
 * Where physics and navigation debug shapes go (TASKS M8-14): {@code physics} cannot see the
 * renderer, so the app passes {@code renderDebugDraw::line} (same signature as the render module's
 * {@code DebugDraw.line}). The shape helpers are built from lines. Colors are {@code 0xAARRGGBB};
 * {@code thread} is the caller's job-system thread index.
 *
 * <p>Thread ownership: as the implementation's (the render {@code DebugDraw} takes one thread per
 * slot).
 */
@FunctionalInterface
public interface DebugSink {
    /** Adds a line. */
    void line(int thread, float x0, float y0, float z0, float x1, float y1, float z1, int argb);

    /** Adds a horizontal circle of 16 segments. */
    default void circle(int thread, float cx, float cy, float cz, float r, int argb) {
        float px = cx + r;
        float pz = cz;
        for (int i = 1; i <= 16; i++) {
            double a = Math.PI * 2 * i / 16;
            float x = cx + (float) Math.cos(a) * r;
            float z = cz + (float) Math.sin(a) * r;
            line(thread, px, cy, pz, x, cy, z, argb);
            px = x;
            pz = z;
        }
    }

    /** Adds a vertical cylinder (two circles and four sides). */
    default void cylinder(int thread, float x, float y, float z, float r, float h, int argb) {
        circle(thread, x, y, z, r, argb);
        circle(thread, x, y + h, z, r, argb);
        line(thread, x + r, y, z, x + r, y + h, z, argb);
        line(thread, x - r, y, z, x - r, y + h, z, argb);
        line(thread, x, y, z + r, x, y + h, z + r, argb);
        line(thread, x, y, z - r, x, y + h, z - r, argb);
    }

    /** Adds the edges of an axis-aligned box. */
    default void box(
            int thread, float x0, float y0, float z0, float x1, float y1, float z1, int argb) {
        line(thread, x0, y0, z0, x1, y0, z0, argb);
        line(thread, x1, y0, z0, x1, y0, z1, argb);
        line(thread, x1, y0, z1, x0, y0, z1, argb);
        line(thread, x0, y0, z1, x0, y0, z0, argb);
        line(thread, x0, y1, z0, x1, y1, z0, argb);
        line(thread, x1, y1, z0, x1, y1, z1, argb);
        line(thread, x1, y1, z1, x0, y1, z1, argb);
        line(thread, x0, y1, z1, x0, y1, z0, argb);
        line(thread, x0, y0, z0, x0, y1, z0, argb);
        line(thread, x1, y0, z0, x1, y1, z0, argb);
        line(thread, x1, y0, z1, x1, y1, z1, argb);
        line(thread, x0, y0, z1, x0, y1, z1, argb);
    }

    /** Adds an arc from {@code a} to {@code b} rising {@code height} in the middle (jumps). */
    default void arc(
            int thread,
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            float height,
            int argb) {
        float px = ax;
        float py = ay;
        float pz = az;
        for (int i = 1; i <= 8; i++) {
            float t = i / 8f;
            float x = ax + (bx - ax) * t;
            float y = ay + (by - ay) * t + height * 4 * t * (1 - t);
            float z = az + (bz - az) * t;
            line(thread, px, py, pz, x, y, z, argb);
            px = x;
            py = y;
            pz = z;
        }
    }
}
