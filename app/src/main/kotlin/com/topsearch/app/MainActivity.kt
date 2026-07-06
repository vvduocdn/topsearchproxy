package com.topsearch.app

import android.Manifest
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import com.topsearch.app.ui.CaptureOverlayBar
import com.topsearch.app.ui.SearchScreen
import com.topsearch.app.ui.WebCaptureScreen
import com.topsearch.app.ui.theme.TopSearchTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SearchViewModel by viewModels()

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    private val projectionLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val pm = getSystemService(MediaProjectionManager::class.java)
            val projection = pm.getMediaProjection(result.resultCode, result.data!!)
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    SearchBridge.mediaProjection = null
                }
            }, null)
            SearchBridge.mediaProjection = projection
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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

        // Xin quyền ghi màn hình — cần trước khi batch đầu tiên được nhận.
        if (SearchBridge.mediaProjection == null) {
            val pm = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(pm.createScreenCaptureIntent())
        }
        viewModel.checkPendingQueue()

        setContent {
            TopSearchTheme {
                val state              by viewModel.state.collectAsState()
                val skipProxy          by viewModel.skipProxy.collectAsState()
                val socketInfo         by viewModel.socketInfo.collectAsState()
                val isConnected        by viewModel.isConnected.collectAsState()
                val isRecording        by viewModel.isRecording.collectAsState()
                val captureIp          by viewModel.captureIp.collectAsState()
                val keywordBatch       by viewModel.keywordBatch.collectAsState()
                val keywordResults     by viewModel.keywordResults.collectAsState()
                val keywordImagePaths  by viewModel.keywordImagePaths.collectAsState()
                val historyEntries     by viewModel.historyEntries.collectAsState()
                val pendingQueuePrompt by viewModel.pendingQueuePrompt.collectAsState()

                Box(Modifier.fillMaxSize()) {
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
                            isRecording       = isRecording,
                            keywordBatch      = keywordBatch,
                            keywordResults    = keywordResults,
                            keywordImagePaths = keywordImagePaths,
                            historyEntries    = historyEntries,
                            onSkipProxyChange = viewModel::setSkipProxy,
                            onRetryKeyword    = viewModel::retryBatchKeyword,
                            onOpenHistory     = viewModel::openHistory,
                            onDeleteHistoryAll = viewModel::deleteHistoryAll,
                            onDeleteHistoryDay = viewModel::deleteHistoryDay,
                            onTestKeyword     = { kw, proxy -> viewModel.addTestKeyword(kw, proxy) },
                            onStopRecording   = viewModel::stopRecording,
                            onSearch          = { kw, city -> viewModel.startSearch(kw, city) },
                        )

                    is SearchState.WebCapturing ->
                        key(s.captureSeq) {
                            WebCaptureScreen(
                                url           = s.url,
                                keyword       = s.keyword,
                                proxyHost     = s.proxyHost,
                                publicIp      = s.proxyIp,
                                spoofLat      = s.spoofLat,
                                spoofLng      = s.spoofLng,
                                onCaptureDone = { paths, jsResults, detectedCity, checkedAt ->
                                    viewModel.onWebCaptureDone(s.keyword, paths, jsResults, detectedCity, checkedAt)
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
                            isRecording       = isRecording,
                            keywordBatch      = keywordBatch,
                            keywordResults    = keywordResults,
                            keywordImagePaths = keywordImagePaths,
                            historyEntries    = historyEntries,
                            onSkipProxyChange = viewModel::setSkipProxy,
                            onRetryKeyword    = viewModel::retryBatchKeyword,
                            onOpenHistory     = viewModel::openHistory,
                            onDeleteHistoryAll = viewModel::deleteHistoryAll,
                            onDeleteHistoryDay = viewModel::deleteHistoryDay,
                            onTestKeyword     = { kw, proxy -> viewModel.addTestKeyword(kw, proxy) },
                            onStopRecording   = viewModel::stopRecording,
                            onSearch          = { _, _ -> },
                        )

                    is SearchState.Done ->
                        SearchScreen(
                            skipProxy             = skipProxy,
                            socketInfo            = socketInfo,
                            isConnected           = isConnected,
                            isRecording           = isRecording,
                            keywordBatch          = keywordBatch,
                            keywordResults        = keywordResults,
                            keywordImagePaths     = keywordImagePaths,
                            historyEntries        = historyEntries,
                            manualResult          = if (s.socketInfo.isBlank() && s.results.isNotEmpty())
                                                        s.keyword to s.results else null,
                            onManualResultDismiss = viewModel::reset,
                            onSkipProxyChange     = viewModel::setSkipProxy,
                            onRetryKeyword        = viewModel::retryBatchKeyword,
                            onOpenHistory         = viewModel::openHistory,
                            onDeleteHistoryAll    = viewModel::deleteHistoryAll,
                            onDeleteHistoryDay    = viewModel::deleteHistoryDay,
                            onTestKeyword         = { kw, proxy -> viewModel.addTestKeyword(kw, proxy) },
                            onStopRecording       = viewModel::stopRecording,
                            onSearch              = { kw, city -> viewModel.startSearch(kw, city) },
                        )

                    is SearchState.Error ->
                        SearchScreen(
                            errorMessage      = s.message,
                            initialKeyword    = s.keyword,
                            skipProxy         = skipProxy,
                            socketInfo        = socketInfo,
                            isConnected       = isConnected,
                            isRecording       = isRecording,
                            keywordBatch      = keywordBatch,
                            keywordResults    = keywordResults,
                            keywordImagePaths = keywordImagePaths,
                            historyEntries    = historyEntries,
                            onSkipProxyChange = viewModel::setSkipProxy,
                            onRetryKeyword    = viewModel::retryBatchKeyword,
                            onOpenHistory     = viewModel::openHistory,
                            onDeleteHistoryAll = viewModel::deleteHistoryAll,
                            onDeleteHistoryDay = viewModel::deleteHistoryDay,
                            onTestKeyword     = { kw, proxy -> viewModel.addTestKeyword(kw, proxy) },
                            onStopRecording   = viewModel::stopRecording,
                            onSearch          = { kw, city -> viewModel.startSearch(kw, city) },
                        )
                }

                if (isRecording) {
                    CaptureOverlayBar(
                        ip       = captureIp,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                } // Box
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
