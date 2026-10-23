package com.saikat.blurpaper

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** name, blur */
private val PRESETS = listOf(
    "Soft" to 0.20f,
    "iOS" to 0.40f,
    "Heavy" to 0.85f
)

class MainActivity : ComponentActivity() {

    /** Image shared into the app from another app (Share sheet). */
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) takeShare(intent)
        setContent {
            BlurpaperTheme {
                BlurpaperApp(incoming = incoming, onIncomingHandled = { incoming = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShare(intent)
    }

    private fun takeShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("image/") == true) {
            incoming = if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                i.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlurpaperApp(incoming: Uri?, onIncomingHandled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember { context.getSharedPreferences("blurpaper", Context.MODE_PRIVATE) }
    val sourceFile = remember { File(context.filesDir, "source.img") }
    val (screenW, screenH) = remember { WallpaperSetter.screenSize(context) }
    val aspect = screenW.toFloat() / screenH

    var source by remember { mutableStateOf<Bitmap?>(null) }
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var comparing by remember { mutableStateOf(false) }
    var showMockup by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(incoming == null && sourceFile.exists()) }
    var busy by remember { mutableStateOf(false) }

    // Live gesture feedback for the hint cards.
    var dragStamp by remember { mutableLongStateOf(0L) }
    var pinchStamp by remember { mutableLongStateOf(0L) }
    var dragActive by remember { mutableStateOf(false) }
    var pinchActive by remember { mutableStateOf(false) }

    var blur by remember { mutableFloatStateOf(prefs.getFloat("blur", 0.4f)) }
    var zoom by remember { mutableFloatStateOf(prefs.getFloat("zoom", 1f)) }
    var panX by remember { mutableFloatStateOf(prefs.getFloat("panX", 0f)) }
    var panY by remember { mutableFloatStateOf(prefs.getFloat("panY", 0f)) }
    var targetIndex by remember { mutableIntStateOf(prefs.getInt("target", Target.Both.ordinal)) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    suspend fun importImage(uri: Uri) {
        busy = true
        val bmp = withContext(Dispatchers.IO) {
            if (WallpaperSetter.copyTo(context, uri, sourceFile)) {
                WallpaperSetter.decodeSource(context, sourceFile)
            } else null
        }
        if (bmp == null) {
            snackbar.showSnackbar("Couldn't open that image")
        } else {
            source = bmp
            zoom = 1f
            panX = 0f
            panY = 0f
            tab = 0
        }
        busy = false
    }

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { importImage(uri) }
    }
    val pick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }

    // Restore the last image on launch.
    LaunchedEffect(Unit) {
        if (incoming == null && sourceFile.exists()) {
            source = withContext(Dispatchers.IO) { WallpaperSetter.decodeSource(context, sourceFile) }
        }
        loading = false
    }

    // Image shared from another app.
    LaunchedEffect(incoming) {
        if (incoming != null) {
            importImage(incoming)
            onIncomingHandled()
        }
    }

    // Live preview.
    LaunchedEffect(source, zoom, panX, panY, blur) {
        val s = source ?: return@LaunchedEffect
        val h = (360 / aspect).roundToInt()
        val (fx, plain) = withContext(Dispatchers.Default) {
            WallpaperSetter.process(s, 360, h, zoom, panX, panY, blur) to
                WallpaperSetter.render(s, 360, h, zoom, panX, panY)
        }
        shown = fx
        original = plain
    }

    // Gesture cards light up briefly after each drag / pinch event.
    LaunchedEffect(dragStamp) {
        if (dragStamp != 0L) {
            dragActive = true
            delay(300)
            dragActive = false
        }
    }
    LaunchedEffect(pinchStamp) {
        if (pinchStamp != 0L) {
            pinchActive = true
            delay(300)
            pinchActive = false
        }
    }

    // Remember settings (debounced).
    LaunchedEffect(blur, zoom, panX, panY, targetIndex) {
        delay(300)
        prefs.edit()
            .putFloat("blur", blur)
            .putFloat("zoom", zoom).putFloat("panX", panX).putFloat("panY", panY)
            .putInt("target", targetIndex)
            .apply()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 20.dp)
        ) {
            val src = source
            if (src == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (loading || busy) {
                        CircularProgressIndicator()
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painterResource(R.drawable.ic_crystal),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("Blurpaper", style = MaterialTheme.typography.displaySmall)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Pick a photo, blur it, set it.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = { pick() }) { Text("Choose image") }
                        }
                    }
                }
            } else {
                // Title banner.
                Row(
                    Modifier.fillMaxWidth().height(52.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painterResource(R.drawable.ic_crystal),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Blurpaper",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Preview: drag to move, pinch to zoom, hold to compare.
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(bottom = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val img = if (comparing) original else shown
                    img?.let {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .aspectRatio(aspect)
                                .clip(RoundedCornerShape(32.dp))
                                .pointerInput(src) {
                                    detectTransformGestures { _, pan, gestureZoom, _ ->
                                        val z = (zoom * gestureZoom).coerceIn(1f, WallpaperSetter.MAX_ZOOM)
                                        val (mx, my) = WallpaperSetter.maxPan(src.width, src.height, aspect, z)
                                        panX = (panX + pan.x / size.width).coerceIn(-mx, mx)
                                        panY = (panY + pan.y / size.height).coerceIn(-my, my)
                                        zoom = z
                                        if (pan.x != 0f || pan.y != 0f) dragStamp = System.nanoTime()
                                        if (gestureZoom != 1f) pinchStamp = System.nanoTime()
                                    }
                                }
                                // Press and hold (without moving) to see the original.
                                .pointerInput(Unit) {
                                    detectTapGestures(onPress = {
                                        val job = scope.launch {
                                            delay(250)
                                            comparing = true
                                        }
                                        tryAwaitRelease()
                                        job.cancel()
                                        comparing = false
                                    })
                                }
                        ) {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (showMockup) MockupOverlay()
                            if (comparing) {
                                Surface(
                                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
                                    shape = RoundedCornerShape(50),
                                    color = Color.Black.copy(alpha = 0.6f)
                                ) {
                                    Text(
                                        "Original",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf("Image", "Effects", "Apply").forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                    }
                }

                Box(Modifier.fillMaxWidth().height(176.dp).padding(top = 16.dp)) {
                    when (tab) {
                        0 -> Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Gesture guide: each card lights up while you use that gesture.
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                GestureCard("Drag", "to move", dragActive, Modifier.weight(1f))
                                GestureCard("Pinch", "to zoom", pinchActive, Modifier.weight(1f))
                                GestureCard("Hold", "to compare", comparing, Modifier.weight(1f))
                            }
                            // Three equal buttons.
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val btn = Modifier.weight(1f).height(44.dp)
                                val pad = PaddingValues(horizontal = 8.dp)
                                FilledTonalButton(
                                    onClick = { pick() },
                                    enabled = !busy,
                                    modifier = btn,
                                    contentPadding = pad
                                ) { Text("Change", maxLines = 1) }
                                OutlinedButton(
                                    onClick = { zoom = 1f; panX = 0f; panY = 0f },
                                    modifier = btn,
                                    contentPadding = pad
                                ) { Text("Reset", maxLines = 1) }
                                if (showMockup) {
                                    Button(
                                        onClick = { showMockup = false },
                                        modifier = btn,
                                        contentPadding = pad
                                    ) { Text("Mockup", maxLines = 1) }
                                } else {
                                    OutlinedButton(
                                        onClick = { showMockup = true },
                                        modifier = btn,
                                        contentPadding = pad
                                    ) { Text("Mockup", maxLines = 1) }
                                }
                            }
                        }

                        1 -> Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PRESETS.forEach { (name, value) ->
                                    FilterChip(
                                        selected = abs(blur - value) < 0.01f,
                                        onClick = { blur = value },
                                        label = { Text(name) }
                                    )
                                }
                            }
                            Text("Blur  ${(blur * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                            Slider(
                                value = blur,
                                onValueChange = { blur = it },
                                modifier = Modifier.fillMaxWidth(0.78f)
                            )
                        }

                        else -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
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
                            Button(
                                onClick = {
                                    if (!busy) scope.launch {
                                        busy = true
                                        val ok = withContext(Dispatchers.IO) {
                                            val out = WallpaperSetter.process(
                                                src, screenW, screenH, zoom, panX, panY, blur
                                            )
                                            WallpaperSetter.apply(context, out, Target.values()[targetIndex])
                                        }
                                        busy = false
                                        snackbar.showSnackbar(if (ok) "Wallpaper set" else "Couldn't set wallpaper")
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) { Text(if (busy) "Setting…" else "Set wallpaper") }
                        }
                    }
                }
            }
        }
    }
}

/** Small hint card that highlights while its gesture is being performed. */
@Composable
private fun GestureCard(title: String, subtitle: String, active: Boolean, modifier: Modifier = Modifier) {
    val container by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "gestureCard"
    )
    val content = if (active) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.height(60.dp),
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Fake clock and app icons drawn over the preview, to judge legibility. */
@Composable
private fun MockupOverlay() {
    val tile = Color.White.copy(alpha = 0.88f)
    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Fri 23", color = Color.White, style = MaterialTheme.typography.labelLarge)
            Text("9:41", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Light)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) { MockupIconRow(tile) }
        }
        MockupIconRow(tile)
    }
}

@Composable
private fun MockupIconRow(color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(4) {
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(color)
            )
        }
    }
}
