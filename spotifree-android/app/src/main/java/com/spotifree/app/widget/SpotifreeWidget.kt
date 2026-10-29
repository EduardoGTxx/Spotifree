package com.spotifree.app.widget

import com.spotifree.app.util.runCatchingCancellable

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.spotifree.app.MainActivity
import com.spotifree.app.R
import java.io.File

/** Home screen player: cover, title, artist and previous / play-pause / next. */
class SpotifreeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetUpdater.read(context)
        val cover = File(context.filesDir, WidgetUpdater.COVER_FILE).takeIf { it.exists() }
            ?.let { runCatchingCancellable { BitmapFactory.decodeFile(it.path) }.getOrNull() }
        provideContent { Content(context, state, cover) }
    }

    @Composable
    private fun Content(context: Context, state: WidgetUpdater.State, cover: android.graphics.Bitmap?) {
        val white = ColorProvider(Color.White)
        val grey = ColorProvider(Color(0xFFB3B3B3))
        Row(
            modifier = GlanceModifier.fillMaxSize()
                .background(Color(0xE6181818))
                .cornerRadius(16.dp)
                .padding(10.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = if (cover != null) ImageProvider(cover) else ImageProvider(R.drawable.ic_widget_placeholder),
                contentDescription = null,
                modifier = GlanceModifier.size(64.dp).cornerRadius(8.dp),
            )
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = state.title.ifBlank { context.getString(R.string.widget_idle) },
                    maxLines = 1,
                    style = TextStyle(color = white, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                )
                Text(text = state.artist, maxLines = 1, style = TextStyle(color = grey, fontSize = 12.sp))
                Spacer(GlanceModifier.height(6.dp))
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Button(context, R.drawable.ic_skip_previous, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    Spacer(GlanceModifier.width(14.dp))
                    Button(context, if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    Spacer(GlanceModifier.width(14.dp))
                    Button(context, R.drawable.ic_skip_next, KeyEvent.KEYCODE_MEDIA_NEXT)
                }
            }
        }
    }

    @Composable
    private fun Button(context: Context, icon: Int, keyCode: Int) {
        Box(
            modifier = GlanceModifier.size(36.dp).clickable(actionSendBroadcast(mediaButtonIntent(context, keyCode))),
            contentAlignment = Alignment.Center,
        ) {
            Image(provider = ImageProvider(icon), contentDescription = null, modifier = GlanceModifier.size(26.dp))
        }
    }

    private fun mediaButtonIntent(context: Context, keyCode: Int): Intent =
        Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(context, "androidx.media3.session.MediaButtonReceiver"))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
}

class SpotifreeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SpotifreeWidget()
}
