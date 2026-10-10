#version 460 core
// Outline mask (TASKS M7-08): writes the nearest highlighted surface's style and whether the
// scene hides it (its depth is behind the depth prepass), so outlines and x-ray silhouettes can
// show through walls.
#ifdef ALPHA_MASK
#include "/engine/material.glsl"
layout(location = 0) in vec4 vUv;
layout(location = 1) flat in uint vMaterial;
#endif
layout(location = 2) flat in uint vStyle;
layout(binding = 11) uniform sampler2D uSceneDepth;
layout(location = 0) out vec2 outMask; // x style / 255, y 1 when occluded

void main() {
#ifdef ALPHA_MASK
    JeddyMaterial m = jMaterials[vMaterial];
    float a = m.baseColor.a;
#ifdef BASE_COLOR_MAP
    a *= texture(jBaseColorMap, vec3(jeddySlotUv(m, 0u, vUv), float(m.layers.x))).a;
#endif
    if (a < m.misc.y) {
        discard;
    }
#endif
    float scene = texelFetch(uSceneDepth, ivec2(gl_FragCoord.xy), 0).r;
    float z = gl_FragCoord.z;
    // Reversed-Z: larger is nearer. The tolerance absorbs differences between this program's
    // and the prepass's transforms.
    bool occluded = scene > z + max(z * 2e-4, 1e-7);
    outMask = vec2(float(vStyle) / 255.0, occluded ? 1.0 : 0.0);
}
