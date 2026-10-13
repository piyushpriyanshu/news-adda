package com.newsadda.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.newsadda.app.data.model.Story

object Actions {
    fun open(ctx: Context, url: String, str: Str) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, str.noBrowser, Toast.LENGTH_SHORT).show()
        }
    }

    fun shareText(story: Story, str: Str): String = buildString {
        append(story.title)
        story.reports.forEach { r ->
            append("\n\n").append(r.publisher ?: r.sourceName)
            if (r.via != null) append(str.viaSuffix(r.via))
            append(": ").append(r.url)
        }
    }

    fun copy(ctx: Context, story: Story, str: Str) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("News", shareText(story, str)))
        Toast.makeText(ctx, str.copied, Toast.LENGTH_SHORT).show()
    }

    fun share(ctx: Context, story: Story, str: Str) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(story, str))
        ctx.startActivity(Intent.createChooser(i, str.shareChooser).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
