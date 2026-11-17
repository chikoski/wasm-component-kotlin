package wasm2kt.wasi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.wasi.wasi.cli.types.ErrorCode as CliErrorCode
import wasm2kt.wasi.wasi.filesystem.types.Advice
import wasm2kt.wasi.wasi.filesystem.types.DescriptorFlags
import wasm2kt.wasi.wasi.filesystem.types.DescriptorType
import wasm2kt.wasi.wasi.filesystem.types.ErrorCode
import wasm2kt.wasi.wasi.filesystem.types.OpenFlags
import wasm2kt.wasi.wasi.filesystem.types.PathFlags
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stateful directory-tree property tests and cancellation/resource-leak checks for
 * [WasiPathDescriptor], [WasiStdin], [WasiStdout], and [WasiStderr] (#162, ADR-0020, ADR-0022).
 */
class WasiFilesystemModelTest {
    private sealed class RefNode {
        class Dir(val children: MutableMap<String, RefNode> = LinkedHashMap()) : RefNode()
        class File(var content: ByteArray = ByteArray(0)) : RefNode()
        class Symlink(val target: String) : RefNode()
    }

    private sealed class LookupResult {
        class Ok(val segments: List<String>, val node: RefNode?) : LookupResult()
        class Err(val code: ErrorCode) : LookupResult()
    }

    private class ReferenceFs(val root: RefNode.Dir = RefNode.Dir()) {
        fun resolveSubpath(subpath: String, followLeafSymlink: Boolean): LookupResult {
            if (subpath.isEmpty()) return LookupResult.Err(ErrorCode.NoEntry)
            if (subpath.indexOf('\u0000') >= 0) return LookupResult.Err(ErrorCode.Invalid)
            if (subpath.indexOf('\\') >= 0 || subpath.startsWith("/")) {
                return LookupResult.Err(ErrorCode.NotPermitted)
            }
            val stack = mutableListOf<String>()
            val queue = ArrayDeque<String>()
            for (seg in subpath.split('/')) queue.addLast(seg)
            var symlinksFollowed = 0

            while (queue.isNotEmpty()) {
                val seg = queue.removeFirst()
                if (seg.isEmpty() || seg == ".") continue
                if (seg == "..") {
                    if (stack.isEmpty()) return LookupResult.Err(ErrorCode.NotPermitted)
                    stack.removeAt(stack.lastIndex)
                    continue
                }
                val hasMore = queue.any { it.isNotEmpty() && it != "." }
                val trailingSlash = !hasMore && queue.isNotEmpty()
                val currentNode = nodeAt(stack)
                if (currentNode != null && currentNode !is RefNode.Dir) {
                    return LookupResult.Err(ErrorCode.NotDirectory)
                }
                val candidate = (currentNode as? RefNode.Dir)?.children?.get(seg)
                val shouldFollow = hasMore || trailingSlash || followLeafSymlink
                if (shouldFollow && candidate is RefNode.Symlink) {
                    symlinksFollowed++
                    if (symlinksFollowed > 40) return LookupResult.Err(ErrorCode.Loop)
                    val linkStr = candidate.target
                    if (linkStr.startsWith("/") || linkStr.contains("\\")) {
                        return LookupResult.Err(ErrorCode.NotPermitted)
                    }
                    val parts = linkStr.split('/')
                    for (i in parts.lastIndex downTo 0) queue.addFirst(parts[i])
                    continue
                }
                if ((hasMore || trailingSlash) && candidate != null && candidate !is RefNode.Dir) {
                    return LookupResult.Err(ErrorCode.NotDirectory)
                }
                stack.add(seg)
            }
            return LookupResult.Ok(stack.toList(), nodeAt(stack))
        }

        fun nodeAt(segments: List<String>): RefNode? {
            var cur: RefNode = root
            for (s in segments) {
                val dir = cur as? RefNode.Dir ?: return null
                cur = dir.children[s] ?: return null
            }
            return cur
        }

        private fun parentDir(segments: List<String>): Pair<RefNode.Dir, String>? {
            if (segments.isEmpty()) return null
            val parent = nodeAt(segments.dropLast(1)) as? RefNode.Dir ?: return null
            return parent to segments.last()
        }

        fun createDirectoryAt(path: String): WitResult<Unit, ErrorCode> {
            return when (val r = resolveSubpath(path, followLeafSymlink = false)) {
                is LookupResult.Err -> WitResult.Err(r.code)
                is LookupResult.Ok -> {
                    if (r.segments.isEmpty()) return WitResult.Err(ErrorCode.Exist)
                    val (parent, name) = parentDir(r.segments) ?: return WitResult.Err(ErrorCode.NoEntry)
                    if (r.node != null) return WitResult.Err(ErrorCode.Exist)
                    parent.children[name] = RefNode.Dir()
                    WitResult.Ok(Unit)
                }
            }
        }

        fun writeFileAt(path: String, bytes: ByteArray): WitResult<Unit, ErrorCode> {
            if (path.endsWith("/") || path.endsWith("/.")) {
                return when (val r = resolveSubpath(path, followLeafSymlink = false)) {
                    is LookupResult.Err -> WitResult.Err(r.code)
                    is LookupResult.Ok -> when (r.node) {
                        is RefNode.Dir -> WitResult.Err(ErrorCode.IsDirectory)
                        null -> WitResult.Err(ErrorCode.IsDirectory)
                        else -> WitResult.Err(ErrorCode.NotDirectory)
                    }
                }
            }
            return when (val r = resolveSubpath(path, followLeafSymlink = false)) {
                is LookupResult.Err -> WitResult.Err(r.code)
                is LookupResult.Ok -> {
                    if (r.node is RefNode.Symlink) return WitResult.Err(ErrorCode.Loop)
                    if (r.node is RefNode.Dir) return WitResult.Err(ErrorCode.IsDirectory)
                    if (r.segments.isEmpty()) return WitResult.Err(ErrorCode.IsDirectory)
                    val (parent, name) = parentDir(r.segments) ?: return WitResult.Err(ErrorCode.NoEntry)
                    val file = (r.node as? RefNode.File) ?: RefNode.File().also { parent.children[name] = it }
                    file.content = bytes.copyOf()
                    WitResult.Ok(Unit)
                }
            }
        }

        fun openForRead(pathFlags: PathFlags, path: String): WitResult<RefNode, ErrorCode> {
            val follow = pathFlags.symlinkFollow
            return when (val r = resolveSubpath(path, followLeafSymlink = follow)) {
                is LookupResult.Err -> WitResult.Err(r.code)
                is LookupResult.Ok -> {
                    if (!follow && r.node is RefNode.Symlink) return WitResult.Err(ErrorCode.Loop)
                    when (val n = r.node) {
                        null -> WitResult.Err(ErrorCode.NoEntry)
                        is RefNode.Dir -> WitResult.Ok(n)
                        is RefNode.Symlink -> WitResult.Err(ErrorCode.Loop)
                        is RefNode.File -> {
                            if (path.endsWith("/") || path.endsWith("/.")) {
                                WitResult.Err(ErrorCode.NotDirectory)
                            } else {
                                WitResult.Ok(n)
                            }
                        }
                    }
                }
            }
        }

        fun symlinkAt(target: String, linkPath: String): WitResult<Unit, ErrorCode> {
            if (target.isEmpty() || target.indexOf('\u0000') >= 0) return WitResult.Err(ErrorCode.Invalid)
            if (target.startsWith("/") || target.contains("\\")) return WitResult.Err(ErrorCode.NotPermitted)
            return when (val r = resolveSubpath(linkPath, followLeafSymlink = false)) {
                is LookupResult.Err -> WitResult.Err(r.code)
                is LookupResult.Ok -> {
                    if (r.segments.isEmpty()) return WitResult.Err(ErrorCode.NotPermitted)
                    val parentSegs = r.segments.dropLast(1)
                    if (!linkTargetStaysInRoot(parentSegs, target)) return WitResult.Err(ErrorCode.NotPermitted)
                    val (parent, name) = parentDir(r.segments) ?: return WitResult.Err(ErrorCode.NoEntry)
                    if (r.node != null) return WitResult.Err(ErrorCode.Exist)
                    parent.children[name] = RefNode.Symlink(target)
                    WitResult.Ok(Unit)
                }
            }
        }

        fun readlinkAt(path: String): WitResult<String, ErrorCode> {
            return when (val r = resolveSubpath(path, followLeafSymlink = false)) {
                is LookupResult.Err -> WitResult.Err(r.code)
                is LookupResult.Ok -> {
                    val link = r.node as? RefNode.Symlink ?: return WitResult.Err(
                        if (r.node == null) ErrorCode.NoEntry else ErrorCode.Invalid,
                    )
                    if (!linkTargetStaysInRoot(r.segments.dropLast(1), link.target)) {
                        WitResult.Err(ErrorCode.NotPermitted)
                    } else {
                        WitResult.Ok(link.target)
                    }
                }
            }
        }

        fun renameAt(oldPath: String, newPath: String): WitResult<Unit, ErrorCode> {
            val oldR = resolveSubpath(oldPath, followLeafSymlink = false) as? LookupResult.Ok
                ?: return WitResult.Err((resolveSubpath(oldPath, followLeafSymlink = false) as LookupResult.Err).code)
            val newR = resolveSubpath(newPath, followLeafSymlink = false) as? LookupResult.Ok
                ?: return WitResult.Err((resolveSubpath(newPath, followLeafSymlink = false) as LookupResult.Err).code)
            if (oldR.segments.isEmpty() || newR.segments.isEmpty()) return WitResult.Err(ErrorCode.NotPermitted)
            val oldParent = parentDir(oldR.segments) ?: return WitResult.Err(ErrorCode.NoEntry)
            val newParent = parentDir(newR.segments) ?: return WitResult.Err(ErrorCode.NoEntry)
            val node = oldR.node ?: return WitResult.Err(ErrorCode.NoEntry)
            if (!subtreeLinksStayInRoot(node, newR.segments)) {
                return WitResult.Err(ErrorCode.NotPermitted)
            }
            if (oldR.segments == newR.segments) return WitResult.Ok(Unit)
            val destExisting = newR.node
            if (destExisting != null) {
                if (node is RefNode.Dir && destExisting !is RefNode.Dir) return WitResult.Err(ErrorCode.NotDirectory)
                if (node !is RefNode.Dir && destExisting is RefNode.Dir) return WitResult.Err(ErrorCode.IsDirectory)
                if (destExisting is RefNode.Dir && destExisting.children.isNotEmpty()) return WitResult.Err(ErrorCode.NotEmpty)
            }
            oldParent.first.children.remove(oldParent.second)
            newParent.first.children[newParent.second] = node
            return WitResult.Ok(Unit)
        }

        fun unlinkFileAt(path: String): WitResult<Unit, ErrorCode> {
            return when (val r = resolveSubpath(path, followLeafSymlink = false)) {
                is LookupResult.Err -> WitResult.Err(r.code)
                is LookupResult.Ok -> {
                    if (r.segments.isEmpty()) return WitResult.Err(ErrorCode.NotPermitted)
                    val node = r.node ?: return WitResult.Err(ErrorCode.NoEntry)
                    if (path.endsWith("/") || path.endsWith("/.")) {
                        return if (node is RefNode.Dir) WitResult.Err(ErrorCode.IsDirectory) else WitResult.Err(ErrorCode.NotDirectory)
                    }
                    if (node is RefNode.Dir) return WitResult.Err(ErrorCode.IsDirectory)
                    val (parent, name) = parentDir(r.segments)!!
                    parent.children.remove(name)
                    WitResult.Ok(Unit)
                }
            }
        }

        fun removeDirectoryAt(path: String): WitResult<Unit, ErrorCode> {
            return when (val r = resolveSubpath(path, followLeafSymlink = false)) {
                is LookupResult.Err -> WitResult.Err(r.code)
                is LookupResult.Ok -> {
                    if (r.segments.isEmpty()) return WitResult.Err(ErrorCode.NotPermitted)
                    val node = r.node ?: return WitResult.Err(ErrorCode.NoEntry)
                    if (node !is RefNode.Dir) return WitResult.Err(ErrorCode.NotDirectory)
                    if (node.children.isNotEmpty()) return WitResult.Err(ErrorCode.NotEmpty)
                    val (parent, name) = parentDir(r.segments)!!
                    parent.children.remove(name)
                    WitResult.Ok(Unit)
                }
            }
        }

        private fun linkTargetStaysInRoot(parentSegments: List<String>, target: String): Boolean {
            if (target.startsWith("/") || target.contains("\\")) return false
            val stack = parentSegments.toMutableList()
            for (seg in target.split('/')) {
                if (seg.isEmpty() || seg == ".") continue
                if (seg == "..") {
                    if (stack.isEmpty()) return false
                    stack.removeAt(stack.lastIndex)
                } else {
                    stack.add(seg)
                }
            }
            return true
        }

        private fun subtreeLinksStayInRoot(node: RefNode, destSegments: List<String>): Boolean = when (node) {
            is RefNode.File -> true
            is RefNode.Symlink -> linkTargetStaysInRoot(destSegments.dropLast(1), node.target)
            is RefNode.Dir -> node.children.all { (name, child) ->
                subtreeLinksStayInRoot(child, destSegments + name)
            }
        }
    }

    private fun assertTreeMatches(dir: Path, ref: RefNode.Dir, context: String) {
        val actualNames = Files.list(dir).use { s ->
            buildList {
                val it = s.iterator()
                while (it.hasNext()) add(it.next().fileName.toString())
            }
        }.sorted()
        assertEquals(ref.children.keys.sorted(), actualNames, "$context directory entries mismatch at $dir")
        for ((name, child) in ref.children) {
            val childPath = dir.resolve(name)
            when (child) {
                is RefNode.Dir -> {
                    assertTrue(Files.isDirectory(childPath, LinkOption.NOFOLLOW_LINKS), "$context expected dir $childPath")
                    assertTreeMatches(childPath, child, context)
                }
                is RefNode.File -> {
                    assertTrue(Files.isRegularFile(childPath, LinkOption.NOFOLLOW_LINKS), "$context expected file $childPath")
                    assertContentEquals(child.content, Files.readAllBytes(childPath), "$context content mismatch at $childPath")
                }
                is RefNode.Symlink -> {
                    assertTrue(Files.isSymbolicLink(childPath), "$context expected symlink $childPath")
                    assertEquals(child.target, Files.readSymbolicLink(childPath).toString(), "$context symlink target mismatch at $childPath")
                }
            }
        }
    }

    @Test
    fun `state machine property tests WasiPathDescriptor against reference tree and rejects sandbox escapes`(@TempDir tempDir: Path) = runBlocking {
        val probeTarget = tempDir.resolve("probe-target.txt")
        val probeDir = tempDir.resolve("probe-dir")
        val probeLink = probeDir.resolve("probe-link.txt")
        val symlinksSupported = try {
            Files.createDirectories(probeDir)
            Files.writeString(probeTarget, "probe")
            runCatching {
                Files.createSymbolicLink(probeLink, Path.of("../probe-target.txt"))
                Files.readSymbolicLink(probeLink).toString() == "../probe-target.txt"
            }.getOrDefault(false)
        } finally {
            Files.deleteIfExists(probeLink)
            Files.deleteIfExists(probeDir)
            Files.deleteIfExists(probeTarget)
        }
        Assumptions.assumeTrue(symlinksSupported, "POSIX-style relative symbolic links are required for this test")

        // Regression check: renaming a directory containing a relative symlink checks link depth against the destination dir, not its parent.
        val renameSandbox = Files.createDirectories(tempDir.resolve("sandbox-dir-symlink-rename"))
        WasiPathDescriptor.createRoot(renameSandbox, readOnly = false).use { renameRoot ->
            val renameModel = ReferenceFs()
            assertEquals(renameModel.createDirectoryAt("d1"), renameRoot.createDirectoryAt("d1"))
            renameModel.writeFileAt("a", "ok".encodeToByteArray()).getOrThrow()
            renameRoot.openAt(
                PathFlags.NONE,
                "a",
                OpenFlags.CREATE or OpenFlags.TRUNCATE,
                DescriptorFlags.READ or DescriptorFlags.WRITE,
            ).getOrThrow().use { f ->
                assertEquals(WitResult.Ok(Unit), f.writeViaStream(flow { emit("ok".encodeToByteArray()) }, 0uL).await())
            }
            assertEquals(renameModel.symlinkAt("../a", "d1/x"), renameRoot.symlinkAt("../a", "d1/x"))
            assertEquals(renameModel.renameAt("d1", "d2"), renameRoot.renameAt("d1", renameRoot, "d2"))
            assertTreeMatches(renameSandbox, renameModel.root, "dir-symlink-rename")
        }

        val outsideFile = tempDir.resolve("outside.txt")
        Files.writeString(outsideFile, "untouched-secret")
        val names = listOf("a", "b", "d1", "d2", "d1/x", "d1/y", "d2/z", "d1/../a", "./b")
        val adversarial = listOf(
            "../outside.txt",
            "d1/../../outside.txt",
            "/etc/passwd",
            "a\\b",
            "bad\u0000name",
            ".",
            "a/",
            "d1/",
        )

        for (seed in 1..25) {
            val rng = Random(seed * 6151 + 5)
            val sandbox = Files.createDirectories(tempDir.resolve("sandbox-$seed"))
            val root = WasiPathDescriptor.createRoot(sandbox, readOnly = false)
            val model = ReferenceFs()

            try {
                repeat(35) { step ->
                    val useAdversarial = rng.nextInt(5) == 0
                    val path = if (useAdversarial) adversarial[rng.nextInt(adversarial.size)] else names[rng.nextInt(names.size)]
                    val ctx = "seed=$seed step=$step path='$path'"

                    when (rng.nextInt(8)) {
                        0 -> {
                            val exp = model.createDirectoryAt(path)
                            val act = root.createDirectoryAt(path)
                            assertEquals(exp.errorOrNull(), act.errorOrNull(), "$ctx createDirectoryAt")
                        }
                        1 -> {
                            val data = "seed-$seed-step-$step".encodeToByteArray()
                            val exp = model.writeFileAt(path, data)
                            val opened = root.openAt(
                                PathFlags.NONE,
                                path,
                                OpenFlags.CREATE or OpenFlags.TRUNCATE,
                                DescriptorFlags.READ or DescriptorFlags.WRITE,
                            )
                            assertEquals(exp.errorOrNull(), opened.errorOrNull(), "$ctx openAt(create)")
                            if (opened is WitResult.Ok) {
                                opened.value.use { f ->
                                    assertEquals(
                                        WitResult.Ok(Unit),
                                        f.writeViaStream(flow { emit(data) }, 0uL).await(),
                                        "$ctx writeViaStream",
                                    )
                                }
                            }
                        }
                        2 -> {
                            val flags = if (rng.nextBoolean()) PathFlags.SYMLINK_FOLLOW else PathFlags.NONE
                            val exp = model.openForRead(flags, path)
                            val opened = root.openAt(flags, path, OpenFlags.NONE, DescriptorFlags.READ)
                            assertEquals(exp.errorOrNull(), opened.errorOrNull(), "$ctx openAt(read)")
                            if (opened is WitResult.Ok) {
                                opened.value.use { desc ->
                                    val (stream, done) = desc.readViaStream(0uL)
                                    val bytes = stream.toList().fold(ByteArray(0)) { acc, chunk -> acc + chunk }
                                    when (val refNode = exp.getOrThrow()) {
                                        is RefNode.Dir -> {
                                            assertEquals(ErrorCode.IsDirectory, done.await().errorOrNull(), "$ctx dir readViaStream")
                                        }
                                        is RefNode.File -> {
                                            assertEquals(WitResult.Ok(Unit), done.await())
                                            assertContentEquals(refNode.content, bytes, "$ctx readViaStream")
                                        }
                                        is RefNode.Symlink -> error("Unexpected symlink node")
                                    }
                                }
                            }
                        }
                        3 -> {
                            val targetCandidates = listOf("a", "../a", "../outside.txt", "/etc/passwd", "d1\\x", "d1/x")
                            val linkTarget = targetCandidates[rng.nextInt(targetCandidates.size)]
                            val linkPath = if (useAdversarial) path else names[rng.nextInt(names.size)]
                            val exp = model.symlinkAt(linkTarget, linkPath)
                            val act = root.symlinkAt(linkTarget, linkPath)
                            assertEquals(exp.errorOrNull(), act.errorOrNull(), "$ctx symlinkAt($linkTarget -> $linkPath)")
                        }
                        4 -> {
                            val exp = model.readlinkAt(path)
                            val act = root.readlinkAt(path)
                            if (exp is WitResult.Ok) {
                                assertEquals(exp, act, "$ctx readlinkAt")
                            } else {
                                assertTrue(act.isErr, "$ctx readlinkAt expected error, got $act")
                                val expectedErr = exp.errorOrNull()
                                if (expectedErr == ErrorCode.Invalid && path.indexOf('\u0000') < 0) {
                                    // Non-symlink regular files/dirs trigger NotLinkException, which mapException may map to Invalid or Io.
                                    assertTrue(
                                        act.errorOrNull() == ErrorCode.Invalid || act.errorOrNull() == ErrorCode.Io,
                                        "$ctx readlinkAt expected Invalid or Io, got ${act.errorOrNull()}",
                                    )
                                } else {
                                    assertEquals(expectedErr, act.errorOrNull(), "$ctx readlinkAt error")
                                }
                            }
                        }
                        5 -> {
                            val dst = if (rng.nextInt(6) == 0) adversarial[rng.nextInt(adversarial.size)] else names[rng.nextInt(names.size)]
                            val exp = model.renameAt(path, dst)
                            val act = root.renameAt(path, root, dst)
                            assertEquals(exp.errorOrNull(), act.errorOrNull(), "$ctx renameAt($path -> $dst)")
                        }
                        6 -> {
                            val exp = model.unlinkFileAt(path)
                            val act = root.unlinkFileAt(path)
                            assertEquals(exp.errorOrNull(), act.errorOrNull(), "$ctx unlinkFileAt")
                        }
                        7 -> {
                            val exp = model.removeDirectoryAt(path)
                            val act = root.removeDirectoryAt(path)
                            assertEquals(exp.errorOrNull(), act.errorOrNull(), "$ctx removeDirectoryAt")
                        }
                    }
                    assertEquals("untouched-secret", Files.readString(outsideFile))
                }
                assertTreeMatches(sandbox, model.root, "seed=$seed")
            } finally {
                root.close()
            }
        }
    }

    @Test
    fun `readOnly mount rejects all mutating operations and leaves directory state unchanged`(@TempDir tempDir: Path) = runBlocking {
        val roDir = Files.createDirectories(tempDir.resolve("ro"))
        Files.writeString(roDir.resolve("seed.txt"), "immutable")
        val sub = Files.createDirectories(roDir.resolve("sub"))
        Files.writeString(sub.resolve("inner.txt"), "inner")

        val root = WasiPathDescriptor.createRoot(roDir, readOnly = true)
        val roFile = root.openAt(PathFlags.NONE, "seed.txt", OpenFlags.NONE, DescriptorFlags.READ).getOrThrow()
        try {
            assertEquals(ErrorCode.ReadOnly, root.createDirectoryAt("newdir").errorOrNull())
            assertEquals(ErrorCode.ReadOnly, root.openAt(PathFlags.NONE, "new.txt", OpenFlags.CREATE, DescriptorFlags.WRITE).errorOrNull())
            assertEquals(ErrorCode.ReadOnly, root.openAt(PathFlags.NONE, "seed.txt", OpenFlags.NONE, DescriptorFlags.WRITE).errorOrNull())
            assertEquals(ErrorCode.ReadOnly, root.openAt(PathFlags.NONE, "seed.txt", OpenFlags.TRUNCATE, DescriptorFlags.READ).errorOrNull())
            assertEquals(ErrorCode.ReadOnly, root.symlinkAt("seed.txt", "link").errorOrNull())
            assertEquals(ErrorCode.ReadOnly, root.renameAt("seed.txt", root, "moved.txt").errorOrNull())
            assertEquals(ErrorCode.ReadOnly, root.unlinkFileAt("seed.txt").errorOrNull())
            assertEquals(ErrorCode.ReadOnly, root.removeDirectoryAt("sub").errorOrNull())
            assertEquals(ErrorCode.ReadOnly, roFile.setSize(0uL).errorOrNull())
            assertEquals(ErrorCode.BadDescriptor, roFile.writeViaStream(flow { emit(byteArrayOf(1)) }, 0uL).await().errorOrNull())
            assertEquals(ErrorCode.BadDescriptor, roFile.appendViaStream(flow { emit(byteArrayOf(1)) }).await().errorOrNull())

            // Reads still succeed without altering files.
            assertEquals(DescriptorType.RegularFile, root.statAt(PathFlags.NONE, "seed.txt").getOrThrow().type)
            assertEquals("immutable", Files.readString(roDir.resolve("seed.txt")))
            assertEquals("inner", Files.readString(sub.resolve("inner.txt")))
        } finally {
            roFile.close()
            root.close()
        }
    }

    @Test
    fun `async filesystem and CLI stream cancellation closes flows and releases channels`(@TempDir tempDir: Path) = runBlocking {
        val sandbox = Files.createDirectories(tempDir.resolve("io-cancel"))
        val bigFile = sandbox.resolve("big.bin")
        // Larger than 64 KiB read chunk so readViaStream yields at least 3 chunks.
        Files.write(bigFile, ByteArray(150_000) { (it and 0xFF).toByte() })

        val manyDir = Files.createDirectories(sandbox.resolve("many"))
        repeat(80) { i -> Files.writeString(manyDir.resolve("entry-$i.txt"), "$i") }

        val root = WasiPathDescriptor.createRoot(sandbox, readOnly = false)
        try {
            // 1. Mid-stream cancellation of readViaStream and readDirectory releases descriptors cleanly.
            val readDesc = root.openAt(PathFlags.NONE, "big.bin", OpenFlags.NONE, DescriptorFlags.READ).getOrThrow()
            readDesc.use { d ->
                assertEquals(WitResult.Ok(Unit), d.advise(0uL, 1uL, Advice.NORMAL))
                val (stream, done) = d.readViaStream(0uL)
                assertEquals(1, stream.take(1).toList().size)
                assertEquals(WitResult.Ok(Unit), done.await())
            }
            assertEquals(ErrorCode.BadDescriptor, readDesc.advise(0uL, 1uL, Advice.NORMAL).errorOrNull())
            assertEquals(WitResult.Ok(Unit), root.unlinkFileAt("big.bin"))
            assertFalse(Files.exists(bigFile))

            val dirDesc = root.openAt(PathFlags.NONE, "many", OpenFlags.DIRECTORY, DescriptorFlags.READ).getOrThrow()
            dirDesc.use { d ->
                val (dirStream, done) = d.readDirectory()
                assertEquals(5, dirStream.take(5).toList().size)
                assertEquals(WitResult.Ok(Unit), done.await())
            }
            // DirectoryStream was closed in finally, so entries and directory can be removed cleanly.
            repeat(80) { i -> assertEquals(WitResult.Ok(Unit), root.unlinkFileAt("many/entry-$i.txt")) }
            assertEquals(WitResult.Ok(Unit), root.removeDirectoryAt("many"))

            // 2. Cancelling writeViaStream and appendViaStream pre-start and mid-stream closes AutoCloseable Flow once.
            val writeDesc = root.openAt(
                PathFlags.NONE,
                "target.bin",
                OpenFlags.CREATE or OpenFlags.TRUNCATE,
                DescriptorFlags.READ or DescriptorFlags.WRITE,
            ).getOrThrow()
            try {
                val preStartWriteClosed = AtomicInteger()
                val preStartWriteFlow = object : Flow<ByteArray> by emptyFlow(), AutoCloseable {
                    override fun close() {
                        preStartWriteClosed.incrementAndGet()
                    }
                }
                val unstartedWrite = writeDesc.writeViaStream(preStartWriteFlow, 0uL)
                unstartedWrite.cancel()
                unstartedWrite.join()
                assertEquals(1, preStartWriteClosed.get())

                val preStartAppendClosed = AtomicInteger()
                val preStartAppendFlow = object : Flow<ByteArray> by emptyFlow(), AutoCloseable {
                    override fun close() {
                        preStartAppendClosed.incrementAndGet()
                    }
                }
                val unstartedAppend = writeDesc.appendViaStream(preStartAppendFlow)
                unstartedAppend.cancel()
                unstartedAppend.join()
                assertEquals(1, preStartAppendClosed.get())

                val midWriteFirstChunk = CompletableDeferred<Unit>()
                val midWriteGate = CompletableDeferred<Unit>()
                val midWriteClosed = AtomicInteger()
                val midWriteFlow = object : Flow<ByteArray> by flow({
                    emit(byteArrayOf(1, 2, 3))
                    midWriteFirstChunk.complete(Unit)
                    midWriteGate.await()
                    emit(byteArrayOf(4, 5, 6))
                }), AutoCloseable {
                    override fun close() {
                        midWriteClosed.incrementAndGet()
                    }
                }
                val activeWrite = writeDesc.writeViaStream(midWriteFlow, 0uL)
                val runner = launch { activeWrite.await() }
                withTimeout(5_000) { midWriteFirstChunk.await() }
                activeWrite.cancelAndJoin()
                runner.join()
                assertEquals(1, midWriteClosed.get())

                // Normal completion must close the flow exactly once (finally + invokeOnCompletion do not double-close).
                val normalWriteClosed = AtomicInteger()
                val normalWriteFlow = object : Flow<ByteArray> by flow({
                    emit(byteArrayOf(7, 8))
                }), AutoCloseable {
                    override fun close() {
                        normalWriteClosed.incrementAndGet()
                    }
                }
                assertEquals(WitResult.Ok(Unit), writeDesc.writeViaStream(normalWriteFlow, 0uL).await())
                assertEquals(1, normalWriteClosed.get())

                // Early validation failure must still close the AutoCloseable Flow once.
                val overflowWriteClosed = AtomicInteger()
                val overflowWriteFlow = object : Flow<ByteArray> by emptyFlow(), AutoCloseable {
                    override fun close() {
                        overflowWriteClosed.incrementAndGet()
                    }
                }
                assertEquals(
                    WitResult.Err(ErrorCode.Overflow),
                    writeDesc.writeViaStream(overflowWriteFlow, Long.MAX_VALUE.toULong() + 1uL).await(),
                )
                assertEquals(1, overflowWriteClosed.get())

                // Mid-stream cancellation of appendViaStream closes AutoCloseable Flow once.
                val midAppendFirstChunk = CompletableDeferred<Unit>()
                val midAppendGate = CompletableDeferred<Unit>()
                val midAppendClosed = AtomicInteger()
                val midAppendFlow = object : Flow<ByteArray> by flow({
                    emit(byteArrayOf(9))
                    midAppendFirstChunk.complete(Unit)
                    midAppendGate.await()
                    emit(byteArrayOf(10))
                }), AutoCloseable {
                    override fun close() {
                        midAppendClosed.incrementAndGet()
                    }
                }
                val activeAppend = writeDesc.appendViaStream(midAppendFlow)
                val appendRunner = launch { activeAppend.await() }
                withTimeout(5_000) { midAppendFirstChunk.await() }
                activeAppend.cancelAndJoin()
                appendRunner.join()
                assertEquals(1, midAppendClosed.get())
            } finally {
                writeDesc.close()
            }
            assertEquals(WitResult.Ok(Unit), root.unlinkFileAt("target.bin"))
        } finally {
            root.close()
        }

        // 3. WasiStdin, WasiStdout, and WasiStderr close flows and complete Deferreds across cancel/error paths.
        val stdinBlocked = CompletableDeferred<Unit>()
        val gatedStdin = WasiStdin {
            object : InputStream() {
                private var count = 0
                override fun read(): Int = -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    count++
                    if (count == 1) {
                        b[off] = 42
                        return 1
                    }
                    runBlocking { stdinBlocked.await() }
                    return -1
                }
            }
        }
        val (inStream, inDone) = gatedStdin.readViaStream()
        val firstChunk = CompletableDeferred<Unit>()
        val collector = launch {
            inStream.collect {
                firstChunk.complete(Unit)
                delay(5_000)
            }
        }
        try {
            withTimeout(5_000) {
                firstChunk.await()
                collector.cancelAndJoin()
                assertEquals(WitResult.Ok(Unit), inDone.await())
            }
        } finally {
            stdinBlocked.complete(Unit)
        }

        val stdoutPreClosed = AtomicInteger()
        val stdoutFlow = object : Flow<ByteArray> by emptyFlow(), AutoCloseable {
            override fun close() {
                stdoutPreClosed.incrementAndGet()
            }
        }
        val outJob = WasiStdout(ByteArrayOutputStream()).writeViaStream(stdoutFlow)
        outJob.cancel()
        outJob.join()
        assertEquals(1, stdoutPreClosed.get())

        val stderrMidGate = CompletableDeferred<Unit>()
        val stderrFirst = CompletableDeferred<Unit>()
        val stderrMidClosed = AtomicInteger()
        val stderrMidFlow = object : Flow<ByteArray> by flow({
            emit("part1".encodeToByteArray())
            stderrFirst.complete(Unit)
            stderrMidGate.await()
            emit("part2".encodeToByteArray())
        }), AutoCloseable {
            override fun close() {
                stderrMidClosed.incrementAndGet()
            }
        }
        val errDeferred = WasiStderr(ByteArrayOutputStream()).writeViaStream(stderrMidFlow)
        val errWaiter = launch { errDeferred.await() }
        withTimeout(5_000) { stderrFirst.await() }
        errDeferred.cancelAndJoin()
        errWaiter.join()
        assertEquals(1, stderrMidClosed.get())

        val failingFlowClosed = AtomicInteger()
        val failingFlow = object : Flow<ByteArray> by flow({
            emit("boom".encodeToByteArray())
        }), AutoCloseable {
            override fun close() {
                failingFlowClosed.incrementAndGet()
            }
        }
        val badOut = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
        }
        assertEquals(WitResult.Err(CliErrorCode.IO), WasiStdout(badOut).writeViaStream(failingFlow).await())
        assertEquals(1, failingFlowClosed.get())
    }
}
