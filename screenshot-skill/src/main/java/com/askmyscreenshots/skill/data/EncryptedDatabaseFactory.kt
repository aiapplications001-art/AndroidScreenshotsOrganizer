package com.askmyscreenshots.skill.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.askmyscreenshots.skill.debug.SkillDebugLog
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object EncryptedDatabaseFactory {
    private val nativeLibraryLoaded: Unit by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SkillDebugLog.i("sqlcipher", "loading_native_library=true")
        System.loadLibrary("sqlcipher")
        SkillDebugLog.i("sqlcipher", "loading_native_library=success")
    }

    fun create(context: Context): SupportSQLiteOpenHelper.Factory {
        nativeLibraryLoaded
        SkillDebugLog.d("sqlcipher", "creating_support_factory=true")
        val passphrase = KeystorePassphraseProvider(context).getOrCreatePassphrase()
        return SupportOpenHelperFactory(passphrase)
    }
}
