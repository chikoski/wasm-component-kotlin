package wasm2kt.wasi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import wasm2kt.runtime.Linker
import wasm2kt.wasi.wasi.clocks.system_clock.Instant
import wasm2kt.wasi.wasi.filesystem.preopens.Preopens
import wasm2kt.wasi.wasi.filesystem.preopens.add as addPreopens
import wasm2kt.wasi.wasi.filesystem.types.Advice
import wasm2kt.wasi.wasi.filesystem.types.Descriptor
import wasm2kt.wasi.wasi.filesystem.types.DescriptorFlags
import wasm2kt.wasi.wasi.filesystem.types.DescriptorStat
import wasm2kt.wasi.wasi.filesystem.types.DescriptorType
import wasm2kt.wasi.wasi.filesystem.types.DirectoryEntry
import wasm2kt.wasi.wasi.filesystem.types.ErrorCode
import wasm2kt.wasi.wasi.filesystem.types.Filesize
import wasm2kt.wasi.wasi.filesystem.types.MetadataHashValue
import wasm2kt.wasi.wasi.filesystem.types.NewTimestamp
import wasm2kt.wasi.wasi.filesystem.types.OpenFlags
import wasm2kt.wasi.wasi.filesystem.types.PathFlags
import wasm2kt.wasi.wasi.filesystem.types.Types
import wasm2kt.wasi.wasi.filesystem.types.add as addTypes
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/** Marker annotation for declarative WASI configuration DSL builders. */
@DslMarker
annotation class WasiDsl

/**
 * A single preopened mount point exposed to the WASI guest via `wasi:filesystem/preopens@0.3.0`.
 *
 * Each mount associates a [guestPath] visible to the guest with a factory [createDescriptor] producing
 * the backing root [Descriptor].
 */
class WasiMount(
    val guestPath: String,
    val createDescriptor: () -> Descriptor,
) {
    /**
     * Mounts [hostPath] on the local filesystem at [guestPath].
     *
     * When [readOnly] is true, write and directory-mutating operations fail with [ErrorCode.ReadOnly].
     */
    constructor(
        guestPath: String,
        hostPath: Path,
        readOnly: Boolean = false,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        guestPath = guestPath,
        createDescriptor = {
            WasiPathDescriptor.createRoot(hostPath, readOnly = readOnly, dispatcher = dispatcher)
        },
    )
}

/**
 * Configuration scope for a single mount declared inside [WasiMountsBuilder.mount].
 */
@WasiDsl
class WasiMountConfig(val guestPath: String) {
    var hostPath: Path? = null
    var readOnly: Boolean = false
    var dispatcher: CoroutineDispatcher? = null
    var descriptor: (() -> Descriptor)? = null

    internal fun build(defaultDispatcher: CoroutineDispatcher): WasiMount {
        val customFactory = descriptor
        val path = hostPath
        return when {
            customFactory != null && path != null -> error(
                "Mount '$guestPath' cannot specify both hostPath and custom descriptor"
            )
            customFactory != null -> WasiMount(guestPath, customFactory)
            path != null -> WasiMount(
                guestPath = guestPath,
                hostPath = path,
                readOnly = readOnly,
                dispatcher = dispatcher ?: defaultDispatcher,
            )
            else -> error("Mount '$guestPath' must specify either hostPath or descriptor")
        }
    }
}

/**
 * Declarative builder for WASI preopened mounts.
 */
@WasiDsl
class WasiMountsBuilder(
    var dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mounts = mutableListOf<WasiMount>()

    /** Mounts [hostPath] read-only at [guestPath]. */
    fun readOnly(guestPath: String, hostPath: Path) {
        mounts.add(WasiMount(guestPath, hostPath, readOnly = true, dispatcher = dispatcher))
    }

    /** Mounts [hostPath] read-write at [guestPath]. */
    fun readWrite(guestPath: String, hostPath: Path) {
        mounts.add(WasiMount(guestPath, hostPath, readOnly = false, dispatcher = dispatcher))
    }

    /** Mounts [hostPath] at [guestPath] with explicit [readOnly] and [dispatcher] settings. */
    fun dir(
        guestPath: String,
        hostPath: Path,
        readOnly: Boolean = false,
        dispatcher: CoroutineDispatcher = this.dispatcher,
    ) {
        mounts.add(WasiMount(guestPath, hostPath, readOnly = readOnly, dispatcher = dispatcher))
    }

    /** Mounts a custom [Descriptor] factory at [guestPath] (e.g. Android DocumentFile or virtual FS). */
    fun custom(guestPath: String, descriptor: () -> Descriptor) {
        mounts.add(WasiMount(guestPath, descriptor))
    }

    /** Mounts a pre-created custom [Descriptor] instance at [guestPath]. */
    fun custom(guestPath: String, descriptor: Descriptor) {
        mounts.add(WasiMount(guestPath) { descriptor })
    }

    /** Configures a mount at [guestPath] using a block DSL. */
    fun mount(guestPath: String, configure: WasiMountConfig.() -> Unit) {
        mounts.add(WasiMountConfig(guestPath).apply(configure).build(dispatcher))
    }

    fun build(): List<WasiMount> = mounts.toList()
}

/**
 * Host marker implementation for `wasi:filesystem/types@0.3.0`.
 */
class WasiFilesystemTypes : Types

/**
 * Host implementation of `wasi:filesystem/preopens@0.3.0`.
 *
 * Defaults to zero preopens ([mounts] empty) for capability-based safety.
 */
class WasiPreopens(
    private val mounts: List<WasiMount> = emptyList(),
) : Preopens {
    override fun getDirectories(): List<Tuple2<Descriptor, String>> =
        mounts.map { Tuple2(it.createDescriptor(), it.guestPath) }
}

/**
 * Combined `wasi:filesystem@0.3.0` (`types` + `preopens`) host implementation bundle.
 */
class WasiFilesystem(
    val types: Types = WasiFilesystemTypes(),
    val preopens: Preopens = WasiPreopens(),
) {
    constructor(mounts: List<WasiMount>) : this(
        types = WasiFilesystemTypes(),
        preopens = WasiPreopens(mounts),
    )

    constructor(configure: WasiMountsBuilder.() -> Unit) : this(
        mounts = WasiMountsBuilder().apply(configure).build(),
    )

    fun addTo(linker: Linker) {
        linker.addTypes(types)
        linker.addPreopens(preopens)
    }
}

/** Registers [filesystem] (`wasi:filesystem/types@0.3.0` and `preopens@0.3.0`) on this [Linker]. */
fun Linker.addWasiFilesystem(filesystem: WasiFilesystem = WasiFilesystem()): Linker = apply {
    filesystem.addTo(this)
}

/** Configures declarative filesystem mounts and registers them on this [Linker]. */
fun Linker.addWasiFilesystem(configure: WasiMountsBuilder.() -> Unit): Linker = apply {
    WasiFilesystem(configure).addTo(this)
}

/**
 * Sandboxed [Descriptor] implementation backed by `java.nio.file.Path` and [FileChannel].
 *
 * Compatible with JVM 11 and Android API 26+. All relative operations stay strictly confined to
 * [rootPath]; attempts to escape via leading `/`, `\`, NUL bytes, `..`, or symlinks pointing
 * outside [rootPath] fail with [ErrorCode.NotPermitted].
 */
class WasiPathDescriptor private constructor(
    private val rootPath: Path,
    private val path: Path,
    private val flags: DescriptorFlags,
    private val readOnlyMount: Boolean,
    private val isDirectory: Boolean,
    private val channel: FileChannel?,
    private val dispatcher: CoroutineDispatcher,
) : Descriptor {

    override fun readViaStream(offset: Filesize): Tuple2<Flow<ByteArray>, Deferred<WitResult<Unit, ErrorCode>>> {
        val result = CompletableDeferred<WitResult<Unit, ErrorCode>>()
        val stream = flow {
            if (isDirectory) {
                result.complete(WitResult.Err(ErrorCode.IsDirectory))
                return@flow
            }
            val ch = channel
            if (ch == null || !flags.read) {
                result.complete(WitResult.Err(ErrorCode.BadDescriptor))
                return@flow
            }
            var pos = offset.toLong()
            if (pos < 0L) {
                result.complete(WitResult.Err(ErrorCode.Overflow))
                return@flow
            }
            try {
                val chunkSize = 65536
                while (true) {
                    val bytes = withContext(dispatcher) {
                        if (!ch.isOpen) throw ClosedChannelException()
                        val buf = ByteBuffer.allocate(chunkSize)
                        val n = ch.read(buf, pos)
                        if (n <= 0) null
                        else {
                            pos += n
                            buf.array().copyOf(n)
                        }
                    } ?: break
                    emit(bytes)
                }
                result.complete(WitResult.Ok(Unit))
            } catch (e: CancellationException) {
                result.complete(WitResult.Ok(Unit))
                throw e
            } catch (t: Throwable) {
                result.complete(WitResult.Err(mapException(t)))
            }
        }
        return Tuple2(stream, result)
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun writeViaStream(data: Flow<ByteArray>, offset: Filesize): Deferred<WitResult<Unit, ErrorCode>> {
        if (isDirectory) {
            (data as? AutoCloseable)?.close()
            return CompletableDeferred(WitResult.Err(ErrorCode.IsDirectory))
        }
        val ch = channel
        if (ch == null || !flags.write) {
            (data as? AutoCloseable)?.close()
            return CompletableDeferred(WitResult.Err(ErrorCode.BadDescriptor))
        }
        if (readOnlyMount) {
            (data as? AutoCloseable)?.close()
            return CompletableDeferred(WitResult.Err(ErrorCode.ReadOnly))
        }
        val startPos = offset.toLong()
        if (startPos < 0L) {
            (data as? AutoCloseable)?.close()
            return CompletableDeferred(WitResult.Err(ErrorCode.Overflow))
        }
        return GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
            try {
                var pos = startPos
                data.collect { chunk ->
                    if (chunk.isNotEmpty()) {
                        withContext(dispatcher) {
                            if (!ch.isOpen) throw ClosedChannelException()
                            val buf = ByteBuffer.wrap(chunk)
                            while (buf.hasRemaining()) {
                                pos += ch.write(buf, pos)
                            }
                        }
                    }
                }
                WitResult.Ok(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                WitResult.Err(mapException(t))
            } finally {
                (data as? AutoCloseable)?.close()
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun appendViaStream(data: Flow<ByteArray>): Deferred<WitResult<Unit, ErrorCode>> {
        if (isDirectory) {
            (data as? AutoCloseable)?.close()
            return CompletableDeferred(WitResult.Err(ErrorCode.IsDirectory))
        }
        val ch = channel
        if (ch == null || !flags.write) {
            (data as? AutoCloseable)?.close()
            return CompletableDeferred(WitResult.Err(ErrorCode.BadDescriptor))
        }
        if (readOnlyMount) {
            (data as? AutoCloseable)?.close()
            return CompletableDeferred(WitResult.Err(ErrorCode.ReadOnly))
        }
        return GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
            try {
                data.collect { chunk ->
                    if (chunk.isNotEmpty()) {
                        withContext(dispatcher) {
                            synchronized(ch) {
                                if (!ch.isOpen) throw ClosedChannelException()
                                var pos = ch.size()
                                val buf = ByteBuffer.wrap(chunk)
                                while (buf.hasRemaining()) {
                                    pos += ch.write(buf, pos)
                                }
                            }
                        }
                    }
                }
                WitResult.Ok(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                WitResult.Err(mapException(t))
            } finally {
                (data as? AutoCloseable)?.close()
            }
        }
    }

    override suspend fun advise(
        offset: Filesize,
        length: Filesize,
        advice: Advice,
    ): WitResult<Unit, ErrorCode> {
        if (isDirectory) return WitResult.Err(ErrorCode.BadDescriptor)
        val ch = channel ?: return WitResult.Err(ErrorCode.BadDescriptor)
        return if (ch.isOpen) WitResult.Ok(Unit) else WitResult.Err(ErrorCode.BadDescriptor)
    }

    override suspend fun syncData(): WitResult<Unit, ErrorCode> = ioResult {
        val ch = channel
        if (ch != null && ch.isOpen && flags.write && !readOnlyMount) {
            ch.force(false)
        }
    }

    override suspend fun getFlags(): WitResult<DescriptorFlags, ErrorCode> = WitResult.Ok(flags)

    override suspend fun getType(): WitResult<DescriptorType, ErrorCode> = ioResult {
        descriptorTypeOf(path, followSymlinks = true)
    }

    override suspend fun setSize(size: Filesize): WitResult<Unit, ErrorCode> {
        if (isDirectory) return WitResult.Err(ErrorCode.IsDirectory)
        if (readOnlyMount) return WitResult.Err(ErrorCode.ReadOnly)
        val ch = channel ?: return WitResult.Err(ErrorCode.BadDescriptor)
        if (!flags.write) return WitResult.Err(ErrorCode.BadDescriptor)
        val newSize = size.toLong()
        if (newSize < 0L) return WitResult.Err(ErrorCode.Invalid)
        return ioResult {
            synchronized(ch) {
                if (!ch.isOpen) throw ClosedChannelException()
                val current = ch.size()
                if (newSize < current) {
                    ch.truncate(newSize)
                } else if (newSize > current) {
                    ch.write(ByteBuffer.allocate(1), newSize - 1)
                }
            }
        }
    }

    override suspend fun setTimes(
        dataAccessTimestamp: NewTimestamp,
        dataModificationTimestamp: NewTimestamp,
    ): WitResult<Unit, ErrorCode> {
        if (readOnlyMount) return WitResult.Err(ErrorCode.ReadOnly)
        if (!isDirectory && !flags.write) return WitResult.Err(ErrorCode.BadDescriptor)
        if (isDirectory && !flags.mutateDirectory) return WitResult.Err(ErrorCode.ReadOnly)
        return ioResult {
            applyTimes(path, dataAccessTimestamp, dataModificationTimestamp, followSymlinks = true)
        }
    }

    override fun readDirectory(): Tuple2<Flow<DirectoryEntry>, Deferred<WitResult<Unit, ErrorCode>>> {
        val result = CompletableDeferred<WitResult<Unit, ErrorCode>>()
        val stream = flow {
            if (!isDirectory) {
                result.complete(WitResult.Err(ErrorCode.NotDirectory))
                return@flow
            }
            if (!flags.read) {
                result.complete(WitResult.Err(ErrorCode.BadDescriptor))
                return@flow
            }
            var ds: DirectoryStream<Path>? = null
            try {
                val opened = withContext(dispatcher) { Files.newDirectoryStream(path) }
                ds = opened
                val iter = opened.iterator()
                while (true) {
                    val batch = withContext(dispatcher) {
                        val entries = ArrayList<DirectoryEntry>(64)
                        while (entries.size < 64 && iter.hasNext()) {
                            val entry = iter.next()
                            val name = entry.fileName?.toString() ?: continue
                            entries.add(DirectoryEntry(descriptorTypeOf(entry, followSymlinks = false), name))
                        }
                        entries
                    }
                    if (batch.isEmpty()) break
                    for (entry in batch) emit(entry)
                }
                result.complete(WitResult.Ok(Unit))
            } catch (e: CancellationException) {
                result.complete(WitResult.Ok(Unit))
                throw e
            } catch (t: Throwable) {
                result.complete(WitResult.Err(mapException(t)))
            } finally {
                withContext(NonCancellable + dispatcher) {
                    runCatching { ds?.close() }
                }
            }
        }
        return Tuple2(stream, result)
    }

    override suspend fun sync(): WitResult<Unit, ErrorCode> = ioResult {
        val ch = channel
        if (ch != null && ch.isOpen && flags.write && !readOnlyMount) {
            ch.force(true)
        }
    }

    override suspend fun createDirectoryAt(path: String): WitResult<Unit, ErrorCode> {
        if (!isDirectory) return WitResult.Err(ErrorCode.NotDirectory)
        if (readOnlyMount || !flags.mutateDirectory) return WitResult.Err(ErrorCode.ReadOnly)
        val resolved = resolveSubpath(path, followLeafSymlink = false)
        val target = when (resolved) {
            is WitResult.Ok -> resolved.value
            is WitResult.Err -> return resolved
        }
        return ioResult {
            Files.createDirectory(target)
            Unit
        }
    }

    override suspend fun stat(): WitResult<DescriptorStat, ErrorCode> = ioResult {
        readStat(path, followSymlinks = true)
    }

    override suspend fun statAt(pathFlags: PathFlags, path: String): WitResult<DescriptorStat, ErrorCode> {
        val follow = pathFlags.symlinkFollow
        val resolved = resolveSubpath(path, followLeafSymlink = follow)
        val target = when (resolved) {
            is WitResult.Ok -> resolved.value
            is WitResult.Err -> return resolved
        }
        return ioResult {
            readStat(target, followSymlinks = follow)
        }
    }

    override suspend fun setTimesAt(
        pathFlags: PathFlags,
        path: String,
        dataAccessTimestamp: NewTimestamp,
        dataModificationTimestamp: NewTimestamp,
    ): WitResult<Unit, ErrorCode> {
        if (!isDirectory) return WitResult.Err(ErrorCode.NotDirectory)
        if (readOnlyMount || !flags.mutateDirectory) return WitResult.Err(ErrorCode.ReadOnly)
        val follow = pathFlags.symlinkFollow
        val resolved = resolveSubpath(path, followLeafSymlink = follow)
        val target = when (resolved) {
            is WitResult.Ok -> resolved.value
            is WitResult.Err -> return resolved
        }
        return ioResult {
            applyTimes(target, dataAccessTimestamp, dataModificationTimestamp, followSymlinks = follow)
        }
    }

    override suspend fun linkAt(
        oldPathFlags: PathFlags,
        oldPath: String,
        newDescriptor: Descriptor,
        newPath: String,
    ): WitResult<Unit, ErrorCode> {
        if (!isDirectory) return WitResult.Err(ErrorCode.NotDirectory)
        val targetDir = newDescriptor as? WasiPathDescriptor
            ?: return WitResult.Err(ErrorCode.CrossDevice)
        if (!targetDir.isDirectory) return WitResult.Err(ErrorCode.NotDirectory)
        if (targetDir.readOnlyMount || !targetDir.flags.mutateDirectory) {
            return WitResult.Err(ErrorCode.ReadOnly)
        }
        if (rootPath != targetDir.rootPath) return WitResult.Err(ErrorCode.CrossDevice)
        val followOld = oldPathFlags.symlinkFollow
        val oldResolved = resolveSubpath(oldPath, followLeafSymlink = followOld)
        val oldTarget = when (oldResolved) {
            is WitResult.Ok -> oldResolved.value
            is WitResult.Err -> return oldResolved
        }
        val newResolved = targetDir.resolveSubpath(newPath, followLeafSymlink = false)
        val newTarget = when (newResolved) {
            is WitResult.Ok -> newResolved.value
            is WitResult.Err -> return newResolved
        }
        return ioResult {
            val noFollow = if (followOld) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)
            if (!Files.exists(oldTarget, *noFollow)) {
                throw NoSuchFileException(oldTarget.toString())
            }
            if (Files.isDirectory(oldTarget, *noFollow)) {
                return@ioResult
            }
            Files.createLink(newTarget, oldTarget)
            Unit
        }.let { result ->
            if (result is WitResult.Ok && Files.isDirectory(oldTarget, *(if (followOld) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)))) {
                WitResult.Err(ErrorCode.NotPermitted)
            } else {
                result
            }
        }
    }

    override suspend fun openAt(
        pathFlags: PathFlags,
        path: String,
        openFlags: OpenFlags,
        flags: DescriptorFlags,
    ): WitResult<Descriptor, ErrorCode> {
        if (!isDirectory) return WitResult.Err(ErrorCode.NotDirectory)
        if (flags.mutateDirectory && !this.flags.mutateDirectory) {
            return WitResult.Err(ErrorCode.ReadOnly)
        }
        if ((flags.write || flags.mutateDirectory || openFlags.truncate || openFlags.create) &&
            (readOnlyMount || !this.flags.mutateDirectory)
        ) {
            return WitResult.Err(ErrorCode.ReadOnly)
        }
        val follow = pathFlags.symlinkFollow
        val resolved = resolveSubpath(path, followLeafSymlink = follow)
        val target = when (resolved) {
            is WitResult.Ok -> resolved.value
            is WitResult.Err -> return resolved
        }
        val openedChannel = java.util.concurrent.atomic.AtomicReference<FileChannel?>(null)
        return try {
            withContext(dispatcher) {
                try {
                    if (!follow && Files.isSymbolicLink(target)) {
                        return@withContext WitResult.Err(ErrorCode.Loop)
                    }
                    val isExistingDir = Files.isDirectory(target)
                    if (isExistingDir) {
                        if (openFlags.create || openFlags.truncate || flags.write) {
                            return@withContext WitResult.Err(ErrorCode.IsDirectory)
                        }
                        return@withContext WitResult.Ok(
                            WasiPathDescriptor(
                                rootPath = rootPath,
                                path = target,
                                flags = flags,
                                readOnlyMount = readOnlyMount,
                                isDirectory = true,
                                channel = null,
                                dispatcher = dispatcher,
                            )
                        )
                    }
                    if (openFlags.directory) {
                        return@withContext if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            WitResult.Err(ErrorCode.NotDirectory)
                        } else {
                            WitResult.Err(ErrorCode.NoEntry)
                        }
                    }
                    if (path.endsWith("/") || path.endsWith("/.")) {
                        return@withContext when {
                            Files.exists(target, LinkOption.NOFOLLOW_LINKS) -> WitResult.Err(ErrorCode.NotDirectory)
                            openFlags.create -> WitResult.Err(ErrorCode.IsDirectory)
                            else -> WitResult.Err(ErrorCode.NoEntry)
                        }
                    }
                    val options = linkedSetOf<OpenOption>()
                    if (flags.read || (!flags.write && !openFlags.create && !openFlags.truncate)) {
                        options.add(StandardOpenOption.READ)
                    }
                    if (flags.write || openFlags.create || openFlags.truncate) {
                        options.add(StandardOpenOption.WRITE)
                    }
                    if (openFlags.create) {
                        if (openFlags.exclusive) {
                            options.add(StandardOpenOption.CREATE_NEW)
                        } else {
                            options.add(StandardOpenOption.CREATE)
                        }
                    } else if (openFlags.exclusive) {
                        return@withContext WitResult.Err(ErrorCode.Invalid)
                    }
                    if (openFlags.truncate && !openFlags.exclusive) {
                        options.add(StandardOpenOption.TRUNCATE_EXISTING)
                    }
                    if (flags.fileIntegritySync) {
                        options.add(StandardOpenOption.SYNC)
                    } else if (flags.dataIntegritySync) {
                        options.add(StandardOpenOption.DSYNC)
                    }
                    val channel = FileChannel.open(target, options)
                    openedChannel.set(channel)
                    ensureActive()
                    WitResult.Ok(
                        WasiPathDescriptor(
                            rootPath = rootPath,
                            path = target,
                            flags = flags,
                            readOnlyMount = readOnlyMount,
                            isDirectory = false,
                            channel = channel,
                            dispatcher = dispatcher,
                        )
                    )
                } catch (e: CancellationException) {
                    runCatching { openedChannel.getAndSet(null)?.close() }
                    throw e
                } catch (t: Throwable) {
                    runCatching { openedChannel.getAndSet(null)?.close() }
                    WitResult.Err(mapException(t))
                }
            }
        } catch (e: CancellationException) {
            runCatching { openedChannel.getAndSet(null)?.close() }
            throw e
        }
    }

    override suspend fun readlinkAt(path: String): WitResult<String, ErrorCode> {
        if (!isDirectory) return WitResult.Err(ErrorCode.NotDirectory)
        val resolved = resolveSubpath(path, followLeafSymlink = false)
        val target = when (resolved) {
            is WitResult.Ok -> resolved.value
            is WitResult.Err -> return resolved
        }
        return withContext(dispatcher) {
            try {
                val linkTarget = Files.readSymbolicLink(target)
                val targetStr = linkTarget.toString()
                val resolvedDest = (target.parent ?: rootPath).resolve(linkTarget).normalize()
                if (linkTarget.isAbsolute || targetStr.startsWith("/") || targetStr.contains("\\") || !resolvedDest.startsWith(rootPath)) {
                    WitResult.Err(ErrorCode.NotPermitted)
                } else {
                    WitResult.Ok(targetStr)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                WitResult.Err(mapException(t))
            }
        }
    }

    override suspend fun removeDirectoryAt(path: String): WitResult<Unit, ErrorCode> {
        if (!isDirectory) return WitResult.Err(ErrorCode.NotDirectory)
        if (readOnlyMount || !flags.mutateDirectory) return WitResult.Err(ErrorCode.ReadOnly)
        val resolved = resolveSubpath(path, followLeafSymlink = false)
        val target = when (resolved) {
            is WitResult.Ok -> resolved.value
            is WitResult.Err -> return resolved
        }
        if (target == this.path || target == this.rootPath) {
            return WitResult.Err(ErrorCode.NotPermitted)
        }
        return withContext(dispatcher) {
            try {
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    return@withContext WitResult.Err(ErrorCode.NoEntry)
                }
                if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    return@withContext WitResult.Err(ErrorCode.NotDirectory)
                }
                Files.delete(target)
                WitResult.Ok(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                WitResult.Err(mapException(t))
            }
        }
    }

    override suspend fun renameAt(
        oldPath: String,
        newDescriptor: Descriptor,
        newPath: String,
    ): WitResult<Unit, ErrorCode> {
        if (!isDirectory) return WitResult.Err(ErrorCode.NotDirectory)
        if (readOnlyMount || !flags.mutateDirectory) return WitResult.Err(ErrorCode.ReadOnly)
        val targetDir = newDescriptor as? WasiPathDescriptor
            ?: return WitResult.Err(ErrorCode.CrossDevice)
        if (!targetDir.isDirectory) return WitResult.Err(ErrorCode.NotDirectory)
        if (targetDir.readOnlyMount || !targetDir.flags.mutateDirectory) {
            return WitResult.Err(ErrorCode.ReadOnly)
        }
        if (rootPath != targetDir.rootPath) return WitResult.Err(ErrorCode.CrossDevice)
        val oldResolved = resolveSubpath(oldPath, followLeafSymlink = false)
        val oldTarget = when (oldResolved) {
            is WitResult.Ok -> oldResolved.value
            is WitResult.Err -> return oldResolved
        }
        val newResolved = targetDir.resolveSubpath(newPath, followLeafSymlink = false)
        val newTarget = when (newResolved) {
            is WitResult.Ok -> newResolved.value
            is WitResult.Err -> return newResolved
        }
        if (oldTarget == this.path || oldTarget == this.rootPath || newTarget == targetDir.path || newTarget == targetDir.rootPath) {
            return WitResult.Err(ErrorCode.NotPermitted)
        }
        return withContext(dispatcher) {
            try {
                if (Files.exists(oldTarget, LinkOption.NOFOLLOW_LINKS) && !symlinkStaysWithinRoot(oldTarget, newTarget, targetDir.rootPath)) {
                    return@withContext WitResult.Err(ErrorCode.NotPermitted)
                }
                Files.move(oldTarget, newTarget, StandardCopyOption.REPLACE_EXISTING)
                WitResult.Ok(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                WitResult.Err(mapException(t))
            }
        }
    }

    override suspend fun symlinkAt(oldPath: String, newPath: String): WitResult<Unit, ErrorCode> {
        if (!isDirectory) return WitResult.Err(ErrorCode.NotDirectory)
        if (readOnlyMount || !flags.mutateDirectory) return WitResult.Err(ErrorCode.ReadOnly)
        if (oldPath.isEmpty()) return WitResult.Err(ErrorCode.Invalid)
        if (oldPath.indexOf('\u0000') >= 0) return WitResult.Err(ErrorCode.Invalid)
        if (oldPath.startsWith("/") || oldPath.contains("\\")) return WitResult.Err(ErrorCode.NotPermitted)
        val resolved = resolveSubpath(newPath, followLeafSymlink = false)
        val newTarget = when (resolved) {
            is WitResult.Ok -> resolved.value
            is WitResult.Err -> return resolved
        }
        val parent = newTarget.parent ?: rootPath
        val resolvedLinkDestination = parent.resolve(oldPath).normalize()
        if (!resolvedLinkDestination.startsWith(rootPath)) {
            return WitResult.Err(ErrorCode.NotPermitted)
        }
        return ioResult {
            Files.createSymbolicLink(newTarget, Paths.get(oldPath))
            Unit
        }
    }

    override suspend fun unlinkFileAt(path: String): WitResult<Unit, ErrorCode> {
        if (!isDirectory) return WitResult.Err(ErrorCode.NotDirectory)
        if (readOnlyMount || !flags.mutateDirectory) return WitResult.Err(ErrorCode.ReadOnly)
        val resolved = resolveSubpath(path, followLeafSymlink = false)
        val target = when (resolved) {
            is WitResult.Ok -> resolved.value
            is WitResult.Err -> return resolved
        }
        if (target == this.path || target == this.rootPath) {
            return WitResult.Err(ErrorCode.NotPermitted)
        }
        return withContext(dispatcher) {
            try {
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    return@withContext WitResult.Err(ErrorCode.NoEntry)
                }
                if (path.endsWith("/") || path.endsWith("/.")) {
                    return@withContext if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                        WitResult.Err(ErrorCode.IsDirectory)
                    } else {
                        WitResult.Err(ErrorCode.NotDirectory)
                    }
                }
                if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    return@withContext WitResult.Err(ErrorCode.IsDirectory)
                }
                Files.delete(target)
                WitResult.Ok(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                WitResult.Err(mapException(t))
            }
        }
    }

    override suspend fun isSameObject(other: Descriptor): Boolean {
        val otherPath = other as? WasiPathDescriptor ?: return false
        return withContext(dispatcher) {
            runCatching { Files.isSameFile(path, otherPath.path) }.getOrDefault(false)
        }
    }

    override suspend fun metadataHash(): WitResult<MetadataHashValue, ErrorCode> = ioResult {
        computeMetadataHash(path, followSymlinks = true)
    }

    override suspend fun metadataHashAt(
        pathFlags: PathFlags,
        path: String,
    ): WitResult<MetadataHashValue, ErrorCode> {
        val follow = pathFlags.symlinkFollow
        val resolved = resolveSubpath(path, followLeafSymlink = follow)
        val target = when (resolved) {
            is WitResult.Ok -> resolved.value
            is WitResult.Err -> return resolved
        }
        return ioResult {
            computeMetadataHash(target, followSymlinks = follow)
        }
    }

    override fun close() {
        runCatching { channel?.close() }
    }

    private suspend fun <T> ioResult(block: () -> T): WitResult<T, ErrorCode> = withContext(dispatcher) {
        try {
            WitResult.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            WitResult.Err(mapException(t))
        }
    }

    private suspend fun resolveSubpath(subpath: String, followLeafSymlink: Boolean): WitResult<Path, ErrorCode> {
        if (!isDirectory) return WitResult.Err(ErrorCode.NotDirectory)
        if (subpath.isEmpty()) return WitResult.Err(ErrorCode.NoEntry)
        if (subpath.indexOf('\u0000') >= 0) return WitResult.Err(ErrorCode.Invalid)
        if (subpath.indexOf('\\') >= 0 || subpath.startsWith("/")) {
            return WitResult.Err(ErrorCode.NotPermitted)
        }
        return withContext(dispatcher) {
            try {
                val baseRel = rootPath.relativize(this@WasiPathDescriptor.path)
                val stack = mutableListOf<String>()
                if (this@WasiPathDescriptor.path != rootPath) {
                    for (part in baseRel) {
                        val s = part.toString()
                        if (s.isNotEmpty() && s != ".") stack.add(s)
                    }
                }
                val queue = ArrayDeque<String>()
                for (seg in subpath.split('/')) {
                    queue.addLast(seg)
                }
                var symlinksFollowed = 0
                while (queue.isNotEmpty()) {
                    val seg = queue.removeFirst()
                    if (seg.isEmpty() || seg == ".") continue
                    if (seg == "..") {
                        if (stack.isEmpty()) return@withContext WitResult.Err(ErrorCode.NotPermitted)
                        stack.removeAt(stack.lastIndex)
                        continue
                    }
                    val hasMoreSegments = queue.any { it.isNotEmpty() && it != "." }
                    val trailingSlash = !hasMoreSegments && queue.isNotEmpty()
                    val candidate = stack.fold(rootPath) { acc, s -> acc.resolve(s) }.resolve(seg)
                    val shouldFollow = hasMoreSegments || trailingSlash || followLeafSymlink
                    if (shouldFollow && Files.isSymbolicLink(candidate)) {
                        symlinksFollowed++
                        if (symlinksFollowed > MAX_SYMLINK_DEPTH) {
                            return@withContext WitResult.Err(ErrorCode.Loop)
                        }
                        val linkTarget = Files.readSymbolicLink(candidate)
                        val linkStr = linkTarget.toString()
                        if (linkTarget.isAbsolute || linkStr.startsWith("/") || linkStr.contains("\\")) {
                            return@withContext WitResult.Err(ErrorCode.NotPermitted)
                        }
                        val linkParts = linkStr.split('/')
                        for (i in linkParts.lastIndex downTo 0) {
                            queue.addFirst(linkParts[i])
                        }
                        continue
                    }
                    if ((hasMoreSegments || trailingSlash) &&
                        Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        return@withContext WitResult.Err(ErrorCode.NotDirectory)
                    }
                    stack.add(seg)
                }
                val resolved = stack.fold(rootPath) { acc, s -> acc.resolve(s) }.normalize()
                if (!resolved.startsWith(rootPath)) {
                    return@withContext WitResult.Err(ErrorCode.NotPermitted)
                }
                if (Files.exists(rootPath)) {
                    val realRoot = rootPath.toRealPath()
                    var check: Path? = resolved
                    while (check != null && !Files.exists(check, LinkOption.NOFOLLOW_LINKS)) {
                        check = check.parent
                    }
                    if (check != null && Files.exists(check, LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.isSymbolicLink(check) && !followLeafSymlink && check == resolved) {
                            val parentReal = check.parent?.toRealPath()
                            if (parentReal != null && !parentReal.startsWith(realRoot)) {
                                return@withContext WitResult.Err(ErrorCode.NotPermitted)
                            }
                        } else if (Files.exists(check)) {
                            val realExisting = check.toRealPath()
                            if (!realExisting.startsWith(realRoot)) {
                                return@withContext WitResult.Err(ErrorCode.NotPermitted)
                            }
                        }
                    }
                }
                WitResult.Ok(resolved)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                WitResult.Err(mapException(t))
            }
        }
    }

    private fun readStat(target: Path, followSymlinks: Boolean): DescriptorStat {
        val options = linkOptions(followSymlinks)
        val attrs = Files.readAttributes(target, BasicFileAttributes::class.java, *options)
        val type = descriptorTypeFromAttributes(attrs)
        val linkCount = (runCatching { Files.getAttribute(target, "unix:nlink", *options) as? Int }.getOrNull() ?: 1)
            .toULong()
        val size = if (attrs.isSymbolicLink) {
            runCatching { Files.readSymbolicLink(target).toString().toByteArray(Charsets.UTF_8).size.toULong() }
                .getOrDefault(0uL)
        } else {
            attrs.size().toULong()
        }
        val atime = attrs.lastAccessTime().toWasiInstant()
        val mtime = attrs.lastModifiedTime().toWasiInstant()
        val ctime = runCatching {
            (Files.getAttribute(target, "unix:ctime", *options) as? FileTime)?.toWasiInstant()
        }.getOrNull() ?: mtime
        return DescriptorStat(
            type = type,
            linkCount = linkCount,
            size = size,
            dataAccessTimestamp = atime,
            dataModificationTimestamp = mtime,
            statusChangeTimestamp = ctime,
        )
    }

    private fun applyTimes(
        target: Path,
        dataAccessTimestamp: NewTimestamp,
        dataModificationTimestamp: NewTimestamp,
        followSymlinks: Boolean,
    ) {
        val options = linkOptions(followSymlinks)
        val view = Files.getFileAttributeView(target, BasicFileAttributeView::class.java, *options)
            ?: throw UnsupportedOperationException("BasicFileAttributeView unavailable")
        val now = java.time.Instant.now()
        val atime = dataAccessTimestamp.toFileTime(now)
        val mtime = dataModificationTimestamp.toFileTime(now)
        view.setTimes(mtime, atime, null)
    }

    private fun computeMetadataHash(target: Path, followSymlinks: Boolean): MetadataHashValue {
        val options = linkOptions(followSymlinks)
        val attrs = Files.readAttributes(target, BasicFileAttributes::class.java, *options)
        val identity = attrs.fileKey()?.toString() ?: target.toAbsolutePath().normalize().toString()
        val size = attrs.size()
        val mtimeNanos = attrs.lastModifiedTime().toInstant().let { it.epochSecond * 1_000_000_000L + it.nano }
        var h1 = FNV_OFFSET_BASIS
        var h2 = FNV_OFFSET_BASIS_2
        for (b in identity.toByteArray(Charsets.UTF_8)) {
            h1 = (h1 xor (b.toULong() and 0xFFuL)) * FNV_PRIME
            h2 = (h2 xor ((b.toULong() and 0xFFuL) shl 1)) * FNV_PRIME
        }
        h1 = (h1 xor size.toULong()) * FNV_PRIME
        h2 = (h2 xor mtimeNanos.toULong()) * FNV_PRIME
        return MetadataHashValue(lower = h1, upper = h2)
    }

    companion object {
        private const val MAX_SYMLINK_DEPTH = 40
        private const val FNV_OFFSET_BASIS = 14695981039346656037uL
        private val FNV_OFFSET_BASIS_2 = 1099511628211uL xor 0x9E3779B97F4A7C15uL
        private const val FNV_PRIME = 1099511628211uL

        /**
         * Creates a root preopen [WasiPathDescriptor] confined to [hostPath].
         */
        fun createRoot(
            hostPath: Path,
            readOnly: Boolean = false,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): WasiPathDescriptor {
            val normalized = hostPath.toAbsolutePath().normalize()
            val flags = if (readOnly) {
                DescriptorFlags.READ
            } else {
                DescriptorFlags.READ or DescriptorFlags.MUTATE_DIRECTORY
            }
            return WasiPathDescriptor(
                rootPath = normalized,
                path = normalized,
                flags = flags,
                readOnlyMount = readOnly,
                isDirectory = true,
                channel = null,
                dispatcher = dispatcher,
            )
        }

        private fun linkOptions(followSymlinks: Boolean): Array<LinkOption> =
            if (followSymlinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)

        private fun symlinkTargetWithinRoot(linkFile: Path, relocatedLinkFile: Path, sandboxRoot: Path): Boolean {
            val linkTarget = Files.readSymbolicLink(linkFile)
            val linkStr = linkTarget.toString()
            if (linkTarget.isAbsolute || linkStr.startsWith("/") || linkStr.contains("\\")) {
                return false
            }
            val resolvedDest = (relocatedLinkFile.parent ?: sandboxRoot).resolve(linkTarget).normalize()
            return resolvedDest.startsWith(sandboxRoot)
        }

        private fun symlinkStaysWithinRoot(sourceRoot: Path, destRoot: Path, sandboxRoot: Path): Boolean {
            if (Files.isSymbolicLink(sourceRoot)) {
                return symlinkTargetWithinRoot(sourceRoot, destRoot, sandboxRoot)
            }
            if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
                return true
            }
            return Files.walk(sourceRoot).use { stream ->
                val it = stream.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    if (Files.isSymbolicLink(entry)) {
                        val movedEntry = destRoot.resolve(sourceRoot.relativize(entry))
                        if (!symlinkTargetWithinRoot(entry, movedEntry, sandboxRoot)) {
                            return@use false
                        }
                    }
                }
                true
            }
        }

        private fun descriptorTypeOf(path: Path, followSymlinks: Boolean): DescriptorType {
            val options = linkOptions(followSymlinks)
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, *options)
            return descriptorTypeFromAttributes(attrs)
        }

        private fun descriptorTypeFromAttributes(attrs: BasicFileAttributes): DescriptorType = when {
            attrs.isSymbolicLink -> DescriptorType.SymbolicLink
            attrs.isDirectory -> DescriptorType.Directory
            attrs.isRegularFile -> DescriptorType.RegularFile
            else -> DescriptorType.Other(null)
        }

        private fun FileTime.toWasiInstant(): Instant {
            val instant = toInstant()
            return Instant(seconds = instant.epochSecond, nanoseconds = instant.nano.toUInt())
        }

        private fun NewTimestamp.toFileTime(now: java.time.Instant): FileTime? = when (this) {
            NewTimestamp.NoChange -> null
            NewTimestamp.Now -> FileTime.from(now)
            is NewTimestamp.Timestamp -> FileTime.from(
                java.time.Instant.ofEpochSecond(value.seconds, value.nanoseconds.toLong())
            )
        }

        private fun mapException(t: Throwable): ErrorCode = when (t) {
            is ClosedChannelException -> ErrorCode.BadDescriptor
            is NoSuchFileException -> ErrorCode.NoEntry
            is FileAlreadyExistsException -> ErrorCode.Exist
            is DirectoryNotEmptyException -> ErrorCode.NotEmpty
            is NotDirectoryException -> ErrorCode.NotDirectory
            is AccessDeniedException -> ErrorCode.Access
            is FileSystemLoopException -> ErrorCode.Loop
            is AtomicMoveNotSupportedException -> ErrorCode.CrossDevice
            is SecurityException -> ErrorCode.NotPermitted
            is IllegalArgumentException -> ErrorCode.Invalid
            is UnsupportedOperationException -> ErrorCode.Unsupported
            is IOException -> {
                val msg = t.message.orEmpty().lowercase()
                when {
                    "read-only" in msg || "readonly" in msg -> ErrorCode.ReadOnly
                    "not empty" in msg -> ErrorCode.NotEmpty
                    "is a directory" in msg -> ErrorCode.IsDirectory
                    "not a directory" in msg -> ErrorCode.NotDirectory
                    "no such file" in msg -> ErrorCode.NoEntry
                    "permission denied" in msg -> ErrorCode.Access
                    "too many levels of symbolic links" in msg -> ErrorCode.Loop
                    else -> ErrorCode.Io
                }
            }
            else -> ErrorCode.Other(t.message)
        }
    }
}
