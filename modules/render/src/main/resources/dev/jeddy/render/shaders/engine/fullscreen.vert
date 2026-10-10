#version 460 core
// One triangle covering the screen; vUv is in [0, 1] over the viewport. Draw 3 vertices with an
// empty vertex array.
layout(location = 0) out vec2 vUv;

void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
