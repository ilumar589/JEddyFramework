// Instance records written by DrawBuilder (DESIGN §9.2): three rows of the interpolated world
// transform (row-major 3x4) and (material, flags, entity, camera fade as float bits). Vertex
// shaders only.
#pragma once

struct JeddyInstance {
    vec4 row0;
    vec4 row1;
    vec4 row2;
    uvec4 data; // x material, y flags, z entity, w camera fade (float bits)
};

layout(std430, binding = 0) readonly buffer JeddyInstances {
    JeddyInstance jeddyInstances[];
};

JeddyInstance jeddyInstance() {
    return jeddyInstances[gl_BaseInstance + gl_InstanceID];
}

vec3 jeddyTransformPoint(JeddyInstance i, vec3 p) {
    vec4 v = vec4(p, 1.0);
    return vec3(dot(i.row0, v), dot(i.row1, v), dot(i.row2, v));
}

vec3 jeddyTransformDirection(JeddyInstance i, vec3 d) {
    return vec3(dot(i.row0.xyz, d), dot(i.row1.xyz, d), dot(i.row2.xyz, d));
}

// The 3x3 part of the instance transform (columns).
mat3 jeddyLinear(JeddyInstance i) {
    return transpose(mat3(i.row0.xyz, i.row1.xyz, i.row2.xyz));
}

// True if the transform mirrors (negative determinant): triangles flip their winding.
bool jeddyMirrored(JeddyInstance i) {
    mat3 m = jeddyLinear(i);
    return dot(m[0], cross(m[1], m[2])) < 0.0;
}

// Transforms a normal by the inverse transpose (the cofactor matrix up to scale), so non-uniform
// scale keeps normals perpendicular; mirrored transforms keep their outward direction.
vec3 jeddyTransformNormal(JeddyInstance i, vec3 n) {
    mat3 m = jeddyLinear(i);
    mat3 cofactor = mat3(cross(m[1], m[2]), cross(m[2], m[0]), cross(m[0], m[1]));
    float det = dot(m[0], cross(m[1], m[2]));
    return normalize(cofactor * n) * (det < 0.0 ? -1.0 : 1.0);
}
