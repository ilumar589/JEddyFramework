#version 460 core
// Forward+ PBR vertex stage (TASKS M7-01): world position, tangent frame and UVs.
#include "/engine/vertex.glsl"
#include "/engine/instance.glsl"
#include "/engine/frame.glsl"

layout(location = 0) out vec3 vWorld;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out vec4 vTangent; // xyz tangent, w bitangent sign
layout(location = 3) out vec4 vUv;      // uv0, uv1
layout(location = 4) flat out uint vMaterial;
layout(location = 5) flat out uint vFlags;
layout(location = 6) flat out float vFade;

void main() {
    JeddyInstance inst = jeddyInstance();
    vec3 n;
    vec3 t;
    float handedness;
    jeddyTangentFrame(n, t, handedness);
    vWorld = jeddyTransformPoint(inst, jeddyPosition);
    float mirror = jeddyMirrored(inst) ? -1.0 : 1.0;
    vNormal = jeddyTransformNormal(inst, n);
    vTangent = vec4(normalize(jeddyTransformDirection(inst, t)), handedness * mirror);
    vUv = vec4(jeddyUv0, jeddyUv1);
    vMaterial = inst.data.x;
    vFlags = inst.data.y;
    vFade = uintBitsToFloat(inst.data.w);
    gl_Position = jViewProjection * vec4(vWorld, 1.0);
}
