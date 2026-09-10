package com.example.bilicache

import java.io.File
import java.io.RandomAccessFile

object M4sMerger {

    private data class Box(
        val type: String,
        val start: Int,
        val end: Int,
        val dataStart: Int
    )

    private fun rd32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or
        ((b[o + 1].toInt() and 0xff) shl 16) or
        ((b[o + 2].toInt() and 0xff) shl 8) or
        (b[o + 3].toInt() and 0xff)

    private fun wr32(b: ByteArray, o: Int, v: Int) {
        b[o]     = ((v ushr 24) and 0xff).toByte()
        b[o + 1] = ((v ushr 16) and 0xff).toByte()
        b[o + 2] = ((v ushr 8) and 0xff).toByte()
        b[o + 3] = (v and 0xff).toByte()
    }

    private fun fcc(b: ByteArray, o: Int): String =
        String(charArrayOf(
            b[o].toInt().toChar(), b[o + 1].toInt().toChar(),
            b[o + 2].toInt().toChar(), b[o + 3].toInt().toChar()
        ))

    private fun parseBoxes(b: ByteArray, start: Int, end: Int): List<Box> {
        val out = mutableListOf<Box>()
        var off = start
        while (off + 8 <= end) {
            var size = rd32(b, off).toLong() and 0xffffffffL
            val type = fcc(b, off + 4)
            var hs = 8
            if (size == 1L) {
                if (off + 16 > end) break
                size = ((rd32(b, off + 8).toLong() and 0xffffffffL) shl 32) or
                       (rd32(b, off + 12).toLong() and 0xffffffffL)
                hs = 16
            } else if (size == 0L) {
                size = (end - off).toLong()
            }
            if (size < hs || off + size > end) break
            val e = (off + size).toInt()
            out += Box(type, off, e, off + hs)
            off = e
        }
        return out
    }

    private fun kids(b: ByteArray, box: Box) = parseBoxes(b, box.dataStart, box.end)
    private fun kid(b: ByteArray, box: Box, t: String) = kids(b, box).find { it.type == t }
    private fun kidsOf(b: ByteArray, box: Box, t: String) = kids(b, box).filter { it.type == t }

    private fun descend(b: ByteArray, box: Box, path: List<String>): Box? {
        var cur: Box = box
        for (t in path) cur = kid(b, cur, t) ?: return null
        return cur
    }

    private fun handlerType(b: ByteArray, trak: Box): String? =
        descend(b, trak, listOf("mdia", "hdlr"))?.let { fcc(b, it.dataStart + 8) }

    private fun trackId(b: ByteArray, trak: Box): Int {
        val tkhd = kid(b, trak, "tkhd") ?: return -1
        val ver = b[tkhd.dataStart].toInt()
        val base = tkhd.dataStart + 4
        return rd32(b, if (ver == 1) base + 16 else base + 8)
    }

    private fun cloneTrakWithId(b: ByteArray, trak: Box, newId: Int): ByteArray {
        val copy = b.copyOfRange(trak.start, trak.end)
        val inner = parseBoxes(copy, 0, copy.size)
        if (inner.isEmpty()) return copy
        val tkhd = kid(copy, inner[0], "tkhd") ?: return copy
        val ver = copy[tkhd.dataStart].toInt()
        val base = tkhd.dataStart + 4
        val off = if (ver == 1) base + 16 else base + 8
        if (off + 4 <= copy.size) wr32(copy, off, newId)
        return copy
    }

    private fun detectCodec(b: ByteArray, trak: Box): String {
        val stbl = descend(b, trak, listOf("mdia", "minf", "stbl")) ?: return ""
        val stsd = kid(b, stbl, "stsd") ?: return ""
        val count = rd32(b, stsd.dataStart + 4)
        if (count < 1) return ""
        val p = stsd.dataStart + 8
        if (p + 8 > stsd.end) return ""
        val entrySize = rd32(b, p)
        if (entrySize < 8 || p + entrySize > stsd.end) return ""
        return fcc(b, p + 4)
    }

    private fun makeBox(type: String, payloads: List<ByteArray>): ByteArray {
        val n = payloads.sumOf { it.size }
        val out = ByteArray(8 + n)
        wr32(out, 0, 8 + n)
        for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
        var o = 8
        for (p in payloads) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    private fun makeMdat(data: ByteArray): ByteArray {
        val out = ByteArray(8 + data.size)
        wr32(out, 0, 8 + data.size)
        out[4] = 0x6d; out[5] = 0x64; out[6] = 0x61; out[7] = 0x74
        System.arraycopy(data, 0, out, 8, data.size)
        return out
    }

    private fun defaultFtyp(): ByteArray {
        val brands = listOf("isom", "iso2", "avc1", "mp41")
        val b = ByteArray(16 + brands.size * 4)
        wr32(b, 0, b.size)
        for (i in 0 until 4) b[4 + i] = "ftyp"[i].code.toByte()
        for (i in 0 until 4) b[8 + i] = "isom"[i].code.toByte()
        wr32(b, 12, 0x200)
        var o = 16
        for (br in brands) { for (i in 0 until 4) b[o + i] = br[i].code.toByte(); o += 4 }
        return b
    }

    private fun remapChunkOffsets(trakBytes: ByteArray, origMdats: List<Box>, newStarts: List<Long>) {
        val inner = parseBoxes(trakBytes, 0, trakBytes.size)
        if (inner.isEmpty()) return
        val stbl = descend(trakBytes, inner[0], listOf("mdia", "minf", "stbl")) ?: return

        fun mapOffset(old: Long): Long {
            for (k in origMdats.indices) {
                val m = origMdats[k]
                if (old >= m.dataStart && old < m.end) {
                    return newStarts[k] + (old - m.dataStart)
                }
            }
            return old
        }

        kid(trakBytes, stbl, "stco")?.let { stco ->
            val n = rd32(trakBytes, stco.dataStart + 4)
            var p = stco.dataStart + 8
            for (i in 0 until n) {
                if (p + 4 > trakBytes.size) break
                val old = rd32(trakBytes, p).toLong() and 0xffffffffL
                wr32(trakBytes, p, mapOffset(old).toInt())
                p += 4
            }
        }
        kid(trakBytes, stbl, "co64")?.let { co64 ->
            val n = rd32(trakBytes, co64.dataStart + 4)
            var p = co64.dataStart + 8
            for (i in 0 until n) {
                if (p + 8 > trakBytes.size) break
                val hi = rd32(trakBytes, p).toLong() and 0xffffffffL
                val lo = rd32(trakBytes, p + 4).toLong() and 0xffffffffL
                val old = (hi shl 32) or lo
                val nv = mapOffset(old)
                wr32(trakBytes, p, (nv ushr 32).toInt())
                wr32(trakBytes, p + 4, nv.toInt())
                p += 8
            }
        }
    }

    /** 合并，返回视频编码 tag。 */
    fun merge(videoFile: File, audioFile: File, outFile: File): String {
        val vBuf = videoFile.readBytes()
        val aBuf = audioFile.readBytes()

        val vBoxes = parseBoxes(vBuf, 0, vBuf.size)
        val aBoxes = parseBoxes(aBuf, 0, aBuf.size)

        val vMoov = vBoxes.find { it.type == "moov" } ?: error("视频缺少 moov")
        val aMoov = aBoxes.find { it.type == "moov" } ?: error("音频缺少 moov")
        val vFtyp = vBoxes.find { it.type == "ftyp" }

        val vTraks = kidsOf(vBuf, vMoov, "trak")
        val aTraks = kidsOf(aBuf, aMoov, "trak")
        require(vTraks.isNotEmpty()) { "视频无轨道" }
        require(aTraks.isNotEmpty()) { "音频无轨道" }

        val vTrak = vTraks.find { handlerType(vBuf, it) == "vide" } ?: vTraks[0]
        val aTrak = aTraks.find { handlerType(aBuf, it) == "soun" } ?: aTraks[0]

        val vCodec = detectCodec(vBuf, vTrak)

        val used = mutableSetOf<Int>()
        for (t in vTraks) used += trackId(vBuf, t)
        for (t in aTraks) used += trackId(aBuf, t)
        var newAudioId = 2
        while (newAudioId in used) newAudioId++

        val vTrakBytes = vBuf.copyOfRange(vTrak.start, vTrak.end)
        val aTrakBytes = cloneTrakWithId(aBuf, aTrak, newAudioId)

        val moovKids = kids(vBuf, vMoov)

        fun buildMoov(): ByteArray {
            val parts = mutableListOf<ByteArray>()
            var inserted = false
            for (c in moovKids) {
                when (c.type) {
                    "trak" -> {
                        if (!inserted) { parts += vTrakBytes; parts += aTrakBytes; inserted = true }
                    }
                    "mvex" -> { /* skip */ }
                    "mvhd" -> {
                        val b = vBuf.copyOfRange(c.start, c.end)
                        val ver = b[8].toInt()
                        val off = if (ver == 1) 116 else 104
                        val expect = if (ver == 1) 120 else 108
                        if (b.size == expect) wr32(b, off, newAudioId + 1)
                        parts += b
                    }
                    else -> parts += vBuf.copyOfRange(c.start, c.end)
                }
            }
            if (!inserted) { parts += vTrakBytes; parts += aTrakBytes }
            return makeBox("moov", parts)
        }

        val ftypBytes = vFtyp?.let { vBuf.copyOfRange(it.start, it.end) } ?: defaultFtyp()

        val moovProbe = buildMoov()
        var pos = ftypBytes.size.toLong() + moovProbe.size.toLong()

        val vMdats = vBoxes.filter { it.type == "mdat" }
        val aMdats = aBoxes.filter { it.type == "mdat" }

        val vStarts = mutableListOf<Long>()
        for (m in vMdats) { vStarts += pos + 8; pos += 8 + (m.end - m.dataStart) }
        val aStarts = mutableListOf<Long>()
        for (m in aMdats) { aStarts += pos + 8; pos += 8 + (m.end - m.dataStart) }

        remapChunkOffsets(vTrakBytes, vMdats, vStarts)
        remapChunkOffsets(aTrakBytes, aMdats, aStarts)

        val moovBytes = buildMoov()

        outFile.parentFile?.mkdirs()
        RandomAccessFile(outFile, "rw").use { raf ->
            raf.write(ftypBytes)
            raf.write(moovBytes)
            for (m in vMdats) {
                raf.write(makeMdat(vBuf.copyOfRange(m.dataStart, m.end)))
            }
            for (m in aMdats) {
                raf.write(makeMdat(aBuf.copyOfRange(m.dataStart, m.end)))
            }
        }

        return vCodec
    }
}
