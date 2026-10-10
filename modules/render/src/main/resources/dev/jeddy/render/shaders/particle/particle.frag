#version 460 core
// Soft round particle sprites (TASKS M7-09), premultiplied alpha (alpha 0 is additive). Instead
// of a depth attachment, the scene depth is read: sprites fade where they near opaque surfaces
// (soft particles) and vanish behind them.
#include "/engine/frame.glsl"

layout(binding = 0) uniform sampler2D uSceneDepth;
layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vCorner;
layout(location = 2) in float vViewDepth;
layout(location = 3) in float vSize;
layout(location = 0) out vec4 outColor;

void main() {
    float d = max(texelFetch(uSceneDepth, ivec2(gl_FragCoord.xy), 0).r, 1e-7);
    vec2 ndc = gl_FragCoord.xy * jViewport.zw * 2.0 - 1.0;
    vec4 v = jInverseProjection * vec4(ndc, d, 1.0);
    float scene = -v.z / v.w;
    float fade = clamp((scene - vViewDepth) / max(vSize, 0.05), 0.0, 1.0);
    float shape = 1.0 - smoothstep(0.3, 1.0, length(vCorner));
    float k = fade * shape;
    if (k <= 0.0) {
        discard;
    }
    outColor = vColor * k;
}
