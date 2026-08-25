#include <mbgl/vulkan/dynamic_texture.hpp>
#include <mbgl/vulkan/context.hpp>
#include <mbgl/vulkan/upload_pass.hpp>
#include <mbgl/vulkan/command_encoder.hpp>
#include <mbgl/util/logging.hpp>
#include <algorithm>
#include <cstring>

namespace mbgl {
namespace vulkan {

#if DYNAMIC_TEXTURE_VULKAN_MULTITHREADED_UPLOAD

DynamicTexture::DynamicTexture(Context& context_, Size size, gfx::TexturePixelType pixelType)
    : gfx::DynamicTexture(context_, size, pixelType),
      context(context_) {
    texture->create();
    const vk::CommandPoolCreateInfo createInfo(vk::CommandPoolCreateFlagBits::eResetCommandBuffer,
                                               context.getBackend().getGraphicsQueueIndex());
    commandPool = context.getBackend().getDevice()->createCommandPoolUnique(
        createInfo, nullptr, context.getBackend().getDispatcher());
}

DynamicTexture::~DynamicTexture() {
    for (auto& texture : texturesToBlit) {
        texture.second->destroy(false);
    }

    if (texture) {
        static_cast<Texture2D&>(*texture).destroy(false);
    }
}

void DynamicTexture::uploadImage(const uint8_t* pixelData, gfx::TextureHandle& texHandle) {
    std::scoped_lock lock(mutex);
    const auto& rect = texHandle.getRectangle();
    const auto imageSize = Size(rect.w, rect.h);

    auto textureToBlit = std::static_pointer_cast<Texture2D>(context.createTexture2D());
    texturesToBlit.emplace(texHandle, textureToBlit);

    textureToBlit->setSize(imageSize);
    textureToBlit->setFormat(texture.get()->getFormat(), gfx::TextureChannelDataType::UnsignedByte);
    textureToBlit->setUsage(Texture2DUsage::Blit);
    textureToBlit->create();

    const auto& device = context.getBackend().getDevice();
    const auto& dispatcher = context.getBackend().getDispatcher();

    const vk::CommandBufferAllocateInfo allocateInfo(commandPool.get(), vk::CommandBufferLevel::ePrimary, 1);
    const auto& commandBuffers = device->allocateCommandBuffersUnique(allocateInfo, dispatcher);
    const auto& commandBuffer = commandBuffers.front();

    commandBuffer->begin(vk::CommandBufferBeginInfo(vk::CommandBufferUsageFlagBits::eOneTimeSubmit), dispatcher);
    textureToBlit->uploadSubRegion(pixelData, imageSize, 0, 0, commandBuffer, true);

    gfx::DynamicTexture::uploadImage(pixelData, texHandle);
}

void DynamicTexture::uploadDeferredImages(gfx::UploadPass&) {
    std::scoped_lock lock(mutex);

    if (texturesToBlit.empty()) {
        return;
    }

    const auto& textureVK = static_cast<Texture2D*>(texture.get());
    context.submitOneTimeCommand(commandPool, [&](const vk::UniqueCommandBuffer& commandBuffer) {
        textureVK->transitionToTransferWriteLayout(commandBuffer);
        for (const auto& pair : texturesToBlit) {
            const auto& rect = pair.first.getRectangle();
            const auto copyInfo = vk::ImageCopy()
                                      .setSrcSubresource({vk::ImageAspectFlagBits::eColor, 0, 0, 1})
                                      .setDstSubresource({vk::ImageAspectFlagBits::eColor, 0, 0, 1})
                                      .setExtent({rect.w, rect.h, 1})
                                      .setDstOffset({rect.x, rect.y, 0});

            const auto& textureToBlitVK = static_cast<Texture2D*>(pair.second.get());
            commandBuffer->copyImage(textureToBlitVK->getVulkanImage(),
                                     textureToBlitVK->getVulkanImageLayout(),
                                     textureVK->getVulkanImage(),
                                     textureVK->getVulkanImageLayout(),
                                     copyInfo,
                                     context.getBackend().getDispatcher());
        }
        textureVK->transitionToShaderReadLayout(commandBuffer);
    });

    for (auto& texture : texturesToBlit) {
        texture.second->destroy(false);
    }

    texturesToBlit.clear();
}

bool DynamicTexture::removeTexture(const gfx::TextureHandle& texHandle) {
    if (gfx::DynamicTexture::removeTexture(texHandle)) {
        std::scoped_lock lock(mutex);

        const auto& tex = texturesToBlit.find(texHandle);
        if (tex != texturesToBlit.end()) {
            tex->second->destroy(false);
            texturesToBlit.erase(tex);
        }
        return true;
    }
    return false;
}
#else

DynamicTexture::DynamicTexture(Context& context_, Size size, gfx::TexturePixelType pixelType)
    : gfx::DynamicTexture(context_, size, pixelType),
      context(context_) {}

DynamicTexture::~DynamicTexture() {
    if (texture) {
        static_cast<Texture2D&>(*texture).destroy(true);
    }
}

void DynamicTexture::uploadImage(const uint8_t* pixelData, gfx::TextureHandle& texHandle) {
    std::scoped_lock lock(mutex);
    const auto& rect = texHandle.getRectangle();

    const auto& backend = context.getBackend();
    const auto& allocator = backend.getAllocator();

    const auto bufferInfo = vk::BufferCreateInfo()
                                .setSize(static_cast<vk::DeviceSize>(rect.w) * rect.h * texture->getPixelStride())
                                .setUsage(vk::BufferUsageFlagBits::eTransferSrc)
                                .setSharingMode(vk::SharingMode::eExclusive);

    VmaAllocationCreateInfo allocationInfo = {};

    allocationInfo.usage = VMA_MEMORY_USAGE_AUTO_PREFER_HOST;
    allocationInfo.requiredFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    allocationInfo.flags = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT;

    SharedBufferAllocation bufferAllocation = std::make_shared<BufferAllocation>(allocator);
    if (!bufferAllocation->create(allocationInfo, bufferInfo)) {
        mbgl::Log::Error(mbgl::Event::Render, "Vulkan texture buffer allocation failed");
        throw std::bad_alloc();
    }

    // MAP RESULT CHECKED, AND THE COPY BOUNDED BY THE ALLOCATION.
    //
    // vmaMapMemory can fail, and its result was ignored: mappedBuffer stays as
    // it was and the memcpy below writes through it anyway. Crash seen on an
    // Android emulator (Pixel_8a, API 36, arm64) -- SIGSEGV/SEGV_ACCERR inside
    // __memcpy_aarch64_simd, called from Texture2D::uploadSubRegion, on the
    // render thread, intermittently.
    //
    // The copy is also clamped to the size VMA actually allocated rather than
    // the size requested. VMA should never return less, but the fault address
    // in that crash sat past the start of the destination mapping, and a
    // partially-uploaded texture is a far better outcome than writing outside
    // it.
    VmaAllocationInfo mappedInfo{};
    vmaGetAllocationInfo(allocator, bufferAllocation->allocation, &mappedInfo);

    if (vmaMapMemory(allocator, bufferAllocation->allocation, &bufferAllocation->mappedBuffer) != VK_SUCCESS ||
        !bufferAllocation->mappedBuffer) {
        mbgl::Log::Error(mbgl::Event::Render, "Vulkan dynamic texture mapping failed; skipping upload");
        return;
    }

    const size_t copySize = std::min(static_cast<size_t>(bufferInfo.size), static_cast<size_t>(mappedInfo.size));
    memcpy(bufferAllocation->mappedBuffer, pixelData, copySize);

    textureBuffersToUpload.emplace(texHandle, std::move(bufferAllocation));

    texture->create();

    gfx::DynamicTexture::uploadImage(pixelData, texHandle);
}

void DynamicTexture::uploadDeferredImages(gfx::UploadPass& uploadPass) {
    std::scoped_lock lock(mutex);

    if (textureBuffersToUpload.empty()) {
        return;
    }

    const auto& textureVK = static_cast<Texture2D*>(texture.get());
    const auto& commandBuffer = static_cast<UploadPass&>(uploadPass).getCommandEncoder().getCommandBuffer();

    textureVK->transitionToTransferWriteLayout(commandBuffer);
    for (auto& pair : textureBuffersToUpload) {
        const auto& rect = pair.first.getRectangle();
        const auto region = vk::BufferImageCopy()
                                .setBufferOffset(0)
                                .setBufferRowLength(rect.w)
                                .setImageSubresource(
                                    vk::ImageSubresourceLayers(vk::ImageAspectFlagBits::eColor, 0, 0, 1))
                                .setImageOffset(vk::Offset3D(rect.x, rect.y))
                                .setImageExtent(vk::Extent3D(rect.w, rect.h, 1));

        commandBuffer->copyBufferToImage(pair.second->buffer,
                                         textureVK->getVulkanImage(),
                                         textureVK->getVulkanImageLayout(),
                                         region,
                                         context.getBackend().getDispatcher());

        context.threadSafeAccessRenderingStats([&](gfx::RenderingStats& stats) {
            stats.numTextureUpdates++;
            stats.textureUpdateBytes += rect.w * rect.h * texture->getPixelStride();
        });
    }
    textureVK->transitionToShaderReadLayout(commandBuffer);

    context.enqueueDeletion([buffers = std::move(textureBuffersToUpload)](Context&) {});
}

bool DynamicTexture::removeTexture(const gfx::TextureHandle& texHandle) {
    if (gfx::DynamicTexture::removeTexture(texHandle)) {
        std::scoped_lock lock(mutex);
        textureBuffersToUpload.erase(texHandle);
        return true;
    }
    return false;
}
#endif

} // namespace vulkan
} // namespace mbgl
