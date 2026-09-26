package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.entities.StudentEntity
import com.muslimedu.attendance.data.remote.ApiService
import com.muslimedu.attendance.data.remote.dto.GateSmsTemplatesData
import com.muslimedu.attendance.data.remote.dto.GateSmsTemplatesRequest
import com.muslimedu.attendance.data.remote.dto.GateSmsTemplatesUpdateRequest
import com.muslimedu.attendance.data.remote.extractApiErrorMessage
import com.muslimedu.attendance.data.repository.StudentRepository
import com.muslimedu.attendance.data.repository.isMissingEndpoint
import com.muslimedu.attendance.security.AuditLogger
import com.muslimedu.attendance.util.SmsTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/** How many of this device's students a text can reach. */
data class ParentNumberStats(val total: Int, val withNumber: Int, val notSent: Int, val refused: Int)

data class ParentSmsUiState(
    val loading: Boolean = true,
    /** Why the messages couldn't be loaded; null once they are. */
    val loadError: String? = null,
    val inTemplate: String = "",
    val outTemplate: String = "",
    /** The Coming In text for a late scan; null when the server has no late messages yet. */
    val lateTemplate: String? = null,
    /** What the server has now - Save is only offered when the text differs. */
    val savedIn: String = "",
    val savedOut: String = "",
    val savedLate: String? = null,
    val defaultIn: String = SmsTemplate.DEFAULT_IN,
    val defaultOut: String = SmsTemplate.DEFAULT_OUT,
    val defaultLate: String = SmsTemplate.DEFAULT_LATE,
    val maxLength: Int = 320,
    val schoolName: String? = null,
    /** The platform's SMS switch (superadmin, web). Null until loaded. */
    val smsEnabled: Boolean? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    val savedNotice: String? = null,
    /** A real student for the preview when there is one. */
    val sampleName: String = "Juan Dela Cruz",
    val sampleCode: String = "2026-00123",
    val stats: ParentNumberStats = ParentNumberStats(0, 0, 0, 0),
) {
    val changed: Boolean
        get() = inTemplate.trim() != savedIn || outTemplate.trim() != savedOut || lateTemplate?.trim() != savedLate
}

/**
 * Admin > Parent SMS: the school's wording for the text a parent gets when
 * their child scans Coming In or Going Out. The wording lives on the school
 * server (the server sends the texts), so this screen needs a connection;
 * the preview is rendered here with the server's own substitution rules
 * ([SmsTemplate]).
 */
@HiltViewModel
class ParentSmsViewModel @Inject constructor(
    private val apiService: ApiService,
    private val studentRepository: StudentRepository,
    private val auditLogger: AuditLogger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ParentSmsUiState())
    val uiState: StateFlow<ParentSmsUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            loadStats()
            _uiState.update { it.copy(loading = true, loadError = null, savedNotice = null, saveError = null) }
            val result = call { apiService.adminGateSmsTemplates(GateSmsTemplatesRequest()) }
            result.fold(
                onSuccess = { data -> _uiState.update { withData(it, data).copy(loading = false) } },
                onFailure = { e -> _uiState.update { it.copy(loading = false, loadError = e.message) } },
            )
        }
    }

    fun onInChange(text: String) = _uiState.update { it.copy(inTemplate = text.take(it.maxLength), savedNotice = null, saveError = null) }

    fun onOutChange(text: String) = _uiState.update { it.copy(outTemplate = text.take(it.maxLength), savedNotice = null, saveError = null) }

    fun onLateChange(text: String) = _uiState.update { it.copy(lateTemplate = text.take(it.maxLength), savedNotice = null, saveError = null) }

    fun resetIn() = onInChange(_uiState.value.defaultIn)

    fun resetOut() = onOutChange(_uiState.value.defaultOut)

    fun resetLate() = onLateChange(_uiState.value.defaultLate)

    fun save() {
        val state = _uiState.value
        if (state.saving || !state.changed) return
        val problem = listOfNotNull("Coming In" to state.inTemplate, "Going Out" to state.outTemplate, state.lateTemplate?.let { "Late" to it })
            .firstNotNullOfOrNull { (label, text) -> validate(label, text) }
        if (problem != null) {
            _uiState.update { it.copy(saveError = problem) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, saveError = null, savedNotice = null) }
            val result = call {
                apiService.adminGateSmsTemplatesUpdate(
                    GateSmsTemplatesUpdateRequest(state.inTemplate.trim(), state.outTemplate.trim(), state.lateTemplate?.trim()),
                )
            }
            result.fold(
                onSuccess = { data ->
                    auditLogger.log(
                        action = AuditLogger.ACTION_PARENT_SMS_MESSAGES_SET,
                        details = "in=\"${data.inTemplate}\", out=\"${data.outTemplate}\", late=\"${data.lateTemplate}\"",
                    )
                    _uiState.update { withData(it, data).copy(saving = false, savedNotice = "Saved - the next scans use these messages.") }
                },
                onFailure = { e -> _uiState.update { it.copy(saving = false, saveError = e.message) } },
            )
        }
    }

    /** The same rules the server applies, checked here first so the admin sees them without a round trip. */
    private fun validate(label: String, text: String): String? = when {
        text.isBlank() -> null // blank = back to the default
        !text.contains("{student}") -> "The $label message must include {student} so parents know whose child it is."
        text.trim().length > _uiState.value.maxLength -> "The $label message is too long (max ${_uiState.value.maxLength} characters)."
        else -> null
    }

    private fun withData(state: ParentSmsUiState, data: GateSmsTemplatesData): ParentSmsUiState {
        val defaultIn = data.defaultIn ?: SmsTemplate.DEFAULT_IN
        val defaultOut = data.defaultOut ?: SmsTemplate.DEFAULT_OUT
        val inText = data.inTemplate ?: defaultIn
        val outText = data.outTemplate ?: defaultOut
        val lateText = data.lateTemplate ?: data.defaultLate
        return state.copy(
            loadError = null,
            inTemplate = inText,
            outTemplate = outText,
            lateTemplate = lateText,
            savedIn = inText,
            savedOut = outText,
            savedLate = lateText,
            defaultIn = defaultIn,
            defaultOut = defaultOut,
            defaultLate = data.defaultLate ?: SmsTemplate.DEFAULT_LATE,
            maxLength = data.maxLength ?: state.maxLength,
            schoolName = data.schoolName,
            smsEnabled = data.smsEnabled,
        )
    }

    private suspend fun loadStats() {
        val students = studentRepository.getAll()
        val sample = students.firstOrNull { it.parentPhone != null } ?: students.firstOrNull()
        _uiState.update {
            it.copy(
                sampleName = sample?.name ?: it.sampleName,
                sampleCode = sample?.code ?: it.sampleCode,
                stats = ParentNumberStats(
                    total = students.size,
                    withNumber = students.count { s -> s.parentPhone != null },
                    notSent = students.count { s -> s.phoneSyncStatus == StudentEntity.RFID_PENDING },
                    refused = students.count { s -> s.phoneSyncStatus == StudentEntity.RFID_FAILED },
                ),
            )
        }
    }

    private suspend fun call(block: suspend () -> com.muslimedu.attendance.data.remote.dto.ApiEnvelope<GateSmsTemplatesData>): Result<GateSmsTemplatesData> =
        try {
            val response = block()
            val data = response.data
            if (response.success && data != null) Result.success(data) else Result.failure(Exception(response.message ?: "The server refused it"))
        } catch (e: HttpException) {
            val message = e.extractApiErrorMessage()
            Result.failure(
                Exception(
                    when {
                        isMissingEndpoint(e.code()) ->
                            "The school server doesn't have parent SMS messages yet - upload the latest server patch (sms-gateway-patch.zip)."
                        e.code() == 401 -> "Session expired - sign out and sign in again in Sync & Account."
                        else -> message ?: "Server error (${e.code()})"
                    },
                ),
            )
        } catch (e: IOException) {
            Result.failure(Exception("No connection. The messages are kept on the school server - connect to the internet to see or change them."))
        }
}
