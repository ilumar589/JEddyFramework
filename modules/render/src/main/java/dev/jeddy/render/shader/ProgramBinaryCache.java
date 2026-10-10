package dev.jeddy.render.shader;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.gl.GLCapabilities;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Linked program binaries on disk (TASKS M5-05, DESIGN §9.2): {@code glGetProgramBinary} after a
 * link, {@code glProgramBinary} on the next start. The key is a SHA-256 of the driver identity
 * (vendor, renderer, version) and the preprocessed sources, so a driver update or a shader edit
 * misses the cache instead of loading a stale binary; a binary the driver rejects is deleted.
 *
 * <p>Thread ownership: the GL thread.
 */
public final class ProgramBinaryCache {
    private static final Logger LOG = Log.logger("render.shader");
    private static final int MAGIC = 0x4E49424A; // "JBIN"

    private final Path dir;
    private final String driver;
    private final boolean supported;

    /** Creates a cache in {@code dir} (created on first store). */
    public ProgramBinaryCache(Path dir, GLCapabilities caps) {
        this.dir = dir;
        this.driver = caps.vendor() + "\n" + caps.renderer() + "\n" + caps.version();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment n = a.allocate(ValueLayout.JAVA_INT);
            glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS, n);
            supported = n.get(ValueLayout.JAVA_INT, 0) > 0;
        }
    }

    /** Returns {@code true} if the driver offers at least one program binary format. */
    public boolean isSupported() {
        return supported;
    }

    /** Returns the cache key of a program made of {@code stageSources}. */
    public String key(String... stageSources) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(driver.getBytes(StandardCharsets.UTF_8));
            for (String s : stageSources) {
                sha.update((byte) 0);
                sha.update(s.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Loads the binary for {@code key} into {@code program}; returns whether it linked. */
    public boolean load(String key, int program) {
        if (!supported) {
            return false;
        }
        Path file = dir.resolve(key + ".bin");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try (Arena a = Arena.ofConfined()) {
            byte[] bytes = Files.readAllBytes(file);
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (bytes.length < 8 || b.getInt(0) != MAGIC) {
                Files.deleteIfExists(file);
                return false;
            }
            int format = b.getInt(4);
            MemorySegment data = a.allocate(bytes.length - 8L);
            MemorySegment.copy(bytes, 8, data, ValueLayout.JAVA_BYTE, 0, bytes.length - 8);
            glProgramBinary(program, format, data, (int) data.byteSize());
            MemorySegment status = a.allocate(ValueLayout.JAVA_INT);
            glGetProgramiv(program, GL_LINK_STATUS, status);
            if (status.get(ValueLayout.JAVA_INT, 0) == GL_TRUE) {
                return true;
            }
            Files.deleteIfExists(file); // the driver changed its mind: recompile
            return false;
        } catch (IOException e) {
            LOG.warn("cannot read program binary " + file, e);
            return false;
        }
    }

    /** Stores {@code program}'s binary under {@code key} (atomically). */
    public void store(String key, int program) {
        if (!supported) {
            return;
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment n = a.allocate(ValueLayout.JAVA_INT);
            glGetProgramiv(program, GL_PROGRAM_BINARY_LENGTH, n);
            int length = n.get(ValueLayout.JAVA_INT, 0);
            if (length <= 0) {
                return;
            }
            MemorySegment data = a.allocate(length);
            MemorySegment format = a.allocate(ValueLayout.JAVA_INT);
            glGetProgramBinary(program, length, n, format, data);
            int written = n.get(ValueLayout.JAVA_INT, 0);
            byte[] out = new byte[8 + written];
            ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(0, MAGIC);
            b.putInt(4, format.get(ValueLayout.JAVA_INT, 0));
            MemorySegment.copy(data, ValueLayout.JAVA_BYTE, 0, out, 8, written);
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, key, ".tmp");
            Files.write(tmp, out);
            Files.move(tmp, dir.resolve(key + ".bin"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warn("cannot store a program binary in " + dir, e);
        }
    }
}
