#include <cstring>
#include "pool/shaderpool.hpp"
#include "core/shadermodule.hpp"
#include "core/device.hpp"
#include "core/pipeline.hpp"

#include <vulkan/vulkan_core.h>

#include <cstddef>
#include <stdexcept>
#include <string>
#include <vector>
#include <utility>

bool lsfg_split_big_shaders = false; // gbafg: did not help on Mali; kept for experiments
size_t lsfg_split_threshold = 400;
using namespace LSFG;
using namespace LSFG::Pool;

Core::ShaderModule ShaderPool::getShader(
        const Core::Device& device, const std::string& name,
        const std::vector<std::pair<size_t, VkDescriptorType>>& types) {
    auto it = shaders.find(name);
    if (it != shaders.end())
        return it->second;

    // grab the shader
    auto bytecode = this->source(name);
    if (bytecode.empty())
        throw std::runtime_error("Shader code is empty: " + name);

    // create the shader module
    Core::ShaderModule shader(device, bytecode, types);
    shaders[name] = shader;
    codes[name] = bytecode;
    typesByName[name] = types;
    return shader;
}

Core::Pipeline ShaderPool::getPipeline(
        const Core::Device& device, const std::string& name) {
    auto it = pipelines.find(name);
    if (it != pipelines.end())
        return it->second;

    // grab the shader module
    auto shader = this->getShader(device, name, {});

    // create the pipeline
    Core::Pipeline pipeline(device, shader);

    // gbafg: some mobile shader compilers (seen on Mali) miscompile LSFG's largest shaders
    // (600+ inline constants). A shader that writes N outputs is split into N variants that
    // each keep a single imageStore, so every variant needs about 1/N of the constants;
    // dispatching all of them produces the same result.
    if (lsfg_split_big_shaders && codes.count(name)) {
        const auto& code = codes[name];
        const auto* w = reinterpret_cast<const uint32_t*>(code.data());
        const size_t n = code.size() / 4;
        std::vector<size_t> writes;
        size_t consts = 0;
        for (size_t i = 5; i < n;) {
            const uint32_t op = w[i] & 0xFFFF, wc = w[i] >> 16;
            if (wc == 0) break;
            if (op == 43) consts++;
            if (op == 99) writes.push_back(i);
            i += wc;
        }
        if (writes.size() >= 2 && consts > lsfg_split_threshold) {
            std::vector<Core::Pipeline> variants;
            for (size_t keep = 0; keep < writes.size(); keep++) {
                std::vector<uint32_t> out;
                out.reserve(n);
                for (size_t i = 0; i < n;) {
                    const uint32_t wc = (i < 5) ? 1 : (w[i] >> 16);
                    bool drop = false;
                    for (size_t k = 0; k < writes.size(); k++)
                        if (k != keep && writes[k] == i) drop = true;
                    if (!drop) out.insert(out.end(), w + i, w + i + wc);
                    i += wc;
                }
                std::vector<uint8_t> bytes(out.size() * 4);
                std::memcpy(bytes.data(), out.data(), bytes.size());
                Core::ShaderModule vm(device, bytes, typesByName[name]);
                variants.emplace_back(device, vm);
            }
            pipeline.setSplits(std::move(variants));
        }
    }
    pipelines[name] = pipeline;
    return pipeline;
}
