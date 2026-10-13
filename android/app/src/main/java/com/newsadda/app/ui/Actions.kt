package com.newsadda.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.newsadda.app.data.model.Story

object Actions {
    fun open(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "लिंक खोलने के लिए कोई ब्राउज़र नहीं मिला", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareText(story: Story): String = buildString {
        append(story.title)
        story.reports.forEach { r ->
            append("\n\n").append(r.publisher ?: r.sourceName)
            if (r.via != null) append(" (${r.via} के माध्यम से)")
            append(": ").append(r.url)
        }
    }

    fun copy(ctx: Context, story: Story) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("खबर", shareText(story)))
        Toast.makeText(ctx, "शीर्षक और लिंक कॉपी हुए", Toast.LENGTH_SHORT).show()
    }

    fun share(ctx: Context, story: Story) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(story))
        ctx.startActivity(Intent.createChooser(i, "खबर शेयर करें").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
