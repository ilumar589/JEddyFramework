#version 460 core
// Depth only; alpha-tested materials discard below their cutoff.
#ifdef ALPHA_MASK
#include "/engine/material.glsl"
layout(location = 0) in vec4 vUv;
layout(location = 1) flat in uint vMaterial;
#endif

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
}
