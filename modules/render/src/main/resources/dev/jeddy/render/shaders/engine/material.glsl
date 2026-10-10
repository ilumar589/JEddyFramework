// Material rows (DESIGN §9.2; MaterialSystem writes them) and their textures: 2D arrays bound per
// draw batch to units 0-4, sampled at the material's layer. Feature defines come from the
// material's feature mask (MaterialData.FEATURE_NAMES).
#pragma once

struct JeddyMaterial {
    vec4 baseColor;     // linear rgba factor
    vec4 emissive;      // rgb factor, w strength
    vec4 pbr;           // metallic, roughness, normal scale, occlusion strength
    vec4 uvScaleOffset; // KHR_texture_transform scale.xy, offset.xy
    vec4 misc;          // x uv rotation, y alpha cutoff
    uvec4 layers;       // base color, normal, ORM, emissive layers
    uvec4 layers2;      // x occlusion layer, y uv1 slots (bit per slot), z material flags
    vec4 reserved;
};

layout(std430, binding = 1) readonly buffer JeddyMaterials {
    JeddyMaterial jMaterials[];
};

layout(binding = 0) uniform sampler2DArray jBaseColorMap;
layout(binding = 1) uniform sampler2DArray jNormalMap;
layout(binding = 2) uniform sampler2DArray jOrmMap;
layout(binding = 3) uniform sampler2DArray jEmissiveMap;
layout(binding = 4) uniform sampler2DArray jOcclusionMap;

vec2 jeddyMaterialUv(JeddyMaterial m, vec2 uv) {
#ifdef UV_TRANSFORM
    float c = cos(m.misc.x);
    float s = sin(m.misc.x);
    uv = vec2(c * uv.x * m.uvScaleOffset.x + s * uv.y * m.uvScaleOffset.y,
              -s * uv.x * m.uvScaleOffset.x + c * uv.y * m.uvScaleOffset.y) + m.uvScaleOffset.zw;
#endif
    return uv;
}

// Picks uv0 or uv1 for texture slot s (bit s of layers2.y).
vec2 jeddySlotUv(JeddyMaterial m, uint slot, vec4 uvs) {
    return jeddyMaterialUv(m, (m.layers2.y & (1u << slot)) != 0u ? uvs.zw : uvs.xy);
}
