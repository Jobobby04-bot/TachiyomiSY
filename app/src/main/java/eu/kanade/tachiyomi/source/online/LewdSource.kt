package eu.kanade.tachiyomi.source.online

import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.manga.MangaController
import exh.metadata.metadata.base.RaisedSearchMetadata
import exh.metadata.metadata.base.getFlatMetadataForManga
import exh.metadata.metadata.base.insertFlatMetadata
import exh.source.EnhancedHttpSource
import kotlin.reflect.KClass
import rx.Completable
import rx.Single
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * LEWD!
 */
interface LewdSource<M : RaisedSearchMetadata, I> : CatalogueSource {
    val db: DatabaseHelper get() = Injekt.get()

    /**
     * The class of the metadata used by this source
     */
    val metaClass: KClass<M>

    /**
     * Parse the supplied input into the supplied metadata object
     */
    fun parseIntoMetadata(metadata: M, input: I)

    /**
     * Use reflection to create a new instance of metadata
     */
    private fun newMetaInstance() = metaClass.constructors.find {
        it.parameters.isEmpty()
    }?.call()
        ?: error("Could not find no-args constructor for meta class: ${metaClass.qualifiedName}!")

    /**
     * Parses metadata from the input and then copies it into the manga
     *
     * Will also save the metadata to the DB if possible
     */
    fun parseToManga(manga: SManga, input: I): Completable {
        val mangaId = (manga as? Manga)?.id
        val metaObservable = if (mangaId != null) {
            // We have to use fromCallable because StorIO messes up the thread scheduling if we use their rx functions
            Single.fromCallable {
                db.getFlatMetadataForManga(mangaId).executeAsBlocking()
            }.map {
                if (it != null) it.raise(metaClass)
                else newMetaInstance()
            }
        } else {
            Single.just(newMetaInstance())
        }

        return metaObservable.map {
            parseIntoMetadata(it, input)
            it.copyTo(manga)
            it
        }.flatMapCompletable {
            if (mangaId != null) {
                it.mangaId = mangaId
                db.insertFlatMetadata(it.flatten())
            } else Completable.complete()
        }
    }

    /**
     * Try to first get the metadata from the DB. If the metadata is not in the DB, calls the input
     * producer and parses the metadata from the input
     *
     * If the metadata needs to be parsed from the input producer, the resulting parsed metadata will
     * also be saved to the DB.
     */
    fun getOrLoadMetadata(mangaId: Long?, inputProducer: () -> Single<I>): Single<M> {
        val metaObservable = if (mangaId != null) {
            // We have to use fromCallable because StorIO messes up the thread scheduling if we use their rx functions
            Single.fromCallable {
                db.getFlatMetadataForManga(mangaId).executeAsBlocking()
            }.map {
                it?.raise(metaClass)
            }
        } else Single.just(null)

        return metaObservable.flatMap { existingMeta ->
            if (existingMeta == null) {
                inputProducer().flatMap { input ->
                    val newMeta = newMetaInstance()
                    parseIntoMetadata(newMeta, input)
                    val newMetaSingle = Single.just(newMeta)
                    if (mangaId != null) {
                        newMeta.mangaId = mangaId
                        db.insertFlatMetadata(newMeta.flatten()).andThen(newMetaSingle)
                    } else newMetaSingle
                }
            } else Single.just(existingMeta)
        }
    }

    fun getDescriptionAdapter(controller: MangaController): RecyclerView.Adapter<*>?

    val SManga.id get() = (this as? Manga)?.id
    val SChapter.mangaId get() = (this as? Chapter)?.manga_id

    companion object {
        fun Source.isLewdSource() = (this is LewdSource<*, *> || (this is EnhancedHttpSource && this.enhancedSource is LewdSource<*, *>))

        fun Source.getLewdSource(): LewdSource<*, *>? {
            return if (!this.isLewdSource()) {
                null
            } else if (this is LewdSource<*, *>) {
                this
            } else if (this is EnhancedHttpSource && this.enhancedSource is LewdSource<*, *>) {
                this.enhancedSource
            } else {
                null
            }
        }
    }
}
