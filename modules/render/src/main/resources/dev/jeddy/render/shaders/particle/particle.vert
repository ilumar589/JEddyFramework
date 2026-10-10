#version 460 core
// Camera-facing particle sprites (TASKS M7-09): instance i draws the i-th particle of the list
// the simulation just filled, as a 4-vertex strip.
#include "/engine/frame.glsl"
#include "particles.glsl"

layout(location = 0) uniform uvec4 uParams; // x capacity, y list to draw
layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vCorner;
layout(location = 2) out float vViewDepth;
layout(location = 3) out float vSize;

void main() {
    uint index = jIndices[jeddyAlive(uParams.y, uint(gl_InstanceID), uParams.x)];
    JeddyParticle p = jParticles[index];
    float t = clamp(p.positionAge.w / p.velocityLife.w, 0.0, 1.0);
    vColor = mix(p.colorStart, p.colorEnd, t);
    float size = mix(p.sizeForces.x, p.sizeForces.y, t);
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) * 2.0 - 1.0;
    vec3 right = vec3(jView[0][0], jView[1][0], jView[2][0]);
    vec3 up = vec3(jView[0][1], jView[1][1], jView[2][1]);
    vec3 world = p.positionAge.xyz + (right * corner.x + up * corner.y) * size;
    vCorner = corner;
    vSize = size;
    vViewDepth = jeddyViewDepth(p.positionAge.xyz);
    gl_Position = size > 0.0 ? jViewProjection * vec4(world, 1.0) : vec4(0.0, 0.0, -2.0, 1.0);
}
