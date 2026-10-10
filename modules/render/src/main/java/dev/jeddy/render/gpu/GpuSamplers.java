package dev.jeddy.render.gpu;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.core.collections.IntIntMap;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Sampler objects, created once per {@link SamplerDesc} and cached (TASKS M5-03).
 *
 * <p>Thread ownership: the GL context thread (main thread).
 */
public final class GpuSamplers {
    private static final int[] WRAP = {
        GL_REPEAT, GL_CLAMP_TO_EDGE, GL_MIRRORED_REPEAT, GL_CLAMP_TO_BORDER
    };

    private final GpuDevice device;
    private final IntIntMap byDesc = new IntIntMap();

    GpuSamplers(GpuDevice device) {
        this.device = device;
    }

    /** Returns the GL sampler for {@code desc}, creating it on first use. */
    public int get(int desc) {
        int name = byDesc.get(desc);
        if (name > 0) {
            return name;
        }
        MemorySegment id = device.scratch();
        glCreateSamplers(1, id);
        name = id.get(ValueLayout.JAVA_INT, 0);
        int mip = SamplerDesc.mip(desc);
        boolean minLinear = SamplerDesc.min(desc) == SamplerDesc.LINEAR;
        int min =
                switch (mip) {
                    case SamplerDesc.MIP_NEAREST ->
                            minLinear ? GL_LINEAR_MIPMAP_NEAREST : GL_NEAREST_MIPMAP_NEAREST;
                    case SamplerDesc.MIP_LINEAR ->
                            minLinear ? GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_LINEAR;
                    default -> minLinear ? GL_LINEAR : GL_NEAREST;
                };
        glSamplerParameteri(name, GL_TEXTURE_MIN_FILTER, min);
        glSamplerParameteri(
                name,
                GL_TEXTURE_MAG_FILTER,
                SamplerDesc.mag(desc) == SamplerDesc.LINEAR ? GL_LINEAR : GL_NEAREST);
        glSamplerParameteri(name, GL_TEXTURE_WRAP_S, WRAP[SamplerDesc.wrap(desc, 0)]);
        glSamplerParameteri(name, GL_TEXTURE_WRAP_T, WRAP[SamplerDesc.wrap(desc, 1)]);
        glSamplerParameteri(name, GL_TEXTURE_WRAP_R, WRAP[SamplerDesc.wrap(desc, 2)]);
        int aniso = SamplerDesc.anisotropy(desc);
        if (aniso > 1) {
            glSamplerParameterf(
                    name,
                    GL_TEXTURE_MAX_ANISOTROPY,
                    Math.min(aniso, device.caps().maxAnisotropy()));
        }
        if (SamplerDesc.compare(desc)) {
            glSamplerParameteri(name, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
            glSamplerParameteri(name, GL_TEXTURE_COMPARE_FUNC, GL_GEQUAL);
        }
        byDesc.put(desc, name);
        return name;
    }

    /** Returns the number of sampler objects created. */
    public int count() {
        return byDesc.size();
    }

    void destroyAll() {
        MemorySegment id = device.scratch();
        for (int s = byDesc.firstSlot(); s >= 0; s = byDesc.nextSlot(s)) {
            id.set(ValueLayout.JAVA_INT, 0, byDesc.valueAt(s));
            glDeleteSamplers(1, id);
        }
        byDesc.clear();
    }
}
