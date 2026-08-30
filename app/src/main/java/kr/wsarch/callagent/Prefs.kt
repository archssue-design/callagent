package kr.wsarch.callagent

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun get(c: Context, k: String, d: String = ""): String = sp(c).getString(k, d) ?: d
    fun set(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
}
