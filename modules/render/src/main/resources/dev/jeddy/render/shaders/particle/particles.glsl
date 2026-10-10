// GPU particles v1 (TASKS M7-09): a fixed pool of particles, a dead-index stack and two alive
// lists that swap every frame. Emitters (from RenderPacket) spawn into the current alive list,
// the simulation moves survivors into the next one, and the next list is drawn as camera-facing
// sprites through an indirect draw whose instance count the GPU writes.
#pragma once

struct JeddyParticle {
    vec4 positionAge;   // xyz world position, w age (s)
    vec4 velocityLife;  // xyz velocity (m/s), w lifetime (s)
    vec4 colorStart;    // premultiplied linear rgba at birth (a = 0: additive)
    vec4 colorEnd;      // at death
    vec4 sizeForces;    // x size at birth, y at death (m, half extent), z gravity scale, w drag
};

layout(std430, binding = 12) buffer JeddyParticles {
    JeddyParticle jParticles[];
};

// Header: dead count, alive counts of lists 0 and 1, then a DrawArraysIndirectCommand at uint 4.
// Indices follow the 16-uint header: dead stack, alive list 0, alive list 1 (capacity each).
layout(std430, binding = 13) buffer JeddyParticleLists {
    uint jDeadCount;
    uint jAliveCount[2];
    uint jListPad;
    uint jDrawCount;
    uint jDrawInstances;
    uint jDrawFirst;
    uint jDrawBaseInstance;
    uint jHeaderPad[8];
    uint jIndices[];
};

uint jeddyDead(uint i, uint capacity) {
    return i;
}

uint jeddyAlive(uint list, uint i, uint capacity) {
    return capacity * (1u + list) + i;
}

// PCG hash (Jarzynski and Olano 2020): one well-mixed uint per input.
uint jeddyHash(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float jeddyRandom(inout uint seed) {
    seed = jeddyHash(seed);
    return float(seed >> 8u) * (1.0 / 16777216.0);
}
