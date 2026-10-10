#version 460 core
// Fast approximate anti-aliasing (TASKS M7-06; after Lottes' FXAA 3.11 quality algorithm): finds
// luma edges, walks along them to their ends and blends across by the pixel's position on the
// edge, plus a sub-pixel blend for thin features. Reads the tone-mapped image with luma in alpha.
#include "/engine/color.glsl"

layout(binding = 0) uniform sampler2D uLdr;
layout(location = 0) uniform vec4 uParams; // x sub-pixel amount, y edge threshold, z threshold min, w dither
layout(location = 1) uniform int uEnabled;
layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

const int SEARCH_STEPS = 10;
const float SEARCH[SEARCH_STEPS] = float[](1.0, 1.0, 1.0, 1.0, 1.5, 2.0, 2.0, 2.0, 4.0, 8.0);

float luma(vec2 uv) {
    return textureLod(uLdr, uv, 0.0).a;
}

vec4 finish(vec3 c) {
    if (uParams.w != 0.0) {
        c += (jeddyGradientNoise(gl_FragCoord.xy) - 0.5) / 255.0;
    }
    return vec4(c, 1.0);
}

void main() {
    vec4 center = textureLod(uLdr, vUv, 0.0);
    if (uEnabled == 0) {
        outColor = finish(center.rgb);
        return;
    }
    vec2 texel = 1.0 / vec2(textureSize(uLdr, 0));
    float m = center.a;
    float n = textureLodOffset(uLdr, vUv, 0.0, ivec2(0, 1)).a;
    float s = textureLodOffset(uLdr, vUv, 0.0, ivec2(0, -1)).a;
    float e = textureLodOffset(uLdr, vUv, 0.0, ivec2(1, 0)).a;
    float w = textureLodOffset(uLdr, vUv, 0.0, ivec2(-1, 0)).a;
    float hi = max(m, max(max(n, s), max(e, w)));
    float lo = min(m, min(min(n, s), min(e, w)));
    float range = hi - lo;
    if (range < max(uParams.z, hi * uParams.y)) {
        outColor = finish(center.rgb);
        return;
    }
    float ne = textureLodOffset(uLdr, vUv, 0.0, ivec2(1, 1)).a;
    float nw = textureLodOffset(uLdr, vUv, 0.0, ivec2(-1, 1)).a;
    float se = textureLodOffset(uLdr, vUv, 0.0, ivec2(1, -1)).a;
    float sw = textureLodOffset(uLdr, vUv, 0.0, ivec2(-1, -1)).a;

    // Sub-pixel aliasing: how much the pixel differs from its neighborhood.
    float average = (2.0 * (n + s + e + w) + ne + nw + se + sw) / 12.0;
    float sub = smoothstep(0.0, 1.0, clamp(abs(average - m) / range, 0.0, 1.0));
    sub = sub * sub * uParams.x;

    // Edge orientation from second differences.
    float horizontalEdge = abs(nw + ne - 2.0 * n) + 2.0 * abs(w + e - 2.0 * m) + abs(sw + se - 2.0 * s);
    float verticalEdge = abs(nw + sw - 2.0 * w) + 2.0 * abs(n + s - 2.0 * m) + abs(ne + se - 2.0 * e);
    bool horizontal = horizontalEdge >= verticalEdge;
    float across = horizontal ? texel.y : texel.x;
    float side1 = horizontal ? s : w;
    float side2 = horizontal ? n : e;
    float gradient1 = abs(side1 - m);
    float gradient2 = abs(side2 - m);
    float edgeLuma;
    float gradient;
    if (gradient1 >= gradient2) {
        across = -across;
        edgeLuma = 0.5 * (side1 + m);
        gradient = gradient1;
    } else {
        edgeLuma = 0.5 * (side2 + m);
        gradient = gradient2;
    }
    float threshold = gradient * 0.25;

    // Walk along the edge, half a pixel across it, in both directions.
    vec2 start = vUv;
    vec2 along;
    if (horizontal) {
        start.y += across * 0.5;
        along = vec2(texel.x, 0.0);
    } else {
        start.x += across * 0.5;
        along = vec2(0.0, texel.y);
    }
    vec2 uv1 = start - along;
    vec2 uv2 = start + along;
    float end1 = luma(uv1) - edgeLuma;
    float end2 = luma(uv2) - edgeLuma;
    bool done1 = abs(end1) >= threshold;
    bool done2 = abs(end2) >= threshold;
    for (int i = 1; i < SEARCH_STEPS && !(done1 && done2); i++) {
        if (!done1) {
            uv1 -= along * SEARCH[i];
            end1 = luma(uv1) - edgeLuma;
            done1 = abs(end1) >= threshold;
        }
        if (!done2) {
            uv2 += along * SEARCH[i];
            end2 = luma(uv2) - edgeLuma;
            done2 = abs(end2) >= threshold;
        }
    }
    float distance1 = horizontal ? vUv.x - uv1.x : vUv.y - uv1.y;
    float distance2 = horizontal ? uv2.x - vUv.x : uv2.y - vUv.y;
    bool nearer1 = distance1 < distance2;
    float distance = min(distance1, distance2);
    float offset = 0.5 - distance / (distance1 + distance2);
    // Blend only on the side where the edge ends in the direction of this pixel's luma.
    bool centerBelow = m < edgeLuma;
    bool consistent = ((nearer1 ? end1 : end2) < 0.0) != centerBelow;
    float blend = max(consistent ? offset : 0.0, sub);
    vec2 uv = vUv;
    if (horizontal) {
        uv.y += blend * across;
    } else {
        uv.x += blend * across;
    }
    outColor = finish(textureLod(uLdr, uv, 0.0).rgb);
}
