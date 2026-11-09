package app.qingjian.inputmethod.cloud

import android.content.Context

/** GIPHY beta API key：只存 Android Keystore 加密后的密文，不写入源码或普通设置备份。 */
object GifConfig {
    private const val PREFS = "giphy"
    private const val KEY = "api_key_cipher"

    fun load(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)?.let(SecretStore::open)

    fun save(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, SecretStore.seal(key.trim())).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun hint(context: Context): String? = load(context)?.takeIf { it.length > 4 }?.let { "····${it.takeLast(4)}" }
}
