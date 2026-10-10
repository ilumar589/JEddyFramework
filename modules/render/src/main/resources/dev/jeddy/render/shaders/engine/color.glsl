// Color helpers: sRGB transfer and tone curves (DESIGN §9.3).
#pragma once

vec3 jeddyLinearToSrgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
}

vec3 jeddySrgbToLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

float jeddyLuminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// ACES filmic fit (Narkowicz 2015).
vec3 jeddyTonemapAces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

// AgX base transform with a polynomial fit of the default contrast curve (Sobotka's AgX, fit by
// Wrensch 2023): log2 encoding of a gamut-compressed inset, a sigmoid, the outset back and a 2.2
// power. Returns linear display values in [0, 1].
vec3 jeddyTonemapAgx(vec3 c) {
    const mat3 inset = mat3(
            0.842479062253094, 0.0423282422610123, 0.0423756549057051,
            0.0784335999999992, 0.878468636469772, 0.0784336,
            0.0792237451477643, 0.0791661274605434, 0.879142973793104);
    const mat3 outset = mat3(
            1.19687900512017, -0.0528968517574562, -0.0529716355144438,
            -0.0980208811401368, 1.15190312990417, -0.0980434501171241,
            -0.0990297440797205, -0.0989611768448433, 1.15107367264116);
    const float minEv = -12.47393;
    const float maxEv = 4.026069;
    c = inset * max(c, vec3(0.0));
    c = clamp(log2(max(c, vec3(1e-10))), minEv, maxEv);
    c = (c - minEv) / (maxEv - minEv);
    vec3 x2 = c * c;
    vec3 x4 = x2 * x2;
    c = 15.5 * x4 * x2 - 40.14 * x4 * c + 31.96 * x4 - 6.868 * x2 * c + 0.4298 * x2
            + 0.1191 * c - 0.00232;
    c = outset * c;
    return pow(clamp(c, 0.0, 1.0), vec3(2.2));
}

// Interleaved gradient noise (Jimenez 2014) in [0, 1): a cheap per-pixel dither.
float jeddyGradientNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}
