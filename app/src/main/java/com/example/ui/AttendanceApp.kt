package com.example.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.verticalScroll
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import com.example.ui.theme.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.analyzer.FaceRecognitionAnalyzer
import com.example.model.AttendanceLog
import com.example.model.Student
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun AttendanceApp(viewModel: AttendanceViewModel) {
    val context = LocalContext.current
    val students by viewModel.registeredStudents.collectAsStateWithLifecycle()
    val logs by viewModel.attendanceLogs.collectAsStateWithLifecycle()
    val detectionState by viewModel.detectionState.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val lastStatusMessage by viewModel.lastStatusMessage.collectAsStateWithLifecycle()
    
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    
    var activeTab by remember { mutableIntStateOf(0) }
    
    // Automatically trigger permission check/request when camera tab is active
    LaunchedEffect(cameraPermissionState.status, activeTab) {
        if (activeTab == 0 && !cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }
    var isFrontCamera by remember { mutableStateOf(true) }
    
    // Enroll Face Form State
    var showEnrollDialog by remember { mutableStateOf(false) }
    var enrollNo by remember { mutableStateOf("") }
    var enrollName by remember { mutableStateOf("") }
    var enrollClass by remember { mutableStateOf("XI-ATPH") }
    var enrollDept by remember { mutableStateOf("Agribisnis Tanaman Pangan & Hortikultura") }
    var activeFaceEmbeddingForEnroll by remember { mutableStateOf<FloatArray?>(null) }
    var activeCroppedFaceBitmapForEnroll by remember { mutableStateOf<Bitmap?>(null) }

    // Settings States
    val gasUrl by viewModel.gasUrl.collectAsStateWithLifecycle()
    val threshold by viewModel.threshold.collectAsStateWithLifecycle()
    var editGasUrl by remember(gasUrl) { mutableStateOf(gasUrl) }

    // Directory Search & Filter States
    var studentSearchQuery by remember { mutableStateOf("") }
    var studentFilterClass by remember { mutableStateOf("SEMUA") } // "SEMUA", "ATPH", "ATU", "APHP"

    val greenAccent = Color(0xFF2E7D32) // Theme forest green for SMKPP Negeri Bima agriculturists
    val darkBg = Color(0xFF121212)

    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin_angle"
    )

    // Clear alert logic
    LaunchedEffect(lastStatusMessage) {
        if (lastStatusMessage != null) {
            kotlinx.coroutines.delay(4000)
            viewModel.clearStatus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Presensi Siswa",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            color = SleekOnBackground
                        )
                        Text(
                            text = "SI-AKSI • SMKPP NEGERI BIMA",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                            color = SleekPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SleekBackground,
                    titleContentColor = SleekOnBackground
                ),
                actions = {
                    if (viewModel.faceEngine.isUsingDemoFallback) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Text(
                                "DEMO MODE",
                                color = Color.White,
                                modifier = Modifier.padding(4.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Badge(
                            containerColor = SleekSecondary,
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Text(
                                "TFLITE ACTIVE",
                                color = SleekPrimary,
                                modifier = Modifier.padding(4.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = SleekBackground,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets.navigationBars,
                modifier = Modifier.border(1.dp, SleekDivider, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Face, contentDescription = "Presensi") },
                    label = { Text("Beranda", fontSize = 10.sp, fontWeight = FontWeight.Medium) },
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SleekPrimary,
                        selectedTextColor = SleekPrimary,
                        indicatorColor = SleekSecondary,
                        unselectedIconColor = SleekOnBackground.copy(alpha = 0.5f),
                        unselectedTextColor = SleekOnBackground.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.testTag("tab_camera")
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Person, contentDescription = "Siswa") },
                    label = { Text("Siswa", fontSize = 10.sp, fontWeight = FontWeight.Medium) },
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SleekPrimary,
                        selectedTextColor = SleekPrimary,
                        indicatorColor = SleekSecondary,
                        unselectedIconColor = SleekOnBackground.copy(alpha = 0.5f),
                        unselectedTextColor = SleekOnBackground.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.testTag("tab_students")
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Riwayat & SINK", fontSize = 10.sp, fontWeight = FontWeight.Medium) },
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SleekPrimary,
                        selectedTextColor = SleekPrimary,
                        indicatorColor = SleekSecondary,
                        unselectedIconColor = SleekOnBackground.copy(alpha = 0.5f),
                        unselectedTextColor = SleekOnBackground.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.testTag("tab_settings")
                )
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeTab) {
                0 -> {
                    // TAB 0: Active Camera Attendance Preview
                    if (cameraPermissionState.status.isGranted) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // Camera preview binding keyed on isFrontCamera to trigger rebuilds on switch
                            key(isFrontCamera) {
                                CameraPreviewContainer(
                                    isFrontCamera = isFrontCamera,
                                    viewModel = viewModel
                                )
                            }

                            // Dynamic box vector drawing on overlay Canvas
                            FaceBoundingBoxOverlay(detectionState = detectionState)

                            // Top left GAS status indicator (biometric connected state)
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(16.dp)
                                    .background(SleekSurface.copy(alpha = 0.85f), RoundedCornerShape(100.dp))
                                    .border(1.dp, SleekDivider, RoundedCornerShape(100.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (gasUrl.isNotBlank()) GreenSuccess else Color.Gray, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (gasUrl.isNotBlank()) "GAS Endpoint: Connected" else "GAS: Disconnected",
                                    color = SleekOnBackground,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Interface buttons inside camera view (e.g. Flip camera)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(16.dp)
                                ) {
                                FloatingActionButton(
                                    onClick = { isFrontCamera = !isFrontCamera },
                                    containerColor = SleekSurface.copy(alpha = 0.8f),
                                    contentColor = SleekPrimary,
                                    modifier = Modifier.testTag("flip_camera_button")
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Putar Kamera")
                                }
                            }

                            // Center high-tech scanning indicator (only when scanning/no-face)
                            if (detectionState is DetectionState.NoFace) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(240.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Circular rotating outline
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .rotate(rotationAngle)
                                            .border(
                                                width = 3.dp,
                                                brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                                                    listOf(SleekPrimary, Color.Transparent)
                                                ),
                                                shape = CircleShape
                                            )
                                    )
                                    // Static soft outer circle
                                    Box(
                                        modifier = Modifier
                                            .size(210.dp)
                                            .border(1.dp, SleekPrimary.copy(alpha = 0.3f), CircleShape)
                                    )
                                    
                                    Icon(
                                        Icons.Default.Face,
                                        contentDescription = null,
                                        modifier = Modifier.size(56.dp),
                                        tint = SleekPrimary.copy(alpha = 0.6f)
                                    )
                                }
                            }

                            // Live feedback indicator panel at the bottom center of camera
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .background(SleekSurface, shape = RoundedCornerShape(24.dp))
                                    .border(1.dp, SleekDivider, RoundedCornerShape(24.dp))
                                    .padding(16.dp)
                            ) {
                                when (val state = detectionState) {
                                    is DetectionState.NoFace -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = SleekPrimary
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                "Mendeteksi wajah...",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = SleekOnBackground
                                            )
                                        }
                                        Text(
                                            "Posisikan wajah Anda tepat di depan kamera perekaman harian.",
                                            fontSize = 12.sp,
                                            color = SleekOnBackground.copy(alpha = 0.6f),
                                            modifier = Modifier.padding(top = 6.dp)
                                        )
                                    }
                                    is DetectionState.FaceDetected -> {
                                        val matchPercent = (state.matchScore * 100).coerceIn(0f, 100f)
                                        val student = state.matchedStudent

                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Profile image in target frame with dual rings and check indicator
                                                Box(modifier = Modifier.size(68.dp)) {
                                                    Image(
                                                        bitmap = state.croppedFace.asImageBitmap(),
                                                        contentDescription = "Deteksi Wajah",
                                                        modifier = Modifier
                                                            .size(64.dp)
                                                            .clip(RoundedCornerShape(16.dp))
                                                            .border(2.dp, if (student != null) GreenSuccess else SleekOutline, RoundedCornerShape(16.dp)),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                    if (student != null) {
                                                        Box(
                                                            modifier = Modifier
                                                                .align(Alignment.BottomEnd)
                                                                .offset(x = 4.dp, y = 4.dp)
                                                                .size(20.dp)
                                                                .background(GreenSuccess, CircleShape)
                                                                .border(2.dp, SleekSurface, CircleShape),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                Icons.Default.Check,
                                                                contentDescription = null,
                                                                tint = Color.White,
                                                                modifier = Modifier.size(12.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                
                                                Spacer(modifier = Modifier.width(16.dp))

                                                Column(modifier = Modifier.weight(1f)) {
                                                    if (student != null) {
                                                        Text(
                                                            text = student.name.uppercase(),
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 16.sp,
                                                            color = SleekOnBackground
                                                        )
                                                        Text(
                                                            text = "NIS: ${student.studentNo}",
                                                            fontSize = 12.sp,
                                                            color = SleekOnBackground.copy(alpha = 0.6f)
                                                        )
                                                        
                                                        Row(
                                                            modifier = Modifier.padding(top = 4.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .background(SleekSecondary, RoundedCornerShape(4.dp))
                                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                                            ) {
                                                                Text(
                                                                    text = student.studentClass,
                                                                    color = SleekPrimary,
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            }
                                                            Box(
                                                                modifier = Modifier
                                                                    .background(SleekSecondary, RoundedCornerShape(4.dp))
                                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                                            ) {
                                                                Text(
                                                                    text = if (student.department.contains("Tanaman")) "XI ATPH" else "AGRIBISNIS",
                                                                    color = SleekPrimary,
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            }
                                                        }
                                                    } else {
                                                        Text(
                                                            text = "Wajah Tidak Dikenali",
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 16.sp,
                                                            color = MaterialTheme.colorScheme.error
                                                        )
                                                        Text(
                                                            text = "Skor tertinggi: %.1f%% (Batas threshold: %.1f%%)".format(matchPercent, threshold * 100),
                                                            fontSize = 12.sp,
                                                            color = SleekOnBackground.copy(alpha = 0.7f)
                                                        )
                                                    }
                                                }
                                                
                                                // Match score badge
                                                Column(
                                                    horizontalAlignment = Alignment.End,
                                                    modifier = Modifier.padding(start = 8.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .background(SleekOnPrimary, RoundedCornerShape(100.dp))
                                                            .border(1.dp, SleekPrimary.copy(alpha = 0.3f), RoundedCornerShape(100.dp))
                                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                                    ) {
                                                        Text(
                                                            text = "COS_SIM: %.4f".format(state.matchScore),
                                                            color = SleekPrimary,
                                                            fontSize = 10.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }

                                            if (student != null) {
                                                Spacer(modifier = Modifier.height(12.dp))
                                                Button(
                                                    onClick = {
                                                        viewModel.submitAttendanceLocalAndRemote(student, state.croppedFace)
                                                    },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(48.dp)
                                                        .testTag("kirim_presensi_button"),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = SleekPrimary,
                                                        contentColor = SleekOnPrimary
                                                    ),
                                                    shape = RoundedCornerShape(14.dp)
                                                ) {
                                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = "KIRIM DATA PRESENSI",
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 12.sp,
                                                        letterSpacing = 1.sp
                                                    )
                                                }
                                            } else {
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Button(
                                                    onClick = {
                                                        activeFaceEmbeddingForEnroll = state.embedding
                                                        activeCroppedFaceBitmapForEnroll = state.croppedFace
                                                        enrollNo = ""
                                                        enrollName = ""
                                                        showEnrollDialog = true
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = SleekSecondary),
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(36.dp)
                                                        .testTag("quick_register_button")
                                                ) {
                                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Daftarkan Wajah Baru", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Request camera permission screen
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Face,
                                contentDescription = null,
                                modifier = Modifier.size(100.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Akses Kamera Diperlukan",
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "SI-AKSI memerlukan kamera untuk proses on-device face recognition untuk absensi harian siswa SMKPP Negeri Bima.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 16.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { cameraPermissionState.launchPermissionRequest() },
                                modifier = Modifier.testTag("grant_permission_button")
                            ) {
                                Text("Berikan Izin Kamera")
                            }
                        }
                    }
                }
                1 -> {
                    // TAB 1: Registered Students Directory List
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Direktori Siswa Terdaftar",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp,
                                color = SleekOnBackground
                            )
                            Box(
                                modifier = Modifier
                                    .background(SleekSecondary, RoundedCornerShape(100.dp))
                                    .border(1.dp, SleekPrimary.copy(alpha = 0.3f), RoundedCornerShape(100.dp))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${students.size} Siswa",
                                    color = SleekPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        
                        Text(
                            text = "Daftar wajah siswa yang terdaftar secara lokal untuk proses pencocokan face recognition di perangkat ini.",
                            fontSize = 12.sp,
                            color = SleekOnBackground.copy(alpha = 0.6f),
                            modifier = Modifier.padding(vertical = 6.dp)
                        )

                        // 🔍 Modern search input field
                        OutlinedTextField(
                            value = studentSearchQuery,
                            onValueChange = { studentSearchQuery = it },
                            label = { Text("Cari Siswa (Nama / NIS)") },
                            placeholder = { Text("Masukkan nama atau nomor induk...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = SleekPrimary) },
                            trailingIcon = {
                                if (studentSearchQuery.isNotEmpty()) {
                                    IconButton(onClick = { studentSearchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = SleekOnBackground.copy(alpha = 0.6f))
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .testTag("student_search_input"),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SleekPrimary,
                                unfocusedBorderColor = SleekOutline,
                                focusedLabelColor = SleekPrimary,
                                unfocusedLabelColor = SleekOutline,
                                focusedTextColor = SleekOnBackground,
                                unfocusedTextColor = SleekOnBackground
                            )
                        )

                        // 🌱 Category filter Chips matching SMKPP Departments
                        val filters = listOf("SEMUA", "ATPH", "ATU", "APHP")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            filters.forEach { filterName ->
                                val isSelected = studentFilterClass == filterName
                                val bg = if (isSelected) SleekPrimary.copy(alpha = 0.15f) else SleekSurface
                                val borderCol = if (isSelected) SleekPrimary else SleekDivider
                                val textCol = if (isSelected) SleekPrimary else SleekOnBackground.copy(alpha = 0.6f)

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(bg, RoundedCornerShape(100.dp))
                                        .border(1.dp, borderCol, RoundedCornerShape(100.dp))
                                        .clickable { studentFilterClass = filterName }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = filterName,
                                        color = textCol,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }

                        val filteredStudents = remember(students, studentSearchQuery, studentFilterClass) {
                            students.filter { student ->
                                val matchesSearch = student.name.contains(studentSearchQuery, ignoreCase = true) || 
                                                    student.studentNo.contains(studentSearchQuery, ignoreCase = true)
                                val matchesFilter = if (studentFilterClass == "SEMUA") {
                                    true
                                } else {
                                    student.studentClass.contains(studentFilterClass, ignoreCase = true)
                                }
                                matchesSearch && matchesFilter
                            }
                        }

                        if (students.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Belum ada data siswa. Posisikan wajah di tab Kamera lalu tekan tombol tambah.", color = SleekOutline, fontSize = 13.sp)
                            }
                        } else if (filteredStudents.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Tidak ada hasil pencocokan hasil pencarian.", color = SleekOutline, fontSize = 13.sp)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filteredStudents) { student ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, SleekDivider, RoundedCornerShape(16.dp)),
                                        colors = CardDefaults.cardColors(containerColor = SleekSurface),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(46.dp)
                                                    .background(SleekSecondary, RoundedCornerShape(23.dp)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Default.Person, contentDescription = null, tint = SleekPrimary)
                                            }
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = student.name.uppercase(),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    color = SleekOnBackground
                                                )
                                                Text(
                                                    text = "NIS: ${student.studentNo} • Kelas: ${student.studentClass}",
                                                    fontSize = 12.sp,
                                                    color = SleekOnBackground.copy(alpha = 0.6f),
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                                Text(
                                                    text = student.department,
                                                    fontSize = 11.sp,
                                                    color = SleekOnBackground.copy(alpha = 0.5f),
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = { viewModel.removeStudent(student) },
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .testTag("delete_student_${student.studentNo}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Hapus",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    // TAB 2: Historical Logs and Google Apps Script Integrations
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()) // Allow scrolling setelan plus historical logs list below it
                    ) {
                        // SECTION: Settings Config
                        Text(
                            text = "Konfigurasi SI-AKSI (GAS Web App)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = SleekOnBackground,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        OutlinedTextField(
                            value = editGasUrl,
                            onValueChange = { editGasUrl = it },
                            label = { Text("Google Apps Script URL Web App") },
                            placeholder = { Text("https://script.google.com/macros/s/...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("gas_url_input"),
                            trailingIcon = {
                                IconButton(
                                    onClick = { viewModel.updateGasUrl(editGasUrl) },
                                    modifier = Modifier.testTag("save_gas_url")
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = "Simpan", tint = SleekPrimary)
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SleekPrimary,
                                unfocusedBorderColor = SleekOutline,
                                focusedLabelColor = SleekPrimary,
                                unfocusedLabelColor = SleekOutline,
                                focusedTextColor = SleekOnBackground,
                                unfocusedTextColor = SleekOnBackground
                            )
                        )
                        Text(
                            text = "Pastikan Web App diset Execute as: Me dan Who has access: Anyone.",
                            fontSize = 11.sp,
                            color = SleekOnBackground.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                        )

                        // SECTION: Threshold Config
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Batas Threshold Cosine Similarity",
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                color = SleekOnBackground
                            )
                            Text(
                                text = "%.2f".format(threshold),
                                fontWeight = FontWeight.Bold,
                                color = SleekPrimary
                            )
                        }
                        
                        Slider(
                            value = threshold,
                            onValueChange = { viewModel.updateThreshold(it) },
                            valueRange = 0.5f..0.95f,
                            colors = SliderDefaults.colors(
                                thumbColor = SleekPrimary,
                                activeTrackColor = SleekPrimary,
                                inactiveTrackColor = SleekSecondary
                            ),
                            modifier = Modifier.testTag("threshold_slider")
                        )
                        Text(
                            text = "Nilai lebih tinggi memperketat verifikasi agar tidak tertukar. Default: 0.75",
                            fontSize = 11.sp,
                            color = SleekOnBackground.copy(alpha = 0.5f),
                            modifier = Modifier.padding(bottom = 24.dp)
                        )

                        // SECTION: Historical Log Logs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Riwayat Log Absensi Lokal",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = SleekOnBackground
                            )
                            if (logs.isNotEmpty()) {
                                TextButton(
                                    onClick = { viewModel.clearAllLogs() },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Bersihkan", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (logs.isEmpty()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Belum ada riwayat absensi yang tercatat.", color = Color.Gray, fontSize = 13.sp)
                                }
                            }
                        } else {
                            // Render list inline manually due to outer scrollable column
                            logs.forEach { log ->
                                LogRowItem(log = log, greenAccent = greenAccent)
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            // Top alert overlay for network states or feedback
            AnimatedVisibility(
                visible = lastStatusMessage != null,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                lastStatusMessage?.let { msg ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (msg.contains("gagal") || msg.contains("Gagal")) MaterialTheme.colorScheme.errorContainer else Color(0xFFE8F5E9)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isErr = msg.contains("gagal") || msg.contains("Gagal")
                            Icon(
                                imageVector = if (isErr) Icons.Default.Warning else Icons.Default.Info,
                                contentDescription = null,
                                tint = if (isErr) MaterialTheme.colorScheme.error else greenAccent
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = msg,
                                fontSize = 13.sp,
                                color = if (isErr) MaterialTheme.colorScheme.onErrorContainer else Color(0xFF1B5E20),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Active Networking uploading loading overlay
            if (isSending) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f))
                        .clickable(enabled = false) {}, // absorb clicks
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .padding(24.dp)
                            .border(1.dp, SleekDivider, RoundedCornerShape(24.dp)),
                        colors = CardDefaults.cardColors(containerColor = SleekSurface),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = SleekPrimary)
                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = "Mengunggah foto bukti & log ke SI-AKSI...",
                                fontWeight = FontWeight.SemiBold,
                                color = SleekOnBackground,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            // ENROLL / STUDENT REGISTRATION DIALOG SCREEN
            if (showEnrollDialog && activeFaceEmbeddingForEnroll != null) {
                AlertDialog(
                    onDismissRequest = { showEnrollDialog = false },
                    containerColor = SleekSurface,
                    titleContentColor = SleekOnBackground,
                    textContentColor = SleekOnBackground,
                    title = { 
                        Text(
                            text = "Daftarkan Siswa Baru", 
                            fontWeight = FontWeight.Bold, 
                            fontSize = 18.sp,
                            letterSpacing = 0.5.sp
                        ) 
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            // Avatar thumbnail preview inside dialog
                            activeCroppedFaceBitmapForEnroll?.let { bmp ->
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "Preview Wajah",
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(CircleShape)
                                            .border(2.dp, SleekPrimary, CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = enrollNo,
                                onValueChange = { enrollNo = it },
                                label = { Text("Nomor Induk Siswa (NIS)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("enroll_no_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = SleekPrimary,
                                    unfocusedBorderColor = SleekOutline,
                                    focusedLabelColor = SleekPrimary,
                                    unfocusedLabelColor = SleekOutline,
                                    focusedTextColor = SleekOnBackground,
                                    unfocusedTextColor = SleekOnBackground
                                )
                            )

                            OutlinedTextField(
                                value = enrollName,
                                onValueChange = { enrollName = it },
                                label = { Text("Nama Lengkap Siswa") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("enroll_name_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = SleekPrimary,
                                    unfocusedBorderColor = SleekOutline,
                                    focusedLabelColor = SleekPrimary,
                                    unfocusedLabelColor = SleekOutline,
                                    focusedTextColor = SleekOnBackground,
                                    unfocusedTextColor = SleekOnBackground
                                )
                            )

                            // Dropdown selections representing agriculture departments
                            Text(
                                text = "Kelas & Program Keahlian (SMKPP Negeri Bima)", 
                                fontSize = 11.sp, 
                                color = SleekOnBackground.copy(alpha = 0.5f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            
                            val classes = listOf("X-ATPH", "XI-ATPH", "XII-ATPH", "X-ATU", "XI-ATU", "XII-ATU", "X-APHP", "XI-APHP", "XII-APHP")
                            var expandedClass by remember { mutableStateOf(false) }
                            
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { expandedClass = true },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SleekPrimary),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SleekOutline),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("class_dropdown_btn")
                                ) {
                                    Text("Kelas: $enrollClass", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                DropdownMenu(
                                    expanded = expandedClass,
                                    onDismissRequest = { expandedClass = false },
                                    modifier = Modifier.background(SleekSurface).border(1.dp, SleekDivider, RoundedCornerShape(8.dp))
                                ) {
                                    classes.forEach { opt ->
                                        DropdownMenuItem(
                                            text = { Text(opt, color = SleekOnBackground, fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                                            onClick = {
                                                enrollClass = opt
                                                enrollDept = when {
                                                    opt.contains("ATPH") -> "Agribisnis Tanaman Pangan & Hortikultura"
                                                    opt.contains("ATU") -> "Agribisnis Ternak Unggas"
                                                    else -> "Agribisnis Pengolahan Hasil Pertanian"
                                                }
                                                expandedClass = false
                                            }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = enrollDept,
                                onValueChange = { enrollDept = it },
                                label = { Text("Kompetensi Keahlian (Jurusan)") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = SleekPrimary,
                                    unfocusedBorderColor = SleekOutline,
                                    focusedLabelColor = SleekPrimary,
                                    unfocusedLabelColor = SleekOutline,
                                    focusedTextColor = SleekOnBackground,
                                    unfocusedTextColor = SleekOnBackground
                                )
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (enrollNo.isNotBlank() && enrollName.isNotBlank()) {
                                    viewModel.enrollStudent(
                                        no = enrollNo,
                                        name = enrollName,
                                        sClass = enrollClass,
                                        dept = enrollDept,
                                        embedding = activeFaceEmbeddingForEnroll!!
                                    )
                                    showEnrollDialog = false
                                } else {
                                    Log.w("Dialog", "Nama dan NIS are mandatory.")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary, contentColor = SleekOnPrimary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("dialog_submit_enroll")
                        ) {
                            Text("Simpan Siswa", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showEnrollDialog = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = SleekPrimary)
                        ) {
                            Text("Batal", fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun LogRowItem(log: AttendanceLog, greenAccent: Color) {
    val dateStr = remember(log.timestamp) {
        val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        sdf.format(Date(log.timestamp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SleekDivider, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SleekSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail indicator of the JPEG Base64 face that was submitted
            log.photoBase64?.let { base64 ->
                val bitmapDec = remember(base64) {
                    try {
                        val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (bitmapDec != null) {
                    Image(
                        bitmap = bitmapDec.asImageBitmap(),
                        contentDescription = "Bukti Perekaman",
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, SleekDivider, RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .background(SleekSecondary, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = SleekPrimary, modifier = Modifier.size(22.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.name.uppercase(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = SleekOnBackground
                )
                Text(
                    text = "NIS: ${log.studentNo} • Kelas: ${log.studentClass} • $dateStr",
                    fontSize = 11.sp,
                    color = SleekOnBackground.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (log.syncErrorMessage != null && log.status == "GAGAL") {
                    Text(
                        text = "Error: ${log.syncErrorMessage}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Sync Status Indicator Badge matching Sleek theme pill style
            when (log.status) {
                "TERKIRIM" -> {
                    Box(
                        modifier = Modifier
                            .background(GreenSuccess.copy(alpha = 0.15f), RoundedCornerShape(100.dp))
                            .border(1.dp, GreenSuccess.copy(alpha = 0.3f), RoundedCornerShape(100.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, contentDescription = "SINK", tint = GreenSuccess, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("GAS", color = GreenSuccess, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }
                    }
                }
                "PENDING" -> {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = SleekPrimary)
                }
                else -> {
                    // GAGAL
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f), RoundedCornerShape(100.dp))
                            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f), RoundedCornerShape(100.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = "Gagal SINK", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Gagal", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreviewContainer(
    isFrontCamera: Boolean,
    viewModel: AttendanceViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    DisposableEffect(isFrontCamera, lifecycleOwner) {
        onDispose {
            try {
                if (cameraProviderFuture.isDone) {
                    cameraProviderFuture.get().unbindAll()
                }
            } catch (e: Exception) {
                Log.e("CameraPreviewContainer", "Error unbinding on dispose", e)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val executor = ContextCompat.getMainExecutor(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                }

                val cameraSelector = if (isFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().apply {
                        setAnalyzer(
                            executor,
                            FaceRecognitionAnalyzer(
                                isFrontCamera = isFrontCamera,
                                listener = object : FaceRecognitionAnalyzer.FaceDetectionListener {
                                    override fun onFaceDetected(
                                        originalFrame: Bitmap,
                                        croppedFace: Bitmap,
                                        boundingBox: Rect,
                                        frameWidth: Int,
                                        frameHeight: Int
                                    ) {
                                        // Feeds coordinates of crop into embedding calculations
                                        viewModel.processFaceDetection(
                                            originalFrame = originalFrame,
                                            croppedFace = croppedFace,
                                            boundingBox = boundingBox,
                                            embedding = viewModel.faceEngine.getFaceEmbedding(croppedFace)
                                        )
                                    }

                                    override fun onNoFaceDetected() {
                                        viewModel.setNoFace()
                                    }

                                    override fun onError(e: Exception) {
                                        Log.e("CameraPreviewContainer", "Analyzer throw", e)
                                    }
                                }
                            )
                        )
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        analysis
                    )
                } catch (e: Exception) {
                    Log.e("CameraPreviewContainer", "Camera Binding crash", e)
                }
            }, executor)
            
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun FaceBoundingBoxOverlay(detectionState: DetectionState) {
    // Keep track of last known face rect and frame dimensions to prevent flickering/snapping to zero
    var lastRect by remember { mutableStateOf<Rect?>(null) }
    var lastFrameSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    
    val hasFace = detectionState is DetectionState.FaceDetected
    
    if (detectionState is DetectionState.FaceDetected) {
        lastRect = detectionState.boundingBox
        lastFrameSize = Pair(detectionState.originalFrame.width, detectionState.originalFrame.height)
    }

    // Animate box opacity smoothly when a face appears or disappears
    val opacity by animateFloatAsState(
        targetValue = if (hasFace) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "BoxOpacity"
    )

    // Base color transition
    val isMatch = (detectionState as? DetectionState.FaceDetected)?.matchedStudent != null
    val targetColor = if (hasFace) {
        if (isMatch) Color(0xFF00E676) else Color(0xFFFF1744) // Green for matches, red for error/unmatched faces
    } else {
        Color(0xFF00E676) // Default color when faded out
    }

    val boxColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "BoxColor"
    )

    // Infinite heartbeat pulse animation to emphasize active tracking state
    val infiniteTransition = rememberInfiniteTransition(label = "PulseGlow")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseProgress"
    )

    // Sweep scan line position progress (vertical loop)
    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ScanProgress"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val containerW = size.width
        val containerH = size.height

        val currentRect = lastRect
        val currentFrameSize = lastFrameSize

        if (opacity > 0f && currentRect != null && currentFrameSize != null) {
            val frameW = currentFrameSize.first
            val frameH = currentFrameSize.second

            val scaleX = containerW / frameW
            val scaleY = containerH / frameH

            val left = currentRect.left * scaleX
            val top = currentRect.top * scaleY
            val right = currentRect.right * scaleX
            val bottom = currentRect.bottom * scaleY

            val width = right - left
            val height = bottom - top

            val centerX = left + width / 2f
            val centerY = top + height / 2f

            // Apply slight interactive pulse to the bounding box dimension
            val pulsedWidth = width * pulseProgress
            val pulsedHeight = height * pulseProgress

            val drawLeft = centerX - pulsedWidth / 2f
            val drawTop = centerY - pulsedHeight / 2f
            val drawRight = centerX + pulsedWidth / 2f
            val drawBottom = centerY + pulsedHeight / 2f

            val strokeWidthPx = 3.dp.toPx()
            val finalColor = boxColor.copy(alpha = opacity)

            // Draw primary bounding box frame
            drawRect(
                color = finalColor,
                topLeft = Offset(drawLeft, drawTop),
                size = Size(pulsedWidth, pulsedHeight),
                style = Stroke(width = strokeWidthPx)
            )

            // Draw glowing halo shadow offset border
            drawRect(
                color = finalColor.copy(alpha = opacity * 0.15f),
                topLeft = Offset(drawLeft - 6.dp.toPx(), drawTop - 6.dp.toPx()),
                size = Size(pulsedWidth + 12.dp.toPx(), pulsedHeight + 12.dp.toPx()),
                style = Stroke(width = 2.dp.toPx())
            )

            // Tech scanning light bar animation sweep
            val scanY = drawTop + pulsedHeight * scanProgress
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        finalColor.copy(alpha = opacity * 0.35f),
                        Color.Transparent
                    ),
                    startY = scanY - 14.dp.toPx(),
                    endY = scanY + 14.dp.toPx()
                ),
                topLeft = Offset(drawLeft, scanY - 14.dp.toPx()),
                size = Size(pulsedWidth, 28.dp.toPx())
            )
            // Bright scanner guideline core
            drawLine(
                color = finalColor,
                start = Offset(drawLeft, scanY),
                end = Offset(drawRight, scanY),
                strokeWidth = 2.dp.toPx()
            )

            // Draw sci-fi biometric brackets notches
            val notchLen = 22.dp.toPx()
            val notchStroke = 5.dp.toPx()

            // Top-Left Corner Notch
            drawLine(finalColor, Offset(drawLeft - notchStroke/2f, drawTop), Offset(drawLeft + notchLen, drawTop), notchStroke)
            drawLine(finalColor, Offset(drawLeft, drawTop - notchStroke/2f), Offset(drawLeft, drawTop + notchLen), notchStroke)

            // Top-Right Corner Notch
            drawLine(finalColor, Offset(drawRight + notchStroke/2f, drawTop), Offset(drawRight - notchLen, drawTop), notchStroke)
            drawLine(finalColor, Offset(drawRight, drawTop - notchStroke/2f), Offset(drawRight, drawTop + notchLen), notchStroke)

            // Bottom-Left Corner Notch
            drawLine(finalColor, Offset(drawLeft - notchStroke/2f, drawBottom), Offset(drawLeft + notchLen, drawBottom), notchStroke)
            drawLine(finalColor, Offset(drawLeft, drawBottom + notchStroke/2f), Offset(drawLeft, drawBottom - notchLen), notchStroke)

            // Bottom-Right Corner Notch
            drawLine(finalColor, Offset(drawRight + notchStroke/2f, drawBottom), Offset(drawRight - notchLen, drawBottom), notchStroke)
            drawLine(finalColor, Offset(drawRight, drawBottom + notchStroke/2f), Offset(drawRight, drawBottom - notchLen), notchStroke)
        }
    }
}

// Custom scroll state constructor for older compose API levels where verticalScroll needs state
@Composable
fun rememberScrollState(): androidx.compose.foundation.ScrollState {
    return androidx.compose.foundation.rememberScrollState()
}
