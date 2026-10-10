#version 460 core
// Minimal HDR → display resolve until the post chain (TASKS M7-06) replaces it: exposure, a
// filmic curve and sRGB encoding (the backbuffer is not sRGB, D23).
#include "/engine/frame.glsl"
#include "/engine/color.glsl"

layout(binding = 0) uniform sampler2D uHdr;
layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec3 hdr = texture(uHdr, vUv).rgb * jEnvironment.w;
    outColor = vec4(jeddyLinearToSrgb(jeddyTonemapAces(hdr)), 1.0);
}
