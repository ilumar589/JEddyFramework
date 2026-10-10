/*
 * jeddy_tools_native: C helpers used by the Jeddy cooker through FFM.
 */
#ifndef JEDDY_TOOLS_NATIVE_H
#define JEDDY_TOOLS_NATIVE_H

#include <stdint.h>

#if defined(_WIN32)
#  if defined(JEDDY_TOOLS_NATIVE_BUILD)
#    define JEDDY_API __declspec(dllexport)
#  else
#    define JEDDY_API __declspec(dllimport)
#  endif
#else
#  define JEDDY_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/** Version of this shim's API. Bumped on any signature change. */
JEDDY_API int32_t jeddy_tools_native_version(void);

/**
 * Generates MikkTSpace tangents for an indexed triangle list, per triangle corner.
 *
 * The result is per corner (not per vertex) because two triangles sharing a vertex may need
 * different tangent frames; the caller re-welds vertices afterwards (glTF 2.0 rules).
 *
 * positions      float[3 * vertexCount]
 * normals        float[3 * vertexCount], unit length
 * uvs            float[2 * vertexCount]
 * indices        uint32[3 * triangleCount], each < vertexCount
 * tangentsOut    float[4 * 3 * triangleCount]: xyz tangent, w bitangent sign (+1 or -1)
 *
 * Returns 1 on success, 0 on invalid input or failure.
 */
JEDDY_API int32_t jeddy_mikk_generate_tangents(const float *positions, const float *normals,
                                               const float *uvs, int32_t vertexCount,
                                               const uint32_t *indices, int32_t triangleCount,
                                               float *tangentsOut);

#ifdef __cplusplus
}
#endif

#endif
