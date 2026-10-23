#include <volk.h>
#include <vulkan/vulkan_core.h>

#include "lsfg_3_1p.hpp"
#include "v3_1p/context.hpp"
#include "core/fence.hpp"
#include "core/pipeline.hpp"
#include "core/shadermodule.hpp"
#include "gbafg_probe_spv.h"
#include "gbafg_synth_spv.h"
#include "gbafg_alt_spv.h"
#include "core/commandbuffer.hpp"
#include "core/commandpool.hpp"
#include "core/descriptorpool.hpp"
#include "core/instance.hpp"
#include "pool/shaderpool.hpp"
#include "common/exception.hpp"
#include "common/utils.hpp"

#include <cstdint>
#include <optional>
#include <cstdlib>
#include <ctime>
#include <functional>
#include <string>
#include <unordered_map>
#include <vector>

using namespace LSFG;
using namespace LSFG_3_1P;

namespace {
    std::optional<Core::Instance> instance;
    std::optional<Vulkan> device;
    std::unordered_map<int32_t, Context> contexts;
}

void LSFG_3_1P::initialize(uint64_t deviceUUID,
        bool isHdr, float flowScale, uint64_t generationCount,
        const std::function<std::vector<uint8_t>(const std::string&)>& loader) {
    if (instance.has_value() || device.has_value())
        return;

    instance.emplace();
    device.emplace(Vulkan {
        .device{*instance, deviceUUID},
        .generationCount = generationCount,
        .flowScale = flowScale,
        .isHdr = isHdr
    });
    contexts = std::unordered_map<int32_t, Context>();

    device->commandPool = Core::CommandPool(device->device);
    device->descriptorPool = Core::DescriptorPool(device->device);

    device->resources = Pool::ResourcePool(device->isHdr, device->flowScale);
    device->shaders = Pool::ShaderPool(loader);

    std::srand(static_cast<uint32_t>(std::time(nullptr)));
}

int32_t LSFG_3_1P::createContext(
        int in0, int in1, const std::vector<int>& outN,
        VkExtent2D extent, VkFormat format) {
    if (!instance.has_value() || !device.has_value())
        throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED, "LSFG not initialized");

    const int32_t id = std::rand();
    contexts.emplace(id, Context(*device, in0, in1, outN, extent, format));
    return id;
}

void LSFG_3_1P::presentContext(int32_t id, int inSem, const std::vector<int>& outSem) {
    if (!instance.has_value() || !device.has_value())
        throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED, "LSFG not initialized");

    auto it = contexts.find(id);
    if (it == contexts.end())
        throw LSFG::vulkan_error(VK_ERROR_UNKNOWN, "Context not found");

    it->second.present(*device, inSem, outSem);
}

void LSFG_3_1P::deleteContext(int32_t id) {
    if (!instance.has_value() || !device.has_value())
        throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED, "LSFG not initialized");

    auto it = contexts.find(id);
    if (it == contexts.end())
        throw LSFG::vulkan_error(VK_ERROR_DEVICE_LOST, "No such context");

    vkDeviceWaitIdle(device->device.handle());
    contexts.erase(it);
}

void LSFG_3_1P::finalize() {
    if (!instance.has_value() || !device.has_value())
        return;

    vkDeviceWaitIdle(device->device.handle());
    contexts.clear();
    device.reset();
    instance.reset();
}

#ifdef __ANDROID__

#include <android/hardware_buffer.h>

int32_t LSFG_3_1P::createContextFromAHB(
        AHardwareBuffer* in0, AHardwareBuffer* in1,
        const std::vector<AHardwareBuffer*>& outN,
        VkExtent2D extent, VkFormat format) {
    if (!instance.has_value() || !device.has_value())
        throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED, "LSFG not initialized");

    const int32_t id = std::rand();
    contexts.emplace(id, Context(*device, in0, in1, outN, extent, format));
    return id;
}

#endif // __ANDROID__

#ifdef __ANDROID__
void LSFG_3_1P::waitIdle() {
    if (!device.has_value()) return;
    vkDeviceWaitIdle(device->device.handle());
}
#endif

// ---- gbafg diagnostics: dump intermediate images ----
namespace {
uint32_t gbafgBpp(VkFormat f) {
    switch (f) {
        case VK_FORMAT_R8_UNORM: return 1;
        case VK_FORMAT_R8G8_UNORM: case VK_FORMAT_R16_SFLOAT: return 2;
        case VK_FORMAT_R8G8B8A8_UNORM: case VK_FORMAT_R16G16_SFLOAT: case VK_FORMAT_R32_SFLOAT: return 4;
        case VK_FORMAT_R16G16B16A16_SFLOAT: case VK_FORMAT_R32G32_SFLOAT: return 8;
        case VK_FORMAT_R32G32B32A32_SFLOAT: return 16;
        default: return 0;
    }
}
}

void LSFG_3_1P::debugDump(int32_t id, std::vector<DumpImg>& out) {
    out.clear();
    if (!device.has_value()) return;
    auto it = contexts.find(id);
    if (it == contexts.end()) return;
    Context& c = it->second;
    std::vector<std::pair<std::string, Core::Image>> imgs;
    for (size_t i = 0; i < 7; i++) imgs.emplace_back("mip" + std::to_string(i), c.mipmaps.getOutImages().at(i));
    for (size_t i = 0; i < 7; i++)
        for (size_t a = 0; a < 3; a++)
            for (size_t b = 0; b < 2; b++)
                imgs.emplace_back("alpha" + std::to_string(i) + "_" + std::to_string(a) + std::to_string(b),
                    c.alpha.at(i).getOutImages().at(a).at(b));
    for (size_t i = 0; i < 2; i++) {
        imgs.emplace_back("atmp" + std::to_string(i) + "_1", c.alpha.at(i).tempImg1);
        imgs.emplace_back("atmp" + std::to_string(i) + "_2", c.alpha.at(i).tempImg2);
        imgs.emplace_back("atmp" + std::to_string(i) + "_30", c.alpha.at(i).tempImgs3.at(0));
        imgs.emplace_back("atmp" + std::to_string(i) + "_31", c.alpha.at(i).tempImgs3.at(1));
    }
    for (size_t i = 0; i < 6; i++) imgs.emplace_back("beta" + std::to_string(i), c.beta.getOutImages().at(i));
    for (size_t i = 0; i < 7; i++) imgs.emplace_back("gamma" + std::to_string(i), c.gamma.at(i).getOutImage());
    for (size_t i = 0; i < 3; i++) {
        imgs.emplace_back("delta" + std::to_string(i) + "a", c.delta.at(i).getOutImage1());
        imgs.emplace_back("delta" + std::to_string(i) + "b", c.delta.at(i).getOutImage2());
    }
    imgs.emplace_back("gen", c.generate.getOutImages().at(0));

    auto dumpList = [&](std::vector<std::pair<std::string, Core::Image>>& imgs) {
    VkDevice dev = device->device.handle();
    VkPhysicalDevice phys = device->device.getPhysicalDevice();
    VkQueue q = device->device.getComputeQueue();
    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.queueFamilyIndex = device->device.getComputeFamilyIdx();
    VkCommandPool pool{};
    if (vkCreateCommandPool(dev, &pci, nullptr, &pool) != VK_SUCCESS) return;
    VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(phys, &mp);
    vkDeviceWaitIdle(dev);
    for (auto& [name, img] : imgs) {
        const VkExtent2D e = img.getExtent();
        const uint32_t bpp = gbafgBpp(img.getFormat());
        if (bpp == 0 || e.width == 0 || e.height == 0 || img.handle() == VK_NULL_HANDLE) continue;
        const VkDeviceSize size = (VkDeviceSize)e.width * e.height * bpp;
        VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; bi.size = size; bi.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        VkBuffer buf{}; if (vkCreateBuffer(dev, &bi, nullptr, &buf) != VK_SUCCESS) continue;
        VkMemoryRequirements r; vkGetBufferMemoryRequirements(dev, buf, &r);
        uint32_t t = UINT32_MAX;
        for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
            if ((r.memoryTypeBits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)
                    && (mp.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) { t = i; break; }
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; ai.allocationSize = r.size; ai.memoryTypeIndex = t;
        VkDeviceMemory mem{};
        if (t == UINT32_MAX || vkAllocateMemory(dev, &ai, nullptr, &mem) != VK_SUCCESS) { vkDestroyBuffer(dev, buf, nullptr); continue; }
        vkBindBufferMemory(dev, buf, mem, 0);
        VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        cai.commandPool = pool; cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cai.commandBufferCount = 1;
        VkCommandBuffer cb{}; vkAllocateCommandBuffers(dev, &cai, &cb);
        VkCommandBufferBeginInfo cbi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; vkBeginCommandBuffer(cb, &cbi);
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        b.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT; b.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        b.oldLayout = VK_IMAGE_LAYOUT_GENERAL; b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; b.image = img.handle();
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
        VkBufferImageCopy rg{}; rg.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; rg.imageExtent = {e.width, e.height, 1};
        vkCmdCopyImageToBuffer(cb, img.handle(), VK_IMAGE_LAYOUT_GENERAL, buf, 1, &rg);
        VkMemoryBarrier hb{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; hb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; hb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &hb, 0, nullptr, 0, nullptr);
        vkEndCommandBuffer(cb);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount = 1; si.pCommandBuffers = &cb;
        vkQueueSubmit(q, 1, &si, VK_NULL_HANDLE); vkQueueWaitIdle(q);
        void* p = nullptr;
        if (vkMapMemory(dev, mem, 0, size, 0, &p) == VK_SUCCESS) {
            DumpImg d{name, e.width, e.height, (int)img.getFormat(), {}};
            d.data.assign(static_cast<uint8_t*>(p), static_cast<uint8_t*>(p) + size);
            out.push_back(std::move(d));
            vkUnmapMemory(dev, mem);
        }
        vkFreeCommandBuffers(dev, pool, 1, &cb);
        vkFreeMemory(dev, mem, nullptr);
        vkDestroyBuffer(dev, buf, nullptr);
    }
    vkDestroyCommandPool(dev, pool, nullptr);
    };
    dumpList(imgs);
    // re-run alpha0 on its own (inputs are still intact): tells a computation difference apart
    // from an in-frame synchronisation problem
    try {
        const uint64_t fc = c.frameIdx > 0 ? c.frameIdx - 1 : 0;
        Core::CommandBuffer cb(device->device, device->commandPool);
        cb.begin();
        c.alpha.at(0).Dispatch(cb, fc);
        cb.end();
        Core::Fence f(device->device);
        cb.submit(device->device.getComputeQueue(), f);
        (void)f.wait(device->device);
        std::vector<std::pair<std::string, Core::Image>> again;
        for (size_t a = 0; a < 3; a++)
            for (size_t b = 0; b < 2; b++)
                again.emplace_back("rerun_alpha0_" + std::to_string(a) + std::to_string(b), c.alpha.at(0).getOutImages().at(a).at(b));
        again.emplace_back("rerun_atmp0_30", c.alpha.at(0).tempImgs3.at(0));
        again.emplace_back("rerun_slot_" + std::to_string(fc % 3), c.alpha.at(0).tempImgs3.at(1));
        // probe: a trivial shader on the same descriptor set copies what the sampler sees
        // from tempImgs3 (centre texel of Input1, +1 x offset of Input2) into the outputs
        {
            static const uint32_t* probeWords = kProbeSpv;
            std::vector<uint8_t> bytes(reinterpret_cast<const uint8_t*>(probeWords),
                reinterpret_cast<const uint8_t*>(probeWords) + sizeof(kProbeSpv));
            Core::ShaderModule pm(device->device, bytes,
                { { 1, VK_DESCRIPTOR_TYPE_SAMPLER }, { 2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE },
                  { 2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE } });
            Core::Pipeline pp(device->device, pm);
            Core::CommandBuffer cb2(device->device, device->commandPool);
            cb2.begin();
            pp.bind(cb2);
            c.alpha.at(0).lastDescriptorSet.at(fc % 3).bind(cb2, pp);
            const auto e = c.alpha.at(0).tempImgs3.at(0).getExtent();
            cb2.dispatch((e.width + 7) >> 3, (e.height + 7) >> 3, 1);
            cb2.end();
            Core::Fence f2(device->device);
            cb2.submit(device->device.getComputeQueue(), f2);
            (void)f2.wait(device->device);
            again.emplace_back("probe_0", c.alpha.at(0).getOutImages().at(fc % 3).at(0));
            again.emplace_back("probe_1", c.alpha.at(0).getOutImages().at(fc % 3).at(1));
        }
        std::vector<DumpImg> extra;
        std::swap(extra, out);
        dumpList(again);
        std::swap(extra, out);
        for (auto& e : extra) out.push_back(std::move(e));
        // alternative binding styles (raw Vulkan: own layout/pool/set per variant)
        for (int k = 0; k < kAltCount; k++) {
            VkDevice dv = device->device.handle();
            const std::string lay = kAltLayout[k];
            std::vector<VkDescriptorSetLayoutBinding> binds;
            for (size_t i = 0; i < lay.size(); i++) {
                VkDescriptorType t = lay[i] == 'S' ? VK_DESCRIPTOR_TYPE_SAMPLER
                    : lay[i] == 'T' ? VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE
                    : lay[i] == 'C' ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                binds.push_back({(uint32_t)i, t, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr});
            }
            VkDescriptorSetLayoutCreateInfo lci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            lci.bindingCount = (uint32_t)binds.size(); lci.pBindings = binds.data();
            VkDescriptorSetLayout dsl{}; vkCreateDescriptorSetLayout(dv, &lci, nullptr, &dsl);
            VkDescriptorPoolSize ps[4] = {{VK_DESCRIPTOR_TYPE_SAMPLER, 4}, {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, 4},
                {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 4}, {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 4}};
            VkDescriptorPoolCreateInfo pci2{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            pci2.maxSets = 1; pci2.poolSizeCount = 4; pci2.pPoolSizes = ps;
            VkDescriptorPool dp{}; vkCreateDescriptorPool(dv, &pci2, nullptr, &dp);
            VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            dai.descriptorPool = dp; dai.descriptorSetCount = 1; dai.pSetLayouts = &dsl;
            VkDescriptorSet ds{}; vkAllocateDescriptorSets(dv, &dai, &ds);
            auto& a0 = c.alpha.at(0);
            VkSampler smp = a0.sampler.handle();
            std::vector<VkDescriptorImageInfo> infos(lay.size());
            std::vector<VkWriteDescriptorSet> wr;
            int inIdx = 0, outIdx = 0;
            for (size_t i = 0; i < lay.size(); i++) {
                VkDescriptorImageInfo& ii = infos[i];
                if (lay[i] == 'S') { ii.sampler = smp; }
                else if (lay[i] == 'O') { ii.imageView = a0.getOutImages().at(fc % 3).at(outIdx++).getView(); ii.imageLayout = VK_IMAGE_LAYOUT_GENERAL; }
                else { ii.imageView = a0.tempImgs3.at(inIdx++).getView(); ii.imageLayout = VK_IMAGE_LAYOUT_GENERAL; if (lay[i] == 'C') ii.sampler = smp; }
                VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
                w.dstSet = ds; w.dstBinding = (uint32_t)i; w.descriptorCount = 1; w.descriptorType = binds[i].descriptorType; w.pImageInfo = &ii;
                wr.push_back(w);
            }
            vkUpdateDescriptorSets(dv, (uint32_t)wr.size(), wr.data(), 0, nullptr);
            VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            plci.setLayoutCount = 1; plci.pSetLayouts = &dsl;
            VkPipelineLayout pl{}; vkCreatePipelineLayout(dv, &plci, nullptr, &pl);
            VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
            smci.codeSize = kAltSize[k]; smci.pCode = kAlt[k];
            VkShaderModule smod{}; vkCreateShaderModule(dv, &smci, nullptr, &smod);
            VkComputePipelineCreateInfo cpci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
            cpci.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, smod, "main", nullptr};
            cpci.layout = pl;
            VkPipeline pipe{}; vkCreateComputePipelines(dv, VK_NULL_HANDLE, 1, &cpci, nullptr, &pipe);
            Core::CommandBuffer cb4(device->device, device->commandPool);
            cb4.begin();
            vkCmdBindPipeline(cb4.handle(), VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
            vkCmdBindDescriptorSets(cb4.handle(), VK_PIPELINE_BIND_POINT_COMPUTE, pl, 0, 1, &ds, 0, nullptr);
            const auto e = a0.tempImgs3.at(0).getExtent();
            vkCmdDispatch(cb4.handle(), (e.width + 7) >> 3, (e.height + 7) >> 3, 1);
            cb4.end();
            Core::Fence f4(device->device);
            cb4.submit(device->device.getComputeQueue(), f4);
            (void)f4.wait(device->device);
            vkDestroyPipeline(dv, pipe, nullptr); vkDestroyShaderModule(dv, smod, nullptr);
            vkDestroyPipelineLayout(dv, pl, nullptr); vkDestroyDescriptorPool(dv, dp, nullptr);
            vkDestroyDescriptorSetLayout(dv, dsl, nullptr);
            std::vector<std::pair<std::string, Core::Image>> sl;
            sl.emplace_back(std::string("alt_") + kAltName[k] + "_0", a0.getOutImages().at(fc % 3).at(0));
            sl.emplace_back(std::string("alt_") + kAltName[k] + "_1", a0.getOutImages().at(fc % 3).at(1));
            std::vector<DumpImg> keep;
            std::swap(keep, out);
            dumpList(sl);
            std::swap(keep, out);
            for (auto& x : keep) out.push_back(std::move(x));
        }
        // synthetic shaders of the same shape (random weights): which shape does the driver get wrong?
        for (int k = 0; k < kSynthCount; k++) {
            std::vector<uint8_t> bytes(reinterpret_cast<const uint8_t*>(kSynth[k]),
                reinterpret_cast<const uint8_t*>(kSynth[k]) + kSynthSize[k]);
            Core::ShaderModule sm(device->device, bytes,
                { { 1, VK_DESCRIPTOR_TYPE_SAMPLER }, { 2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE },
                  { 2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE } });
            Core::Pipeline sp(device->device, sm);
            Core::CommandBuffer cb3(device->device, device->commandPool);
            cb3.begin();
            sp.bind(cb3);
            c.alpha.at(0).lastDescriptorSet.at(fc % 3).bind(cb3, sp);
            const auto e = c.alpha.at(0).tempImgs3.at(0).getExtent();
            cb3.dispatch((e.width + 7) >> 3, (e.height + 7) >> 3, 1);
            cb3.end();
            Core::Fence f3(device->device);
            cb3.submit(device->device.getComputeQueue(), f3);
            (void)f3.wait(device->device);
            std::vector<std::pair<std::string, Core::Image>> sl;
            sl.emplace_back("synth" + std::to_string(k) + "_0", c.alpha.at(0).getOutImages().at(fc % 3).at(0));
            sl.emplace_back("synth" + std::to_string(k) + "_1", c.alpha.at(0).getOutImages().at(fc % 3).at(1));
            std::vector<DumpImg> keep;
            std::swap(keep, out);
            dumpList(sl);
            std::swap(keep, out);
            for (auto& x : keep) out.push_back(std::move(x));
        }
    } catch (...) {}
}
