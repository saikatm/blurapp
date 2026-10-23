package com.saikat.blurpaper

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BlurpaperTheme { BlurpaperApp() } }
    }
}

@Composable
private fun BlurpaperApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var base by remember { mutableStateOf<Bitmap?>(null) }     // screen-sized original
    var small by remember { mutableStateOf<Bitmap?>(null) }    // preview source
    var shown by remember { mutableStateOf<Bitmap?>(null) }    // blurred preview
    var blur by rememberSaveable { mutableFloatStateOf(0.4f) }
    var targetIndex by rememberSaveable { mutableIntStateOf(Target.Both.ordinal) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val bmp = withContext(Dispatchers.IO) { WallpaperSetter.load(context, uri) }
            if (bmp == null) {
                snackbar.showSnackbar("Couldn't open that image")
            } else {
                base = bmp
                small = WallpaperSetter.smallPreview(bmp)
            }
            busy = false
        }
    }
    val pick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }

    LaunchedEffect(small, blur) {
        val s = small ?: return@LaunchedEffect
        shown = withContext(Dispatchers.Default) { WallpaperSetter.blur(s, blur) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            val b = base
            if (b != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (!busy) scope.launch {
                            busy = true
                            val ok = withContext(Dispatchers.IO) {
                                val out = WallpaperSetter.blur(b, blur)
                                WallpaperSetter.apply(context, out, Target.values()[targetIndex])
                            }
                            busy = false
                            snackbar.showSnackbar(if (ok) "Wallpaper set" else "Couldn't set wallpaper")
                        }
                    }
                ) { Text(if (busy) "Setting…" else "Set wallpaper") }
            }
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 20.dp)
        ) {
            if (base == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Blurpaper", style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Pick a photo, blur it, set it.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { pick() }, enabled = !busy) { Text("Choose image") }
                    }
                }
            } else {
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    shown?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .aspectRatio(it.width.toFloat() / it.height)
                                .clip(RoundedCornerShape(28.dp))
                        )
                    }
                }
                Text("Blur  ${(blur * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(value = blur, onValueChange = { blur = it })
                Spacer(Modifier.height(4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val items = Target.values()
                    items.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = targetIndex == i,
                            onClick = { targetIndex = i },
                            shape = SegmentedButtonDefaults.itemShape(i, items.size)
                        ) { Text(t.label) }
                    }
                }
                TextButton(onClick = { pick() }, enabled = !busy) { Text("Change image") }
                Spacer(Modifier.height(72.dp))
            }
        }
    }
}
