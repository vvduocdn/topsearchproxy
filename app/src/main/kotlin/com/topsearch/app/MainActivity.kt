package com.topsearch.app

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.topsearch.app.ui.SearchScreen
import com.topsearch.app.ui.WebCaptureScreen
import com.topsearch.app.ui.theme.TopSearchTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SearchViewModel by viewModels()

    // Xin quyền Location khi app mở lần đầu.
    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Kết quả xử lý trong LocationHelper, không cần làm thêm. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Xin quyền GPS ngay khi mở app.
        if (!LocationHelper.hasPermission(this)) {
            locationPermLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }

        // Khởi động service WebSocket để nhận keyword từ server.
        SearchService.start(this)
        viewModel.checkPendingQueue()

        setContent {
            TopSearchTheme {
                val state              by viewModel.state.collectAsState()
                val skipProxy          by viewModel.skipProxy.collectAsState()
                val socketInfo         by viewModel.socketInfo.collectAsState()
                val isConnected        by viewModel.isConnected.collectAsState()
                val keywordBatch       by viewModel.keywordBatch.collectAsState()
                val keywordResults     by viewModel.keywordResults.collectAsState()
                val historyEntries     by viewModel.historyEntries.collectAsState()
                val pendingQueuePrompt by viewModel.pendingQueuePrompt.collectAsState()

                if (pendingQueuePrompt) {
                    PendingQueueDialog(
                        onResume = { viewModel.resumePendingQueue() },
                        onDismiss = { viewModel.dismissPendingPrompt() },
                    )
                }

                when (val s = state) {
                    is SearchState.Idle ->
                        SearchScreen(
                            skipProxy         = skipProxy,
                            socketInfo        = socketInfo,
                            isConnected       = isConnected,
                            keywordBatch      = keywordBatch,
                            keywordResults    = keywordResults,
                            historyEntries    = historyEntries,
                            onSkipProxyChange = viewModel::setSkipProxy,
                            onRetryKeyword    = viewModel::retryBatchKeyword,
                            onOpenHistory     = viewModel::openHistory,
                            onDeleteHistoryAll = viewModel::deleteHistoryAll,
                            onDeleteHistoryDay = viewModel::deleteHistoryDay,
                            onSearch          = { kw, city -> viewModel.startSearch(kw, city) },
                        )

                    is SearchState.WebCapturing ->
                        key(s.keyword) {
                            WebCaptureScreen(
                                url           = s.url,
                                keyword       = s.keyword,
                                proxyHost     = s.proxyHost,
                                spoofLat      = s.spoofLat,
                                spoofLng      = s.spoofLng,
                                onCaptureDone = { paths, jsResults, detectedCity ->
                                    viewModel.onWebCaptureDone(s.keyword, paths, jsResults, detectedCity)
                                },
                                onError       = { err ->
                                    viewModel.onWebCaptureError(s.keyword, err)
                                },
                            )
                        }

                    is SearchState.Analyzing ->
                        SearchScreen(
                            loadingStep       = "Đang phân tích kết quả (OCR)...",
                            skipProxy         = skipProxy,
                            socketInfo        = socketInfo,
                            isConnected       = isConnected,
                            keywordBatch      = keywordBatch,
                            keywordResults    = keywordResults,
                            historyEntries    = historyEntries,
                            onSkipProxyChange = viewModel::setSkipProxy,
                            onRetryKeyword    = viewModel::retryBatchKeyword,
                            onOpenHistory     = viewModel::openHistory,
                            onDeleteHistoryAll = viewModel::deleteHistoryAll,
                            onDeleteHistoryDay = viewModel::deleteHistoryDay,
                            onSearch          = { _, _ -> },
                        )

                    is SearchState.Done ->
                        SearchScreen(
                            skipProxy             = skipProxy,
                            socketInfo            = socketInfo,
                            isConnected           = isConnected,
                            keywordBatch          = keywordBatch,
                            keywordResults        = keywordResults,
                            historyEntries        = historyEntries,
                            manualResult          = if (s.socketInfo.isBlank() && s.results.isNotEmpty())
                                                        s.keyword to s.results else null,
                            onManualResultDismiss = viewModel::reset,
                            onSkipProxyChange     = viewModel::setSkipProxy,
                            onRetryKeyword        = viewModel::retryBatchKeyword,
                            onOpenHistory         = viewModel::openHistory,
                            onDeleteHistoryAll    = viewModel::deleteHistoryAll,
                            onDeleteHistoryDay    = viewModel::deleteHistoryDay,
                            onSearch              = { kw, city -> viewModel.startSearch(kw, city) },
                        )

                    is SearchState.Error ->
                        SearchScreen(
                            errorMessage      = s.message,
                            initialKeyword    = s.keyword,
                            skipProxy         = skipProxy,
                            socketInfo        = socketInfo,
                            isConnected       = isConnected,
                            keywordBatch      = keywordBatch,
                            keywordResults    = keywordResults,
                            historyEntries    = historyEntries,
                            onSkipProxyChange = viewModel::setSkipProxy,
                            onRetryKeyword    = viewModel::retryBatchKeyword,
                            onOpenHistory     = viewModel::openHistory,
                            onDeleteHistoryAll = viewModel::deleteHistoryAll,
                            onDeleteHistoryDay = viewModel::deleteHistoryDay,
                            onSearch          = { kw, city -> viewModel.startSearch(kw, city) },
                        )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun PendingQueueDialog(
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Có keyword chưa hoàn thành",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Text(
                    "App tìm thấy keyword hôm nay chưa xử lý xong. Bạn có thể tiếp tục queue hoặc bỏ qua và xoá dữ liệu chờ.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Bỏ qua")
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = onResume,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Tiếp tục")
                    }
                }
            }
        }
    }
}
