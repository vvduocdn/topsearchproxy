package com.topsearch.app.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import com.topsearch.app.CheckStatus
import com.topsearch.app.KeywordBatchItem
import com.topsearch.app.KeywordQueueStore
import com.topsearch.app.SearchResult

data class VietnamCity(
    val label:     String,
    val nearParam: String,
    val lat:       Double       = 0.0,
    val lng:       Double       = 0.0,
    val proxyPool: List<String> = emptyList(),
) {
    val proxyHostPort: String get() = proxyPool.randomOrNull() ?: ""
    fun withNoProxy() = copy(proxyPool = emptyList())
}

val VIETNAM_CITIES = listOf(
    VietnamCity("🌐 Toàn quốc",   "",                          0.0,      0.0),
    VietnamCity("🏙 Hà Nội",       "Ha Noi, Vietnam",           21.0285,  105.8542, com.topsearch.app.CityProxies.HN_POOL_PUBLIC),
    VietnamCity("🌆 TP. HCM",      "Ho Chi Minh City, Vietnam", 10.8231,  106.6297, com.topsearch.app.CityProxies.HCM_POOL_PUBLIC),
    VietnamCity("🌊 Đà Nẵng",      "Da Nang, Vietnam",          16.0544,  108.2022, com.topsearch.app.CityProxies.DN_POOL_PUBLIC),
    VietnamCity("🌸 Huế",          "Hue, Vietnam",              16.4637,  107.5909),
    VietnamCity("🏖 Nha Trang",    "Nha Trang, Vietnam",        12.2388,  109.1967),
    VietnamCity("🏝 Phú Quốc",     "Phu Quoc, Vietnam",         10.2899,  103.9840),
    VietnamCity("🏔 Đà Lạt",       "Da Lat, Vietnam",           11.9404,  108.4583),
    VietnamCity("⚓ Hải Phòng",    "Hai Phong, Vietnam",        20.8449,  106.6881),
    VietnamCity("🏞 Cần Thơ",      "Can Tho, Vietnam",          10.0452,  105.7469),
    VietnamCity("🌿 Bình Dương",   "Binh Duong, Vietnam",       11.1673,  106.6669),
    VietnamCity("🏭 Đồng Nai",     "Dong Nai, Vietnam",         10.9452,  107.1351),
)

@Composable
fun SearchScreen(
    loadingStep:          String               = "",
    countdown:            Int                  = 0,
    errorMessage:         String               = "",
    initialKeyword:       String               = "",
    skipProxy:            Boolean              = false,
    socketInfo:           String               = "",
    isConnected:          Boolean              = false,
    keywordBatch:         List<KeywordBatchItem>            = emptyList(),
    keywordResults:       Map<String, List<SearchResult>>   = emptyMap(),
    keywordImagePaths:    Map<String, List<String>>         = emptyMap(),
    historyEntries:       List<KeywordQueueStore.Entry>     = emptyList(),
    manualResult:         Pair<String, List<SearchResult>>? = null,
    onManualResultDismiss: () -> Unit                       = {},
    onSkipProxyChange:    (Boolean) -> Unit                 = {},
    onRetryKeyword:       (String) -> Unit                  = {},
    onOpenHistory:        () -> Unit                        = {},
    onDeleteHistoryAll:   () -> Unit                        = {},
    onDeleteHistoryDay:   (String) -> Unit                  = {},
    onTestKeyword:        (keyword: String, proxy: String) -> Unit = { _, _ -> },
    isRecording:          Boolean                           = false,
    onStopRecording:      () -> Unit                        = {},
    onSearch:             (keyword: String, city: VietnamCity) -> Unit,
) {
    val isLoading = loadingStep.isNotEmpty()
    var showManual by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    if (manualResult != null && manualResult.second.isNotEmpty()) {
        KeywordResultsDialog(
            keyword   = manualResult.first,
            results   = manualResult.second,
            onDismiss = onManualResultDismiss,
        )
    }

    // Nếu có lỗi manual search → giữ manual mode
    LaunchedEffect(errorMessage) {
        if (errorMessage.isNotEmpty()) showManual = true
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (showHistory) {
            SearchHistoryScreen(
                entries = historyEntries,
                onBack = { showHistory = false },
                onDeleteAll = onDeleteHistoryAll,
                onDeleteDay = onDeleteHistoryDay,
            )
            return@Surface
        }
        AnimatedContent(
            targetState = showManual,
            transitionSpec = {
                if (targetState) {
                    slideInVertically { it } + fadeIn(tween(280)) togetherWith
                        fadeOut(tween(180))
                } else {
                    fadeIn(tween(280)) togetherWith
                        slideOutVertically { it } + fadeOut(tween(180))
                }
            },
            label = "search_mode",
        ) { isManual ->
            if (isManual) {
                ManualSearchContent(
                    isLoading        = isLoading,
                    loadingStep      = loadingStep,
                    errorMessage     = errorMessage,
                    initialKeyword   = initialKeyword,
                    skipProxy        = skipProxy,
                    isConnected      = isConnected,
                    onSkipProxyChange = onSkipProxyChange,
                    onSearch         = onSearch,
                    onBack           = { showManual = false },
                )
            } else {
                StandbyContent(
                    isLoading        = isLoading,
                    loadingStep      = loadingStep,
                    isConnected      = isConnected,
                    socketInfo       = socketInfo,
                    keywordBatch     = keywordBatch,
                    keywordResults   = keywordResults,
                    keywordImagePaths = keywordImagePaths,
                    onRetryKeyword   = onRetryKeyword,
                    onTestKeyword    = onTestKeyword,
                    isRecording      = isRecording,
                    onStopRecording  = onStopRecording,
                    onHistoryClick   = {
                        onOpenHistory()
                        showHistory = true
                    },
                    onManualClick    = { showManual = true },
                )
            }
        }
    }
}

// ── Standby screen ─────────────────────────────────────────────────────────────

@Composable
private fun StandbyContent(
    isLoading:      Boolean,
    loadingStep:    String,
    isConnected:    Boolean,
    socketInfo:     String,
    keywordBatch:   List<KeywordBatchItem>          = emptyList(),
    keywordResults: Map<String, List<SearchResult>> = emptyMap(),
    keywordImagePaths: Map<String, List<String>> = emptyMap(),
    onRetryKeyword: (String) -> Unit = {},
    onTestKeyword:  (keyword: String, proxy: String) -> Unit = { _, _ -> },
    isRecording:    Boolean  = false,
    onStopRecording: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    onManualClick:  () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.45f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ring_scale",
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue  = 0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1400),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring_alpha",
    )

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))

        // ── Top bar ────────────────────────────────────────────────────────
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "TopSearch",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onHistoryClick) {
                Icon(Icons.Default.History, contentDescription = "Lịch sử")
            }
            ConnectionBadge(isConnected)
        }

        AnimatedVisibility(
            visible = isRecording,
            enter   = expandVertically() + fadeIn(),
            exit    = shrinkVertically() + fadeOut(),
        ) {
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEF4444)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Đang ghi màn hình",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFEF4444),
                    )
                }
                TextButton(
                    onClick = onStopRecording,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        "Dừng ghi",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFEF4444),
                    )
                }
            }
        }

        Spacer(Modifier.weight(0.35f))

        // ── Pulse ring ─────────────────────────────────────────────────────
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(130.dp)) {
            if (isConnected && !isLoading) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .scale(ringScale)
                        .clip(CircleShape)
                        .background(Color(0xFF22C55E).copy(alpha = ringAlpha)),
                )
            }
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isLoading   -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            isConnected -> Color(0xFFDCFCE7)
                            else        -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(34.dp),
                        strokeWidth = 2.5.dp,
                    )
                } else {
                    Icon(
                        imageVector     = Icons.Default.Search,
                        contentDescription = null,
                        modifier        = Modifier.size(36.dp),
                        tint            = if (isConnected) Color(0xFF16A34A)
                                          else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // ── Status text ────────────────────────────────────────────────────
        Text(
            text = when {
                isLoading   -> loadingStep
                isConnected -> "Đang chờ keyword từ server..."
                else        -> "Đang kết nối lại..."
            },
            style      = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = when {
                isLoading   -> MaterialTheme.colorScheme.primary
                isConnected -> Color(0xFF16A34A)
                else        -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            },
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = when {
                isLoading   -> "WebView đang chụp kết quả Google..."
                isConnected -> "Server sẽ gửi keyword tự động khi có yêu cầu"
                else        -> "Kiểm tra kết nối mạng nếu mất quá lâu"
            },
            style     = MaterialTheme.typography.bodySmall,
            color     = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.42f),
            textAlign = TextAlign.Center,
        )

        if (keywordBatch.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            KeywordBatchPanel(keywordBatch, keywordResults, keywordImagePaths, onRetryKeyword)
        }

        Spacer(Modifier.weight(0.45f))

        // AnimatedVisibility(
        //     visible = socketInfo.isNotEmpty(),
        //     enter   = fadeIn() + expandVertically(),
        //     exit    = fadeOut() + shrinkVertically(),
        // ) {
        //     Card(
        //         modifier = Modifier
        //             .fillMaxWidth()
        //             .padding(bottom = 12.dp),
        //         colors = CardDefaults.cardColors(
        //             containerColor = MaterialTheme.colorScheme.primaryContainer,
        //         ),
        //         shape = RoundedCornerShape(14.dp),
        //     ) {
        //         Text(
        //             text       = socketInfo,
        //             color      = MaterialTheme.colorScheme.onPrimaryContainer,
        //             modifier   = Modifier.padding(14.dp),
        //             style      = MaterialTheme.typography.bodySmall,
        //             lineHeight = 19.sp,
        //         )
        //     }
        // }

        // ── Test keyword panel ─────────────────────────────────────────────
        TestKeywordPanel(isLoading = isLoading, onTestKeyword = onTestKeyword)

        Spacer(Modifier.height(8.dp))

        // ── Manual button ──────────────────────────────────────────────────
        OutlinedButton(
            onClick  = onManualClick,
            enabled  = !isLoading,
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Tìm kiếm thủ công", fontSize = 14.sp)
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── Test keyword panel ──────────────────────────────────────────────────────────

@Composable
private fun TestKeywordPanel(
    isLoading:    Boolean,
    onTestKeyword: (keyword: String, proxy: String) -> Unit,
) {
    val defaultProxy = "117.5.220.204:33978:lnjgv_itweb:dqzFqlTn"
    var expanded  by remember { mutableStateOf(false) }
    var keyword   by remember { mutableStateOf("") }
    var proxy     by remember { mutableStateOf(defaultProxy) }
    val keyboard  = LocalSoftwareKeyboardController.current

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Test keyword",
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier.weight(1f),
                    color      = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (expanded) "Thu gọn" else "Mở rộng",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut(),
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    OutlinedTextField(
                        value         = keyword,
                        onValueChange = { keyword = it },
                        enabled       = !isLoading,
                        placeholder   = { Text("Keyword cần test", fontSize = 13.sp) },
                        singleLine    = true,
                        shape         = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier      = Modifier.fillMaxWidth(),
                        textStyle     = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value         = proxy,
                        onValueChange = { proxy = it },
                        enabled       = !isLoading,
                        placeholder   = { Text("Proxy (tuỳ chọn, vd: host:port:user:pass)", fontSize = 12.sp) },
                        singleLine    = true,
                        shape         = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                        modifier      = Modifier.fillMaxWidth(),
                        textStyle     = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            keyboard?.hide()
                            val kw = keyword.trim()
                            if (kw.isNotBlank()) {
                                onTestKeyword(kw, proxy.trim())
                                keyword = ""
                                proxy   = defaultProxy
                            }
                        },
                        enabled  = !isLoading && keyword.isNotBlank(),
                        shape    = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Thêm vào queue", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ── Manual search screen ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualSearchContent(
    isLoading:        Boolean,
    loadingStep:      String,
    errorMessage:     String,
    initialKeyword:   String,
    skipProxy:        Boolean,
    isConnected:      Boolean,
    onSkipProxyChange: (Boolean) -> Unit,
    onSearch:         (keyword: String, city: VietnamCity) -> Unit,
    onBack:           () -> Unit,
) {
    var keyword      by remember(initialKeyword) { mutableStateOf(initialKeyword) }
    var selectedCity by remember { mutableStateOf(VIETNAM_CITIES[0]) }
    var dropdownOpen by remember { mutableStateOf(false) }
    val keyboard     = LocalSoftwareKeyboardController.current

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))

        // ── Top bar ────────────────────────────────────────────────────────
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = !isLoading) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
            }
            Text(
                "Tìm kiếm thủ công",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.weight(1f).padding(start = 4.dp),
            )
            ConnectionBadge(isConnected)
        }

        Spacer(Modifier.height(20.dp))

        // ── Keyword ────────────────────────────────────────────────────────
        OutlinedTextField(
            value         = keyword,
            onValueChange = { keyword = it },
            enabled       = !isLoading,
            placeholder   = { Text("Nhập keyword cần phân tích…") },
            leadingIcon   = {
                Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary)
            },
            singleLine    = true,
            shape         = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                if (keyword.isNotBlank()) onSearch(keyword, selectedCity)
            }),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))

        // ── City picker ────────────────────────────────────────────────────
        ExposedDropdownMenuBox(
            expanded         = dropdownOpen && !isLoading,
            onExpandedChange = { if (!isLoading) dropdownOpen = it },
        ) {
            OutlinedTextField(
                value         = selectedCity.label,
                onValueChange = {},
                readOnly      = true,
                enabled       = !isLoading,
                label         = { Text("Khu vực tìm kiếm") },
                leadingIcon   = {
                    Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(dropdownOpen) },
                shape         = RoundedCornerShape(16.dp),
                modifier      = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                colors        = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )
            ExposedDropdownMenu(
                expanded         = dropdownOpen,
                onDismissRequest = { dropdownOpen = false },
            ) {
                VIETNAM_CITIES.forEach { city ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                city.label,
                                fontWeight = if (city == selectedCity) FontWeight.SemiBold else FontWeight.Normal,
                                color      = if (city == selectedCity) MaterialTheme.colorScheme.primary
                                             else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick        = { selectedCity = city; dropdownOpen = false },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── Proxy toggle ───────────────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked         = skipProxy,
                onCheckedChange = onSkipProxyChange,
                enabled         = !isLoading,
            )
            Text(
                "Không dùng proxy (dùng mạng điện thoại trực tiếp)",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isLoading) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                        else MaterialTheme.colorScheme.onBackground,
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── Search button ──────────────────────────────────────────────────
        Button(
            onClick  = {
                keyboard?.hide()
                if (keyword.isNotBlank()) onSearch(keyword, selectedCity)
            },
            enabled  = !isLoading && keyword.isNotBlank(),
            shape    = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color       = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text(loadingStep, fontSize = 15.sp)
            } else {
                Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Bắt đầu tìm kiếm", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // ── Error ──────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = errorMessage.isNotEmpty(),
            enter   = fadeIn(),
            exit    = fadeOut(),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                colors   = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                shape    = RoundedCornerShape(12.dp),
            ) {
                Text(
                    errorMessage,
                    color    = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(14.dp),
                    style    = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.weight(1f))
    }
}

// ── Keyword batch panel ─────────────────────────────────────────────────────────

@Composable
private fun SearchHistoryScreen(
    entries: List<KeywordQueueStore.Entry>,
    onBack: () -> Unit,
    onDeleteAll: () -> Unit,
    onDeleteDay: (String) -> Unit,
) {
    val groups = remember(entries) {
        entries
            .sortedWith(compareByDescending<KeywordQueueStore.Entry> { it.queuedAt }.thenBy { it.keyword })
            .groupBy { it.queuedAt }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${entries.size} keyword",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = onDeleteAll,
                        enabled = entries.isNotEmpty(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Xóa tất cả")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(padding)
                .padding(horizontal = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                }
                Text(
                    "Lịch sử keyword",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Chưa có lịch sử",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    groups.forEach { (day, dayEntries) ->
                        HistoryDayGroup(day, dayEntries, onDeleteDay)
                        Spacer(Modifier.height(10.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun HistoryDayGroup(
    day: String,
    entries: List<KeywordQueueStore.Entry>,
    onDeleteDay: (String) -> Unit,
) {
    val done = entries.count { it.status == CheckStatus.DONE }
    val error = entries.count { it.status == CheckStatus.ERROR }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(day, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${entries.size} keyword · $done done · $error lỗi",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onDeleteDay(day) }) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Xóa ngày", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(6.dp))
            entries.forEachIndexed { index, item ->
                HistoryRow(item)
                if (index < entries.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: KeywordQueueStore.Entry) {
    val statusText = when (item.status) {
        CheckStatus.PENDING -> "Đang chờ"
        CheckStatus.IN_PROGRESS -> "Đang xử lý"
        CheckStatus.DONE -> "Hoàn thành"
        CheckStatus.ERROR -> item.errorMessage.ifBlank { "Lỗi chưa rõ" }
    }
    val statusColor = when (item.status) {
        CheckStatus.DONE -> Color(0xFF16A34A)
        CheckStatus.ERROR -> MaterialTheme.colorScheme.error
        CheckStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
        CheckStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.keyword,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.status.name,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "Country: ${item.country} · Retry: ${item.retryCount}" +
                if (item.completedAt.isNotBlank()) " · Time: ${item.completedAt}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Proxy: ${item.proxy.ifBlank { "N/A" }}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Request: ${item.requestId}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.66f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.status == CheckStatus.ERROR) {
            Text(
                statusText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun KeywordBatchPanel(
    items:          List<KeywordBatchItem>,
    keywordResults: Map<String, List<SearchResult>> = emptyMap(),
    keywordImagePaths: Map<String, List<String>> = emptyMap(),
    onRetryKeyword: (String) -> Unit = {},
) {
    val totalCount = items.size
    val doneCount = items.count { it.status == CheckStatus.DONE }
    val runningCount = items.count { it.status == CheckStatus.IN_PROGRESS }
    val errorCount = items.count { it.status == CheckStatus.ERROR }
    val progress = if (totalCount == 0) 0f else doneCount / totalCount.toFloat()

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Batch keywords",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val statusText = buildString {
                        append("$doneCount/$totalCount hoàn thành")
                        if (runningCount > 0) append(" · $runningCount đang chạy")
                        if (errorCount > 0) append(" · $errorCount lỗi")
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .width(92.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(99.dp)),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                )
            }

            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 236.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                items.forEachIndexed { index, item ->
                    KeywordBatchRow(
                        item = item,
                        results = keywordResults[item.requestId],
                        imagePaths = keywordImagePaths[item.requestId].orEmpty(),
                        onRetryKeyword = onRetryKeyword,
                    )
                    if (index < items.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 46.dp, end = 14.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeywordBatchRow(
    item: KeywordBatchItem,
    results: List<SearchResult>? = null,
    imagePaths: List<String> = emptyList(),
    onRetryKeyword: (String) -> Unit = {},
) {
    var showResults by remember { mutableStateOf(false) }
    val canOpenResults = item.status == CheckStatus.DONE
    val resultCount = results?.size ?: 0
    val subtitle = when (item.status) {
        CheckStatus.PENDING -> "Đang chờ"
        CheckStatus.IN_PROGRESS -> "Đang xử lý"
        CheckStatus.DONE -> "$resultCount kết quả"
        CheckStatus.ERROR -> item.errorMessage.ifBlank { "Lỗi chưa rõ" }
    }
    val contentColor = when (item.status) {
        CheckStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
        CheckStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
        CheckStatus.DONE -> MaterialTheme.colorScheme.onSurface
        CheckStatus.ERROR -> MaterialTheme.colorScheme.error
    }

    if (showResults) {
        KeywordResultsDialog(
            keyword   = item.keyword,
            results   = results ?: emptyList(),
            imagePaths = imagePaths,
            onDismiss = { showResults = false },
            onRetry   = {
                showResults = false
                onRetryKeyword(item.requestId)
            },
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canOpenResults) { showResults = true }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (item.status) {
                CheckStatus.PENDING -> Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
                )
                CheckStatus.IN_PROGRESS -> CircularProgressIndicator(
                    modifier    = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                CheckStatus.DONE -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint     = Color(0xFF22C55E),
                )
                CheckStatus.ERROR -> Icon(
                    Icons.Default.Cancel,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint     = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.keyword,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (item.status == CheckStatus.IN_PROGRESS) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Medium
                },
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (item.completedAt.isNotBlank()) {
                    "$subtitle · ${item.completedAt}"
                } else {
                    subtitle
                },
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.notes.isNotBlank()) {
                Text(
                    text = "⚠ ${item.notes}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFF59E0B),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.status == CheckStatus.ERROR) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { onRetryKeyword(item.requestId) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("Thử lại", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
@Composable
private fun KeywordResultsDialog(
    keyword:   String,
    results:   List<SearchResult>,
    imagePaths: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onRetry:   (() -> Unit)? = null,
) {
    val visibleResults = results.take(10)
    val imageBitmaps = remember(imagePaths) {
        imagePaths.mapNotNull { path ->
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 320.dp, max = 380.dp),
        shape = RoundedCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        title = {
            Column {
                Text(
                    text = keyword,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(if (results.isEmpty()) "Chưa có kết quả" else "${visibleResults.size}/${results.size} kết quả")
                        if (imageBitmaps.isNotEmpty()) append(" · ${imageBitmaps.size} ảnh")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Ảnh",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (imageBitmaps.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Không có ảnh",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            imageBitmaps.forEach { bitmap ->
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "Screenshot",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.FillWidth,
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Top",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                        if (results.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f))
                                    .padding(horizontal = 12.dp, vertical = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Chưa có kết quả",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            visibleResults.forEach { r ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            r.rank.toString(),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            r.domain.ifBlank { r.title },
                                            style      = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color      = MaterialTheme.colorScheme.onSurface,
                                            maxLines   = 1,
                                            overflow   = TextOverflow.Ellipsis,
                                        )
                                        if (r.url.isNotBlank()) {
                                            Text(
                                                r.url,
                                                style    = MaterialTheme.typography.labelSmall,
                                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text("Thử lại", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Đóng")
            }
        },
    )
}

// ── Shared ──────────────────────────────────────────────────────────────────────

@Composable
private fun ConnectionBadge(isConnected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isConnected) Color(0xFF22C55E) else Color(0xFFBDBDBD)),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text  = if (isConnected) "Kết nối" else "Chưa kết nối",
            style = MaterialTheme.typography.labelSmall,
            color = if (isConnected) Color(0xFF16A34A)
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
        )
    }
}

// ── Capture overlay bar ──────────────────────────────────────────────────────────

@Composable
fun CaptureOverlayBar(ip: String, modifier: Modifier = Modifier) {
    var timeText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val fmt = java.text.SimpleDateFormat("HH:mm dd/MM/yyyy", java.util.Locale.getDefault())
        while (true) {
            timeText = fmt.format(java.util.Date())
            kotlinx.coroutines.delay(1_000)
        }
    }

    val maskedIp = remember(ip) {
        val parts = ip.split(".")
        if (parts.size == 4) "${parts[0]}.***.${parts[3]}" else ip
    }

    // modifier chỉ dùng cho positioning (align) — background đặt trên inner Row để không bị ảnh hưởng
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text       = if (maskedIp.isNotBlank()) "IP: $maskedIp" else "",
                color      = Color.White,
                fontSize   = 12.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
            Text(
                text       = timeText,
                color      = Color.White,
                fontSize   = 12.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }
}
