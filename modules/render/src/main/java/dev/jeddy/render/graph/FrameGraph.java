package dev.jeddy.render.graph;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.core.handle.Handles;
import dev.jeddy.gl.GLDebug;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.GpuTextures;
import dev.jeddy.render.gpu.PipelineState;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Frame graph v1 (TASKS M5-10, DESIGN §9.2): passes declare the resources they read and write;
 * {@link #compile} drops passes whose results nobody uses, checks that every read has an earlier
 * writer, allocates the transient textures (sized relative to the frame, pooled by descriptor) and
 * framebuffers, and works out memory barriers; {@link #execute} then runs the live passes in
 * declaration order with their barriers, clears, framebuffer, viewport and GPU timers.
 *
 * <p>Passes are declared once at startup. The graph is static between compiles (resize, settings
 * change), so executing a frame allocates nothing.
 *
 * <p>Thread ownership: the GL thread.
 */
public final class FrameGraph implements AutoCloseable {
    /** The window's default framebuffer (color). Writing it makes a pass live. */
    public static final int BACKBUFFER = 0;

    private static final int KIND_BACKBUFFER = 0;
    private static final int KIND_TRANSIENT = 1;
    private static final int KIND_IMPORTED_TEXTURE = 2;
    private static final int KIND_IMPORTED_BUFFER = 3;

    /** How a pass reads a resource; decides the barrier after a compute write. */
    private static final int READ_TEXTURE = GL_TEXTURE_FETCH_BARRIER_BIT;

    private static final int READ_STORAGE = GL_SHADER_STORAGE_BARRIER_BIT;
    private static final int READ_INDIRECT = GL_COMMAND_BARRIER_BIT;
    private static final int READ_IMAGE = GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;

    private final GpuDevice device;
    private final PassContext context = new PassContext(this);
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment clearValue = arena.allocate(16, 16);

    // Resources.
    private final List<String> resourceNames = new ArrayList<>();
    private final List<int[]> resourceDesc = new ArrayList<>(); // kind, format
    private final List<float[]> resourceScale = new ArrayList<>();
    private int[] glNames = new int[8];
    private int[] textureHandles = new int[8];

    // Passes.
    private final List<Pass> passes = new ArrayList<>();
    private Pass[] live = new Pass[0];
    private GpuTimers timers;
    private int width;
    private int height;
    private boolean compiled;
    private long barriers;

    /** A pass and its resource declarations. */
    public final class Pass {
        final String name;
        final boolean compute;
        final PassExecutor executor;
        final List<int[]> reads = new ArrayList<>(); // resource, usage
        final List<Integer> writes = new ArrayList<>();
        final List<Integer> colorTargets = new ArrayList<>();
        final List<float[]> colorClears = new ArrayList<>();
        int depthTarget = -1;
        float depthClear = Float.NaN;
        boolean sideEffect;
        final int index;
        // Compiled.
        int framebuffer; // GL name; 0 = default framebuffer
        int framebufferHandle = Handles.NULL;
        int barrierBits;
        boolean clears;

        Pass(String name, boolean compute, PassExecutor executor, int index) {
            this.name = name;
            this.compute = compute;
            this.executor = executor;
            this.index = index;
        }

        /** Renders into {@code resource} at the next color attachment, loading its contents. */
        public Pass color(int resource) {
            colorTargets.add(resource);
            colorClears.add(null);
            writes.add(resource);
            return this;
        }

        /** Renders into {@code resource}, cleared to {@code (r, g, b, a)} first. */
        public Pass color(int resource, float r, float g, float b, float a) {
            colorTargets.add(resource);
            colorClears.add(new float[] {r, g, b, a});
            writes.add(resource);
            return this;
        }

        /** Uses {@code resource} as the depth attachment, cleared to {@code clear} unless NaN. */
        public Pass depth(int resource, float clear) {
            depthTarget = resource;
            depthClear = clear;
            writes.add(resource);
            return this;
        }

        /** Tests against {@code resource} as depth without writing it (or clearing it). */
        public Pass depthReadOnly(int resource) {
            depthTarget = resource;
            depthClear = Float.NaN;
            reads.add(new int[] {resource, 0});
            return this;
        }

        /** Samples {@code resource} as a texture. */
        public Pass readsTexture(int resource) {
            reads.add(new int[] {resource, READ_TEXTURE});
            return this;
        }

        /** Reads {@code resource} as a shader storage buffer. */
        public Pass readsStorage(int resource) {
            reads.add(new int[] {resource, READ_STORAGE});
            return this;
        }

        /** Reads {@code resource} as draw or dispatch indirect arguments. */
        public Pass readsIndirect(int resource) {
            reads.add(new int[] {resource, READ_INDIRECT});
            return this;
        }

        /** Reads {@code resource} with image loads. */
        public Pass readsImage(int resource) {
            reads.add(new int[] {resource, READ_IMAGE});
            return this;
        }

        /** Writes {@code resource} from shaders (image stores or storage buffer writes). */
        public Pass writes(int resource) {
            writes.add(resource);
            return this;
        }

        /** Keeps the pass even when nothing reads its outputs (readbacks, queries). */
        public Pass sideEffect() {
            sideEffect = true;
            return this;
        }

        /** Returns the pass name. */
        public String name() {
            return name;
        }
    }

    /** Creates an empty graph. */
    public FrameGraph(GpuDevice device) {
        this.device = device;
        resourceNames.add("backbuffer");
        resourceDesc.add(new int[] {KIND_BACKBUFFER, 0});
        resourceScale.add(new float[] {1});
    }

    GpuDevice device() {
        return device;
    }

    /**
     * Declares a transient texture of {@code internalFormat}, {@code scale} times the frame size.
     */
    public int texture(String name, int internalFormat, float scale) {
        return resource(name, KIND_TRANSIENT, internalFormat, scale);
    }

    /**
     * Declares a texture owned elsewhere (shadow atlas, environment); set it with {@link
     * #setTexture}.
     */
    public int importedTexture(String name) {
        return resource(name, KIND_IMPORTED_TEXTURE, 0, 1);
    }

    /** Declares a buffer owned elsewhere (light clusters, indirect arguments). */
    public int importedBuffer(String name) {
        return resource(name, KIND_IMPORTED_BUFFER, 0, 1);
    }

    /** Sets the GL name of an imported texture. */
    public void setTexture(int resource, int glName) {
        ensure(resource);
        glNames[resource] = glName;
    }

    /** Sets the GL name of an imported buffer. */
    public void setBuffer(int resource, int glName) {
        ensure(resource);
        glNames[resource] = glName;
    }

    private int resource(String name, int kind, int format, float scale) {
        resourceNames.add(name);
        resourceDesc.add(new int[] {kind, format});
        resourceScale.add(new float[] {scale});
        compiled = false;
        int id = resourceNames.size() - 1;
        ensure(id);
        return id;
    }

    private void ensure(int resource) {
        if (resource >= glNames.length) {
            glNames = java.util.Arrays.copyOf(glNames, Math.max(resource + 1, glNames.length * 2));
            textureHandles = java.util.Arrays.copyOf(textureHandles, glNames.length);
        }
    }

    /** Adds a graphics pass. */
    public Pass addPass(String name, PassExecutor executor) {
        compiled = false;
        Pass p = new Pass(name, false, executor, passes.size());
        passes.add(p);
        return p;
    }

    /** Adds a compute pass. */
    public Pass addComputePass(String name, PassExecutor executor) {
        compiled = false;
        Pass p = new Pass(name, true, executor, passes.size());
        passes.add(p);
        return p;
    }

    /**
     * Culls, validates and allocates for a {@code w}×{@code h} frame. Call after declaring passes
     * and whenever the frame size changes.
     *
     * @throws IllegalStateException if a pass reads a transient nobody wrote before it
     */
    public void compile(int w, int h) {
        releaseTargets();
        width = w;
        height = h;
        int n = passes.size();
        // Liveness, back to front.
        boolean[] isLive = new boolean[n];
        boolean[] needed = new boolean[resourceNames.size()];
        for (int i = n - 1; i >= 0; i--) {
            Pass p = passes.get(i);
            boolean keep = p.sideEffect;
            for (int r : p.writes) {
                int kind = resourceDesc.get(r)[0];
                keep |= needed[r] || kind != KIND_TRANSIENT;
            }
            if (!keep) {
                continue;
            }
            isLive[i] = true;
            for (int[] read : p.reads) {
                needed[read[0]] = true;
            }
            if (p.depthTarget >= 0) {
                needed[p.depthTarget] = true;
            }
        }
        // Validation and barriers, front to back.
        boolean[] written = new boolean[resourceNames.size()];
        int[] pendingCompute = new int[resourceNames.size()]; // 1 if last written by compute
        List<Pass> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!isLive[i]) {
                continue;
            }
            Pass p = passes.get(i);
            int bits = 0;
            for (int[] read : p.reads) {
                int r = read[0];
                if (resourceDesc.get(r)[0] == KIND_TRANSIENT && !written[r]) {
                    throw new IllegalStateException(
                            "pass '"
                                    + p.name
                                    + "' reads '"
                                    + resourceNames.get(r)
                                    + "' before any pass writes it");
                }
                if (pendingCompute[r] != 0) {
                    bits |= read[1];
                }
            }
            p.barrierBits = bits;
            for (int r : p.writes) {
                written[r] = true;
                pendingCompute[r] = p.compute ? 1 : 0;
            }
            order.add(p);
        }
        // Transient textures.
        for (int r = 1; r < resourceNames.size(); r++) {
            int[] desc = resourceDesc.get(r);
            if (desc[0] != KIND_TRANSIENT || !needed[r] && !written[r]) {
                continue;
            }
            float scale = resourceScale.get(r)[0];
            int tw = Math.max(1, Math.round(w * scale));
            int th = Math.max(1, Math.round(h * scale));
            int t =
                    device.textures()
                            .create(
                                    GpuTextures.KIND_2D,
                                    desc[1],
                                    tw,
                                    th,
                                    1,
                                    1,
                                    resourceNames.get(r));
            textureHandles[r] = t;
            glNames[r] = device.textures().glName(t);
        }
        // Framebuffers.
        for (Pass p : order) {
            if (p.compute || p.colorTargets.isEmpty() && p.depthTarget < 0) {
                continue;
            }
            if (p.colorTargets.contains(BACKBUFFER)) {
                if (p.colorTargets.size() != 1 || p.depthTarget >= 0) {
                    throw new IllegalStateException(
                            "pass '" + p.name + "' mixes the backbuffer with other attachments");
                }
                p.framebuffer = 0;
                continue;
            }
            int[] colors = new int[p.colorTargets.size()];
            for (int c = 0; c < colors.length; c++) {
                colors[c] = targetTexture(p.colorTargets.get(c));
            }
            int depth = p.depthTarget >= 0 ? targetTexture(p.depthTarget) : Handles.NULL;
            p.framebufferHandle = device.framebuffers().create(colors, depth, p.name);
            p.framebuffer = device.framebuffers().glName(p.framebufferHandle);
        }
        for (Pass p : order) {
            p.clears = !Float.isNaN(p.depthClear) && p.depthTarget >= 0;
            for (float[] c : p.colorClears) {
                p.clears |= c != null;
            }
        }
        live = order.toArray(new Pass[0]);
        if (timers != null) {
            timers.close();
        }
        timers = new GpuTimers(Math.max(1, passes.size()));
        compiled = true;
    }

    private int targetTexture(int resource) {
        if (resourceDesc.get(resource)[0] != KIND_TRANSIENT) {
            throw new IllegalStateException(
                    "only transient textures can be attachments in v1: "
                            + resourceNames.get(resource));
        }
        return textureHandles[resource];
    }

    /** Runs the live passes for {@code frame}. Allocates nothing. */
    public void execute(long frame) {
        if (!compiled) {
            throw new IllegalStateException("compile the frame graph first");
        }
        context.width = width;
        context.height = height;
        context.frame = frame;
        timers.beginFrame();
        var state = device.state();
        for (Pass p : live) {
            timers.begin(p.index);
            if (p.barrierBits != 0) {
                glMemoryBarrier(p.barrierBits);
                barriers++;
            }
            if (!p.compute && (!p.colorTargets.isEmpty() || p.depthTarget >= 0)) {
                state.bindFramebuffer(p.framebuffer);
                float scale =
                        p.colorTargets.isEmpty()
                                ? scaleOf(p.depthTarget)
                                : scaleOf(p.colorTargets.get(0));
                state.viewport(
                        0,
                        0,
                        Math.max(1, Math.round(width * scale)),
                        Math.max(1, Math.round(height * scale)));
                if (p.clears) {
                    // Clears obey the color mask, depth mask and scissor test: open them up.
                    long s = state.state();
                    s = PipelineState.withColorMask(s, 0xF);
                    s = PipelineState.withScissor(s, false);
                    s = PipelineState.withDepthWrite(s, true);
                    state.apply(s);
                }
                for (int c = 0; c < p.colorClears.size(); c++) {
                    float[] rgba = p.colorClears.get(c);
                    if (rgba != null) {
                        MemorySegment.copy(rgba, 0, clearValue, ValueLayout.JAVA_FLOAT, 0, 4);
                        glClearNamedFramebufferfv(p.framebuffer, GL_COLOR, c, clearValue);
                    }
                }
                if (p.depthTarget >= 0 && !Float.isNaN(p.depthClear)) {
                    clearValue.set(ValueLayout.JAVA_FLOAT, 0, p.depthClear);
                    glClearNamedFramebufferfv(p.framebuffer, GL_DEPTH, 0, clearValue);
                }
            }
            GLDebug.pushGroup(p.name);
            p.executor.execute(context);
            GLDebug.popGroup();
            timers.end(p.index);
        }
    }

    private float scaleOf(int resource) {
        return resource == BACKBUFFER ? 1f : resourceScale.get(resource)[0];
    }

    /** Returns the number of declared passes. */
    public int passCount() {
        return passes.size();
    }

    /** Returns the name of pass {@code i}. */
    public String passName(int i) {
        return passes.get(i).name;
    }

    /** Returns {@code true} if pass {@code i} survived culling. */
    public boolean isLive(int i) {
        Pass p = passes.get(i);
        for (Pass l : live) {
            if (l == p) {
                return true;
            }
        }
        return false;
    }

    /** Returns the number of live passes. */
    public int livePassCount() {
        return live.length;
    }

    /** Returns the smoothed GPU time of pass {@code i} in milliseconds. */
    public double passMillis(int i) {
        return timers == null ? 0 : timers.millis(i);
    }

    /** Returns the number of memory barriers issued so far. */
    public long barriers() {
        return barriers;
    }

    /** Returns the GL name of a texture resource (after {@link #compile}). */
    int textureName(int resource) {
        return glNames[resource];
    }

    /** Returns the GL name of a buffer resource. */
    int bufferName(int resource) {
        return glNames[resource];
    }

    /** Returns the GL name of a texture resource, for passes outside the graph (debugging). */
    public int glTexture(int resource) {
        return glNames[resource];
    }

    private void releaseTargets() {
        for (Pass p : passes) {
            if (p.framebufferHandle != Handles.NULL) {
                device.framebuffers().destroy(p.framebufferHandle);
                p.framebufferHandle = Handles.NULL;
            }
        }
        for (int r = 1; r < resourceNames.size(); r++) {
            if (resourceDesc.get(r)[0] == KIND_TRANSIENT && textureHandles[r] != Handles.NULL) {
                device.textures().destroy(textureHandles[r]);
                textureHandles[r] = Handles.NULL;
                glNames[r] = 0;
            }
        }
    }

    /** Deletes the transient textures, framebuffers and timer queries. */
    @Override
    public void close() {
        releaseTargets();
        if (timers != null) {
            timers.close();
        }
        arena.close();
    }
}
