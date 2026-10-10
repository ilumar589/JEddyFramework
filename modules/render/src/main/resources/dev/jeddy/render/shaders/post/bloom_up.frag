#version 460 core
// Bloom upsample (TASKS M7-06): a 3x3 tent filter over the next smaller level, added to this
// level's downsample. The sum over all levels is a wide, smooth glow.
layout(binding = 0) uniform sampler2D uLow;
layout(binding = 1) uniform sampler2D uHigh;
layout(location = 0) in vec2 vUv;
layout(location = 0) out vec3 outColor;

void main() {
    vec2 t = 1.0 / vec2(textureSize(uLow, 0));
    vec3 s = textureLod(uLow, vUv, 0.0).rgb * 4.0;
    s += (textureLod(uLow, vUv + vec2(-t.x, 0.0), 0.0).rgb
            + textureLod(uLow, vUv + vec2(t.x, 0.0), 0.0).rgb
            + textureLod(uLow, vUv + vec2(0.0, -t.y), 0.0).rgb
            + textureLod(uLow, vUv + vec2(0.0, t.y), 0.0).rgb) * 2.0;
    s += textureLod(uLow, vUv + vec2(-t.x, -t.y), 0.0).rgb
            + textureLod(uLow, vUv + vec2(t.x, -t.y), 0.0).rgb
            + textureLod(uLow, vUv + vec2(-t.x, t.y), 0.0).rgb
            + textureLod(uLow, vUv + vec2(t.x, t.y), 0.0).rgb;
    outColor = textureLod(uHigh, vUv, 0.0).rgb + s * (1.0 / 16.0);
}
