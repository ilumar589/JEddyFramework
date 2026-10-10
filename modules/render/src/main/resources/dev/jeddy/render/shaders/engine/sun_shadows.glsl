// Cascaded sun shadows (TASKS M7-04): SunShadows fills uniform block 1 and binds the depth array
// at unit 7 (comparison sampler, reversed-Z: lit where the fragment is at least as close to the
// sun as the stored caster).
#pragma once
#include "frame.glsl"

layout(std140, binding = 1) uniform JeddySunShadows {
    mat4 jCascadeMatrix[4];
    vec4 jCascadeSplits; // far view depth per cascade
    vec4 jCascadeTexel;  // world size of a texel per cascade
    vec4 jSunShadowParams; // x normal offset (texels), y map size, z fade start (fraction), w cascades
};

layout(binding = 7) uniform sampler2DArrayShadow jSunShadowMap;

float jeddyCascadeSample(int c, vec3 world, vec3 geometricNormal, vec3 l) {
    float NoL = clamp(dot(geometricNormal, l), 0.0, 1.0);
    // Normal offset grows at grazing light, where acne would appear first.
    vec3 offset = geometricNormal * (jSunShadowParams.x * jCascadeTexel[c] * (1.5 - NoL));
    vec4 p = jCascadeMatrix[c] * vec4(world + offset, 1.0);
    vec2 uv = p.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
        return 1.0;
    }
    float texel = 1.0 / jSunShadowParams.y;
    float sum = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            sum += texture(jSunShadowMap, vec4(uv + vec2(x, y) * texel, float(c), p.z));
        }
    }
    return sum / 9.0;
}

float jeddySunShadow(vec3 world, vec3 geometricNormal, vec3 l, float viewDepth) {
    int cascades = int(jSunShadowParams.w);
    for (int c = 0; c < cascades; c++) {
        if (viewDepth <= jCascadeSplits[c]) {
            float shadow = jeddyCascadeSample(c, world, normalize(geometricNormal), l);
            // Fade out towards the end of the last cascade.
            if (c == cascades - 1) {
                float start = jCascadeSplits[c] * jSunShadowParams.z;
                shadow = mix(shadow, 1.0, clamp((viewDepth - start) / max(jCascadeSplits[c] - start, 1e-3), 0.0, 1.0));
            }
            return shadow;
        }
    }
    return 1.0;
}
