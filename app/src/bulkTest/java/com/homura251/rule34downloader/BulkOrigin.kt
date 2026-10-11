package com.homura251.rule34downloader

/** Uses the same API protocol as production; all originals cross HTTP and real MediaStore. */
internal class BulkOrigin(tag: String, count: Int, checkpoint: Boolean) : TestApiOrigin(tag, count) {
    init {
        if (checkpoint) {
            mediaPauseId.set(1)
            if (count > 200) pagePauseBefore.set(count.toLong() - 199)
        }
    }
}
