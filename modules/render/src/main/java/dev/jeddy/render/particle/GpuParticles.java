package dev.jeddy.render.particle;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuBuffers;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.PipelineState;
import dev.jeddy.render.gpu.SamplerDesc;
import dev.jeddy.render.gpu.StateCache;
import dev.jeddy.render.graph.FrameGraph;
import dev.jeddy.render.graph.PassContext;
import dev.jeddy.render.shader.ProgramCache;
import dev.jeddy.render.shader.ShaderFeatures;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * GPU particles v1 (TASKS M7-09, DESIGN §9.3 pass 7): a fixed pool simulated by compute shaders,
 * fed by the packet's emitters ({@link RenderPacket#addEmitter}) and drawn as soft, camera-facing,
 * premultiplied-alpha sprites (alpha 0 is additive: sparks, fire, magic) after the transparent
 * pass. The CPU only turns emitter rates into spawn counts (bursts once per game frame); spawning,
 * aging, forces, death and the draw's instance count stay on the GPU. Sprites are not sorted.
 *
 * <p>Thread ownership: the render thread.
 */
public final class GpuParticles implements AutoCloseable {
    /** Storage binding of the particle pool. */
    public static final int PARTICLES_BINDING = 12;

    /** Storage binding of the counters, indirect draw and index lists. */
    public static final int LISTS_BINDING = 13;

    /** Storage binding of this frame's emitters. */
    public static final int EMITTERS_BINDING = 14;

    /** Bytes per particle. */
    public static final int PARTICLE_BYTES = 80;

    private static final int HEADER_BYTES = 64;
    private static final int EMITTER_BYTES = 112;
    private static final long DRAW_OFFSET = 16;

    private final GpuDevice device;
    private final ProgramCache programs;
    private final int capacity;
    private final int emitProgram;
    private final int simulateProgram;
    private final int argsProgram;
    private final int drawProgram;
    private final int emptyVao;
    private final int nearest;
    private final int particleBuffer;
    private final int listBuffer;
    private final long listBytes;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment counters = arena.allocate(16, 16);
    private int current;
    private int spawnTotal;
    private int emitterCount;
    private long emittersOffset;
    private long emittersBytes;
    private long lastPacketFrame = Long.MIN_VALUE;
    private long random = 0x9E3779B97F4A7C15L;
    private float seconds;
    private int depth = -1;
    private int frame;
    private FrameRing ring;

    /** Creates a pool of {@code capacity} particles (all dead). */
    public GpuParticles(GpuDevice device, ProgramCache programs, int capacity, int emptyVao) {
        this.device = device;
        this.programs = programs;
        this.capacity = capacity;
        this.emptyVao = emptyVao;
        emitProgram =
                programs.defineCompute(
                        "particle emit", "particle/particle_emit.comp", ShaderFeatures.NONE);
        simulateProgram =
                programs.defineCompute(
                        "particle simulate",
                        "particle/particle_simulate.comp",
                        ShaderFeatures.NONE);
        argsProgram =
                programs.defineCompute(
                        "particle args", "particle/particle_args.comp", ShaderFeatures.NONE);
        drawProgram =
                programs.define(
                        "particles",
                        "particle/particle.vert",
                        "particle/particle.frag",
                        ShaderFeatures.NONE);
        nearest = device.samplers().get(SamplerDesc.NEAREST_CLAMP);
        particleBuffer = device.buffers().create((long) capacity * PARTICLE_BYTES, 0, "particles");
        listBytes = HEADER_BYTES + 3L * capacity * 4;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment init = a.allocate(listBytes, 16);
            init.fill((byte) 0);
            init.set(ValueLayout.JAVA_INT, 0, capacity); // every particle starts dead
            for (int i = 0; i < capacity; i++) {
                init.set(ValueLayout.JAVA_INT, HEADER_BYTES + i * 4L, i);
            }
            listBuffer = device.buffers().create(init, GpuBuffers.DYNAMIC, "particle lists");
        }
    }

    /** Returns the pool size. */
    public int capacity() {
        return capacity;
    }

    /** Returns the GL name of the counters and lists buffer (header: dead, alive 0, alive 1). */
    public int listsGlName() {
        return device.buffers().glName(listBuffer);
    }

    /** Returns the GL name of the particle pool. */
    public int particlesGlName() {
        return device.buffers().glName(particleBuffer);
    }

    /** Returns the alive list the last frame drew (0 or 1), where the next frame emits. */
    public int drawnList() {
        return current;
    }

    /** Returns the particles spawned by the last {@link #collect}. */
    public int spawned() {
        return spawnTotal;
    }

    /**
     * Declares the compute passes and the sprite pass (after the transparent pass), drawing into
     * {@code hdr} and reading {@code sceneDepth}.
     */
    public void declare(FrameGraph graph, int hdr, int sceneDepth) {
        depth = sceneDepth;
        int pool = graph.importedBuffer("particles");
        graph.setBuffer(pool, device.buffers().glName(particleBuffer));
        // The counters, alive and dead lists, and the indirect draw command.
        int lists = graph.importedBuffer("particle lists");
        graph.setBuffer(lists, device.buffers().glName(listBuffer));
        graph.addComputePass("particle emit", this::emit)
                .readsStorage(lists)
                .writes(pool)
                .writes(lists);
        graph.addComputePass("particle simulate", this::simulate)
                .readsStorage(pool)
                .readsStorage(lists)
                .writes(pool)
                .writes(lists);
        graph.addComputePass("particle args", this::args).readsStorage(lists).writes(lists);
        graph.addPass("particles", this::draw)
                .color(hdr)
                .readsTexture(sceneDepth)
                .readsStorage(pool)
                .readsStorage(lists)
                .readsIndirect(lists);
    }

    /**
     * Turns the packet's emitters into this frame's spawns and copies them into the ring: rates
     * times {@code elapsedSeconds} (rounded at random), plus bursts once per game frame.
     */
    public void collect(RenderPacket p, FrameRing frameRing, float elapsedSeconds) {
        ring = frameRing;
        seconds = elapsedSeconds;
        boolean newFrame = p.frame != lastPacketFrame;
        lastPacketFrame = p.frame;
        int n = p.emitterCount;
        emitterCount = 0;
        spawnTotal = 0;
        if (n == 0) {
            return;
        }
        emittersBytes = (long) n * EMITTER_BYTES;
        emittersOffset =
                ring.allocate(emittersBytes, device.caps().shaderStorageBufferOffsetAlignment());
        if (emittersOffset < 0) {
            return;
        }
        MemorySegment m = ring.memory();
        float[] e = p.emitters;
        int total = 0;
        for (int i = 0; i < n; i++) {
            int o = i * RenderPacket.EMITTER_FLOATS;
            float rate = e[o + 20];
            float expected = rate * elapsedSeconds;
            int count = (int) expected;
            if (nextUniform() < expected - count) {
                count++;
            }
            if (newFrame) {
                count += (int) e[o + 21];
            }
            count = Math.min(count, capacity);
            long at = emittersOffset + (long) i * EMITTER_BYTES;
            MemorySegment.copy(e, o, m, ValueLayout.JAVA_FLOAT, at, RenderPacket.EMITTER_FLOATS);
            m.set(ValueLayout.JAVA_INT, at + 96, total);
            m.set(ValueLayout.JAVA_INT, at + 100, count);
            m.set(ValueLayout.JAVA_INT, at + 104, 0);
            m.set(ValueLayout.JAVA_INT, at + 108, 0);
            total += count;
        }
        emitterCount = n;
        spawnTotal = total;
    }

    private float nextUniform() {
        random ^= random << 13;
        random ^= random >>> 7;
        random ^= random << 17;
        return (random >>> 40) * (1f / (1 << 24));
    }

    private void bindBuffers(StateCache state) {
        state.bindStorage(
                PARTICLES_BINDING,
                device.buffers().glName(particleBuffer),
                0,
                (long) capacity * PARTICLE_BYTES);
        state.bindStorage(LISTS_BINDING, device.buffers().glName(listBuffer), 0, listBytes);
    }

    private void emit(PassContext ctx) {
        frame++;
        int program = programs.get(emitProgram, 0);
        if (spawnTotal == 0 || program == 0) {
            return;
        }
        StateCache state = ctx.state();
        state.useProgram(program);
        bindBuffers(state);
        state.bindStorage(EMITTERS_BINDING, ring.glName(), emittersOffset, emittersBytes);
        glProgramUniform4ui(program, 0, spawnTotal, emitterCount, capacity, current);
        glProgramUniform1ui(program, 1, frame * 0x9E3779B1);
        glDispatchCompute((spawnTotal + 63) / 64, 1, 1);
    }

    private void simulate(PassContext ctx) {
        int program = programs.get(simulateProgram, 0);
        if (program == 0) {
            return;
        }
        StateCache state = ctx.state();
        state.useProgram(program);
        bindBuffers(state);
        glProgramUniform4ui(program, 0, capacity, current, 0, 0);
        glProgramUniform1f(program, 1, seconds);
        glDispatchCompute((capacity + 255) / 256, 1, 1);
    }

    private void args(PassContext ctx) {
        int program = programs.get(argsProgram, 0);
        if (program == 0) {
            return;
        }
        StateCache state = ctx.state();
        state.useProgram(program);
        bindBuffers(state);
        glProgramUniform4ui(program, 0, capacity, current, 0, 0);
        glDispatchCompute(1, 1, 1);
        current = 1 - current;
    }

    private void draw(PassContext ctx) {
        int program = programs.get(drawProgram, 0);
        if (program == 0) {
            return;
        }
        StateCache state = ctx.state();
        state.apply(
                PipelineState.withBlend(
                        PipelineState.FULLSCREEN, PipelineState.BLEND_PREMULTIPLIED));
        state.useProgram(program);
        bindBuffers(state);
        // "args" already swapped the lists: current is the one the simulation filled.
        glProgramUniform4ui(program, 0, capacity, current, 0, 0);
        state.bindTexture(0, ctx.texture(depth));
        state.bindSampler(0, nearest);
        state.bindVertexArray(emptyVao);
        state.bindDrawIndirect(device.buffers().glName(listBuffer));
        glDrawArraysIndirect(GL_TRIANGLE_STRIP, DRAW_OFFSET);
    }

    /** Reads the dead and alive counters back (tests and debug views; stalls). */
    public int[] readCounters() {
        glGetNamedBufferSubData(device.buffers().glName(listBuffer), 0, 16, counters);
        return new int[] {
            counters.get(ValueLayout.JAVA_INT, 0),
            counters.get(ValueLayout.JAVA_INT, 4),
            counters.get(ValueLayout.JAVA_INT, 8)
        };
    }

    /** Compiles every program now (tests, loading screens). */
    public void compileBlocking() {
        programs.getBlocking(emitProgram, 0);
        programs.getBlocking(simulateProgram, 0);
        programs.getBlocking(argsProgram, 0);
        programs.getBlocking(drawProgram, 0);
    }

    @Override
    public void close() {
        device.buffers().destroy(particleBuffer);
        device.buffers().destroy(listBuffer);
        arena.close();
    }
}
