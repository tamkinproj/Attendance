package com.muslimedu.attendance.viewmodel

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.dao.AttendanceDao
import com.muslimedu.attendance.data.db.dao.StudentDao
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed class ExportState {
    data object Idle : ExportState()
    data object Exporting : ExportState()
    data class Success(val uri: Uri, val rowCount: Int) : ExportState()
    data class Failed(val reason: String) : ExportState()
}

@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attendanceDao: AttendanceDao,
    private val studentDao: StudentDao,
) : ViewModel() {

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    fun export() {
        viewModelScope.launch {
            _state.value = ExportState.Exporting
            try {
                val rows = attendanceDao.getAll()
                val namesById = studentDao.getAll().associate { it.studentId to it.name }

                val exportDir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
                val file = File(exportDir, "attendance_${System.currentTimeMillis()}.csv")
                file.bufferedWriter().use { writer ->
                    writer.appendLine(
                        "student_id,student_name,scan_date,check_in_time,status,verified_by_rfid," +
                            "verified_by_face,face_match_score,manual_override,sync_status",
                    )
                    rows.forEach { row ->
                        writer.appendLine(
                            listOf(
                                row.studentId,
                                csvEscape(namesById[row.studentId] ?: ""),
                                row.scanDate,
                                row.checkInTime,
                                row.status,
                                row.verifiedByRfid,
                                row.verifiedByFace,
                                row.faceMatchScore ?: "",
                                row.manualOverride,
                                row.syncStatus,
                            ).joinToString(","),
                        )
                    }
                }

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                _state.value = ExportState.Success(uri, rows.size)
            } catch (e: Exception) {
                _state.value = ExportState.Failed(e.message ?: "Export failed")
            }
        }
    }

    fun reset() {
        _state.value = ExportState.Idle
    }

    private fun csvEscape(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
