// Per-frame constants (DESIGN §9.2, §9.3), written once per frame by SceneRenderer into a uniform
// buffer at binding 0. Layout std140; FrameConstants in Java mirrors it.
#pragma once

layout(std140, binding = 0) uniform JeddyFrame {
    mat4 jView;
    mat4 jProjection;
    mat4 jViewProjection;
    mat4 jInverseViewProjection;
    mat4 jInverseProjection;
    vec4 jEye;          // xyz camera position (world), w time in seconds
    vec4 jViewport;     // width, height, 1 / width, 1 / height (pixels)
    vec4 jClusterZ;     // near, far, slice scale, slice bias (view-space depth slicing)
    uvec4 jClusterDims; // clusters along x, y, z; tile size in pixels
    vec4 jSunDirection; // xyz unit direction the sunlight travels, w cascade count
    vec4 jSunColor;     // rgb linear color times illuminance, w 1 when the sun is on
    vec4 jEnvironment;  // x specular scale, y diffuse scale, z prefiltered mip count, w exposure
    uvec4 jCounts;      // x lights, y decals, z frame number, w flags (bit 0 orthographic)
};

const float J_PI = 3.14159265358979;

bool jeddyOrthographic() {
    return (jCounts.w & 1u) != 0u;
}

// World-space position of a pixel at window depth d (reversed-Z, depth in [0, 1]).
vec3 jeddyWorldFromDepth(vec2 uv, float d) {
    vec4 p = jInverseViewProjection * vec4(uv * 2.0 - 1.0, d, 1.0);
    return p.xyz / p.w;
}

// Positive view-space distance along -Z of a world position.
float jeddyViewDepth(vec3 world) {
    return -(jView * vec4(world, 1.0)).z;
}
