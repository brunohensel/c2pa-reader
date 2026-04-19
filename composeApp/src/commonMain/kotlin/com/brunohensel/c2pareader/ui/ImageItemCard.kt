package com.brunohensel.c2pareader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.brunohensel.c2pareader.C2paReader
import com.brunohensel.c2pareader.manifest.AiStatus
import com.brunohensel.c2pareader.manifest.ManifestSummary
import com.brunohensel.c2pareader.manifest.summarize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import c2pareader.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

@OptIn(ExperimentalResourceApi::class)
@Composable
fun ImageItemCard(image: GalleryImage, modifier: Modifier = Modifier) {
    var state by remember(image.id) { mutableStateOf<CardState>(CardState.Loading) }

    LaunchedEffect(image.id) {
        state = CardState.Loading
        val bytes = withContext(Dispatchers.Default) {
            when (image) {
                is GalleryImage.Bundled -> runCatching { Res.readBytes(image.resourcePath) }.getOrNull()
                is GalleryImage.Picked -> image.bytes
            }
        }
        if (bytes == null) {
            state = CardState.Ready(summary = null, imageModel = null)
            return@LaunchedEffect
        }
        val summary = withContext(Dispatchers.Default) {
            summarize(C2paReader.read(bytes))
        }
        state = CardState.Ready(summary = summary, imageModel = bytes)
    }

    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            val model = (state as? CardState.Ready)?.imageModel
            AsyncImage(
                model = model,
                contentDescription = image.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    image.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                when (val s = state) {
                    CardState.Loading -> Text("Reading manifest…", style = MaterialTheme.typography.bodySmall)
                    is CardState.Ready -> SummaryBody(s.summary)
                }
            }
        }
    }
}

@Composable
private fun SummaryBody(summary: ManifestSummary?) {
    if (summary == null) {
        Text("No C2PA manifest", style = MaterialTheme.typography.bodyMedium)
        return
    }
    AiBadge(summary.aiStatus)
    summary.tool?.let { LabelValue("Tool", it) }
    summary.action?.let { LabelValue("Action", it) }
    summary.format?.let { LabelValue("Format", it) }
}

@Composable
private fun AiBadge(status: AiStatus) {
    val (text, color) = when (status) {
        AiStatus.AI_GENERATED -> "AI-generated" to Color(0xFFB71C1C)
        AiStatus.AI_EDITED -> "AI-edited" to Color(0xFFE65100)
        AiStatus.NOT_AI -> "Not AI" to Color(0xFF1B5E20)
        AiStatus.UNKNOWN -> "C2PA present · AI unknown" to Color(0xFF455A64)
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private sealed class CardState {
    data object Loading : CardState()
    data class Ready(val summary: ManifestSummary?, val imageModel: Any?) : CardState()
}
