package com.spawnlau.gemmapocket

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.spawnlau.gemmapocket.shared.*

internal class AndroidPhotoIndex(context: Context) : SQLiteOpenHelper(context, "system-photo-index.sqlite", null, 1), PhotoIndex {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE photo_index (asset_id TEXT PRIMARY KEY, revision TEXT NOT NULL, vector BLOB, read_ms REAL NOT NULL, embed_ms REAL NOT NULL, error TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized override fun load(): List<IndexedPhoto> = readableDatabase.rawQuery("SELECT * FROM photo_index", null).use { cursor ->
        buildList { while (cursor.moveToNext()) {
            val vector = if (cursor.isNull(2)) null else runCatching { PhotoVectorCodec.decode(cursor.getBlob(2)) }.getOrNull()
            add(IndexedPhoto(cursor.getString(0), cursor.getString(1), vector, cursor.getDouble(3), cursor.getDouble(4), cursor.getString(5)))
        } }
    }
    @Synchronized override fun save(photo: IndexedPhoto) {
        val values = ContentValues().apply {
            put("asset_id", photo.id); put("revision", photo.revision)
            photo.vector?.let { put("vector", PhotoVectorCodec.encode(it)) } ?: putNull("vector")
            put("read_ms", photo.readMs); put("embed_ms", photo.embeddingMs); put("error", photo.error)
        }
        check(writableDatabase.insertWithOnConflict("photo_index", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }
    @Synchronized override fun retain(ids: List<String>) {
        val allowed = ids.toHashSet(); val db = writableDatabase
        val obsolete = db.rawQuery("SELECT asset_id FROM photo_index", null).use { cursor ->
            buildList { while (cursor.moveToNext()) { val id = cursor.getString(0); if (id !in allowed) add(id) } }
        }
        db.beginTransaction()
        try {
            obsolete.forEach { db.delete("photo_index", "asset_id = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
