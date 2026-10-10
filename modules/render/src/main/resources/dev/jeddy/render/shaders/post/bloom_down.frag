#version 460 core
// Bloom downsample (TASKS M7-06; Jimenez 2014, "Next generation post processing in Call of Duty:
// Advanced Warfare"): a 13-tap filter that halves the resolution without aliasing. The first
// level weights each 2x2 box by 1 / (1 + luma) (Karis average) so single bright pixels do not
// flicker.
#include "/engine/color.glsl"

layout(binding = 0) uniform sampler2D uSource;
layout(location = 0) uniform int uFirst;
layout(location = 0) in vec2 vUv;
layout(location = 0) out vec3 outColor;

vec3 tap(vec2 uv) {
    // Clamp NaNs, negatives and half-float overflow out of the chain.
    vec3 c = textureLod(uSource, uv, 0.0).rgb;
    return clamp(c, vec3(0.0), vec3(60000.0));
}

vec3 karis(vec3 a, vec3 b, vec3 c, vec3 d, out float weight) {
    vec3 box = (a + b + c + d) * 0.25;
    weight = 1.0 / (1.0 + jeddyLuminance(box));
    return box * weight;
}

void main() {
    vec2 t = 1.0 / vec2(textureSize(uSource, 0));
    vec3 a = tap(vUv + t * vec2(-2.0, 2.0));
    vec3 b = tap(vUv + t * vec2(0.0, 2.0));
    vec3 c = tap(vUv + t * vec2(2.0, 2.0));
    vec3 d = tap(vUv + t * vec2(-2.0, 0.0));
    vec3 e = tap(vUv);
    vec3 f = tap(vUv + t * vec2(2.0, 0.0));
    vec3 g = tap(vUv + t * vec2(-2.0, -2.0));
    vec3 h = tap(vUv + t * vec2(0.0, -2.0));
    vec3 i = tap(vUv + t * vec2(2.0, -2.0));
    vec3 j = tap(vUv + t * vec2(-1.0, 1.0));
    vec3 k = tap(vUv + t * vec2(1.0, 1.0));
    vec3 l = tap(vUv + t * vec2(-1.0, -1.0));
    vec3 m = tap(vUv + t * vec2(1.0, -1.0));
    if (uFirst != 0) {
        float w0, w1, w2, w3, w4;
        vec3 sum = karis(j, k, l, m, w0) * 0.5;
        sum += karis(a, b, d, e, w1) * 0.125;
        sum += karis(b, c, e, f, w2) * 0.125;
        sum += karis(d, e, g, h, w3) * 0.125;
        sum += karis(e, f, h, i, w4) * 0.125;
        outColor = sum / (w0 * 0.5 + (w1 + w2 + w3 + w4) * 0.125);
    } else {
        outColor = e * 0.125 + (a + c + g + i) * 0.03125 + (b + d + f + h) * 0.0625
                + (j + k + l + m) * 0.125;
    }
}
