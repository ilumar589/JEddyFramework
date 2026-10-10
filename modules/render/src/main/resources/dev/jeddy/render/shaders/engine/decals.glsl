// Clustered decals (TASKS M7-07, DESIGN §9.3 pass 5): boxes that project shapes and textures
// downward onto opaque surfaces: selection circles, area-of-effect templates (circle, cone, line)
// and textured marks such as scorch and blood. They are assigned to clusters with the lights; a
// cluster's list holds its lights, then its decals. Only the lit passes include this file.
#pragma once
#include "frame.glsl"
#include "lights.glsl"

struct JeddyDecal {
    vec4 rows[3]; // world to local box [-1, 1]^3 (row-major 3x4)
    vec4 bounds;  // xyz center, w radius (cluster culling)
    vec4 color;   // linear rgb, a opacity
    vec4 params;  // x shape (uint bits), y p0, z p1, w emission (0 tints, 1 glows)
    vec4 extra;   // x fill opacity, y texture layer
};

layout(std430, binding = 6) readonly buffer JeddyDecals {
    JeddyDecal jDecals[];
};

layout(binding = 10) uniform sampler2DArray jDecalTextures;

const uint J_DECAL_TEXTURE = 0u;
const uint J_DECAL_RING = 1u;
const uint J_DECAL_DISC = 2u;
const uint J_DECAL_CONE = 3u;
const uint J_DECAL_LINE = 4u;

// Accumulated decals at a surface point: a premultiplied albedo tint (albedo becomes
// albedo * (1 - a) + rgb) and emitted light.
struct JeddyDecalResult {
    vec4 tint;
    vec3 emitted;
};

// Opacity of a shape with a filled interior and an outline, from the signed distance to its edge
// in meters (negative inside); aa is the pixel footprint in meters.
float jeddyDecalOutline(float distance, float outline, float fill, float aa) {
    float inside = 1.0 - smoothstep(-aa, aa, distance);
    float rim = 1.0 - smoothstep(outline - aa, outline + aa, -distance);
    return inside * max(fill, rim);
}

// Opacity of decal d at local position p; scale holds the box half extents (meters) along local
// x and z; aa the pixel footprint in meters.
float jeddyDecalShape(JeddyDecal d, vec3 p, vec2 scale, float aa) {
    uint shape = floatBitsToUint(d.params.x);
    float fill = d.extra.x;
    if (shape == J_DECAL_RING) {
        float r = length(p.xz) * scale.x;
        float soft = max(d.params.z, aa);
        float inner = d.params.y * scale.x;
        return smoothstep(inner - soft, inner + soft, r)
                * (1.0 - smoothstep(scale.x - soft * 2.0, scale.x, r));
    }
    if (shape == J_DECAL_DISC) {
        return jeddyDecalOutline(length(p.xz * scale) - scale.x, d.params.z, fill, aa);
    }
    if (shape == J_DECAL_CONE) {
        vec2 q = p.xz * scale; // meters, apex at the origin, axis +z
        float halfAngle = d.params.y;
        float r = length(q);
        float radial = r - scale.y;
        // Signed distance to the side edges (rays at +-halfAngle from the axis).
        float lateral = abs(q.x) * cos(halfAngle) - q.y * sin(halfAngle);
        return jeddyDecalOutline(max(radial, lateral), d.params.z, fill, aa);
    }
    if (shape == J_DECAL_LINE) {
        vec2 q = abs(p.xz) * scale - scale;
        return jeddyDecalOutline(max(q.x, q.y), d.params.z, fill, aa);
    }
    return 1.0;
}

// Applies the decals of cluster range (offset, packed counts) at world position world, with
// geometric normal normal; dpdx and dpdy are the screen derivatives of world (taken in uniform
// control flow by the caller).
JeddyDecalResult jeddyDecals(uvec2 range, vec3 world, vec3 normal, vec3 dpdx, vec3 dpdy) {
    JeddyDecalResult result;
    result.tint = vec4(0.0);
    result.emitted = vec3(0.0);
    uint lights = range.y & 0xFFFFu;
    uint decals = range.y >> 16u;
    // Surfaces turned away from the projection axis (walls, sides) fade out.
    float facing = smoothstep(0.35, 0.65, normal.y);
    if (decals == 0u || facing <= 0.0) {
        return result;
    }
    float footprint = max(length(dpdx), length(dpdy));
    for (uint k = 0u; k < decals; k++) {
        JeddyDecal d = jDecals[jClusterLights[range.x + lights + k]];
        vec4 w = vec4(world, 1.0);
        vec3 p = vec3(dot(d.rows[0], w), dot(d.rows[1], w), dot(d.rows[2], w));
        if (any(greaterThan(abs(p), vec3(1.0)))) {
            continue;
        }
        vec2 scale = vec2(1.0 / length(d.rows[0].xyz), 1.0 / length(d.rows[2].xyz));
        float a = d.color.a * facing * (1.0 - smoothstep(0.75, 1.0, abs(p.y)));
        vec3 color = d.color.rgb;
        if (floatBitsToUint(d.params.x) == J_DECAL_TEXTURE) {
            vec2 uv = p.xz * 0.5 + 0.5;
            vec2 gx = vec2(dot(d.rows[0].xyz, dpdx), dot(d.rows[2].xyz, dpdx)) * 0.5;
            vec2 gy = vec2(dot(d.rows[0].xyz, dpdy), dot(d.rows[2].xyz, dpdy)) * 0.5;
            vec4 t = textureGrad(jDecalTextures, vec3(uv, d.extra.y), gx, gy);
            color *= t.rgb;
            a *= t.a;
        } else {
            a *= jeddyDecalShape(d, p, scale, footprint);
        }
        if (a <= 0.0) {
            continue;
        }
        float glow = d.params.w;
        // Tints composite over each other in list order (premultiplied); glow adds.
        float tint = a * (1.0 - glow);
        result.tint = result.tint * (1.0 - tint) + vec4(color, 1.0) * tint;
        result.emitted += color * a * glow;
    }
    return result;
}
