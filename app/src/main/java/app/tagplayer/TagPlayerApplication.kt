package app.tagplayer

import android.app.Application
import app.tagplayer.data.LibraryRepository

class TagPlayerApplication : Application() {
    lateinit var library: LibraryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        library = LibraryRepository(this)
    }
}
