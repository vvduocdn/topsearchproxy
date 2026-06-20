package com.topsearch.app

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.topsearch.app.ui.ResultsScreen
import com.topsearch.app.ui.SearchScreen
import com.topsearch.app.ui.WebCaptureScreen
import com.topsearch.app.ui.theme.TopSearchTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SearchViewModel by viewModels()

    // Xin quyền Location khi app mở lần đầu
    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Kết quả xử lý trong LocationHelper — không cần làm gì thêm */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Xin quyền GPS ngay khi mở app
        if (!LocationHelper.hasPermission(this)) {
            locationPermLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }

        // Khởi động service WebSocket — nhận keyword từ server
        SearchService.start(this)

        setContent {
            TopSearchTheme {
                val state        by viewModel.state.collectAsState()
                val skipProxy    by viewModel.skipProxy.collectAsState()
                val socketInfo   by viewModel.socketInfo.collectAsState()
                val isConnected  by viewModel.isConnected.collectAsState()

                var viewingResults by remember { mutableStateOf(false) }

                // Reset viewing flag when leaving Done state (new search or reset)
                LaunchedEffect(state) {
                    if (state !is SearchState.Done) viewingResults = false
                }

                when (val s = state) {
                    is SearchState.Idle ->
                        SearchScreen(
                            skipProxy         = skipProxy,
                            socketInfo        = socketInfo,
                            isConnected       = isConnected,
                            onSkipProxyChange = viewModel::setSkipProxy,
                            onSearch          = { kw, city -> viewModel.startSearch(kw, city) },
                        )

                    is SearchState.WebCapturing ->
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

                    is SearchState.Analyzing ->
                        SearchScreen(
                            loadingStep       = "Đang phân tích kết quả (OCR)…",
                            skipProxy         = skipProxy,
                            socketInfo        = socketInfo,
                            isConnected       = isConnected,
                            onSkipProxyChange = viewModel::setSkipProxy,
                            onSearch          = { _, _ -> },
                        )

                    is SearchState.Done ->
                        if (viewingResults) {
                            ResultsScreen(
                                keyword        = s.keyword,
                                results        = s.results,
                                screenshotPath = s.screenshotPath,
                                city           = s.city,
                                proxyIp        = s.proxyIp,
                                socketInfo     = s.socketInfo,
                                onBack         = { viewingResults = false },
                                onSearchAgain  = viewModel::reset,
                            )
                        } else {
                            SearchScreen(
                                skipProxy         = skipProxy,
                                socketInfo        = socketInfo,
                                isConnected       = isConnected,
                                lastKeyword       = s.keyword,
                                lastResultCount   = s.results.size,
                                lastResultInfo    = s.socketInfo,
                                onViewResults     = { viewingResults = true },
                                onSkipProxyChange = viewModel::setSkipProxy,
                                onSearch          = { kw, city -> viewModel.startSearch(kw, city) },
                            )
                        }

                    is SearchState.Error ->
                        SearchScreen(
                            errorMessage      = s.message,
                            initialKeyword    = s.keyword,
                            skipProxy         = skipProxy,
                            socketInfo        = socketInfo,
                            isConnected       = isConnected,
                            onSkipProxyChange = viewModel::setSkipProxy,
                            onSearch          = { kw, city -> viewModel.startSearch(kw, city) },
                        )
                }
            }
        }
    }
}
