#pragma once
#include <cstdint>
#include <vector>

namespace lsfg_android {
// gbafg: Mali (seen on Immortalis-G925) miscompiles shaders that sample two different textures
// with the same coordinate and the same ConstOffset: the fetches get merged and one texture's
// data is used for both. This rewrites every ConstOffset sample into an explicit coordinate
// offset (coord + offset / textureSize), which is mathematically the same and dodges the bug.
// Returns the number of rewritten instructions (0 = unchanged).
int fold_const_offsets(std::vector<uint8_t> &spirv);
extern bool g_fold_offsets;
}
