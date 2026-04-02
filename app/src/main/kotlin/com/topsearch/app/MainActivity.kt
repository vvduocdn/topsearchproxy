package com.topsearch.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

        // Xin quyền GPS ngay khi mở app
        if (!LocationHelper.hasPermission(this)) {
            locationPermLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }

        setContent {
            TopSearchTheme {
                val state by viewModel.state.collectAsState()

                when (val s = state) {
                    is SearchState.Idle ->
                        SearchScreen(
                            onSearch = { kw, city -> viewModel.startSearch(kw, city) },
                        )

                    is SearchState.WebCapturing ->
                        WebCaptureScreen(
                            url           = s.url,
                            proxyHost     = s.proxyHost,
                            spoofLat      = s.spoofLat,
                            spoofLng      = s.spoofLng,
                            onCaptureDone = { path, jsResults ->
                                viewModel.onWebCaptureDone(s.keyword, path, jsResults)
                            },
                            onError       = { err ->
                                viewModel.onWebCaptureError(s.keyword, err)
                            },
                        )

                    is SearchState.Analyzing ->
                        SearchScreen(
                            loadingStep = "Đang phân tích kết quả (OCR)…",
                            onSearch    = { _, _ -> },
                        )

                    is SearchState.Done ->
                        ResultsScreen(
                            keyword        = s.keyword,
                            results        = s.results,
                            screenshotPath = s.screenshotPath,
                            city           = s.city,
                            onSearchAgain  = viewModel::reset,
                        )

                    is SearchState.Error ->
                        SearchScreen(
                            errorMessage   = s.message,
                            initialKeyword = s.keyword,
                            onSearch       = { kw, city -> viewModel.startSearch(kw, city) },
                        )
                }
            }
        }
    }
}
