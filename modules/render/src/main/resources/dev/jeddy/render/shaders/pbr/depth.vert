#version 460 core
// Depth prepass and shadow casters (TASKS M7-04, M7-05): positions, plus UVs and the material
// for alpha-tested surfaces, and what the main view's camera fade needs (M7-10).
#include "/engine/vertex.glsl"
#include "/engine/instance.glsl"

layout(location = 0) uniform mat4 uViewProjection;
#ifdef ALPHA_MASK
layout(location = 0) out vec4 vUv;
layout(location = 1) flat out uint vMaterial;
#endif
layout(location = 2) out vec3 vWorld;
layout(location = 3) flat out uint vFlags;
layout(location = 4) flat out float vFade;

void main() {
    JeddyInstance inst = jeddyInstance();
    vec3 world = jeddyTransformPoint(inst, jeddyPosition);
#ifdef ALPHA_MASK
    vUv = vec4(jeddyUv0, jeddyUv1);
    vMaterial = inst.data.x;
#endif
    vWorld = world;
    vFlags = inst.data.y;
    vFade = uintBitsToFloat(inst.data.w);
    gl_Position = uViewProjection * vec4(world, 1.0);
}
