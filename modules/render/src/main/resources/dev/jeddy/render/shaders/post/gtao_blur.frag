#version 460 core
// Depth-aware 4x4 blur of the half-resolution AO (TASKS M7-05): removes the noise pattern
// without bleeding occlusion across depth edges.
#include "/engine/frame.glsl"

layout(binding = 0) uniform sampler2D uAo;
layout(binding = 1) uniform sampler2D uDepth;
layout(location = 0) in vec2 vUv;
layout(location = 0) out float outAo;

float viewDepth(vec2 uv) {
    float d = max(textureLod(uDepth, uv, 0.0).r, 1e-7);
    vec4 p = jInverseProjection * vec4(uv * 2.0 - 1.0, d, 1.0);
    return -p.z / p.w;
}

void main() {
    vec2 texel = 1.0 / vec2(textureSize(uAo, 0));
    float center = viewDepth(vUv);
    float sum = 0.0;
    float weight = 0.0;
    for (int y = -2; y < 2; y++) {
        for (int x = -2; x < 2; x++) {
            vec2 uv = vUv + (vec2(x, y) + 0.5) * texel;
            float w = 1.0 / (1e-3 + abs(viewDepth(uv) - center) / max(center, 1e-3) * 50.0);
            sum += textureLod(uAo, uv, 0.0).r * w;
            weight += w;
        }
    }
    outAo = sum / weight;
}
