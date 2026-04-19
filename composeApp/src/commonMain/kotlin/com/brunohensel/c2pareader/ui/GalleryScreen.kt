package com.brunohensel.c2pareader.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch

private val initialBundled: List<GalleryImage.Bundled> = listOf(
    GalleryImage.Bundled(
        id = "firefly",
        label = "Firefly tabby cat (JPEG)",
        resourcePath = "files/firefly_tabby_cat.jpg",
    ),
    GalleryImage.Bundled(
        id = "phase3-webp",
        label = "Phase 3 test (WebP)",
        resourcePath = "files/sample1.webp",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen() {
    val images = remember { mutableStateListOf<GalleryImage>().also { it.addAll(initialBundled) } }
    val scope = rememberCoroutineScope()
    var pickCounter by remember { mutableStateOf(0) }

    val picker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        if (file == null) return@rememberFilePickerLauncher
        scope.launch {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@launch
            pickCounter += 1
            val name = file.name.ifBlank { "Picked image #$pickCounter" }
            images.add(
                0,
                GalleryImage.Picked(
                    id = "picked-$pickCounter",
                    label = name,
                    bytes = bytes,
                ),
            )
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("C2PA Reader") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Add image") },
                icon = { Text("+") },
                onClick = { picker.launch() },
            )
        },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            items(images, key = { it.id }) { ImageItemCard(it) }
        }
    }
}
