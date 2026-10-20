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
    fun scannerSkipsShieldedFolders() {
        val root = Files.createTempDirectory("tagplayer-shield-scan").toFile()
        val kept = java.io.File(root, "留下")
        val blocked = java.io.File(root, "不要")
        val nested = java.io.File(root, "只挡这一层")
        kept.mkdirs()
        blocked.mkdirs()
        nested.mkdirs()
        java.io.File(kept, "甲.mp3").writeBytes(byteArrayOf(1))
        java.io.File(blocked, "乙.mp3").writeBytes(byteArrayOf(2))
        java.io.File(java.io.File(blocked, "更深").apply { mkdirs() }, "丙.mp3").writeBytes(byteArrayOf(3))
        java.io.File(nested, "丁.mp3").writeBytes(byteArrayOf(4))
        java.io.File(java.io.File(nested, "子目录").apply { mkdirs() }, "戊.mp3").writeBytes(byteArrayOf(5))
        val files = listAudioFiles(
            root,
            skipFolderPaths = setOf(blocked.absolutePath),
            skipExactFolders = setOf(nested.absolutePath),
        )
        assertEquals(listOf("戊.mp3", "甲.mp3"), files.map { it.name }.sorted())
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

    @Test
    fun movingASongKeepsTagsWhenTheFileIdChanges() {
        val tagged = TagFile(songs = mapOf("old" to addTag(SongRecord(title = "夜曲"), "夜里", 5)))
        val moved = tagged.moveSong("old", "new")
        assertEquals(null, moved.songs["old"])
        assertTrue("夜里" in effectiveTags(moved.songs.getValue("new")))
        assertEquals("夜曲", moved.songs.getValue("new").title)
    }

    @Test
    fun coverUndoRemembersThePreviousPicture() {
        val store = CoverUndoStore(java.io.File(Files.createTempDirectory("cover-undo").toFile(), "undo"))
        assertFalse(store.has("song"))
        store.save("song", EmbeddedCover("image/png", byteArrayOf(1, 2, 3)))
        val saved = store.load("song")
        assertTrue(saved is SavedCover.Image)
        store.move("song", "renamed")
        assertFalse(store.has("song"))
        assertTrue(store.load("renamed") is SavedCover.Image)
        store.save("renamed", null)
        assertTrue(store.load("renamed") is SavedCover.Empty)
    }

    @Test
    fun itunesResultKeepsAlbumAndGenre() {
        val body = """
            {"results":[
              {"trackName":"夜曲","artistName":"周杰倫","collectionName":"十一月的蕭邦","primaryGenreName":"華語流行樂","artworkUrl100":"https://example/100x100bb.jpg"},
              {"artistName":"没歌名"}
            ]}
        """.trimIndent()
        val matches = parseItunesMatches(body)
        assertEquals(1, matches.size)
        assertEquals("十一月的蕭邦", matches[0].album)
        assertEquals("華語流行樂", matches[0].genre)
    }

    @Test
    fun textUndoRemembersThePreviousFields() {
        val store = TextUndoStore(java.io.File(Files.createTempDirectory("text-undo").toFile(), "undo"))
        store.save("song", EmbeddedText(title = "夜曲", artist = "周杰伦", album = "十一月的萧邦", genre = "流行"))
        assertEquals("十一月的萧邦", store.load("song")?.album)
        store.move("song", "renamed")
        assertEquals(null, store.load("song"))
        assertEquals("流行", store.load("renamed")?.genre)
    }

    @Test
    fun coverChoicesKeepNearMatchesAndPutTheCloserOneFirst() {
        val choices = rankCoverChoices(
            listOf(
                TrackMatch("七里香", "周杰倫", "七里香", "華語流行樂", artworkUrl = "https://example/a.jpg"),
                TrackMatch("夜曲", "周杰倫", "十一月的蕭邦", "華語流行樂", artworkUrl = "https://example/b.jpg"),
                TrackMatch("没有图", "周杰倫", "空", "空"),
            ),
            "周杰伦",
            "夜曲",
        )
        assertEquals(listOf("夜曲", "七里香"), choices.map { it.title })
    }

    @Test
    fun onlineCoverIsRejectedWhenTheTitleDoesNotMatch() {
        val wrong = CoverCandidate("周杰倫", "七里香", "https://example/100x100bb.jpg")
        assertEquals(null, chooseCoverUrl(listOf(wrong), "周杰伦", "夜曲"))
        val right = CoverCandidate("周杰伦", "夜曲", "https://example/100x100bb.jpg")
        assertEquals("https://example/600x600bb.jpg", chooseCoverUrl(listOf(wrong, right), "周杰伦", "夜曲"))
    }

    @Test
    fun onlineMatchKeepsArtistAlbumGenreAndDoesNotReviveARemovedTag() {
        val match = chooseTrackMatch(
            listOf(
                TrackMatch("七里香", "周杰倫", "七里香", "華語流行樂", releaseDate = "2004-08-03T00:00:00Z"),
                TrackMatch("夜曲", "周杰倫", "十一月的蕭邦", "華語流行樂", releaseDate = "2005-11-01T00:00:00Z"),
            ),
            "周杰伦",
            "夜曲",
        )
        assertEquals("夜曲", match?.title)
        assertEquals("2005-11-01", releaseDay(match?.releaseDate.orEmpty()))
        val removed = removeTag(SongRecord(), "周杰倫", 5)
        val applied = TagFile(songs = mapOf("song" to removed)).applyOnlineMatch(
            "song",
            CachedMatch("周杰倫", "十一月的蕭邦", "華語流行樂", "2005-11-01"),
            9,
        ).songs.getValue("song")
        assertFalse("周杰倫" in effectiveTags(applied))
        assertTrue("十一月的蕭邦" in effectiveTags(applied))
        assertTrue("華語流行樂" in effectiveTags(applied))
        assertEquals("華語流行樂", applied.genre)
        assertEquals("2005-11-01", applied.releaseDate)
    }

    @Test
    fun recognitionSwapsAFileNameThatPutsTheTitleFirst() {
        val matches = listOf(TrackMatch("夜曲", "周杰倫", "十一月的蕭邦", "華語流行樂", releaseDate = "2005-11-01"))
        val track = buildLocalTrack(
            fileName = "夜曲 - 周杰伦.mp3",
            path = "/music/夜曲 - 周杰伦.mp3",
            uri = "",
            size = 1,
            modified = 0,
            contentId = "song",
            metadata = TrackMetadata(),
        )
        assertEquals("周杰伦", track.title)
        assertEquals("夜曲", track.artist)
        val found = findOnlineMatch(track, track.artist, track.title) { _, _ -> matches }
        assertEquals("夜曲", found?.title)
        assertEquals("周杰倫", found?.artist)
        val record = TagFile().applyOnlineMatch(
            "song",
            CachedMatch(found!!.artist, found.album, found.genre, "2005-11-01", found.title),
            3,
        ).songs.getValue("song")
        assertTrue(record.recognizedNames)
        assertEquals("夜曲", displayTitle(track, record))
        assertEquals("周杰倫", displayArtist(track, record))
        val rescanned = record.withMetadata("周杰伦", "夜曲", "叶惠美", 0, 1, 9)
        assertEquals("夜曲", rescanned.title)
        assertEquals("周杰倫", rescanned.artist)
        assertEquals("叶惠美", rescanned.album)
        val overwritten = SongRecord(title = "周杰伦", artist = "夜曲", metaUpdatedAt = 20)
        assertEquals("夜曲", mergeSong(overwritten, record).title)
        assertEquals("夜曲", mergeSong(record, overwritten).title)
    }

    @Test
    fun yearRangeDropsSongsOutsideIt() {
        val night = LocalTrack(contentId = "night", title = "夜曲")
        val old = LocalTrack(contentId = "old", title = "旧歌")
        val file = TagFile(
            songs = mapOf(
                "night" to SongRecord(releaseDate = "2005-11-01"),
                "old" to SongRecord(releaseDate = "1978-01-01"),
            ),
        )
        val filtered = filterTracks(listOf(night, old), file, PlaylistFilter(yearFrom = 2000, yearTo = 2009))
        assertEquals(listOf(night), filtered)
    }

    @Test
    fun listSecondLineUsesArtistThenAlbum() {
        assertEquals("周杰伦 - 叶惠美", listDetail("周杰伦", "叶惠美", emptyList(), showArtist = true, showAlbum = true))
        assertEquals("周杰伦", listDetail("周杰伦", "叶惠美", emptyList(), showArtist = true, showAlbum = false))
        assertEquals("叶惠美", listDetail("周杰伦", "叶惠美", emptyList(), showArtist = false, showAlbum = true))
        assertEquals("夜里", listDetail("周杰伦", "叶惠美", listOf("夜里"), showArtist = false, showAlbum = false))
    }

    @Test
    fun folderShieldHidesAFolderWithoutHidingItsSibling() {
        val night = LocalTrack(contentId = "a", title = "夜曲", path = "/music/周杰伦/叶惠美/夜曲.mp3")
        val sword = LocalTrack(contentId = "b", title = "双截棍", path = "/music/周杰伦/范特西/双截棍.mp3")
        val classic = LocalTrack(contentId = "c", title = "春", path = "/music/古典/春.mp3")
        val browse = browseFolders(listOf(night, sword, classic), null, emptySet())
        assertEquals(listOf("古典", "周杰伦"), browse.entries.map { it.name })
        val inside = browseFolders(listOf(night, sword, classic), browse.entries.first { it.name == "周杰伦" }.path, emptySet())
        assertEquals(listOf("叶惠美", "范特西"), inside.entries.map { it.name })
        val blocked = toggleFolderShield(emptySet(), inside.entries.first { it.name == "范特西" }.path)
        assertTrue(folderBlocks(sword, blocked))
        assertFalse(folderBlocks(night, blocked))
        assertFalse(folderBlocks(classic, blocked))
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
