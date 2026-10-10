#version 460 core

#include "common/fog.glsl"

layout(location = 0) in vec2 uv;
layout(location = 1) in float depth;
layout(location = 0) out vec4 color;

layout(binding = 0) uniform sampler2D albedo;

void main() {
    color = vec4(applyFog(texture(albedo, uv).rgb, depth), 1.0);
}
