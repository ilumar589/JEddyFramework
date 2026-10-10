// GPU skinning (TASKS M12-04, DESIGN §13.4, D80). The frame's palettes sit in a storage buffer at
// binding 9, three vec4 rows per joint matrix (row-major 3x4: joint model transform x inverse
// bind, relative to the instance transform); a skinned instance keeps its first palette matrix
// plus one in instance flag bits 12..31. The skin stream gives four joints (u16) and four weights
// (unorm16) per vertex. Vertex shaders of SKINNED variants only.
#pragma once
#include "/engine/vertex.glsl"

layout(location = 4) in uvec4 jeddyJoints;
layout(location = 5) in vec4 jeddyWeights;

layout(std430, binding = 9) readonly buffer JeddyPalettes {
    vec4 jeddyPaletteRows[];
};

// A blended skinning transform: three rows of a 3x4 matrix.
struct JeddySkin {
    vec4 row0;
    vec4 row1;
    vec4 row2;
};

// Blends the vertex's four palette matrices (linear blend skinning). Instances without a palette
// get the identity, so a skinned mesh drawn without one keeps its bind pose.
JeddySkin jeddySkin(uint instanceFlags) {
    uint palette = instanceFlags >> 12u;
    JeddySkin s;
    if (palette == 0u) {
        s.row0 = vec4(1.0, 0.0, 0.0, 0.0);
        s.row1 = vec4(0.0, 1.0, 0.0, 0.0);
        s.row2 = vec4(0.0, 0.0, 1.0, 0.0);
        return s;
    }
    uint base = palette - 1u;
    // Quantized weights may not sum to exactly 1.
    vec4 w = jeddyWeights / max(dot(jeddyWeights, vec4(1.0)), 1e-6);
    s.row0 = vec4(0.0);
    s.row1 = vec4(0.0);
    s.row2 = vec4(0.0);
    for (int k = 0; k < 4; k++) {
        uint r = (base + jeddyJoints[k]) * 3u;
        s.row0 += w[k] * jeddyPaletteRows[r];
        s.row1 += w[k] * jeddyPaletteRows[r + 1u];
        s.row2 += w[k] * jeddyPaletteRows[r + 2u];
    }
    return s;
}

vec3 jeddySkinPoint(JeddySkin s, vec3 p) {
    vec4 v = vec4(p, 1.0);
    return vec3(dot(s.row0, v), dot(s.row1, v), dot(s.row2, v));
}

// Directions (normals and tangents) by the linear part; joints scale uniformly, so normals stay
// perpendicular. Not normalized: the instance transform's normalization follows.
vec3 jeddySkinDirection(JeddySkin s, vec3 d) {
    return vec3(dot(s.row0.xyz, d), dot(s.row1.xyz, d), dot(s.row2.xyz, d));
}
