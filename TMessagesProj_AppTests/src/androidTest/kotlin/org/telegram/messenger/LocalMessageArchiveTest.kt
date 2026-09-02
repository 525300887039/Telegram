package org.telegram.messenger

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMessageArchiveTest {
    @Test
    fun archiveSuffixRemovesEncryptionExtension() {
        assertEquals(".mp4", LocalMessageArchive.archiveSuffix("video.mp4.enc"))
        assertEquals(".ogg", LocalMessageArchive.archiveSuffix("voice.ogg.enc"))
        assertEquals(".jpg", LocalMessageArchive.archiveSuffix("photo.jpg"))
        assertEquals(".bin", LocalMessageArchive.archiveSuffix("media.enc"))
    }
}
