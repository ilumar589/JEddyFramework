#version 460 core
// Ground-truth ambient occlusion at half resolution (TASKS M7-05; Jimenez et al. 2016): view
// positions and normals reconstructed from the depth prepass, a few slice directions per pixel
// rotated by interleaved gradient noise, horizon search on both sides, and the cosine-weighted
// visibility integral of each slice.
#include "/engine/frame.glsl"

layout(binding = 0) uniform sampler2D uDepth;
layout(location = 0) uniform vec4 uParams; // x radius (m), y intensity exponent, z slices, w steps
layout(location = 0) in vec2 vUv;
layout(location = 0) out float outAo;

vec3 viewPos(vec2 uv) {
    // Sky texels (reversed-Z 0, infinite far plane) become very distant points, not infinities.
    float d = max(textureLod(uDepth, uv, 0.0).r, 1e-7);
    vec4 p = jInverseProjection * vec4(uv * 2.0 - 1.0, d, 1.0);
    return p.xyz / p.w;
}

void main() {
    float depth = textureLod(uDepth, vUv, 0.0).r;
    if (depth <= 0.0) {
        outAo = 1.0; // sky (reversed-Z far plane)
        return;
    }
    vec2 texel = jViewport.zw;
    vec3 p = viewPos(vUv);
    vec3 pr = viewPos(vUv + vec2(texel.x, 0.0));
    vec3 pl = viewPos(vUv - vec2(texel.x, 0.0));
    vec3 pu = viewPos(vUv + vec2(0.0, texel.y));
    vec3 pd = viewPos(vUv - vec2(0.0, texel.y));
    vec3 dx = abs(pr.z - p.z) < abs(p.z - pl.z) ? pr - p : p - pl;
    vec3 dy = abs(pu.z - p.z) < abs(p.z - pd.z) ? pu - p : p - pd;
    vec3 n = normalize(cross(dx, dy));
    vec3 v = jeddyOrthographic() ? vec3(0.0, 0.0, 1.0) : normalize(-p);
    if (dot(n, v) < 0.0) {
        n = -n;
    }
    float radius = uParams.x;
    // Pixels covered by the radius at this depth (projection y scale × half the viewport).
    float scale = jProjection[1][1] * 0.5 * jViewport.y;
    float radiusPx = jeddyOrthographic() ? radius * scale : radius * scale / max(-p.z, 1e-3);
    radiusPx = min(radiusPx, 0.25 * jViewport.y);
    if (radiusPx < 1.0) {
        outAo = 1.0;
        return;
    }
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    int slices = int(uParams.z);
    int steps = int(uParams.w);
    float visibility = 0.0;
    for (int s = 0; s < slices; s++) {
        float phi = (float(s) + noise) * J_PI / float(slices);
        vec2 dir = vec2(cos(phi), sin(phi));
        vec3 dir3 = vec3(dir, 0.0);
        vec3 orthoDir = dir3 - dot(dir3, v) * v;
        vec3 axis = normalize(cross(dir3, v));
        vec3 projN = n - axis * dot(n, axis);
        float projLen = max(length(projN), 1e-4);
        float cosN = clamp(dot(projN, v) / projLen, -1.0, 1.0);
        float angleN = sign(dot(orthoDir, projN)) * acos(cosN);
        float h[2];
        for (int side = 0; side < 2; side++) {
            float sgn = side == 0 ? -1.0 : 1.0;
            // Unoccluded, the horizon is the tangent plane; distant samples fade towards it.
            float lowCos = cos(angleN + sgn * J_PI * 0.5);
            float maxCos = lowCos;
            for (int k = 0; k < steps; k++) {
                float t = (float(k) + fract(noise + 0.618 * float(k))) / float(steps);
                vec2 offset = dir * sgn * max(t * radiusPx, 1.0) * texel;
                vec3 delta = viewPos(vUv + offset) - p;
                float len2 = dot(delta, delta);
                float inv = inversesqrt(max(len2, 1e-8));
                float c = dot(delta, v) * inv;
                // Full weight up to 40% of the radius, then a linear fade (XeGTAO's falloff).
                float falloff = clamp((radius - len2 * inv) / (0.6 * radius), 0.0, 1.0);
                maxCos = max(maxCos, mix(lowCos, c, falloff));
            }
            float horizon = sgn * acos(clamp(maxCos, -1.0, 1.0));
            h[side] = side == 0 ? max(horizon, angleN - J_PI * 0.5) : min(horizon, angleN + J_PI * 0.5);
        }
        float sinN = sin(angleN);
        float a = -cos(2.0 * h[0] - angleN) + cosN + 2.0 * h[0] * sinN;
        float b = -cos(2.0 * h[1] - angleN) + cosN + 2.0 * h[1] * sinN;
        visibility += projLen * 0.25 * (a + b);
    }
    outAo = pow(clamp(visibility / float(slices), 0.0, 1.0), uParams.y);
}
