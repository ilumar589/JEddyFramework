#version 460 core
// Depth only; alpha-tested materials discard below their cutoff, and in the main view (uCameraFade)
// faded surfaces drop the same dithered pixels as the lit pass.
#include "/engine/camera_fade.glsl"
layout(location = 1) uniform int uCameraFade;
layout(location = 2) in vec3 vWorld;
layout(location = 3) flat in uint vFlags;
layout(location = 4) flat in float vFade;
#ifdef ALPHA_MASK
#include "/engine/material.glsl"
layout(location = 0) in vec4 vUv;
layout(location = 1) flat in uint vMaterial;
#endif

void main() {
    if (uCameraFade != 0 && jeddyFadeDiscard(gl_FragCoord.xy, jeddyCameraFade(vWorld, vFlags, vFade))) {
        discard;
    }
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
