package com.quicklauncher.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone as P

data class ContactHit(val name: String, val number: String)

/** Looks contacts up by name or by (part of) a phone number. */
object Contacts {
    fun granted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun search(ctx: Context, query: String, limit: Int = 6): List<ContactHit> {
        if (!granted(ctx)) return emptyList()
        val q = query.trim()
        val digits = q.filter { it.isDigit() }
        val out = ArrayList<ContactHit>()
        val seen = HashSet<String>()
        runCatching {
            val sel: String?; val args: Array<String>?
            if (q.isEmpty()) { sel = null; args = null }
            else if (digits.isNotEmpty() && digits.length >= q.count { it.isLetterOrDigit() }) {
                // looks like a number
                sel = "${P.NUMBER} LIKE ? OR ${P.NORMALIZED_NUMBER} LIKE ?"; args = arrayOf("%$digits%", "%$digits%")
            } else {
                sel = "${P.DISPLAY_NAME} LIKE ?"; args = arrayOf("%$q%")
            }
            ctx.contentResolver.query(P.CONTENT_URI, arrayOf(P.DISPLAY_NAME, P.NUMBER), sel, args, "${P.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    if (seen.add(name + "|" + num.filter { it.isDigit() })) out.add(ContactHit(name, num.trim()))
                }
            }
        }
        return out
    }
}
