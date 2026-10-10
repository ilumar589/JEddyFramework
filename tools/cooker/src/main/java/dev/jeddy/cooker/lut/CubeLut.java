package dev.jeddy.cooker.lut;

import java.io.IOException;

/**
 * A parsed Adobe/Resolve {@code .cube} 3D LUT: {@code LUT_3D_SIZE n}, optional {@code
 * DOMAIN_MIN}/{@code DOMAIN_MAX} and {@code TITLE} lines, {@code #} comments, then n³ lines of
 * {@code r g b} with red varying fastest. 1D LUTs ({@code LUT_1D_SIZE}) are rejected.
 *
 * @param size entries per axis
 * @param rgb {@code size³ × 3} values, red fastest
 * @param domainMin input value of the first entry per channel
 * @param domainMax input value of the last entry per channel
 * @param title the {@code TITLE}, or an empty string
 */
public record CubeLut(int size, float[] rgb, float[] domainMin, float[] domainMax, String title) {
    /** Largest accepted size (256³ entries). */
    public static final int MAX_SIZE = 256;

    /** Parses {@code text}; {@code name} is used in error messages. */
    public static CubeLut parse(String text, String name) throws IOException {
        int size = 0;
        float[] min = {0, 0, 0};
        float[] max = {1, 1, 1};
        String title = "";
        float[] rgb = null;
        int count = 0;
        int lineNumber = 0;
        for (String raw : text.split("\\R")) {
            lineNumber++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] t = line.split("\\s+");
            char first = t[0].charAt(0);
            if (Character.isLetter(first)) {
                switch (t[0]) {
                    case "TITLE" -> title = line.substring(5).strip().replace("\"", "");
                    case "LUT_3D_SIZE" -> {
                        size = integer(t, name, lineNumber);
                        if (size < 2 || size > MAX_SIZE) {
                            throw error(name, lineNumber, "LUT_3D_SIZE must be 2.." + MAX_SIZE);
                        }
                        rgb = new float[size * size * size * 3];
                    }
                    case "LUT_1D_SIZE" ->
                            throw error(name, lineNumber, "1D LUTs are not supported");
                    case "DOMAIN_MIN" -> min = triple(t, name, lineNumber);
                    case "DOMAIN_MAX" -> max = triple(t, name, lineNumber);
                    case "LUT_3D_INPUT_RANGE" -> {
                        float[] r = pair(t, name, lineNumber);
                        min = new float[] {r[0], r[0], r[0]};
                        max = new float[] {r[1], r[1], r[1]};
                    }
                    default -> {
                        // Unknown keywords (vendor extensions) are ignored.
                    }
                }
                continue;
            }
            if (rgb == null) {
                throw error(name, lineNumber, "data before LUT_3D_SIZE");
            }
            if (count == size * size * size) {
                throw error(name, lineNumber, "more than " + count + " entries");
            }
            float[] v = values(t, 0, name, lineNumber);
            System.arraycopy(v, 0, rgb, count * 3, 3);
            count++;
        }
        if (rgb == null) {
            throw new IOException(name + ": no LUT_3D_SIZE");
        }
        if (count != size * size * size) {
            throw new IOException(name + ": " + count + " entries, expected " + size * size * size);
        }
        for (int c = 0; c < 3; c++) {
            if (!(max[c] > min[c])) {
                throw new IOException(name + ": DOMAIN_MAX must exceed DOMAIN_MIN");
            }
        }
        return new CubeLut(size, rgb, min, max, title);
    }

    /**
     * Samples the LUT trilinearly at an input color (in the LUT's domain), writing {@code
     * out[0..2]}.
     */
    public void sample(float r, float g, float b, float[] out) {
        float[] in = {r, g, b};
        int[] i0 = new int[3];
        float[] f = new float[3];
        for (int c = 0; c < 3; c++) {
            float x = (in[c] - domainMin[c]) / (domainMax[c] - domainMin[c]) * (size - 1);
            x = Math.clamp(x, 0f, size - 1f);
            i0[c] = Math.min((int) x, size - 2);
            f[c] = x - i0[c];
        }
        for (int c = 0; c < 3; c++) {
            float v = 0;
            for (int corner = 0; corner < 8; corner++) {
                int dx = corner & 1;
                int dy = corner >> 1 & 1;
                int dz = corner >> 2 & 1;
                float w =
                        (dx == 1 ? f[0] : 1 - f[0])
                                * (dy == 1 ? f[1] : 1 - f[1])
                                * (dz == 1 ? f[2] : 1 - f[2]);
                int index = ((i0[2] + dz) * size + i0[1] + dy) * size + i0[0] + dx;
                v += w * rgb[index * 3 + c];
            }
            out[c] = v;
        }
    }

    private static int integer(String[] t, String name, int line) throws IOException {
        if (t.length < 2) {
            throw error(name, line, t[0] + " needs a value");
        }
        try {
            return Integer.parseInt(t[1]);
        } catch (NumberFormatException e) {
            throw error(name, line, "not an integer: " + t[1]);
        }
    }

    private static float[] triple(String[] t, String name, int line) throws IOException {
        return values(t, 1, name, line);
    }

    private static float[] pair(String[] t, String name, int line) throws IOException {
        if (t.length < 3) {
            throw error(name, line, t[0] + " needs two values");
        }
        try {
            return new float[] {Float.parseFloat(t[1]), Float.parseFloat(t[2])};
        } catch (NumberFormatException e) {
            throw error(name, line, "not a number");
        }
    }

    private static float[] values(String[] t, int from, String name, int line) throws IOException {
        if (t.length < from + 3) {
            throw error(name, line, "expected three numbers");
        }
        float[] v = new float[3];
        try {
            for (int i = 0; i < 3; i++) {
                v[i] = Float.parseFloat(t[from + i]);
                if (!Float.isFinite(v[i])) {
                    throw error(name, line, "not finite: " + t[from + i]);
                }
            }
        } catch (NumberFormatException e) {
            throw error(name, line, "not a number: " + e.getMessage());
        }
        return v;
    }

    private static IOException error(String name, int line, String message) {
        return new IOException(name + ":" + line + ": " + message);
    }
}
