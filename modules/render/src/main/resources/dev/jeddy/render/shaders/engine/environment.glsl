// Image-based lighting (TASKS M7-02, DESIGN §10.3): a GGX-prefiltered radiance cube (mip i for
// roughness i / (levels - 1)), SH9 irradiance (already convolved with the cosine lobe; uniform
// block at binding 2) and the split-sum BRDF LUT.
#pragma once
#include "frame.glsl"

layout(binding = 5) uniform samplerCube jEnvironmentMap;
layout(binding = 6) uniform sampler2D jBrdfLut;

layout(std140, binding = 2) uniform JeddyIrradiance {
    vec4 jSh[9]; // rgb per coefficient: Y00, Y1-1, Y10, Y11, Y2-2, Y2-1, Y20, Y21, Y22
};

vec3 jeddyIrradiance(vec3 n) {
    vec3 e = jSh[0].rgb * 0.282095
        + jSh[1].rgb * (0.488603 * n.y)
        + jSh[2].rgb * (0.488603 * n.z)
        + jSh[3].rgb * (0.488603 * n.x)
        + jSh[4].rgb * (1.092548 * n.x * n.y)
        + jSh[5].rgb * (1.092548 * n.y * n.z)
        + jSh[6].rgb * (0.315392 * (3.0 * n.z * n.z - 1.0))
        + jSh[7].rgb * (1.092548 * n.x * n.z)
        + jSh[8].rgb * (0.546274 * (n.x * n.x - n.y * n.y));
    return max(e, vec3(0.0));
}

// Split-sum scale and bias of F0 for (N.V, roughness).
vec2 jeddyDfg(float NoV, float roughness) {
    return texture(jBrdfLut, vec2(NoV, roughness)).rg;
}

vec3 jeddyPrefiltered(vec3 r, float roughness) {
    return textureLod(jEnvironmentMap, r, roughness * max(jEnvironment.z - 1.0, 0.0)).rgb;
}
