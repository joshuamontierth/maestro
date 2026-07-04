package com.maestro.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maestro.app.face.EngineStatus
import com.maestro.app.face.FaceAnalyzer
import com.maestro.app.face.FaceSignals
import com.maestro.app.face.GestureConfig
import com.maestro.app.face.PageAction
import com.maestro.app.pdf.PdfController
import com.maestro.app.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

data class ReaderUiState(
    val hasDocument: Boolean = false,
    val fileName: String? = null,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val pageBitmap: ImageBitmap? = null,
    val detectionEnabled: Boolean = true,
    val status: EngineStatus = EngineStatus.NO_FACE,
    val isLoading: Boolean = false,
    val lastSignals: FaceSignals = FaceSignals(present = false)
)

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val pdfController = PdfController()
    private val docPrefs = app.getSharedPreferences("maestro_doc", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    val config: StateFlow<GestureConfig> = settingsStore.configFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, GestureConfig())

    // Read on the camera-analysis callback; kept in sync with the persisted config.
    @Volatile
    private var currentConfig: GestureConfig = GestureConfig()
    private var detectionInitialised = false

    private var renderWidthPx: Int = 0
    private var renderJob: Job? = null

    val faceAnalyzer = FaceAnalyzer(
        configProvider = { currentConfig },
        onResult = ::onEngineResult
    )

    init {
        viewModelScope.launch {
            settingsStore.configFlow.collect { cfg ->
                currentConfig = cfg
                if (!detectionInitialised) {
                    detectionInitialised = true
                    _uiState.value = _uiState.value.copy(detectionEnabled = cfg.enabledByDefault)
                }
            }
        }
        reopenLastDocument()
    }

    private fun reopenLastDocument() {
        val file = currentFile()
        if (!file.exists()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { pdfController.open(file) }.onSuccess {
                val savedPage = docPrefs.getInt(KEY_PAGE, 0)
                    .coerceIn(0, (pdfController.pageCount - 1).coerceAtLeast(0))
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        hasDocument = true,
                        fileName = docPrefs.getString(KEY_NAME, file.name),
                        pageCount = pdfController.pageCount,
                        pageIndex = savedPage
                    )
                    renderCurrentPage()
                }
            }
        }
    }

    fun setRenderWidth(px: Int) {
        if (px <= 0 || px == renderWidthPx) return
        renderWidthPx = px
        if (_uiState.value.hasDocument) renderCurrentPage()
    }

    fun importPdf(uri: Uri) {
        val app = getApplication<Application>()
        val displayName = queryDisplayName(app, uri) ?: "document.pdf"
        openInto(displayName) { output ->
            app.contentResolver.openInputStream(uri)?.use { it.copyTo(output) }
                ?: error("Could not open the selected file")
        }
    }

    /** Opens the bundled placeholder score (assets/sample_score.pdf). */
    fun openSample() {
        val app = getApplication<Application>()
        openInto("Sample Score") { output ->
            app.assets.open("sample_score.pdf").use { it.copyTo(output) }
        }
    }

    /** Copies a PDF into app storage via [writer], opens it, and renders page 1. */
    private fun openInto(displayName: String, writer: (OutputStream) -> Unit) {
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val target = currentFile()
            val result = runCatching {
                target.outputStream().use { writer(it) }
                pdfController.open(target)
            }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    docPrefs.edit().putString(KEY_NAME, displayName).putInt(KEY_PAGE, 0).apply()
                    _uiState.value = _uiState.value.copy(
                        hasDocument = true,
                        fileName = displayName,
                        pageCount = pdfController.pageCount,
                        pageIndex = 0,
                        isLoading = false
                    )
                    renderCurrentPage()
                }.onFailure {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
        }
    }

    fun nextPage() = goToPage(_uiState.value.pageIndex + 1)
    fun prevPage() = goToPage(_uiState.value.pageIndex - 1)

    fun goToPage(index: Int) {
        val state = _uiState.value
        if (!state.hasDocument) return
        val clamped = index.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
        if (clamped == state.pageIndex && state.pageBitmap != null) return
        _uiState.value = state.copy(pageIndex = clamped)
        docPrefs.edit().putInt(KEY_PAGE, clamped).apply()
        renderCurrentPage()
    }

    fun setDetectionEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(detectionEnabled = enabled)
    }

    fun saveConfig(config: GestureConfig) {
        viewModelScope.launch { settingsStore.save(config) }
    }

    private fun onEngineResult(status: EngineStatus, fired: PageAction?, signals: FaceSignals) {
        _uiState.value = _uiState.value.copy(status = status, lastSignals = signals)
        when (fired) {
            PageAction.NEXT -> nextPage()
            PageAction.PREVIOUS -> prevPage()
            null -> Unit
        }
    }

    private fun renderCurrentPage() {
        val width = renderWidthPx
        if (width <= 0 || !_uiState.value.hasDocument) return
        val index = _uiState.value.pageIndex
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.IO) {
            val bitmap = runCatching { pdfController.renderPage(index, width) }.getOrNull()
            val image: ImageBitmap? = bitmap?.asImageBitmap()
            withContext(Dispatchers.Main) {
                if (_uiState.value.pageIndex == index) {
                    _uiState.value = _uiState.value.copy(pageBitmap = image)
                }
            }
        }
    }

    private fun currentFile(): File = File(getApplication<Application>().filesDir, "current.pdf")

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
    }

    override fun onCleared() {
        super.onCleared()
        renderJob?.cancel()
        faceAnalyzer.close()
        pdfController.close()
    }

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_PAGE = "page"
    }
}
