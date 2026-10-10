#include "jeddy_tools_native.h"

#include <stddef.h>

#include "mikktspace.h"

typedef struct {
    const float *positions;
    const float *normals;
    const float *uvs;
    const uint32_t *indices;
    int32_t vertexCount;
    int32_t triangleCount;
    float *tangentsOut;
} JeddyMikkContext;

static JeddyMikkContext *ctx(const SMikkTSpaceContext *c) { return (JeddyMikkContext *)c->m_pUserData; }

static int getNumFaces(const SMikkTSpaceContext *c) { return ctx(c)->triangleCount; }

static int getNumVerticesOfFace(const SMikkTSpaceContext *c, const int face) {
    (void)c;
    (void)face;
    return 3;
}

static uint32_t vertexIndex(const SMikkTSpaceContext *c, int face, int vert) {
    return ctx(c)->indices[3 * face + vert];
}

static void getPosition(const SMikkTSpaceContext *c, float out[], const int face, const int vert) {
    const float *p = ctx(c)->positions + 3 * (size_t)vertexIndex(c, face, vert);
    out[0] = p[0];
    out[1] = p[1];
    out[2] = p[2];
}

static void getNormal(const SMikkTSpaceContext *c, float out[], const int face, const int vert) {
    const float *n = ctx(c)->normals + 3 * (size_t)vertexIndex(c, face, vert);
    out[0] = n[0];
    out[1] = n[1];
    out[2] = n[2];
}

static void getTexCoord(const SMikkTSpaceContext *c, float out[], const int face, const int vert) {
    const float *t = ctx(c)->uvs + 2 * (size_t)vertexIndex(c, face, vert);
    out[0] = t[0];
    out[1] = t[1];
}

static void setTSpaceBasic(const SMikkTSpaceContext *c, const float tangent[], const float sign,
                           const int face, const int vert) {
    float *o = ctx(c)->tangentsOut + 4 * (size_t)(3 * face + vert);
    o[0] = tangent[0];
    o[1] = tangent[1];
    o[2] = tangent[2];
    o[3] = sign < 0.0f ? -1.0f : 1.0f;
}

JEDDY_API int32_t jeddy_tools_native_version(void) { return 1; }

JEDDY_API int32_t jeddy_mikk_generate_tangents(const float *positions, const float *normals,
                                               const float *uvs, int32_t vertexCount,
                                               const uint32_t *indices, int32_t triangleCount,
                                               float *tangentsOut) {
    if (!positions || !normals || !uvs || !indices || !tangentsOut || vertexCount <= 0 ||
        triangleCount <= 0) {
        return 0;
    }
    for (int32_t i = 0; i < 3 * triangleCount; i++) {
        if (indices[i] >= (uint32_t)vertexCount) {
            return 0;
        }
    }

    JeddyMikkContext data = {positions, normals, uvs, indices, vertexCount, triangleCount, tangentsOut};
    SMikkTSpaceInterface iface = {0};
    iface.m_getNumFaces = getNumFaces;
    iface.m_getNumVerticesOfFace = getNumVerticesOfFace;
    iface.m_getPosition = getPosition;
    iface.m_getNormal = getNormal;
    iface.m_getTexCoord = getTexCoord;
    iface.m_setTSpaceBasic = setTSpaceBasic;
    iface.m_setTSpace = NULL;

    SMikkTSpaceContext context;
    context.m_pInterface = &iface;
    context.m_pUserData = &data;
    return genTangSpaceDefault(&context) ? 1 : 0;
}
