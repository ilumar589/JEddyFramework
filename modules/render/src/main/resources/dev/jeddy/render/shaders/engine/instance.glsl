// Instance records written by DrawBuilder (DESIGN §9.2): three rows of the interpolated world
// transform (row-major 3x4) and (material, flags, entity, 0). Vertex shaders only.
#pragma once

struct JeddyInstance {
    vec4 row0;
    vec4 row1;
    vec4 row2;
    uvec4 data; // x material, y flags, z entity
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
