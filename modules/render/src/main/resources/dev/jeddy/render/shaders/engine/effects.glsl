// Screen-space terms and per-pixel effects shared by the lit passes (TASKS M7-05, M7-07, M7-10).
#pragma once
#include "frame.glsl"

#ifdef SCREEN_AO
layout(binding = 9) uniform sampler2D jAmbientOcclusion;
float jeddyScreenOcclusion(vec2 fragCoord) {
    return texture(jAmbientOcclusion, fragCoord * jViewport.zw).r;
}
#else
float jeddyScreenOcclusion(vec2 fragCoord) {
    return 1.0;
}
#endif

#ifdef FOG_OF_WAR
#include "fog_of_war.glsl"
#endif

vec3 jeddyApplyEffects(vec3 color, vec3 world, uint flags) {
#ifdef FOG_OF_WAR
    color = jeddyFogOfWar(color, world);
#endif
    return color;
}
