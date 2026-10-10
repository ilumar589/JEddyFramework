package dev.jeddy.render.gpu;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.core.handle.HandlePool;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.gl.GLDebug;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * Framebuffer objects over {@link GpuTextures} (TASKS M5-03).
 *
 * <p>Thread ownership: the GL context thread (main thread).
 */
public final class GpuFramebuffers {
    private final GpuDevice device;
    private final HandlePool handles = new HandlePool(16, Handles.MAX_INDEX + 1);
    private int[] names = new int[16];

    GpuFramebuffers(GpuDevice device) {
        this.device = device;
    }

    /**
     * Creates a framebuffer that renders into one layer (mip 0) of a 2D array color texture
     * (texture blits, atlas updates).
     */
    public int createColorLayer(int colorTexture, int layer, String label) {
        MemorySegment ids = device.scratch();
        glCreateFramebuffers(1, ids);
        int name = ids.get(ValueLayout.JAVA_INT, 0);
        glNamedFramebufferTextureLayer(
                name, GL_COLOR_ATTACHMENT0, device.textures().glName(colorTexture), 0, layer);
        glNamedFramebufferDrawBuffer(name, GL_COLOR_ATTACHMENT0);
        int status = glCheckNamedFramebufferStatus(name, GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            ids.set(ValueLayout.JAVA_INT, 0, name);
            glDeleteFramebuffers(1, ids);
            throw new IllegalStateException(
                    label + " is incomplete: 0x" + Integer.toHexString(status));
        }
        if (label != null) {
            GLDebug.label(GL_FRAMEBUFFER, name, label);
        }
        int h = handles.allocate();
        int i = Handles.index(h);
        if (i >= names.length) {
            names = Arrays.copyOf(names, Math.max(i + 1, names.length * 2));
        }
        names[i] = name;
        return h;
    }

    /**
     * Creates a depth-only framebuffer rendering into one layer of an array texture (shadow
     * cascades) or level 0 of a 2D texture ({@code layer < 0}).
     */
    public int createDepthLayer(int depthTexture, int layer, String label) {
        MemorySegment ids = device.scratch();
        glCreateFramebuffers(1, ids);
        int name = ids.get(ValueLayout.JAVA_INT, 0);
        int tex = device.textures().glName(depthTexture);
        if (layer < 0) {
            glNamedFramebufferTexture(name, GL_DEPTH_ATTACHMENT, tex, 0);
        } else {
            glNamedFramebufferTextureLayer(name, GL_DEPTH_ATTACHMENT, tex, 0, layer);
        }
        glNamedFramebufferDrawBuffer(name, GL_NONE);
        glNamedFramebufferReadBuffer(name, GL_NONE);
        int status = glCheckNamedFramebufferStatus(name, GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            ids.set(ValueLayout.JAVA_INT, 0, name);
            glDeleteFramebuffers(1, ids);
            throw new IllegalStateException(
                    label + " is incomplete: 0x" + Integer.toHexString(status));
        }
        if (label != null) {
            GLDebug.label(GL_FRAMEBUFFER, name, label);
        }
        int h = handles.allocate();
        int i = Handles.index(h);
        if (i >= names.length) {
            names = Arrays.copyOf(names, Math.max(i + 1, names.length * 2));
        }
        names[i] = name;
        return h;
    }

    /**
     * Creates a framebuffer with color attachments 0..n-1 (level 0 of each texture handle) and an
     * optional depth attachment ({@link Handles#NULL} for none).
     *
     * @throws IllegalStateException if the framebuffer is incomplete
     */
    public int create(int[] colorTextures, int depthTexture, String label) {
        MemorySegment ids = device.scratch();
        glCreateFramebuffers(1, ids);
        int name = ids.get(ValueLayout.JAVA_INT, 0);
        GpuTextures textures = device.textures();
        for (int c = 0; c < colorTextures.length; c++) {
            glNamedFramebufferTexture(
                    name, GL_COLOR_ATTACHMENT0 + c, textures.glName(colorTextures[c]), 0);
            ids.setAtIndex(ValueLayout.JAVA_INT, c, GL_COLOR_ATTACHMENT0 + c);
        }
        if (colorTextures.length == 0) {
            glNamedFramebufferDrawBuffer(name, GL_NONE);
            glNamedFramebufferReadBuffer(name, GL_NONE);
        } else {
            glNamedFramebufferDrawBuffers(name, colorTextures.length, ids);
        }
        if (depthTexture != Handles.NULL) {
            glNamedFramebufferTexture(name, GL_DEPTH_ATTACHMENT, textures.glName(depthTexture), 0);
        }
        int status = glCheckNamedFramebufferStatus(name, GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            ids.set(ValueLayout.JAVA_INT, 0, name);
            glDeleteFramebuffers(1, ids);
            throw new IllegalStateException(
                    label + " is incomplete: 0x" + Integer.toHexString(status));
        }
        if (label != null) {
            GLDebug.label(GL_FRAMEBUFFER, name, label);
        }
        int h = handles.allocate();
        int i = Handles.index(h);
        if (i >= names.length) {
            names = Arrays.copyOf(names, Math.max(i + 1, names.length * 2));
        }
        names[i] = name;
        return h;
    }

    /** Returns the GL framebuffer name. */
    public int glName(int handle) {
        if (!handles.isValid(handle)) {
            throw new IllegalArgumentException("stale framebuffer " + Handles.toString(handle));
        }
        return names[Handles.index(handle)];
    }

    /** Deletes the framebuffer (not its textures). */
    public void destroy(int handle) {
        int name = glName(handle);
        device.state().forgetFramebuffer(name);
        MemorySegment id = device.scratch();
        id.set(ValueLayout.JAVA_INT, 0, name);
        glDeleteFramebuffers(1, id);
        handles.free(handle);
    }

    void destroyAll() {
        for (int i = 0; i < handles.highWaterMark(); i++) {
            int h = handles.handleAt(i);
            if (h != Handles.NULL) {
                destroy(h);
            }
        }
    }
}
