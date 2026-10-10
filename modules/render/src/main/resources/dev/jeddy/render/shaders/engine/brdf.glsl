// Cook-Torrance terms (DESIGN §9.3): GGX distribution, height-correlated Smith visibility and
// Schlick Fresnel, plus Lambert diffuse. Roughness is perceptual; alpha = roughness^2.
#pragma once

float jeddyD_GGX(float NoH, float alpha) {
    float a2 = alpha * alpha;
    float f = (NoH * a2 - NoH) * NoH + 1.0;
    return a2 / (3.14159265358979 * f * f);
}

float jeddyV_SmithGGXCorrelated(float NoV, float NoL, float alpha) {
    float a2 = alpha * alpha;
    float ggxL = NoV * sqrt((NoL - a2 * NoL) * NoL + a2);
    float ggxV = NoL * sqrt((NoV - a2 * NoV) * NoV + a2);
    return 0.5 / max(ggxV + ggxL, 1e-5);
}

vec3 jeddyF_Schlick(float VoH, vec3 f0) {
    float f = pow(1.0 - VoH, 5.0);
    return f + f0 * (1.0 - f);
}

// Shading inputs of one surface point.
struct JeddySurface {
    vec3 position;   // world
    vec3 normal;     // world, unit, facing the viewer
    vec3 view;       // unit vector to the eye
    vec3 diffuse;    // albedo * (1 - metallic)
    vec3 f0;         // specular reflectance at normal incidence
    float roughness; // perceptual, clamped
    float alpha;     // roughness^2
    float NoV;
    vec3 energy;     // multiple-scattering compensation, 1 + f0 (1 / E_ss - 1)
};

// Radiance reflected towards the viewer from light arriving along l with irradiance-scaled
// color (radiance * NoL is applied here).
vec3 jeddyShade(JeddySurface s, vec3 l, vec3 lightColor) {
    vec3 h = normalize(s.view + l);
    float NoL = clamp(dot(s.normal, l), 0.0, 1.0);
    if (NoL <= 0.0) {
        return vec3(0.0);
    }
    float NoH = clamp(dot(s.normal, h), 0.0, 1.0);
    float LoH = clamp(dot(l, h), 0.0, 1.0);
    float D = jeddyD_GGX(NoH, s.alpha);
    float V = jeddyV_SmithGGXCorrelated(s.NoV, NoL, s.alpha);
    vec3 F = jeddyF_Schlick(LoH, s.f0);
    vec3 specular = D * V * F * s.energy;
    vec3 diffuse = s.diffuse / 3.14159265358979;
    return (diffuse + specular) * lightColor * NoL;
}
