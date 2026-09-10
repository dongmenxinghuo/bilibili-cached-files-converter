package com.example.bilicache

object CacheScanner {

    private const val BILI_ROOT =
        "/storage/emulated/0/Android/data/tv.danmaku.bili/download"

    /**
     * 用 shell 扫描所有 B 站缓存。
     * 输出格式（每行一条）：
     *   dir<TAB>videoPath<TAB>audioPath<TAB>sizeBytes<TAB>title
     */
    fun scan(): List<BiliCacheItem> {
        val script = """
            find $BILI_ROOT -name entry.json -type f 2>/dev/null | while read f; do
              d=$(dirname "${'$'}f")
              v="${'$'}d/video.m4s"
              a="${'$'}d/audio.m4s"
              if [ -f "${'$'}v" ] && [ -f "${'$'}a" ]; then
                sv=$(stat -c %s "${'$'}v" 2>/dev/null || echo 0)
                sa=$(stat -c %s "${'$'}a" 2>/dev/null || echo 0)
                sz=$((sv+sa))
                title=$(grep -o '"title":"[^"]*"' "${'$'}f" | head -1 | sed 's/"title":"//;s/"${'$'}//')
                [ -z "${'$'}title" ] && title=$(grep -o '"part":"[^"]*"' "${'$'}f" | head -1 | sed 's/"part":"//;s/"${'$'}//')
                [ -z "${'$'}title" ] && title=$(basename "${'$'}d")
                printf '%s\t%s\t%s\t%s\t%s\n' "${'$'}d" "${'$'}v" "${'$'}a" "${'$'}sz" "${'$'}title"
              fi
            done
        """.trimIndent()

        val output = ShizukuHelper.exec(script, 120_000)
        val items = mutableListOf<BiliCacheItem>()
        for (line in output.split('\n')) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val parts = l.split('\t')
            if (parts.size < 5) continue
            val size = parts[3].toLongOrNull() ?: 0L
            items += BiliCacheItem(
                dir = parts[0],
                videoPath = parts[1],
                audioPath = parts[2],
                sizeBytes = size,
                title = parts[4].ifBlank { parts[0].substringAfterLast('/') }
            )
        }
        return items.sortedByDescending { it.sizeBytes }
    }
}
