// Spot and point light shadows from the atlas (TASKS M7-04). LocalShadows writes one record per
// view (a spot cone, or one cube face of a point light) into storage buffer 8; a light's params.y
// is the index of its first record (-1: no shadow). Unit 8 holds the atlas with comparison.
#pragma once

struct JeddyShadowView {
    mat4 viewProjection;
    vec4 rect;   // atlas uv offset xy, uv scale zw
    vec4 params; // x texel size at 1 m, y 1 / atlas size, z 1 for a cube face
};

layout(std430, binding = 8) readonly buffer JeddyShadowViews {
    JeddyShadowView jShadowViews[];
};

layout(binding = 8) uniform sampler2DShadow jShadowAtlas;

float jeddyLocalShadow(JeddyLight light, vec3 world, vec3 geometricNormal, vec3 l) {
    int index = floatBitsToInt(light.params.y);
    if (index < 0) {
        return 1.0;
    }
    vec3 v = world - light.positionRange.xyz;
    if (floatBitsToUint(light.colorType.w) == J_LIGHT_POINT) {
        vec3 a = abs(v);
        int face = a.x >= a.y && a.x >= a.z ? (v.x > 0.0 ? 0 : 1)
                : (a.y >= a.z ? (v.y > 0.0 ? 2 : 3) : (v.z > 0.0 ? 4 : 5));
        index += face;
    }
    JeddyShadowView view = jShadowViews[index];
    float distance = length(v);
    vec3 n = normalize(geometricNormal);
    float NoL = clamp(dot(n, l), 0.0, 1.0);
    vec3 offset = n * (view.params.x * distance * (2.0 - NoL));
    vec4 p = view.viewProjection * vec4(world + offset, 1.0);
    if (p.w <= 0.0) {
        return 1.0;
    }
    p.xyz /= p.w;
    if (any(greaterThan(abs(p.xy), vec2(1.0)))) {
        return 1.0;
    }
    vec2 uv = view.rect.xy + (p.xy * 0.5 + 0.5) * view.rect.zw;
    float texel = view.params.y;
    // Keep the 2x2 kernel inside the tile.
    vec2 lo = view.rect.xy + texel;
    vec2 hi = view.rect.xy + view.rect.zw - texel;
    float sum = 0.0;
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            vec2 q = clamp(uv + (vec2(x, y) - 0.5) * texel, lo, hi);
            sum += texture(jShadowAtlas, vec3(q, p.z));
        }
    }
    return sum * 0.25;
}
