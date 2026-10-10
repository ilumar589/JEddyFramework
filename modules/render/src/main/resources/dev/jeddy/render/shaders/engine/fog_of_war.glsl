// Fog of war (TASKS M7-10, DESIGN §11): a visibility grid over the level's XZ bounds, from the
// physics visibility system: 0 unexplored, about 0.5 explored but not in sight, 1 visible.
// Explored areas are dimmed and desaturated; unexplored ones go black. Bilinear filtering of the
// grid gives soft edges.
#pragma once
#include "frame.glsl"

layout(binding = 12) uniform sampler2D jFogOfWar;

vec3 jeddyFogOfWar(vec3 color, vec3 world) {
    if (jFogParams.z == 0.0) {
        return color;
    }
    vec2 uv = (world.xz - jFogBounds.xy) * jFogBounds.zw;
    float v = texture(jFogOfWar, uv).r;
    float explored = smoothstep(0.05, 0.45, v);
    float visible = smoothstep(0.55, 0.95, v);
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    vec3 dimmed = mix(vec3(luma), color, jFogParams.y) * jFogParams.x;
    return mix(dimmed, color, visible) * explored;
}
