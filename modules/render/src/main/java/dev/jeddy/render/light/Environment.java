package dev.jeddy.render.light;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.core.handle.Handles;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuBuffers;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.GpuTextures;
import dev.jeddy.render.gpu.SamplerDesc;
import dev.jeddy.render.gpu.StateCache;
import dev.jeddy.render.shader.ProgramCache;
import dev.jeddy.render.shader.ShaderFeatures;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Image-based lighting inputs (TASKS M7-02, DESIGN §9.3): the prefiltered radiance cube (unit 5),
 * the split-sum BRDF LUT (unit 6; generated on the GPU at startup by {@code pbr/brdf_lut.comp}) and
 * the SH9 irradiance uniform block (binding 2). Until a cooked environment ({@code .ktx2} cube with
 * {@code jeddy.sh9}) is set, a uniform gray environment lights the scene.
 *
 * <p>Thread ownership: the GL thread.
 */
public final class Environment implements AutoCloseable {
    /** Texture unit of the radiance cube. */
    public static final int CUBE_UNIT = 5;

    /** Texture unit of the BRDF LUT. */
    public static final int LUT_UNIT = 6;

    /** Uniform binding of the SH9 block. */
    public static final int SH_BINDING = 2;

    /** BRDF LUT size. */
    public static final int LUT_SIZE = 128;

    private static final float Y00 = 0.282095f;

    private final GpuDevice device;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment sh = arena.allocate(9 * 16, 16);
    private final MemorySegment uniformTexels = arena.allocate(6 * 8, 8); // one RGBA16F per face
    private final float[] uniformSh = new float[27];
    private final int shBuffer;
    private boolean shDirty;
    private final int lut;
    private final int defaultCube;
    private final int cubeSampler;
    private final int lutSampler;
    private int cube;
    private int levels = 1;
    private boolean ownsCube;

    /** Creates the default environment and generates the BRDF LUT. */
    public Environment(GpuDevice device, ProgramCache programs) {
        this.device = device;
        shBuffer = device.buffers().create(9 * 16, GpuBuffers.DYNAMIC, "environment SH9");
        lut = device.textures().create2D(GL_RG16F, LUT_SIZE, LUT_SIZE, 1, "BRDF LUT");
        int lutProgram =
                programs.getBlocking(
                        programs.defineCompute(
                                "brdf lut", "pbr/brdf_lut.comp", ShaderFeatures.NONE),
                        0);
        glUseProgram(lutProgram);
        glProgramUniform1i(lutProgram, 0, 512);
        glBindImageTexture(0, device.textures().glName(lut), 0, false, 0, GL_WRITE_ONLY, GL_RG16F);
        glDispatchCompute((LUT_SIZE + 7) / 8, (LUT_SIZE + 7) / 8, 1);
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);
        glUseProgram(0);
        device.state().invalidate();
        glEnable(GL_TEXTURE_CUBE_MAP_SEAMLESS); // filtering across cube faces, for every cube
        cubeSampler =
                device.samplers()
                        .get(
                                SamplerDesc.of(
                                        SamplerDesc.LINEAR,
                                        SamplerDesc.LINEAR,
                                        SamplerDesc.MIP_LINEAR,
                                        SamplerDesc.CLAMP));
        lutSampler = device.samplers().get(SamplerDesc.LINEAR_CLAMP);
        defaultCube =
                device.textures()
                        .create(
                                GpuTextures.KIND_CUBE,
                                GL_RGBA16F,
                                1,
                                1,
                                1,
                                1,
                                "default environment");
        setUniform(0.3f, 0.3f, 0.32f);
    }

    /**
     * Uses a uniform environment of radiance {@code (r, g, b)}: the cube is that color and the
     * irradiance is {@code π} times it.
     */
    public void setUniform(float r, float g, float b) {
        MemorySegment px = uniformTexels;
        for (int f = 0; f < 6; f++) {
            px.set(ValueLayout.JAVA_SHORT, f * 8L, Float.floatToFloat16(r));
            px.set(ValueLayout.JAVA_SHORT, f * 8L + 2, Float.floatToFloat16(g));
            px.set(ValueLayout.JAVA_SHORT, f * 8L + 4, Float.floatToFloat16(b));
            px.set(ValueLayout.JAVA_SHORT, f * 8L + 6, Float.floatToFloat16(1));
        }
        device.textures().uploadLevel(defaultCube, 0, px.address(), (int) px.byteSize());
        float[] coefficients = uniformSh; // only the constant band is non-zero
        float scale = (float) Math.PI / Y00;
        coefficients[0] = r * scale;
        coefficients[1] = g * scale;
        coefficients[2] = b * scale;
        releaseCube();
        cube = defaultCube;
        levels = 1;
        writeSh(coefficients);
    }

    /**
     * Uses a cooked environment: a prefiltered radiance cube (texture handle, owned by the caller
     * unless {@code takeOwnership}) and its SH9 irradiance ({@code 27} floats, RGB per basis).
     */
    public void set(int cubeTexture, float[] sh9, boolean takeOwnership) {
        if (device.textures().kind(cubeTexture) != GpuTextures.KIND_CUBE) {
            throw new IllegalArgumentException("environment needs a cube map");
        }
        releaseCube();
        cube = cubeTexture;
        ownsCube = takeOwnership;
        levels = device.textures().levels(cubeTexture);
        writeSh(sh9);
    }

    private void writeSh(float[] c) {
        for (int i = 0; i < 9; i++) {
            sh.set(ValueLayout.JAVA_FLOAT, i * 16L, c[i * 3]);
            sh.set(ValueLayout.JAVA_FLOAT, i * 16L + 4, c[i * 3 + 1]);
            sh.set(ValueLayout.JAVA_FLOAT, i * 16L + 8, c[i * 3 + 2]);
            sh.set(ValueLayout.JAVA_FLOAT, i * 16L + 12, 0f);
        }
        shDirty = true;
    }

    /**
     * Uploads changed SH coefficients through the frame ring (a GPU-side copy, D103), or from the
     * CPU when {@code ring} is null or full. The renderer calls it before binding.
     */
    public void flush(FrameRing ring) {
        if (!shDirty) {
            return;
        }
        if (ring == null
                || !ring.upload(device.buffers().glName(shBuffer), 0, sh, 0, sh.byteSize())) {
            device.buffers().update(shBuffer, 0, sh);
        }
        shDirty = false;
    }

    private void releaseCube() {
        if (ownsCube && cube != Handles.NULL && cube != defaultCube) {
            device.textures().destroy(cube);
        }
        ownsCube = false;
    }

    /** Returns the prefiltered mip count (the shader's roughness → LOD scale). */
    public int levels() {
        return levels;
    }

    /** Returns the BRDF LUT texture handle. */
    public int lutTexture() {
        return lut;
    }

    /** Binds the cube, the LUT and the SH block. */
    public void bind(StateCache state) {
        state.bindTexture(CUBE_UNIT, device.textures().glName(cube));
        state.bindSampler(CUBE_UNIT, cubeSampler);
        state.bindTexture(LUT_UNIT, device.textures().glName(lut));
        state.bindSampler(LUT_UNIT, lutSampler);
        state.bindUniform(SH_BINDING, device.buffers().glName(shBuffer), 0, 9 * 16);
    }

    @Override
    public void close() {
        releaseCube();
        device.textures().destroy(defaultCube);
        device.textures().destroy(lut);
        device.buffers().destroy(shBuffer);
        arena.close();
    }
}
