// Camera fade (TASKS M7-10): roofs, walls and trees between the camera and the party dissolve
// with a screen-door dither, so no sorting is needed and the depth prepass and the lit pass
// discard the same pixels. Two sources: a per-instance fade (the game hides a roof when the party
// is inside) and, for instances flagged RenderPacket.FLAG_FADE_OCCLUDER, reveal circles around
// up to four points (the party) wherever the surface is in front of them.
#pragma once
#include "frame.glsl"

const uint J_FLAG_FADE_OCCLUDER = 32u;

float jeddyCameraFade(vec3 world, uint flags, float instanceFade) {
    float fade = instanceFade;
    if ((flags & J_FLAG_FADE_OCCLUDER) == 0u) {
        return fade;
    }
    uint n = uint(jFogParams.w);
    vec3 f = (jView * vec4(world, 1.0)).xyz;
    for (uint i = 0u; i < n; i++) {
        vec4 reveal = jReveal[i];
        vec3 r = (jView * vec4(reveal.xyz, 1.0)).xyz;
        if (f.z <= r.z + 0.25) {
            continue; // not in front of the point
        }
        // Lateral distance at the point's depth (perspective) or in the view plane (ortho).
        vec2 lateral = jeddyOrthographic() ? f.xy - r.xy : f.xy / -f.z * -r.z - r.xy;
        float d = length(lateral) / reveal.w;
        fade = max(fade, 1.0 - smoothstep(0.75, 1.0, d));
    }
    return fade;
}

// 4x4 ordered dither: true where a pixel of a surface faded by fade (0..1) is dropped.
bool jeddyFadeDiscard(vec2 fragCoord, float fade) {
    if (fade <= 0.0) {
        return false;
    }
    const float bayer[16] = float[](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0,
            9.0, 15.0, 7.0, 13.0, 5.0);
    ivec2 p = ivec2(fragCoord) & 3;
    return fade > (bayer[p.y * 4 + p.x] + 0.5) / 16.0;
}
