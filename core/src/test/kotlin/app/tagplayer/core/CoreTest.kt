package app.tagplayer.core

import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CoreTest {
    @Test
    fun tagAddAndRemoveFollowTheNewerOperation() {
        val added = addTag(SongRecord(), "  夜里  ", 10)
        assertEquals(setOf("夜里"), effectiveTags(added))
        val removed = removeTag(added, "夜里", 20)
        assertFalse("夜里" in effectiveTags(removed))
        assertTrue("夜里" in effectiveTags(addTag(removed, "夜里", 30)))
    }

    @Test
    fun mergeKeepsTheNewerTagOperationAndBothSongs() {
        val phone = addTag(SongRecord(), "夜里", 50)
        val olderRemoval = removeTag(SongRecord(), "夜里", 40)
        val newerRemoval = removeTag(SongRecord(), "夜里", 60)
        assertTrue("夜里" in effectiveTags(mergeSong(phone, olderRemoval)))
        assertFalse("夜里" in effectiveTags(mergeSong(phone, newerRemoval)))

        val left = TagFile(songs = mapOf("phone" to addTag(SongRecord(title = "甲"), "通勤", 5)))
        val right = TagFile(songs = mapOf("computer" to addTag(SongRecord(title = "乙"), "夜里", 6)))
        val merged = mergeTagFiles(left, right)
        assertEquals(setOf("phone", "computer"), merged.songs.keys)
        assertEquals(setOf("通勤"), effectiveTags(merged.songs.getValue("phone")))
    }

    @Test
    fun metadataUpdateDoesNotEraseTagsOrSongsOnTheOtherDevice() {
        val remote = TagFile(songs = mapOf("remote" to addTag(SongRecord(title = "只在手机"), "夜里", 1)))
        val local = LocalTrack(contentId = "local", title = "甲", artist = "乙", album = "丙", durationMs = 1000, size = 20)
        val next = remote.upsertMetadata(listOf(local), now = 2)
        assertEquals(setOf("夜里"), effectiveTags(next.songs.getValue("remote")))
        assertEquals("甲", next.songs.getValue("local").title)
        assertEquals("乙", next.songs.getValue("local").artist)
    }

    @Test
    fun presetMergeUsesTheNewerCopy() {
        val older = Preset("1", "睡前", listOf("夜里"), "all", emptyList(), 10)
        val newer = Preset("1", "睡前", listOf("安静"), "any", listOf("说唱"), 20)
        val other = Preset("2", "通勤", listOf("通勤"), "all", emptyList(), 15)
        val merged = mergeTagFiles(
            TagFile(presets = listOf(older, other)),
            TagFile(presets = listOf(newer)),
        )
        val sleep = merged.presets.single { it.id == "1" }
        assertEquals(listOf("安静"), sleep.include)
        assertEquals("any", sleep.includeMode)
        assertEquals(2, merged.presets.size)
    }

    @Test
    fun filterSupportsIncludeExcludeAndSearch() {
        val night = LocalTrack("night", "夜航", "甲")
        val talk = LocalTrack("talk", "访谈", "乙")
        val file = TagFile(
            songs = mapOf(
                "night" to addTag(SongRecord(), "夜里", 1).let { addTag(it, "安静", 2) },
                "talk" to addTag(SongRecord(), "访谈", 1),
            ),
        )
        val tracks = listOf(night, talk)
        assertEquals(listOf(night), filterTracks(tracks, file, PlaylistFilter(include = listOf("夜里", "安静"))))
        assertEquals(
            listOf(night, talk),
            filterTracks(tracks, file, PlaylistFilter(include = listOf("夜里", "访谈"), includeMode = IncludeMode.Any)),
        )
        assertEquals(
            emptyList(),
            filterTracks(tracks, file, PlaylistFilter(include = listOf("夜里"), exclude = listOf("安静"))),
        )
        assertEquals(listOf(night, talk), filterTracks(tracks, file, PlaylistFilter()))
        assertEquals(listOf(talk), filterTracks(tracks, file, PlaylistFilter(query = "访谈")))
    }

    @Test
    fun shuffleIsAPermutationAndPreviousRestartsNearTheStart() {
        val random = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
        }
        assertEquals(listOf("b", "c", "d", "a"), shuffledQueue(listOf("a", "b", "c", "d"), random))
        assertEquals(listOf("b", "c", "a"), queueStartingWith(listOf("a", "b", "c"), "b", random))

        val queue = PlayQueue(listOf("a", "b", "c"), index = 1)
        assertEquals(PlayQueue(queue.ids, 2), (nextTrack(queue) as QueueStep.Move).queue)
        assertEquals(QueueStep.RoundDone, nextTrack(PlayQueue(queue.ids, 2)))
        assertEquals(PreviousStep.Restart, previousTrack(queue, positionMs = 4_000))
        assertEquals(PlayQueue(queue.ids, 0), (previousTrack(queue, positionMs = 500) as PreviousStep.Move).queue)
    }

    @Test
    fun contentIdUsesHeadAndTailOnlyWhenTheFileIsLarge() {
        val small = contentId(4, byteArrayOf(1, 2, 3, 4), byteArrayOf(9, 9, 9, 9))
        assertEquals(small, contentId(4, byteArrayOf(1, 2, 3, 4), byteArrayOf(0)))
        assertNotEquals(small, contentId(4, byteArrayOf(1, 2, 3, 5), ByteArray(0)))

        val size = CONTENT_ID_SAMPLE_BYTES + 10L
        val head = ByteArray(CONTENT_ID_SAMPLE_BYTES) { 1 }
        assertNotEquals(
            contentId(size, head, ByteArray(CONTENT_ID_SAMPLE_BYTES) { 2 }),
            contentId(size, head, ByteArray(CONTENT_ID_SAMPLE_BYTES) { 3 }),
        )

        val file = Files.createTempFile("tagplayer", ".bin").toFile()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(contentIdOfFile(file), contentIdOfFile(file))
        file.delete()
    }

    @Test
    fun jsonRoundTripAndConflictMergePreserveChineseTags() {
        val directory = Files.createTempDirectory("tagplayer-tags").toFile()
        val main = java.io.File(directory, "tags.json")
        val repository = TagRepository(FileTagIo(main))
        repository.update { it.withSong("song", addTag(SongRecord(), "夜里", 10)) }
        val conflict = java.io.File(directory, "tags.json.sync-conflict-20261019-AB.json")
        conflict.writeText(
            TagCodec.encode(
                TagFile(songs = mapOf("song" to addTag(SongRecord(title = "夜"), "安静", 20))),
            ),
        )
        val read = repository.read()
        val song = read.songs.getValue("song")
        assertEquals(setOf("夜里", "安静"), effectiveTags(song))
        assertEquals("夜", song.title)
        assertFalse(conflict.exists())
        val again = TagCodec.decode(main.readText())
        assertEquals(setOf("夜里", "安静"), effectiveTags(again.songs.getValue("song")))
    }

    @Test
    fun corruptTagFileIsNotOverwritten() {
        val directory = Files.createTempDirectory("tagplayer-broken").toFile()
        val main = java.io.File(directory, "tags.json")
        main.writeText("{")
        val repository = TagRepository(FileTagIo(main))
        assertFailsWith<TagFileException> { repository.read() }
        assertEquals("{", main.readText())
    }

    @Test
    fun scannerSkipsHiddenAndNonAudioFiles() {
        val root = Files.createTempDirectory("tagplayer-music").toFile()
        java.io.File(root, "甲 - 夜航.mp3").writeBytes(byteArrayOf(1, 2, 3))
        java.io.File(root, "note.txt").writeText("x")
        java.io.File(root, ".hidden.flac").writeBytes(byteArrayOf(1))
        val album = java.io.File(root, "专辑")
        album.mkdirs()
        java.io.File(album, "乙 - 白日.flac").writeBytes(byteArrayOf(4, 5, 6, 7))
        val files = listAudioFiles(root)
        assertEquals(listOf("乙 - 白日.flac", "甲 - 夜航.mp3"), files.map { it.name }.sorted())
        val track = trackFromFile(files.first { it.name.startsWith("甲") }, TrackMetadata())
        assertEquals("甲", track.artist)
        assertEquals("夜航", track.title)
    }

    @Test
    fun titleTagThatIsActuallyTheArtistUsesTheFileName() {
        val track = namedTrack(TrackMetadata(title = "周杰伦", artist = "周杰伦"))
        assertEquals("夜曲", track.title)
        assertEquals("周杰伦", track.artist)
        assertEquals("夜曲", displayTitle(track, null))
    }

    @Test
    fun swappedTitleAndArtistPreferTheFileName() {
        val track = namedTrack(TrackMetadata(title = "周杰伦", artist = "夜曲"))
        assertEquals("夜曲", track.title)
        assertEquals("周杰伦", track.artist)
    }

    private fun namedTrack(metadata: TrackMetadata) = buildLocalTrack(
        fileName = "周杰伦 - 夜曲.mp3",
        path = "/music/周杰伦 - 夜曲.mp3",
        uri = "",
        size = 1,
        modified = 0,
        contentId = "song",
        metadata = metadata,
    )
}
