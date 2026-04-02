package com.topsearch.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * @param nearParam    tên địa điểm cho Google near= param
 * @param lat / lng    tọa độ thực dùng cho UULE + spoof geolocation
 * @param proxyHostPort "host:port:user:pass" — proxy residential theo tỉnh (blank = trực tiếp)
 */
data class VietnamCity(
    val label:         String,
    val nearParam:     String,
    val lat:           Double = 0.0,
    val lng:           Double = 0.0,
    val proxyHostPort: String = "",
)

val VIETNAM_CITIES = listOf(
    VietnamCity("🌐 Toàn quốc",   "",                          0.0,      0.0),
    VietnamCity("🏙 Hà Nội",       "Ha Noi, Vietnam",           21.0285,  105.8542, com.topsearch.app.CityProxies.HN),
    VietnamCity("🌆 TP. HCM",      "Ho Chi Minh City, Vietnam", 10.8231,  106.6297, com.topsearch.app.CityProxies.HCM),
    VietnamCity("🌊 Đà Nẵng",      "Da Nang, Vietnam",          16.0544,  108.2022, com.topsearch.app.CityProxies.DN),
    VietnamCity("🌸 Huế",          "Hue, Vietnam",              16.4637,  107.5909),
    VietnamCity("🏖 Nha Trang",    "Nha Trang, Vietnam",        12.2388,  109.1967),
    VietnamCity("🏝 Phú Quốc",     "Phu Quoc, Vietnam",         10.2899,  103.9840),
    VietnamCity("🏔 Đà Lạt",       "Da Lat, Vietnam",           11.9404,  108.4583),
    VietnamCity("⚓ Hải Phòng",    "Hai Phong, Vietnam",        20.8449,  106.6881),
    VietnamCity("🏞 Cần Thơ",      "Can Tho, Vietnam",          10.0452,  105.7469),
    VietnamCity("🌿 Bình Dương",   "Binh Duong, Vietnam",       11.1673,  106.6669),
    VietnamCity("🏭 Đồng Nai",     "Dong Nai, Vietnam",         10.9452,  107.1351),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    loadingStep:    String = "",
    countdown:      Int    = 0,
    errorMessage:   String = "",
    initialKeyword: String = "",
    onSearch:       (keyword: String, city: VietnamCity) -> Unit,
) {
    val isLoading    = loadingStep.isNotEmpty()
    var keyword      by remember(initialKeyword) { mutableStateOf(initialKeyword) }
    var selectedCity by remember { mutableStateOf(VIETNAM_CITIES[0]) }
    var dropdownOpen by remember { mutableStateOf(false) }
    var useProxy     by remember { mutableStateOf(false) }
    val keyboard     = LocalSoftwareKeyboardController.current

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            // ── Logo ─────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        ))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Search, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("TopSearch", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
            Text("Top 10 kết quả tìm kiếm thực tế",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))

            Spacer(Modifier.height(36.dp))

            // ── Keyword ───────────────────────────────────────────────────
            OutlinedTextField(
                value         = keyword,
                onValueChange = { keyword = it },
                enabled       = !isLoading,
                placeholder   = { Text("Nhập keyword cần phân tích…") },
                leadingIcon   = {
                    Icon(Icons.Default.Search, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                },
                singleLine    = true,
                shape         = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    val city = if (useProxy) selectedCity else selectedCity.copy(proxyHostPort = "")
                    if (keyword.isNotBlank()) onSearch(keyword, city)
                }),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            // ── Location picker ───────────────────────────────────────────
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
                        Icon(Icons.Default.LocationOn, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
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
                                Text(city.label,
                                    fontWeight = if (city == selectedCity)
                                        FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (city == selectedCity)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface)
                            },
                            onClick = { selectedCity = city; dropdownOpen = false },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // ── Proxy toggle ───────────────────────────────────────────────
            Row(
                modifier     = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked         = useProxy,
                    onCheckedChange = { useProxy = it },
                    enabled         = !isLoading,
                )
                Text(
                    text  = "Bật proxy theo tỉnh",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isLoading)
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                    else
                        MaterialTheme.colorScheme.onBackground,
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Button ─────────────────────────────────────────────────────
            Button(
                onClick  = {
                    keyboard?.hide()
                    val city = if (useProxy) selectedCity
                               else selectedCity.copy(proxyHostPort = "")
                    if (keyword.isNotBlank()) onSearch(keyword, city)
                },
                enabled  = !isLoading && keyword.isNotBlank(),
                shape    = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text(loadingStep, fontSize = 15.sp)
                } else {
                    Icon(Icons.Default.Search, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Bắt đầu tìm kiếm", fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }

            // ── Error ──────────────────────────────────────────────────────
            AnimatedVisibility(visible = errorMessage.isNotEmpty(),
                enter = fadeIn(), exit = fadeOut()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    colors   = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(14.dp),
                        style    = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }
}
