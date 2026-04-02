package com.topsearch.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.topsearch.app.SearchResult

// ── Google colour palette ─────────────────────────────────────────────────────
private val GoogleBlue  = Color(0xFF1A73E8)
private val GoogleGrey  = Color(0xFF70757A)
private val GoogleGreen = Color(0xFF0D652D)
private val DividerColor = Color(0xFFE8EAED)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    keyword:        String,
    results:        List<SearchResult>,
    screenshotPath: String,
    city:           String = "",
    onSearchAgain:  () -> Unit,
) {
    var showScreenshot by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Kết quả tìm kiếm",
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            buildString {
                                append("\"$keyword\"")
                                if (city.isNotBlank() && city != "🌐 Toàn quốc") append("  ·  $city")
                            },
                            style    = MaterialTheme.typography.bodySmall,
                            color    = GoogleBlue,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onSearchAgain) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                actions = {
                    if (screenshotPath.isNotBlank()) {
                        IconButton(onClick = { showScreenshot = true }) {
                            Icon(Icons.Default.Image, contentDescription = "Xem ảnh")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->

        if (results.isEmpty()) {
            EmptyState(Modifier.fillMaxSize().padding(padding), onSearchAgain)
        } else {
            LazyColumn(
                modifier       = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                // Header "Khoảng X kết quả"
                item {
                    Text(
                        "Khoảng ${results.size} kết quả",
                        fontSize = 13.sp,
                        color    = GoogleGrey,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    HorizontalDivider(color = DividerColor)
                }

                itemsIndexed(results) { _, result ->
                    GoogleResultRow(result)
                    HorizontalDivider(
                        color    = DividerColor,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick  = onSearchAgain,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape    = RoundedCornerShape(24.dp),
                    ) {
                        Text("Tìm kiếm keyword khác")
                    }
                }
            }
        }
    }

    if (showScreenshot) {
        ScreenshotDialog(screenshotPath) { showScreenshot = false }
    }
}

// ── Google-style result row ───────────────────────────────────────────────────

@Composable
private fun GoogleResultRow(result: SearchResult) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // ── Rank badge ──────────────────────────────────────────────────
        val rankColor = when {
            result.rank <= 3  -> GoogleBlue
            result.rank <= 7  -> GoogleGreen
            else              -> GoogleGrey
        }
        Box(
            modifier         = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(rankColor.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("${result.rank}", color = rankColor,
                fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {

            // ── Favicon + domain + Ads badge ────────────────────────────
            if (result.domain.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier          = Modifier.padding(bottom = 4.dp),
                ) {
                    // Favicon placeholder
                    Box(
                        modifier         = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(domainColor(result.domain)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            result.domain.first().uppercaseChar().toString(),
                            fontSize   = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color      = Color.White,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        result.domain,
                        fontSize = 13.sp,
                        color    = GoogleGrey,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Ads badge
                    if (result.isAd) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFFFFF0C2))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(
                                "Quảng cáo",
                                fontSize   = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color      = Color(0xFF7A5C00),
                            )
                        }
                    }
                }
            }

            // ── Title — xanh Google ──────────────────────────────────────
            Text(
                text       = result.title,
                fontSize   = 18.sp,
                fontWeight = FontWeight.Normal,
                color      = if (result.isAd) Color(0xFF1A73E8) else GoogleBlue,
                lineHeight = 24.sp,
            )
        }
    }
}

/** Hash domain → màu đa dạng cho favicon placeholder */
private fun domainColor(domain: String): Color {
    val palette = listOf(
        Color(0xFF1A73E8), Color(0xFFE53935), Color(0xFF43A047),
        Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFF00ACC1),
        Color(0xFF6D4C41), Color(0xFF039BE5), Color(0xFF7CB342),
        Color(0xFFD81B60),
    )
    return palette[domain.hashCode().and(0x7FFFFFFF) % palette.size]
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(modifier: Modifier, onRetry: () -> Unit) {
    Column(
        modifier            = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🔍", fontSize = 52.sp)
        Spacer(Modifier.height(16.dp))
        Text("Không trích xuất được kết quả",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Vui lòng thử lại",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, shape = RoundedCornerShape(12.dp)) { Text("Thử lại") }
    }
}

// ── Screenshot dialog ─────────────────────────────────────────────────────────

@Composable
private fun ScreenshotDialog(path: String, onDismiss: () -> Unit) {
    val bitmap = remember(path) {
        runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier         = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = "Screenshot",
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Text("Không tải được ảnh", color = Color.White)
            }
            IconButton(
                onClick  = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).systemBarsPadding(),
            ) {
                Text("✕", color = Color.White, fontSize = 20.sp)
            }
        }
    }
}
