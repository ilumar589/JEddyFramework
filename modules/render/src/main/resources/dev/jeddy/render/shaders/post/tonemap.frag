#version 460 core
// HDR composite (TASKS M7-06, DESIGN §9.3 pass 9): bloom mixed in, exposure (automatic times
// compensation), AgX or ACES tone mapping, sRGB encoding, a color-grading LUT in display space
// and a dither. Writes the display image, with its luma in alpha when FXAA follows.
#include "/engine/color.glsl"
#include "exposure.glsl"

layout(binding = 0) uniform sampler2D uHdr;
layout(binding = 1) uniform sampler2D uBloom;
layout(binding = 2) uniform sampler3D uLut;
layout(location = 0) uniform vec4 uParams; // x compensation, y bloom mix, z 1 / bloom levels, w LUT size
layout(location = 1) uniform uint uFlags;  // bit 0 AgX (else ACES), 1 LUT, 2 dither, 3 bloom
layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec3 hdr = textureLod(uHdr, vUv, 0.0).rgb;
    if ((uFlags & 8u) != 0u) {
        vec3 bloom = textureLod(uBloom, vUv, 0.0).rgb * uParams.z;
        hdr = mix(hdr, bloom, uParams.y);
    }
    hdr *= jExposure * uParams.x;
    vec3 display = (uFlags & 1u) != 0u ? jeddyTonemapAgx(hdr) : jeddyTonemapAces(hdr);
    vec3 encoded = jeddyLinearToSrgb(display);
    if ((uFlags & 2u) != 0u) {
        float n = uParams.w;
        encoded = textureLod(uLut, encoded * ((n - 1.0) / n) + 0.5 / n, 0.0).rgb;
    }
    if ((uFlags & 4u) != 0u) {
        encoded += (jeddyGradientNoise(gl_FragCoord.xy) - 0.5) / 255.0;
    }
    outColor = vec4(encoded, dot(encoded, vec3(0.299, 0.587, 0.114)));
}
