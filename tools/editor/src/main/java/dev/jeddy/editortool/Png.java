package dev.jeddy.editortool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes RGBA8 pixels read back from OpenGL (rows bottom-up) as an opaque RGB PNG, with nothing but
 * {@code java.base}.
 *
 * <p>Thread ownership: stateless; any thread (cold path).
 */
final class Png {
    private Png() {}

    static void write(Path file, MemorySegment rgba, int width, int height) throws IOException {
        byte[] raw = new byte[(width * 3 + 1) * height];
        int o = 0;
        for (int y = height - 1; y >= 0; y--) {
            raw[o++] = 0; // filter: none
            long row = (long) y * width * 4;
            for (int x = 0; x < width; x++) {
                long p = row + x * 4L;
                raw[o++] = rgba.get(ValueLayout.JAVA_BYTE, p);
                raw[o++] = rgba.get(ValueLayout.JAVA_BYTE, p + 1);
                raw[o++] = rgba.get(ValueLayout.JAVA_BYTE, p + 2);
            }
        }
        Deflater deflater = new Deflater(6);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream(raw.length / 4);
        byte[] buf = new byte[1 << 16];
        while (!deflater.finished()) {
            z.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();

        ByteArrayOutputStream png = new ByteArrayOutputStream(z.size() + 64);
        png.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        byte[] header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = 8; // bit depth
        header[9] = 2; // RGB
        chunk(png, "IHDR", header);
        chunk(png, "IDAT", z.toByteArray());
        chunk(png, "IEND", new byte[0]);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, png.toByteArray());
    }

    private static void chunk(OutputStream out, String type, byte[] data) throws IOException {
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.write(len);
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.write(c);
    }

    private static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }
}
