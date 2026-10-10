#version 460 core
// Forward+ PBR (TASKS M7-01, M7-02, M7-03; DESIGN §9.3): glTF metallic-roughness with GGX,
// height-correlated Smith and Schlick terms, multiple-scattering energy compensation, the sun,
// clustered point and spot lights, and image-based lighting from a prefiltered cube and SH9.
#include "/engine/frame.glsl"
#include "/engine/brdf.glsl"
#include "/engine/material.glsl"
#include "/engine/lights.glsl"
#include "/engine/environment.glsl"
#include "/engine/shadows.glsl"
#include "/engine/effects.glsl"

layout(location = 0) in vec3 vWorld;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec4 vTangent;
layout(location = 3) in vec4 vUv;
layout(location = 4) flat in uint vMaterial;
layout(location = 5) flat in uint vFlags;

layout(location = 0) out vec4 outColor;

void main() {
    JeddyMaterial m = jMaterials[vMaterial];
    vec4 base = m.baseColor;
#ifdef BASE_COLOR_MAP
    base *= texture(jBaseColorMap, vec3(jeddySlotUv(m, 0u, vUv), float(m.layers.x)));
#endif
#ifdef ALPHA_MASK
    if (base.a < m.misc.y) {
        discard;
    }
    base.a = 1.0;
#endif
    float metallic = m.pbr.x;
    float roughness = m.pbr.y;
    float occlusion = 1.0;
#ifdef ORM_MAP
    vec3 orm = texture(jOrmMap, vec3(jeddySlotUv(m, 2u, vUv), float(m.layers.z))).rgb;
    occlusion = mix(1.0, orm.r, m.pbr.w);
    roughness *= orm.g;
    metallic *= orm.b;
#endif
#ifdef OCCLUSION_MAP
    occlusion = mix(1.0, texture(jOcclusionMap, vec3(jeddySlotUv(m, 4u, vUv), float(m.layers2.x))).r, m.pbr.w);
#endif
    roughness = clamp(roughness, 0.045, 1.0);
    metallic = clamp(metallic, 0.0, 1.0);

    vec3 n = normalize(vNormal);
#ifdef DOUBLE_SIDED
    if (!gl_FrontFacing) {
        n = -n;
    }
#endif
#ifdef NORMAL_MAP
    vec2 xy = texture(jNormalMap, vec3(jeddySlotUv(m, 1u, vUv), float(m.layers.y))).rg * 2.0 - 1.0;
    xy *= m.pbr.z;
    vec3 tn = vec3(xy, sqrt(clamp(1.0 - dot(xy, xy), 0.0, 1.0)));
    vec3 t = normalize(vTangent.xyz - n * dot(n, vTangent.xyz));
    vec3 b = cross(n, t) * vTangent.w;
    n = normalize(t * tn.x + b * tn.y + n * tn.z);
#endif

    JeddySurface s;
    s.position = vWorld;
    s.view = jeddyOrthographic() ? normalize(vec3(jView[0][2], jView[1][2], jView[2][2]))
                                 : normalize(jEye.xyz - vWorld);
    // Keep normals that face away from the viewer (normal maps, interpolation) usable.
    s.normal = n;
    s.NoV = max(dot(n, s.view), 1e-4);
    s.diffuse = base.rgb * (1.0 - metallic);
    s.f0 = mix(vec3(0.04), base.rgb, metallic);
    s.roughness = roughness;
    s.alpha = roughness * roughness;
    vec2 dfg = jeddyDfg(s.NoV, roughness);
    s.energy = 1.0 + s.f0 * (1.0 / max(dfg.x + dfg.y, 1e-4) - 1.0);

    float viewDepth = jeddyViewDepth(vWorld);
    vec3 color = vec3(0.0);

    // Sun.
    if (jSunColor.w > 0.0) {
        vec3 l = -jSunDirection.xyz;
        float shadow = jeddySunShadow(vWorld, vNormal, l, viewDepth);
        color += jeddyShade(s, l, jSunColor.rgb) * shadow;
    }

    // Clustered point and spot lights.
    uvec2 range = jClusterRanges[jeddyClusterIndex(gl_FragCoord.xy, viewDepth)];
    for (uint i = 0u; i < range.y; i++) {
        JeddyLight light = jLights[jClusterLights[range.x + i]];
        vec3 toLight = light.positionRange.xyz - vWorld;
        float d2 = dot(toLight, toLight);
        vec3 l = toLight * inversesqrt(max(d2, 1e-8));
        float attenuation = jeddyDistanceAttenuation(d2, light.positionRange.w);
        if (floatBitsToUint(light.colorType.w) == J_LIGHT_SPOT) {
            attenuation *= jeddySpotAttenuation(light, l);
        }
        if (attenuation <= 0.0) {
            continue;
        }
        attenuation *= jeddyLocalShadow(light, vWorld, vNormal, l);
        color += jeddyShade(s, l, light.colorType.rgb * attenuation);
    }

    // Image-based lighting, with ambient occlusion from the material and screen space.
    float ao = occlusion * jeddyScreenOcclusion(gl_FragCoord.xy);
    vec3 r = reflect(-s.view, n);
    vec3 specular = jeddyPrefiltered(r, roughness) * (s.f0 * dfg.x + dfg.y) * s.energy;
    vec3 diffuse = jeddyIrradiance(n) * s.diffuse / J_PI;
    // Specular occlusion (Lagarde): AO weakens with gloss and grazing views.
    float so = clamp(pow(s.NoV + ao, exp2(-16.0 * roughness - 1.0)) - 1.0 + ao, 0.0, 1.0);
    color += (diffuse * ao * jEnvironment.y + specular * so * jEnvironment.x);

#ifdef EMISSIVE_MAP
    color += m.emissive.rgb * m.emissive.w
            * texture(jEmissiveMap, vec3(jeddySlotUv(m, 3u, vUv), float(m.layers.w))).rgb;
#else
    color += m.emissive.rgb * m.emissive.w;
#endif

    color = jeddyApplyEffects(color, vWorld, vFlags);
    outColor = vec4(color, base.a);
}
