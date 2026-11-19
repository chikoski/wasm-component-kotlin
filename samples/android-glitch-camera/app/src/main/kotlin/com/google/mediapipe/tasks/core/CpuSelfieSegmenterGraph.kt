package com.google.mediapipe.tasks.core

import android.content.Context
import android.os.SystemClock
import com.google.mediapipe.formats.proto.RectProto
import com.google.mediapipe.framework.AndroidAssetUtil
import com.google.mediapipe.framework.AndroidPacketCreator
import com.google.mediapipe.framework.Graph
import com.google.mediapipe.framework.Packet
import com.google.mediapipe.framework.PacketGetter
import com.google.mediapipe.framework.PacketListCallback
import com.google.mediapipe.framework.ProtoUtil
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.proto.CalculatorProto
import com.google.mediapipe.tasks.TensorsToSegmentationCalculatorOptionsProto
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.imagesegmenter.proto.SegmenterOptionsProto.SegmenterOptions.Activation
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs MediaPipe's selfie segmentation model on CPU while avoiding
 * `mediapipe.tasks.TensorsToSegmentationCalculator`, whose `Open()` unconditionally compiles a
 * GLSL 3.10 compute shader even when `Delegate.CPU` is requested (causing `GL_INVALID_ENUM`
 * failures on GLES 3.0 drivers and emulators).
 *
 * The expanded `ImageSegmenterGraph` config is obtained prior to graph start and rewritten to
 * replace `TensorsToSegmentationCalculator` with the pure-CPU `TensorsToFloatsCalculator`.
 *
 * Placed in `com.google.mediapipe.tasks.core` so it can access MediaPipe's package-private
 * [TaskInfo], [ModelResourcesCache], and [ModelResourcesCacheService] without reflection.
 */
class CpuSelfieSegmenterGraph private constructor(
    private val graph: Graph,
    private val modelResourcesCache: ModelResourcesCache,
    private val packetCreator: AndroidPacketCreator,
    private val imageInputStream: String,
    private val normRectInputStream: String?,
    val requiresSigmoid: Boolean,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var nextTimestampMicros = 0L

    @Volatile
    private var latestFloats: FloatArray? = null

    private fun attachCallback() {
        graph.addMultiStreamCallback(
            listOf(OUTPUT_FLOATS_STREAM),
            PacketListCallback { packets ->
                val packet = packets.firstOrNull()
                if (packet != null && !packet.isEmpty) {
                    latestFloats = PacketGetter.getFloat32Vector(packet)
                }
            },
            false,
        )
    }

    private fun start() {
        graph.startRunningGraph()
        graph.waitUntilGraphIdle()
    }

    fun segment(inputImage: MPImage): FloatArray? = synchronized(lock) {
        if (closed) return null
        val timestamp = maxOf(
            nextTimestampMicros + TIMESTAMP_STEP_MICROS,
            SystemClock.elapsedRealtimeNanos() / 1_000L,
        )
        nextTimestampMicros = timestamp
        latestFloats = null

        var imagePacket: Packet? = null
        var normRectPacket: Packet? = null
        return try {
            imagePacket = packetCreator.createImage(inputImage)
            if (normRectInputStream != null) {
                normRectPacket = packetCreator.createProto(IDENTITY_NORM_RECT)
            }
            imagePacket?.let { packet ->
                graph.addConsumablePacketToInputStream(imageInputStream, packet, timestamp)
                imagePacket = null
            }
            normRectPacket?.let { packet ->
                graph.addConsumablePacketToInputStream(normRectInputStream!!, packet, timestamp)
                normRectPacket = null
            }
            graph.waitUntilGraphIdle()
            latestFloats.also { latestFloats = null }
        } finally {
            imagePacket?.release()
            normRectPacket?.release()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { graph.closeAllPacketSources() }
            runCatching { graph.waitUntilGraphDone() }
            runCatching { modelResourcesCache.release() }
            runCatching { graph.tearDown() }
        }
    }

    internal data class RewrittenCpuGraphSpec(
        val config: CalculatorProto.CalculatorGraphConfig,
        val requiresSigmoid: Boolean,
    )

    companion object {
        private const val IMAGE_IN_STREAM_NAME = "image_in"
        private const val NORM_RECT_IN_STREAM_NAME = "norm_rect_in"
        private const val OUTPUT_SIZE_IN_STREAM_NAME = "output_size_in"
        private const val TASK_GRAPH_NAME = "mediapipe.tasks.vision.image_segmenter.ImageSegmenterGraph"
        internal const val OUTPUT_FLOATS_STREAM = "confidence_floats"
        private const val TIMESTAMP_STEP_MICROS = 1L

        private val cachedSpecs = ConcurrentHashMap<String, RewrittenCpuGraphSpec>()

        private val IDENTITY_NORM_RECT: RectProto.NormalizedRect by lazy {
            RectProto.NormalizedRect.newBuilder()
                .setXCenter(0.5f)
                .setYCenter(0.5f)
                .setWidth(1.0f)
                .setHeight(1.0f)
                .setRotation(0f)
                .build()
        }

        fun create(context: Context, modelAssetPath: String): CpuSelfieSegmenterGraph {
            val appContext = context.applicationContext
            // Ensure ImageSegmenter's static initializer loads libmediapipe_tasks_vision_jni.so
            // and BaseVisionTaskApi's static initializer registers mediapipe.NormalizedRect.
            Class.forName(ImageSegmenter::class.java.name)
            runCatching {
                ProtoUtil.registerTypeName(RectProto.NormalizedRect::class.java, "mediapipe.NormalizedRect")
            }
            AndroidAssetUtil.initializeNativeAssetManager(appContext)

            val spec = cachedSpecs[modelAssetPath] ?: buildCpuGraphSpec(modelAssetPath).also { built ->
                cachedSpecs.putIfAbsent(modelAssetPath, built)
            }
            val cpuConfig = spec.config
            val inputStreamNames = cpuConfig.inputStreamList.map { stripTagIndex(it) }
            val imageStream = inputStreamNames.firstOrNull { it.startsWith("image") } ?: IMAGE_IN_STREAM_NAME
            val normRectStream = inputStreamNames.firstOrNull { it.contains("norm_rect") }

            val runtimeGraph = Graph()
            val runtimeCache = ModelResourcesCache()
            return try {
                runtimeGraph.loadBinaryGraph(cpuConfig)
                runtimeGraph.setServiceObject(ModelResourcesCacheService(), runtimeCache)
                val packetCreator = AndroidPacketCreator(runtimeGraph)
                CpuSelfieSegmenterGraph(
                    graph = runtimeGraph,
                    modelResourcesCache = runtimeCache,
                    packetCreator = packetCreator,
                    imageInputStream = imageStream,
                    normRectInputStream = normRectStream,
                    requiresSigmoid = spec.requiresSigmoid,
                ).also { instance ->
                    instance.attachCallback()
                    instance.start()
                }
            } catch (t: Throwable) {
                runCatching { runtimeCache.release() }
                runCatching { runtimeGraph.tearDown() }
                throw t
            }
        }

        private fun buildCpuGraphSpec(modelAssetPath: String): RewrittenCpuGraphSpec {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)
                .setDelegate(Delegate.CPU)
                .build()
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build()
            val taskInfo = TaskInfo.builder<ImageSegmenter.ImageSegmenterOptions>()
                .setTaskName(ImageSegmenter::class.java.simpleName)
                .setTaskRunningModeName(RunningMode.IMAGE.name)
                .setTaskGraphName(TASK_GRAPH_NAME)
                .setInputStreams(
                    listOf(
                        "IMAGE:$IMAGE_IN_STREAM_NAME",
                        "NORM_RECT:$NORM_RECT_IN_STREAM_NAME",
                        "OUTPUT_SIZE:$OUTPUT_SIZE_IN_STREAM_NAME",
                    ),
                )
                .setOutputStreams(
                    listOf(
                        "CONFIDENCE_MASKS:confidence_masks",
                        "QUALITY_SCORES:quality_scores",
                        "IMAGE:image_out",
                    ),
                )
                .setTaskOptions(options)
                .setEnableFlowLimiting(false)
                .build()

            val expandedConfig = expandTaskGraphConfig(taskInfo.generateGraphConfig())
            return rewriteExpandedConfigForCpuSpec(expandedConfig)
        }

        private fun expandTaskGraphConfig(
            templateConfig: CalculatorProto.CalculatorGraphConfig,
        ): CalculatorProto.CalculatorGraphConfig {
            val templateGraph = Graph()
            val templateCache = ModelResourcesCache()
            return try {
                templateGraph.loadBinaryGraph(templateConfig)
                templateGraph.setServiceObject(ModelResourcesCacheService(), templateCache)
                templateGraph.calculatorGraphConfig
                    ?: error("MediaPipe returned null expanded CalculatorGraphConfig")
            } finally {
                runCatching { templateCache.release() }
                runCatching { templateGraph.tearDown() }
            }
        }

        internal fun rewriteExpandedConfigForCpu(
            expandedConfig: CalculatorProto.CalculatorGraphConfig,
        ): CalculatorProto.CalculatorGraphConfig = rewriteExpandedConfigForCpuSpec(expandedConfig).config

        internal fun rewriteExpandedConfigForCpuSpec(
            expandedConfig: CalculatorProto.CalculatorGraphConfig,
        ): RewrittenCpuGraphSpec {
            val segNode = expandedConfig.nodeList.firstOrNull { isTensorsToSegmentationNode(it) }
                ?: error("Expanded ImageSegmenterGraph does not contain TensorsToSegmentationCalculator")

            val activation = readActivation(segNode)
            val tensorsInputEntry = segNode.inputStreamList.firstOrNull { it.startsWith("TENSORS:") }
                ?: segNode.inputStreamList.firstOrNull { stripTagIndex(it).contains("tensor", ignoreCase = true) }
                ?: segNode.inputStreamList.firstOrNull()
                ?: error("TensorsToSegmentationCalculator has no input streams")
            val tensorsStreamName = stripTagIndex(tensorsInputEntry)

            val droppedIndices = HashSet<Int>()
            val deadStreams = HashSet<String>()
            expandedConfig.nodeList.forEachIndexed { index, node ->
                if (isDirectlyExcludedNode(node)) {
                    droppedIndices.add(index)
                    node.outputStreamList.forEach { deadStreams.add(stripTagIndex(it)) }
                }
            }
            // Drop any downstream nodes whose inputs depend on dropped calculators.
            var changed = true
            while (changed) {
                changed = false
                expandedConfig.nodeList.forEachIndexed { index, node ->
                    if (index !in droppedIndices && node.inputStreamList.any { stripTagIndex(it) in deadStreams }) {
                        droppedIndices.add(index)
                        node.outputStreamList.forEach { deadStreams.add(stripTagIndex(it)) }
                        changed = true
                    }
                }
            }

            val retainedNodes = expandedConfig.nodeList.filterIndexed { index, _ -> index !in droppedIndices }
            val tensorsToFloatsNode = CalculatorProto.CalculatorGraphConfig.Node.newBuilder()
                .setCalculator("TensorsToFloatsCalculator")
                .addInputStream("TENSORS:$tensorsStreamName")
                .addOutputStream("FLOATS:$OUTPUT_FLOATS_STREAM")
                .build()

            val consumedStreamNames = HashSet<String>().apply {
                for (node in retainedNodes) {
                    node.inputStreamList.forEach { add(stripTagIndex(it)) }
                }
                tensorsToFloatsNode.inputStreamList.forEach { add(stripTagIndex(it)) }
            }
            val retainedInputs = expandedConfig.inputStreamList.filter { stripTagIndex(it) in consumedStreamNames }

            val rewrittenConfig = expandedConfig.toBuilder()
                .clearNode()
                .addAllNode(retainedNodes)
                .addNode(tensorsToFloatsNode)
                .clearInputStream()
                .addAllInputStream(retainedInputs)
                .clearOutputStream()
                .addOutputStream("FLOATS:$OUTPUT_FLOATS_STREAM")
                .build()

            return RewrittenCpuGraphSpec(
                config = rewrittenConfig,
                requiresSigmoid = activation == Activation.SIGMOID,
            )
        }

        private fun readActivation(segNode: CalculatorProto.CalculatorGraphConfig.Node): Activation =
            runCatching {
                if (segNode.hasOptions()) {
                    segNode.options
                        .getExtension(
                            TensorsToSegmentationCalculatorOptionsProto
                                .TensorsToSegmentationCalculatorOptions.ext,
                        )
                        .segmenterOptions
                        .activation
                } else {
                    Activation.NONE
                }
            }.getOrDefault(Activation.NONE)

        private fun isTensorsToSegmentationNode(node: CalculatorProto.CalculatorGraphConfig.Node): Boolean =
            node.calculator.endsWith("TensorsToSegmentationCalculator") ||
                node.name.contains("TensorsToSegmentationCalculator")

        private fun isDirectlyExcludedNode(node: CalculatorProto.CalculatorGraphConfig.Node): Boolean =
            isTensorsToSegmentationNode(node) ||
                node.calculator.endsWith("MergeToVectorCalculator") ||
                node.name.contains("MergeToVectorCalculator") ||
                node.calculator.endsWith("MergeImagesToVectorCalculator") ||
                node.name.contains("MergeImagesToVectorCalculator") ||
                node.calculator.endsWith("ImagePropertiesCalculator") ||
                node.name.contains("ImagePropertiesCalculator") ||
                ((node.calculator.endsWith("PassThroughCalculator") ||
                    node.name.contains("PassThroughCalculator")) &&
                    node.outputStreamList.any { stripTagIndex(it) == "image_out" })

        private fun stripTagIndex(streamEntry: String): String {
            val colon = streamEntry.lastIndexOf(':')
            return if (colon >= 0) streamEntry.substring(colon + 1) else streamEntry
        }
    }
}
