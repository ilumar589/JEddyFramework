#version 460 core
// Outlines and highlight fills (TASKS M7-08): from the outline mask, a display-space overlay
// (premultiplied, sRGB-encoded) that the tone-map pass composites before FXAA. Outside a
// highlighted shape, the nearest highlighted pixel within the style's width draws an
// anti-aliased outline; inside, a fill shows (stronger where the scene hides the shape, an x-ray
// silhouette).
layout(binding = 0) uniform sampler2D uMask;
layout(location = 0) uniform vec4 uColors[16]; // sRGB-encoded rgb, a opacity
layout(location = 16) uniform vec4 uParams[16]; // x width px, y visible fill, z hidden fill, w hidden outline
layout(location = 32) uniform int uRadius;
layout(location = 0) out vec4 outOverlay;

uint styleOf(vec2 m) {
    return uint(m.x * 255.0 + 0.5);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(uMask, 0);
    vec2 center = texelFetch(uMask, p, 0).rg;
    uint style = styleOf(center);
    if (style != 0u) {
        vec4 c = uColors[style];
        float fill = c.a * (center.y > 0.5 ? uParams[style].z : uParams[style].y);
        outOverlay = vec4(c.rgb * fill, fill);
        return;
    }
    float best = 1e9;
    uint bestStyle = 0u;
    float hidden = 0.0;
    int r = uRadius;
    float limit = (float(r) + 1.0) * (float(r) + 1.0);
    for (int y = -r; y <= r; y++) {
        for (int x = -r; x <= r; x++) {
            float d2 = float(x * x + y * y);
            if (d2 > limit || d2 >= best) {
                continue;
            }
            vec2 m = texelFetch(uMask, clamp(p + ivec2(x, y), ivec2(0), size - 1), 0).rg;
            uint s = styleOf(m);
            if (s != 0u) {
                best = d2;
                bestStyle = s;
                hidden = m.y;
            }
        }
    }
    if (bestStyle == 0u) {
        outOverlay = vec4(0.0);
        return;
    }
    vec4 c = uColors[bestStyle];
    vec4 params = uParams[bestStyle];
    // A pixel d away from the nearest shape pixel spans [d - 1, d] from the shape edge.
    float a = clamp(params.x + 1.0 - sqrt(best), 0.0, 1.0) * c.a;
    if (hidden > 0.5) {
        a *= params.w;
    }
    outOverlay = vec4(c.rgb * a, a);
}
