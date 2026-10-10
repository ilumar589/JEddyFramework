#version 460 core
// Background (DESIGN §9.3 pass 6): the environment cube where no geometry wrote depth. Drawn at
// depth 0 (the far plane in reversed-Z) with GL_GEQUAL.
#include "/engine/frame.glsl"
#include "/engine/environment.glsl"

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec3 far = jeddyWorldFromDepth(vUv, 0.0001);
    vec3 dir = jeddyOrthographic()
            ? -vec3(jView[0][2], jView[1][2], jView[2][2])
            : normalize(far - jEye.xyz);
    outColor = vec4(textureLod(jEnvironmentMap, dir, 0.0).rgb * jEnvironment.x, 1.0);
}
