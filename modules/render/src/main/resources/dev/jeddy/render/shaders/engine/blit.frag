#version 460 core
// Copies (and rescales) a texture or one layer of an array texture into the bound target, e.g. a
// decal texture into the decal array (TASKS M7-07). Minification uses the source's mips.
#ifdef SOURCE_ARRAY
layout(binding = 0) uniform sampler2DArray uSource;
#else
layout(binding = 0) uniform sampler2D uSource;
#endif
layout(location = 0) uniform float uLayer;
layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
#ifdef SOURCE_ARRAY
    outColor = texture(uSource, vec3(vUv, uLayer));
#else
    outColor = texture(uSource, vUv);
#endif
}
