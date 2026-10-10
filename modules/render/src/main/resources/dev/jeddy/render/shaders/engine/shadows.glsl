// Shadow lookups (TASKS M7-04). Without SUN_SHADOWS / LOCAL_SHADOWS the functions return 1.
#pragma once
#include "frame.glsl"
#include "lights.glsl"

#ifdef SUN_SHADOWS
#include "sun_shadows.glsl"
#else
float jeddySunShadow(vec3 world, vec3 geometricNormal, vec3 l, float viewDepth) {
    return 1.0;
}
#endif

#ifdef LOCAL_SHADOWS
#include "local_shadows.glsl"
#else
float jeddyLocalShadow(JeddyLight light, vec3 world, vec3 geometricNormal, vec3 l) {
    return 1.0;
}
#endif
