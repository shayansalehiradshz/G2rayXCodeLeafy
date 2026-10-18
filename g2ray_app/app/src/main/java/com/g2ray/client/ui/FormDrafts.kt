package com.g2ray.client.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.g2ray.client.model.G2RayProfile
import com.g2ray.client.parser.VlessUriParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Drafts survive rotation without putting credentials or large imports in saved-instance bundles. */
internal class FormDraftViewModel : ViewModel() {
    private val texts = mutableMapOf<String, MutableState<String>>()
    val imports = mutableMapOf<String, ProfileImportDraft>()
    fun text(key: String, initial: String): MutableState<String> = texts.getOrPut(key) { mutableStateOf(initial) }
    fun parse(draft: ProfileImportDraft) {
        if (draft.parsing.value || draft.importing.value) return
        val source = draft.inputText.value
        draft.parsing.value = true
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.Default) { VlessUriParser.parseAllProfiles(source) }
                draft.results.value = results
                draft.selection.value = results.mapIndexed { index, result -> index to result.isSuccess }.toMap()
                draft.errors.value = results.mapNotNull { it.exceptionOrNull()?.message }
                draft.status.value = if (results.isEmpty()) "No supported configs found. Paste or scan a VLESS config or this project's all-config QR." else ""
            } finally { draft.parsing.value = false }
        }
    }
}

internal class ProfileImportDraft(accountId: String) {
    var targetAccountId = mutableStateOf(accountId)
    var inputText = mutableStateOf("")
    var results = mutableStateOf<List<Result<G2RayProfile>>>(emptyList())
    var selection = mutableStateOf<Map<Int, Boolean>>(emptyMap())
    var status = mutableStateOf("")
    var errors = mutableStateOf<List<String>>(emptyList())
    var importing = mutableStateOf(false)
    var parsing = mutableStateOf(false)
    var importedAccountId = mutableStateOf<String?>(null)
}
