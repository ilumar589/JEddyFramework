// The mesh vertex layout of MeshRegistry (DESIGN §9.2, D27) and QTangent decoding.
#pragma once

layout(location = 0) in vec3 jeddyPosition;
layout(location = 1) in vec4 jeddyQTangent; // snorm16 x4, normalized by the vertex fetch
layout(location = 2) in vec2 jeddyUv0;
layout(location = 3) in vec2 jeddyUv1;

vec3 jeddyQuatRotate(vec4 q, vec3 v) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}

// Normal, tangent and bitangent sign of the vertex (MeshEncoding is the reference).
void jeddyTangentFrame(out vec3 normal, out vec3 tangent, out float handedness) {
    vec4 q = normalize(jeddyQTangent);
    normal = jeddyQuatRotate(q, vec3(0.0, 0.0, 1.0));
    tangent = jeddyQuatRotate(q, vec3(1.0, 0.0, 0.0));
    handedness = jeddyQTangent.w < 0.0 ? -1.0 : 1.0;
}
