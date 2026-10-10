package dev.jeddy.asset.format;

import static dev.jeddy.asset.format.Bytes.F32;
import static dev.jeddy.asset.format.Bytes.U16;
import static dev.jeddy.asset.format.Bytes.U32;
import static dev.jeddy.asset.format.Bytes.U64;
import static dev.jeddy.asset.format.Bytes.checkRange;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * A cooked material ({@code .jmat}, {@code docs/formats/jmat.md}, TASKS M4-06): shading model,
 * feature mask, PBR factors, alpha mode and texture references ({@code StrId}s of {@code .ktx2}
 * assets). The material loader declares each texture as a dependency, so textures load with the
 * material.
 *
 * <p>Thread ownership: immutable; any thread. {@link Builder} is single-threaded tool code.
 */
public final class MaterialData {
    /** {@code "JMAT"} in file order. */
    public static final int MAGIC = 0x54414D4A;

    /** The format version this code reads and writes. */
    public static final int VERSION = 1;

    /** Size of the fixed part; the texture table follows. */
    public static final int HEADER_SIZE = 128;

    /** Bytes per texture table entry. */
    public static final int TEXTURE_ENTRY_SIZE = 16;

    /** Shading model: PBR metallic-roughness (glTF). */
    public static final int SHADING_PBR = 0;

    /** Shading model: unlit (emissive and base color only). */
    public static final int SHADING_UNLIT = 1;

    /** Shading model: foliage (two-sided lighting, wind). */
    public static final int SHADING_FOLIAGE = 2;

    /** Alpha mode: opaque. */
    public static final int ALPHA_OPAQUE = 0;

    /** Alpha mode: alpha test against {@link #alphaCutoff()}. */
    public static final int ALPHA_MASK = 1;

    /** Alpha mode: blended (sorted, drawn after opaque). */
    public static final int ALPHA_BLEND = 2;

    /** Flag: render both faces. */
    public static final int FLAG_DOUBLE_SIDED = 1;

    /** Flag: casts shadows. */
    public static final int FLAG_CASTS_SHADOWS = 2;

    /** Texture slot: base color (sRGB BC7). */
    public static final int SLOT_BASE_COLOR = 0;

    /** Texture slot: normal map (BC5). */
    public static final int SLOT_NORMAL = 1;

    /** Texture slot: occlusion, roughness, metallic packed in R, G, B (BC7). */
    public static final int SLOT_ORM = 2;

    /** Texture slot: emissive (sRGB BC7). */
    public static final int SLOT_EMISSIVE = 3;

    /**
     * Texture slot: occlusion on its own (BC4), used when the source's occlusion map reads a
     * different UV set than its metallic-roughness map and so cannot be packed into ORM.
     */
    public static final int SLOT_OCCLUSION = 4;

    /** Texture flag: sampled with the second UV set. */
    public static final int TEXTURE_UV1 = 1;

    /** Feature bit: a base color texture. Bits 0..15 are the engine's; the rest are custom. */
    public static final int FEATURE_BASE_COLOR_MAP = 1;

    /** Feature bit: a normal map. */
    public static final int FEATURE_NORMAL_MAP = 1 << 1;

    /** Feature bit: an ORM texture. */
    public static final int FEATURE_ORM_MAP = 1 << 2;

    /** Feature bit: an emissive texture. */
    public static final int FEATURE_EMISSIVE_MAP = 1 << 3;

    /** Feature bit: a separate occlusion texture ({@link #SLOT_OCCLUSION}). */
    public static final int FEATURE_OCCLUSION_MAP = 1 << 4;

    /** Feature bit: alpha test. */
    public static final int FEATURE_ALPHA_MASK = 1 << 5;

    /** Feature bit: alpha blending. */
    public static final int FEATURE_ALPHA_BLEND = 1 << 6;

    /** Feature bit: double-sided (back faces flip the normal). */
    public static final int FEATURE_DOUBLE_SIDED = 1 << 7;

    /** Feature bit: a non-identity UV transform. */
    public static final int FEATURE_UV_TRANSFORM = 1 << 8;

    /** Feature bit: some texture reads the second UV set. */
    public static final int FEATURE_UV1 = 1 << 9;

    /**
     * Shader {@code #define} names of the engine feature bits, bit {@code i} at index {@code i}.
     */
    public static final List<String> FEATURE_NAMES =
            List.of(
                    "BASE_COLOR_MAP",
                    "NORMAL_MAP",
                    "ORM_MAP",
                    "EMISSIVE_MAP",
                    "OCCLUSION_MAP",
                    "ALPHA_MASK",
                    "ALPHA_BLEND",
                    "DOUBLE_SIDED",
                    "UV_TRANSFORM",
                    "UV1");

    /** Mask of the engine-defined feature bits. */
    public static final int ENGINE_FEATURES = 0xFFFF;

    private final int flags;
    private final int shadingModel;
    private final int featureMask;
    private final int alphaMode;
    private final float alphaCutoff;
    private final float[] baseColor;
    private final float[] emissive;
    private final float emissiveStrength;
    private final float metallic;
    private final float roughness;
    private final float normalScale;
    private final float occlusionStrength;
    private final float[] uvTransform;
    private final float uvRotation;
    private final int[] textureSlots;
    private final int[] textureFlags;
    private final long[] textureIds;

    private MaterialData(Builder b) {
        flags = b.flags;
        shadingModel = b.shadingModel;
        featureMask = b.featureMask;
        alphaMode = b.alphaMode;
        alphaCutoff = b.alphaCutoff;
        baseColor = b.baseColor.clone();
        emissive = b.emissive.clone();
        emissiveStrength = b.emissiveStrength;
        metallic = b.metallic;
        roughness = b.roughness;
        normalScale = b.normalScale;
        occlusionStrength = b.occlusionStrength;
        uvTransform = b.uvTransform.clone();
        uvRotation = b.uvRotation;
        textureSlots = Arrays.copyOf(b.textureSlots, b.textureCount);
        textureFlags = Arrays.copyOf(b.textureFlags, b.textureCount);
        textureIds = Arrays.copyOf(b.textureIds, b.textureCount);
    }

    /**
     * Parses a {@code .jmat}. The result copies everything it needs, so the file memory may be
     * released afterwards.
     *
     * @throws AssetFormatException if the file is malformed
     */
    public static MaterialData read(MemorySegment file) throws AssetFormatException {
        checkRange(file, 0, HEADER_SIZE, ".jmat header");
        if (file.get(U32, 0) != MAGIC) {
            throw new AssetFormatException("not a .jmat file");
        }
        int version = Short.toUnsignedInt(file.get(U16, 4));
        if (version != VERSION) {
            throw new AssetFormatException(".jmat version " + version + ", expected " + VERSION);
        }
        int count = file.get(U32, 92);
        if (count < 0 || count > 64) {
            throw new AssetFormatException(".jmat has " + count + " textures");
        }
        checkRange(file, HEADER_SIZE, (long) TEXTURE_ENTRY_SIZE * count, ".jmat textures");
        Builder b = new Builder();
        b.flags = Short.toUnsignedInt(file.get(U16, 6));
        b.shadingModel = file.get(U32, 8);
        b.featureMask = file.get(U32, 12);
        b.alphaMode = Byte.toUnsignedInt(file.get(ValueLayout.JAVA_BYTE, 16));
        if (b.alphaMode > ALPHA_BLEND) {
            throw new AssetFormatException(".jmat alpha mode " + b.alphaMode + " is unknown");
        }
        b.alphaCutoff = file.get(F32, 20);
        for (int i = 0; i < 4; i++) {
            b.baseColor[i] = file.get(F32, 24 + 4L * i);
            b.uvTransform[i] = file.get(F32, 72 + 4L * i);
        }
        for (int i = 0; i < 3; i++) {
            b.emissive[i] = file.get(F32, 40 + 4L * i);
        }
        b.emissiveStrength = file.get(F32, 52);
        b.metallic = file.get(F32, 56);
        b.roughness = file.get(F32, 60);
        b.normalScale = file.get(F32, 64);
        b.occlusionStrength = file.get(F32, 68);
        b.uvRotation = file.get(F32, 88);
        for (int i = 0; i < count; i++) {
            long at = HEADER_SIZE + (long) TEXTURE_ENTRY_SIZE * i;
            b.texture(file.get(U32, at), file.get(U64, at + 8), file.get(U32, at + 4));
        }
        return new MaterialData(b);
    }

    /** Encodes the material. */
    public byte[] toBytes() {
        int count = textureIds.length;
        ByteBuffer b =
                ByteBuffer.allocate(HEADER_SIZE + TEXTURE_ENTRY_SIZE * count)
                        .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, MAGIC);
        b.putShort(4, (short) VERSION);
        b.putShort(6, (short) flags);
        b.putInt(8, shadingModel);
        b.putInt(12, featureMask);
        b.put(16, (byte) alphaMode);
        b.putFloat(20, alphaCutoff);
        for (int i = 0; i < 4; i++) {
            b.putFloat(24 + 4 * i, baseColor[i]);
            b.putFloat(72 + 4 * i, uvTransform[i]);
        }
        for (int i = 0; i < 3; i++) {
            b.putFloat(40 + 4 * i, emissive[i]);
        }
        b.putFloat(52, emissiveStrength);
        b.putFloat(56, metallic);
        b.putFloat(60, roughness);
        b.putFloat(64, normalScale);
        b.putFloat(68, occlusionStrength);
        b.putFloat(88, uvRotation);
        b.putInt(92, count);
        for (int i = 0; i < count; i++) {
            int at = HEADER_SIZE + TEXTURE_ENTRY_SIZE * i;
            b.putInt(at, textureSlots[i]);
            b.putInt(at + 4, textureFlags[i]);
            b.putLong(at + 8, textureIds[i]);
        }
        return b.array();
    }

    /** Returns the flags ({@link #FLAG_DOUBLE_SIDED}, ...). */
    public int flags() {
        return flags;
    }

    /** Returns {@code true} if both faces are rendered. */
    public boolean doubleSided() {
        return (flags & FLAG_DOUBLE_SIDED) != 0;
    }

    /** Returns {@code true} if the material casts shadows. */
    public boolean castsShadows() {
        return (flags & FLAG_CASTS_SHADOWS) != 0;
    }

    /** Returns the shading model ({@link #SHADING_PBR}, ...). */
    public int shadingModel() {
        return shadingModel;
    }

    /** Returns the shader feature mask (selects the permutation; meaning set by the renderer). */
    public int featureMask() {
        return featureMask;
    }

    /** Returns the alpha mode ({@link #ALPHA_OPAQUE}, ...). */
    public int alphaMode() {
        return alphaMode;
    }

    /** Returns the alpha-test threshold for {@link #ALPHA_MASK}. */
    public float alphaCutoff() {
        return alphaCutoff;
    }

    /** Returns base color factor component {@code i} (RGBA, linear). */
    public float baseColor(int i) {
        return baseColor[i];
    }

    /** Returns emissive factor component {@code i} (RGB, linear). */
    public float emissive(int i) {
        return emissive[i];
    }

    /** Returns the emissive strength multiplier. */
    public float emissiveStrength() {
        return emissiveStrength;
    }

    /** Returns the metallic factor. */
    public float metallic() {
        return metallic;
    }

    /** Returns the roughness factor. */
    public float roughness() {
        return roughness;
    }

    /** Returns the normal map scale. */
    public float normalScale() {
        return normalScale;
    }

    /** Returns the occlusion strength. */
    public float occlusionStrength() {
        return occlusionStrength;
    }

    /** Returns UV transform component {@code i}: scale U, scale V, offset U, offset V. */
    public float uvTransform(int i) {
        return uvTransform[i];
    }

    /** Returns the UV rotation in radians. */
    public float uvRotation() {
        return uvRotation;
    }

    /** Returns the number of textures. */
    public int textureCount() {
        return textureIds.length;
    }

    /** Returns the slot of texture {@code i} ({@link #SLOT_BASE_COLOR}, ...). */
    public int textureSlot(int i) {
        return textureSlots[i];
    }

    /** Returns the flags of texture {@code i} ({@link #TEXTURE_UV1}). */
    public int textureFlags(int i) {
        return textureFlags[i];
    }

    /** Returns the asset id of texture {@code i}. */
    public long textureId(int i) {
        return textureIds[i];
    }

    /** Returns the index of the texture in {@code slot}, or -1. */
    public int findTexture(int slot) {
        for (int i = 0; i < textureSlots.length; i++) {
            if (textureSlots[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    /** Builds materials (cooker, tests). Defaults follow glTF. */
    public static final class Builder {
        int flags = FLAG_CASTS_SHADOWS;
        int shadingModel = SHADING_PBR;
        int featureMask;
        int alphaMode = ALPHA_OPAQUE;
        float alphaCutoff = 0.5f;
        final float[] baseColor = {1, 1, 1, 1};
        final float[] emissive = {0, 0, 0};
        float emissiveStrength = 1;
        float metallic = 1;
        float roughness = 1;
        float normalScale = 1;
        float occlusionStrength = 1;
        final float[] uvTransform = {1, 1, 0, 0};
        float uvRotation;
        int[] textureSlots = new int[4];
        int[] textureFlags = new int[4];
        long[] textureIds = new long[4];
        int textureCount;

        /** Creates a builder with glTF defaults. */
        public Builder() {}

        /** Sets the flags. */
        public Builder flags(int value) {
            flags = value;
            return this;
        }

        /** Sets the shading model. */
        public Builder shadingModel(int value) {
            shadingModel = value;
            return this;
        }

        /** Sets the feature mask. */
        public Builder featureMask(int value) {
            featureMask = value;
            return this;
        }

        /** Sets the alpha mode and cutoff. */
        public Builder alpha(int mode, float cutoff) {
            alphaMode = mode;
            alphaCutoff = cutoff;
            return this;
        }

        /** Sets the base color factor (linear RGBA). */
        public Builder baseColor(float r, float g, float b, float a) {
            baseColor[0] = r;
            baseColor[1] = g;
            baseColor[2] = b;
            baseColor[3] = a;
            return this;
        }

        /** Sets the emissive factor (linear RGB) and strength. */
        public Builder emissive(float r, float g, float b, float strength) {
            emissive[0] = r;
            emissive[1] = g;
            emissive[2] = b;
            emissiveStrength = strength;
            return this;
        }

        /** Sets metallic and roughness factors. */
        public Builder metallicRoughness(float metal, float rough) {
            metallic = metal;
            roughness = rough;
            return this;
        }

        /** Sets the normal scale and occlusion strength. */
        public Builder normalOcclusion(float normal, float occlusion) {
            normalScale = normal;
            occlusionStrength = occlusion;
            return this;
        }

        /** Sets the UV transform ({@code KHR_texture_transform}). */
        public Builder uvTransform(
                float scaleU, float scaleV, float offsetU, float offsetV, float rotation) {
            uvTransform[0] = scaleU;
            uvTransform[1] = scaleV;
            uvTransform[2] = offsetU;
            uvTransform[3] = offsetV;
            uvRotation = rotation;
            return this;
        }

        /** Adds a texture in {@code slot}. */
        public Builder texture(int slot, long id, int textureFlags) {
            if (textureCount == textureIds.length) {
                textureSlots = Arrays.copyOf(textureSlots, textureCount * 2);
                this.textureFlags = Arrays.copyOf(this.textureFlags, textureCount * 2);
                textureIds = Arrays.copyOf(textureIds, textureCount * 2);
            }
            textureSlots[textureCount] = slot;
            this.textureFlags[textureCount] = textureFlags;
            textureIds[textureCount] = id;
            textureCount++;
            return this;
        }

        /**
         * Sets the engine feature bits ({@link #ENGINE_FEATURES}) from the textures, alpha mode,
         * flags and UV transform, keeping custom bits.
         */
        public Builder deriveFeatures() {
            int f = 0;
            for (int i = 0; i < textureCount; i++) {
                f |=
                        switch (textureSlots[i]) {
                            case SLOT_BASE_COLOR -> FEATURE_BASE_COLOR_MAP;
                            case SLOT_NORMAL -> FEATURE_NORMAL_MAP;
                            case SLOT_ORM -> FEATURE_ORM_MAP;
                            case SLOT_EMISSIVE -> FEATURE_EMISSIVE_MAP;
                            case SLOT_OCCLUSION -> FEATURE_OCCLUSION_MAP;
                            default -> 0;
                        };
                if ((textureFlags[i] & TEXTURE_UV1) != 0) {
                    f |= FEATURE_UV1;
                }
            }
            if (alphaMode == ALPHA_MASK) {
                f |= FEATURE_ALPHA_MASK;
            } else if (alphaMode == ALPHA_BLEND) {
                f |= FEATURE_ALPHA_BLEND;
            }
            if ((flags & FLAG_DOUBLE_SIDED) != 0) {
                f |= FEATURE_DOUBLE_SIDED;
            }
            if (uvTransform[0] != 1
                    || uvTransform[1] != 1
                    || uvTransform[2] != 0
                    || uvTransform[3] != 0
                    || uvRotation != 0) {
                f |= FEATURE_UV_TRANSFORM;
            }
            featureMask = (featureMask & ~ENGINE_FEATURES) | f;
            return this;
        }

        /** Builds the material. */
        public MaterialData build() {
            return new MaterialData(this);
        }
    }
}
