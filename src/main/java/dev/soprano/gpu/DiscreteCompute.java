package dev.soprano.gpu;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMappedMemoryRange;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.util.LinkedHashMap;
import java.util.Objects;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;

/** Owns an independent Vulkan instance, logical device and serial compute queue. */
public final class DiscreteCompute {
    private static final int MAX_WORDS = 4 * 1024 * 1024;
    private static final int MAX_SHADER_CHARS = 128 * 1024;
    private static final int MAX_ITERATIONS = 1_024;
    private static final int MAX_DISPATCH_GROUPS = 65_535;
    private static final int MAX_SUBMITTED_GROUPS = 2_000_000;
    private static final long FENCE_TIMEOUT = 3_000_000_000L;
    private static Engine engine;
    private static volatile IllegalStateException unavailable;
    private static volatile boolean closing;

    public record Result(int[] storage, String deviceName) { }

    private DiscreteCompute() { }

    /** Lets callers avoid staging a job after a terminal hardware failure. */
    public static boolean mayRun() { return !closing && unavailable == null; }

    /** One 32-bit SSBO at set zero, binding zero, plus push constants [iteration, constants...]. */
    public static synchronized Result run(String glsl, int[] storage, int groups, int iterations, int[] constants) {
        Objects.requireNonNull(glsl, "compute shader");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(constants, "push constants");
        if (glsl.isEmpty() || glsl.length() > MAX_SHADER_CHARS || storage.length < 1 || storage.length > MAX_WORDS
            || groups < 1 || groups > MAX_DISPATCH_GROUPS || iterations < 1 || iterations > MAX_ITERATIONS
            || (long) groups * iterations > MAX_SUBMITTED_GROUPS || constants.length > 31) {
            throw new IllegalArgumentException("Soprano GPU work exceeds a bounded compute dispatch.");
        }
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("The GPU request was cancelled.");
        if (closing) throw new IllegalStateException("The Soprano GPU compute session has closed.");
        if (unavailable != null) throw unavailable;
        if (engine == null) {
            try {
                engine = Engine.open();
                Runtime.getRuntime().addShutdownHook(new Thread(DiscreteCompute::close, "Soprano Vulkan cleanup"));
            } catch (LinkageError | RuntimeException failure) {
                if (engine != null) disposeEngine();
                unavailable = new IllegalStateException("Discrete Vulkan compute is unavailable. " + failure.getMessage(), failure);
                throw unavailable;
            }
        }
        try {
            return engine.run(glsl, storage, groups, iterations, constants);
        } catch (FenceTimeout failure) {
            Engine abandoned = engine;
            engine = null;
            unavailable = new IllegalStateException(failure.getMessage(), failure);
            Thread.ofPlatform().daemon().name("Soprano pending Vulkan cleanup").start(() -> abandoned.finishPending(failure.work));
            throw unavailable;
        } catch (DeviceLost failure) {
            disposeEngine();
            unavailable = new IllegalStateException("The discrete Vulkan device stopped responding.", failure);
            throw unavailable;
        } catch (LinkageError failure) {
            unavailable = new IllegalStateException("The Vulkan shader runtime is unavailable. " + failure.getMessage(), failure);
            throw unavailable;
        }
    }

    public static synchronized void close() {
        closing = true;
        disposeEngine();
    }

    private static void disposeEngine() {
        if (engine == null) return;
        engine.close();
        engine = null;
    }

    private static void check(int result, String operation) {
        if (result == VK_ERROR_DEVICE_LOST) throw new DeviceLost(operation);
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed (Vulkan result " + result + ").");
    }

    private static final class DeviceLost extends IllegalStateException {
        DeviceLost(String operation) { super(operation + " reported a lost Vulkan device."); }
    }

    private static final class FenceTimeout extends RuntimeException {
        final Work work;
        FenceTimeout(Work work, int result) {
            super(result == VK_TIMEOUT ? "The discrete GPU did not finish the compute dispatch in time."
                : "The discrete GPU's compute completion could not be confirmed (Vulkan result " + result + ").");
            this.work = work;
        }
    }

    private record Adapter(VkPhysicalDevice physical, int family, String name, long localBytes,
                           long maxStorageBytes, int maxGroups, int maxPushBytes) { }
    private record PipelineKey(String glsl, int pushBytes) { }
    private record Pipeline(long descriptorLayout, long layout, long pipeline) { }

    private static final class Engine implements AutoCloseable {
        private final VkInstance instance;
        private final Adapter adapter;
        private final VkDevice device;
        private final VkQueue queue;
        private final LinkedHashMap<PipelineKey, Pipeline> pipelines = new LinkedHashMap<>(4, .75f, true);

        private Engine(VkInstance instance, Adapter adapter, VkDevice device, VkQueue queue) {
            this.instance = instance;
            this.adapter = adapter;
            this.device = device;
            this.queue = queue;
        }

        static Engine open() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var app = VkApplicationInfo.calloc(stack).sType$Default().pApplicationName(stack.UTF8("Soprano Compute"))
                    .apiVersion(VK_API_VERSION_1_0);
                var info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
                var pointer = stack.mallocPointer(1);
                check(vkCreateInstance(info, null, pointer), "Creating the Vulkan instance");
                VkInstance instance = new VkInstance(pointer.get(0), info);
                VkDevice device = null;
                try {
                    Adapter adapter = chooseAdapter(instance, stack);
                    var queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack).sType$Default()
                        .queueFamilyIndex(adapter.family()).pQueuePriorities(stack.floats(.25f));
                    var deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queueInfo);
                    check(vkCreateDevice(adapter.physical(), deviceInfo, null, pointer), "Opening " + adapter.name());
                    device = new VkDevice(pointer.get(0), adapter.physical(), deviceInfo);
                    vkGetDeviceQueue(device, adapter.family(), 0, pointer);
                    return new Engine(instance, adapter, device, new VkQueue(pointer.get(0), device));
                } catch (RuntimeException | LinkageError failure) {
                    if (device != null) vkDestroyDevice(device, null);
                    vkDestroyInstance(instance, null);
                    throw failure;
                }
            }
        }

        private static Adapter chooseAdapter(VkInstance instance, MemoryStack stack) {
            var count = stack.ints(0);
            check(vkEnumeratePhysicalDevices(instance, count, null), "Enumerating Vulkan devices");
            if (count.get(0) == 0) throw new IllegalStateException("No Vulkan device was found.");
            var devices = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, devices), "Reading Vulkan devices");
            Adapter best = null;
            for (int i = 0; i < count.get(0); i++) {
                try (MemoryStack local = MemoryStack.stackPush()) {
                    VkPhysicalDevice physical = new VkPhysicalDevice(devices.get(i), instance);
                    var properties = VkPhysicalDeviceProperties.calloc(local);
                    vkGetPhysicalDeviceProperties(physical, properties);
                    if (properties.deviceType() != VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) continue;
                    int family = computeFamily(physical, local);
                    if (family < 0) continue;
                    var memory = VkPhysicalDeviceMemoryProperties.calloc(local);
                    vkGetPhysicalDeviceMemoryProperties(physical, memory);
                    long bytes = 0;
                    for (int heap = 0; heap < memory.memoryHeapCount(); heap++) {
                        var candidate = memory.memoryHeaps(heap);
                        if ((candidate.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) bytes = Math.max(bytes, candidate.size());
                    }
                    if (best != null && bytes <= best.localBytes()) continue;
                    var limits = properties.limits();
                    best = new Adapter(physical, family, properties.deviceNameString(), bytes,
                        Integer.toUnsignedLong(limits.maxStorageBufferRange()), limits.maxComputeWorkGroupCount(0),
                        limits.maxPushConstantsSize());
                }
            }
            if (best == null) throw new IllegalStateException("No discrete GPU exposed a Vulkan compute queue.");
            return best;
        }

        private static int computeFamily(VkPhysicalDevice device, MemoryStack stack) {
            var count = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
            var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);
            int shared = -1;
            for (int i = 0; i < count.get(0); i++) {
                var family = families.get(i);
                int flags = family.queueFlags();
                if (family.queueCount() == 0 || (flags & VK_QUEUE_COMPUTE_BIT) == 0) continue;
                if ((flags & VK_QUEUE_GRAPHICS_BIT) == 0) return i;
                if (shared == -1) shared = i;
            }
            return shared;
        }

        Result run(String glsl, int[] storage, int groups, int iterations, int[] constants) {
            long bytes = (long) storage.length * Integer.BYTES;
            int pushBytes = (constants.length + 1) * Integer.BYTES;
            if (bytes > adapter.maxStorageBytes() || groups > adapter.maxGroups() || pushBytes > adapter.maxPushBytes()) {
                throw new IllegalStateException("The selected Vulkan device does not support this compute workload size.");
            }
            try (MemoryStack stack = MemoryStack.stackPush(); Work work = new Work(device)) {
                Pipeline pipeline = pipeline(glsl, pushBytes, stack);
                allocateStorage(work, bytes, stack);
                var data = MemoryUtil.memIntBuffer(work.mapped, storage.length);
                data.put(0, storage, 0, storage.length);
                var range = VkMappedMemoryRange.calloc(stack).sType$Default().memory(work.memory).offset(0).size(VK_WHOLE_SIZE);
                if (!work.coherent) check(vkFlushMappedMemoryRanges(device, range), "Flushing input storage");
                long set = descriptors(work, pipeline.descriptorLayout(), bytes, stack);
                VkCommandBuffer commands = commands(work, stack);
                record(commands, pipeline, set, groups, iterations, constants, stack);
                submit(work, commands, stack);
                if (!work.coherent) check(vkInvalidateMappedMemoryRanges(device, range), "Invalidating GPU output storage");
                int[] output = new int[storage.length];
                data.get(0, output, 0, output.length);
                return new Result(output, adapter.name());
            }
        }

        private Pipeline pipeline(String glsl, int pushBytes, MemoryStack stack) {
            PipelineKey key = new PipelineKey(glsl, pushBytes);
            Pipeline cached = pipelines.get(key);
            if (cached != null) return cached;
            long descriptorLayout = 0, layout = 0, shader = 0;
            long compiler = 0, result = 0;
            boolean created = false;
            var output = stack.mallocLong(1);
            try {
                compiler = shaderc_compiler_initialize();
                if (compiler == 0) throw new IllegalStateException("Could not initialize the Vulkan shader compiler.");
                result = shaderc_compile_into_spv(compiler, glsl, shaderc_compute_shader, "soprano.comp", "main", 0);
                if (result == 0) throw new IllegalStateException("The Vulkan shader compiler returned no result.");
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                    throw new IllegalStateException("The Soprano compute shader could not compile. " + shaderc_result_get_error_message(result));
                }
                var shaderInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(shaderc_result_get_bytes(result));
                check(vkCreateShaderModule(device, shaderInfo, null, output), "Creating the compute shader");
                shader = output.get(0);
                var binding = VkDescriptorSetLayoutBinding.calloc(1, stack).binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                var setInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binding);
                check(vkCreateDescriptorSetLayout(device, setInfo, null, output), "Creating the storage layout");
                descriptorLayout = output.get(0);
                var push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushBytes);
                var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(push);
                check(vkCreatePipelineLayout(device, layoutInfo, null, output), "Creating the compute pipeline layout");
                layout = output.get(0);
                var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(layout).basePipelineIndex(-1);
                info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"));
                check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, output), "Creating the compute pipeline");
                Pipeline pipeline = new Pipeline(descriptorLayout, layout, output.get(0));
                if (pipelines.size() == 4) destroy(pipelines.pollFirstEntry().getValue());
                pipelines.put(key, pipeline);
                created = true;
                return pipeline;
            } finally {
                if (shader != 0) vkDestroyShaderModule(device, shader, null);
                if (result != 0) shaderc_result_release(result);
                if (compiler != 0) shaderc_compiler_release(compiler);
                if (!created) {
                    if (layout != 0) vkDestroyPipelineLayout(device, layout, null);
                    if (descriptorLayout != 0) vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
                }
            }
        }

        private void allocateStorage(Work work, long bytes, MemoryStack stack) {
            var output = stack.mallocLong(1);
            var info = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            check(vkCreateBuffer(device, info, null, output), "Creating the compute storage buffer");
            work.buffer = output.get(0);
            var requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, work.buffer, requirements);
            var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(adapter.physical(), properties);
            int best = -1, bestScore = -1;
            for (int i = 0; i < properties.memoryTypeCount(); i++) {
                int flags = properties.memoryTypes(i).propertyFlags();
                if ((requirements.memoryTypeBits() & (1 << i)) == 0 || (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0) continue;
                int score = ((flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0 ? 4 : 0)
                    + ((flags & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) != 0 ? 2 : 0)
                    + ((flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0 ? 1 : 0);
                if (score > bestScore) { best = i; bestScore = score; }
            }
            if (best < 0) throw new IllegalStateException("The compute buffer has no host-visible Vulkan memory type.");
            work.coherent = (properties.memoryTypes(best).propertyFlags() & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
            var allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(requirements.size()).memoryTypeIndex(best);
            check(vkAllocateMemory(device, allocation, null, output), "Allocating Vulkan compute memory");
            work.memory = output.get(0);
            check(vkBindBufferMemory(device, work.buffer, work.memory, 0), "Binding the compute storage buffer");
            var pointer = stack.mallocPointer(1);
            check(vkMapMemory(device, work.memory, 0, VK_WHOLE_SIZE, 0, pointer), "Mapping the compute storage buffer");
            work.mapped = pointer.get(0);
        }

        private long descriptors(Work work, long layout, long bytes, MemoryStack stack) {
            var size = VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            var pool = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(size);
            var output = stack.mallocLong(1);
            check(vkCreateDescriptorPool(device, pool, null, output), "Creating the compute descriptor pool");
            work.descriptorPool = output.get(0);
            var allocation = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(work.descriptorPool).pSetLayouts(stack.longs(layout));
            check(vkAllocateDescriptorSets(device, allocation, output), "Allocating the compute descriptor set");
            long set = output.get(0);
            var info = VkDescriptorBufferInfo.calloc(1, stack).buffer(work.buffer).offset(0).range(bytes);
            var write = VkWriteDescriptorSet.calloc(1, stack).sType$Default().dstSet(set).dstBinding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(info);
            vkUpdateDescriptorSets(device, write, null);
            return set;
        }

        private VkCommandBuffer commands(Work work, MemoryStack stack) {
            var pool = VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(adapter.family());
            var output = stack.mallocLong(1);
            check(vkCreateCommandPool(device, pool, null, output), "Creating the compute command pool");
            work.commandPool = output.get(0);
            var allocation = VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(work.commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            var pointer = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, allocation, pointer), "Allocating the compute command buffer");
            return new VkCommandBuffer(pointer.get(0), device);
        }

        private static void record(VkCommandBuffer commands, Pipeline pipeline, long set, int groups,
                                   int iterations, int[] constants, MemoryStack stack) {
            var begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(commands, begin), "Beginning the compute command buffer");
            vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipeline());
            vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.layout(), 0, stack.longs(set), null);
            var push = stack.mallocInt(constants.length + 1);
            push.put(1, constants, 0, constants.length);
            var compute = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            for (int i = 0; i < iterations; i++) {
                push.put(0, i);
                vkCmdPushConstants(commands, pipeline.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                vkCmdDispatch(commands, groups, 1, 1);
                if (i + 1 < iterations) vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, compute, null, null);
            }
            var output = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, output, null, null);
            check(vkEndCommandBuffer(commands), "Ending the compute command buffer");
        }

        private void submit(Work work, VkCommandBuffer commands, MemoryStack stack) {
            var fence = VkFenceCreateInfo.calloc(stack).sType$Default();
            var output = stack.mallocLong(1);
            check(vkCreateFence(device, fence, null, output), "Creating the compute fence");
            work.fence = output.get(0);
            var submission = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(commands.address()));
            check(vkQueueSubmit(queue, submission, work.fence), "Submitting the compute work");
            int wait = vkWaitForFences(device, work.fence, true, FENCE_TIMEOUT);
            if (wait != VK_SUCCESS && wait != VK_ERROR_DEVICE_LOST) {
                work.detached = true;
                throw new FenceTimeout(work, wait);
            }
            check(wait, "Waiting for the compute result");
        }

        void finishPending(Work work) {
            int result = vkWaitForFences(device, work.fence, true, -1L);
            if (result != VK_SUCCESS && result != VK_ERROR_DEVICE_LOST) result = vkDeviceWaitIdle(device);
            if (result != VK_SUCCESS && result != VK_ERROR_DEVICE_LOST) return;
            work.detached = false;
            work.close();
            close();
        }

        private void destroy(Pipeline pipeline) {
            vkDestroyPipeline(device, pipeline.pipeline(), null);
            vkDestroyPipelineLayout(device, pipeline.layout(), null);
            vkDestroyDescriptorSetLayout(device, pipeline.descriptorLayout(), null);
        }

        @Override public void close() {
            for (Pipeline pipeline : pipelines.values()) destroy(pipeline);
            pipelines.clear();
            vkDestroyDevice(device, null);
            vkDestroyInstance(instance, null);
        }
    }

    private static final class Work implements AutoCloseable {
        final VkDevice device;
        long buffer, memory, mapped, descriptorPool, commandPool, fence;
        boolean coherent, detached;

        Work(VkDevice device) { this.device = device; }

        @Override public void close() {
            if (detached) return;
            if (fence != 0) vkDestroyFence(device, fence, null);
            if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
            if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
            if (mapped != 0) vkUnmapMemory(device, memory);
            if (buffer != 0) vkDestroyBuffer(device, buffer, null);
            if (memory != 0) vkFreeMemory(device, memory, null);
        }
    }
}
