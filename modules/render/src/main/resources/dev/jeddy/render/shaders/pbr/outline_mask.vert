#version 460 core
// Outline mask (TASKS M7-08): highlighted instances, positions only, carrying their highlight
// style (instance flags bits 8..11) to the fragment shader.
#include "/engine/vertex.glsl"
#include "/engine/instance.glsl"
#ifdef SKINNED
#include "/engine/skinning.glsl"
#endif

layout(location = 0) uniform mat4 uViewProjection;
layout(location = 2) flat out uint vStyle;
#ifdef ALPHA_MASK
layout(location = 0) out vec4 vUv;
layout(location = 1) flat out uint vMaterial;
#endif

void main() {
    JeddyInstance inst = jeddyInstance();
#ifdef SKINNED
    vec3 world = jeddyTransformPoint(inst, jeddySkinPoint(jeddySkin(inst.data.y), jeddyPosition));
#else
    vec3 world = jeddyTransformPoint(inst, jeddyPosition);
#endif
    vStyle = (inst.data.y >> 8u) & 15u;
#ifdef ALPHA_MASK
    vUv = vec4(jeddyUv0, jeddyUv1);
    vMaterial = inst.data.x;
#endif
    gl_Position = uViewProjection * vec4(world, 1.0);
}
