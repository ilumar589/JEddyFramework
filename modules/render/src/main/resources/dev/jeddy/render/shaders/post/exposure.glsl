// Auto-exposure state shared by the histogram, the average and the composite (TASKS M7-06).
#pragma once

layout(std430, binding = 5) buffer JeddyExposure {
    uint jHistogram[256]; // bin 0: black; bins 1..255: log2 luminance over the configured range
    float jExposure;      // current exposure multiplier (0 before the first frame: snap)
    float jAverageLuminance;
    float jTargetExposure;
    float jExposurePad;
};
