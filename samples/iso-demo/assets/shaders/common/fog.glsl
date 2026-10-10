#pragma once

vec3 applyFog(vec3 color, float depth) {
#ifdef FOG
    return mix(color, vec3(0.6, 0.7, 0.8), clamp(depth * 0.02, 0.0, 1.0));
#else
    return color;
#endif
}
