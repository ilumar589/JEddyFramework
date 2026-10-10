// Punctual lights and the cluster grid (DESIGN §9.3, TASKS M7-03). Lights live in a storage
// buffer (binding 2); the light-cull compute pass writes, per cluster, an (offset, count) pair
// (binding 3) into a list of light indices (binding 4).
#pragma once
#include "frame.glsl"

const uint J_LIGHT_POINT = 0u;
const uint J_LIGHT_SPOT = 1u;

struct JeddyLight {
    vec4 positionRange;  // xyz world position, w range in meters (0 = unlimited)
    vec4 colorType;      // rgb linear color times intensity (candela), w type
    vec4 directionOuter; // xyz unit direction the spot points, w cos(outer angle)
    vec4 params;         // x cos(inner angle), y shadow index (< 0: none), zw unused
};

layout(std430, binding = 2) readonly buffer JeddyLights {
    JeddyLight jLights[];
};

layout(std430, binding = 3) readonly buffer JeddyClusterGrid {
    uvec2 jClusterRanges[]; // offset, count
};

layout(std430, binding = 4) readonly buffer JeddyClusterItems {
    uint jClusterLights[];
};

// Index of the cluster holding a fragment at window position fragCoord and view depth z.
uint jeddyClusterIndex(vec2 fragCoord, float viewDepth) {
    uvec3 dims = jClusterDims.xyz;
    uvec2 tile = min(uvec2(fragCoord) / jClusterDims.w, dims.xy - 1u);
    float slice;
    if (jeddyOrthographic()) {
        slice = (viewDepth - jClusterZ.x) / (jClusterZ.y - jClusterZ.x) * float(dims.z);
    } else {
        slice = log(max(viewDepth, 1e-4)) * jClusterZ.z + jClusterZ.w;
    }
    uint z = uint(clamp(slice, 0.0, float(dims.z - 1u)));
    return (z * dims.y + tile.y) * dims.x + tile.x;
}

// Smooth inverse-square falloff that reaches 0 at the light's range (Karis 2013).
float jeddyDistanceAttenuation(float d2, float range) {
    float falloff = 1.0 / max(d2, 1e-4);
    if (range > 0.0) {
        float r = d2 / (range * range);
        float window = clamp(1.0 - r * r, 0.0, 1.0);
        falloff *= window * window;
    }
    return falloff;
}

float jeddySpotAttenuation(JeddyLight light, vec3 l) {
    float cosOuter = light.directionOuter.w;
    float cosInner = light.params.x;
    float cd = dot(-l, light.directionOuter.xyz);
    float t = clamp((cd - cosOuter) / max(cosInner - cosOuter, 1e-4), 0.0, 1.0);
    return t * t;
}
