#include "spirv_fold_offsets.hpp"

#include <cstring>
#include <unordered_map>

namespace lsfg_android {

bool g_fold_offsets = true;

namespace {
enum : uint32_t {
    OpCapability = 17, OpTypeInt = 21, OpTypeFloat = 22, OpTypeVector = 23, OpTypeImage = 25,
    OpTypeSampledImage = 27, OpConstant = 43, OpFunction = 54, OpSampledImage = 86,
    OpImageSampleImplicitLod = 87, OpImageSampleExplicitLod = 88, OpImage = 100,
    OpImageQuerySizeLod = 103, OpConvertUToF = 112, OpConvertSToF = 111, OpFAdd = 129, OpFDiv = 136,
    OpLabel = 248,
};
constexpr uint32_t CapImageQuery = 50;
struct Ins { uint32_t op; std::vector<uint32_t> w; }; // w includes the first word
}

int fold_const_offsets(std::vector<uint8_t> &spirv) {
    if (spirv.size() < 20 || spirv.size() % 4) return 0;
    const size_t n = spirv.size() / 4;
    std::vector<uint32_t> words(n);
    std::memcpy(words.data(), spirv.data(), spirv.size());
    if (words[0] != 0x07230203) return 0;
    uint32_t bound = words[3];
    std::vector<Ins> ins;
    for (size_t i = 5; i < n;) {
        const uint32_t wc = words[i] >> 16;
        if (wc == 0 || i + wc > n) return 0;
        ins.push_back({words[i] & 0xFFFF, std::vector<uint32_t>(words.begin() + i, words.begin() + i + wc)});
        i += wc;
    }
    // types
    uint32_t tFloat = 0, tInt = 0, tIntSigned = 1, tV2F = 0, tV2I = 0, cInt0 = 0;
    bool hasImageQuery = false;
    std::unordered_map<uint32_t, uint32_t> sampledTypeToImage, idType, typeIsV2F;
    std::unordered_map<uint32_t, uint32_t> sampledImageOf; // sampled image id -> image id
    for (auto &x : ins) {
        const auto &w = x.w;
        if (x.op == OpCapability && w[1] == CapImageQuery) hasImageQuery = true;
        if (x.op == OpTypeFloat && w[2] == 32) tFloat = w[1];
        if (x.op == OpTypeInt && w[2] == 32 && !tInt) { tInt = w[1]; tIntSigned = w[3]; }
        if (x.op == OpTypeInt && w[2] == 32 && w[3] == 1) { tInt = w[1]; tIntSigned = 1; }
    }
    for (auto &x : ins) {
        const auto &w = x.w;
        if (x.op == OpTypeVector && w[3] == 2 && w[2] == tFloat) tV2F = w[1];
        if (x.op == OpTypeVector && w[3] == 2 && w[2] == tInt) tV2I = w[1];
        if (x.op == OpTypeSampledImage) sampledTypeToImage[w[1]] = w[2];
        if (x.op == OpConstant && w[1] == tInt && w.size() == 4 && w[3] == 0) cInt0 = w[2];
        if (x.op == OpSampledImage) sampledImageOf[w[2]] = w[3];
        if (w.size() >= 3 && (x.op == 61 || (x.op >= 79 && x.op <= 200))) idType[w[2]] = w[1];
    }
    if (!tFloat || !tInt) return 0;
    // declarations to add before the first function
    std::vector<Ins> decl;
    if (!tV2F) { tV2F = bound++; decl.push_back({OpTypeVector, {(4u << 16) | OpTypeVector, tV2F, tFloat, 2}}); }
    if (!tV2I) { tV2I = bound++; decl.push_back({OpTypeVector, {(4u << 16) | OpTypeVector, tV2I, tInt, 2}}); }
    if (!cInt0) { cInt0 = bound++; decl.push_back({OpConstant, {(4u << 16) | OpConstant, tInt, cInt0, 0}}); }
    // image type of each OpSampledImage result
    std::unordered_map<uint32_t, uint32_t> imageTypeOfSampled;
    for (auto &x : ins)
        if (x.op == OpSampledImage) {
            auto it = sampledTypeToImage.find(x.w[1]);
            if (it != sampledTypeToImage.end()) imageTypeOfSampled[x.w[2]] = it->second;
        }
    int changed = 0;
    std::vector<Ins> out;
    out.reserve(ins.size() + 64);
    bool declDone = false;
    for (auto &x : ins) {
        if (x.op == OpFunction && !declDone) {
            for (auto &d : decl) out.push_back(d);
            declDone = true;
        }
        if ((x.op == OpImageSampleExplicitLod || x.op == OpImageSampleImplicitLod) && x.w.size() >= 6) {
            const uint32_t mask = x.w[5];
            const uint32_t si = x.w[3], coord = x.w[4];
            auto itT = imageTypeOfSampled.find(si);
            auto itC = idType.find(coord);
            if ((mask & 0x8) && !(mask & ~0xFu) && itT != imageTypeOfSampled.end()
                    && itC != idType.end() && itC->second == tV2F) {
                // locate the ConstOffset operand
                size_t k = 6;
                if (mask & 0x1) k++;
                if (mask & 0x2) k++;
                if (mask & 0x4) k += 2;
                if (k < x.w.size()) {
                    const uint32_t offId = x.w[k];
                    const uint32_t img = bound++, sz = bound++, szf = bound++, of = bound++, dv = bound++, c2 = bound++;
                    const uint32_t cvt = tIntSigned ? OpConvertSToF : OpConvertUToF;
                    out.push_back({OpImage, {(4u << 16) | OpImage, itT->second, img, si}});
                    out.push_back({OpImageQuerySizeLod, {(5u << 16) | OpImageQuerySizeLod, tV2I, sz, img, cInt0}});
                    out.push_back({cvt, {(4u << 16) | cvt, tV2F, szf, sz}});
                    out.push_back({OpConvertSToF, {(4u << 16) | OpConvertSToF, tV2F, of, offId}});
                    out.push_back({OpFDiv, {(5u << 16) | OpFDiv, tV2F, dv, of, szf}});
                    out.push_back({OpFAdd, {(5u << 16) | OpFAdd, tV2F, c2, coord, dv}});
                    std::vector<uint32_t> nw(x.w.begin(), x.w.begin() + 5);
                    nw[4] = c2;
                    nw.push_back(mask & ~0x8u);
                    for (size_t j = 6; j < x.w.size(); j++) if (j != k) nw.push_back(x.w[j]);
                    nw[0] = (static_cast<uint32_t>(nw.size()) << 16) | x.op;
                    out.push_back({x.op, nw});
                    changed++;
                    continue;
                }
            }
        }
        out.push_back(x);
    }
    if (!changed) return 0;
    if (!hasImageQuery) {
        // capabilities come first
        out.insert(out.begin(), Ins{OpCapability, {(2u << 16) | OpCapability, CapImageQuery}});
    }
    std::vector<uint32_t> res(words.begin(), words.begin() + 5);
    res[3] = bound;
    for (auto &x : out) res.insert(res.end(), x.w.begin(), x.w.end());
    spirv.resize(res.size() * 4);
    std::memcpy(spirv.data(), res.data(), spirv.size());
    return changed;
}

} // namespace lsfg_android
