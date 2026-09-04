package com.ferforastieri.valkyris.feature.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.database.RuleEntity
import com.ferforastieri.valkyris.core.model.Rule
import com.ferforastieri.valkyris.core.model.Camera
import com.ferforastieri.valkyris.core.model.DetectorKind
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel class RulesViewModel @Inject constructor(private val repository:ValkyrisRepository):ViewModel(){val rules=repository.cachedRules().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList<RuleEntity>());val cameras=repository.cameras;private val _detectors=MutableStateFlow<List<DetectorKind>>(emptyList());val detectors=_detectors.asStateFlow();init{refresh();viewModelScope.launch{runCatching{repository.refreshCameras()};_detectors.value=runCatching{repository.api.detectors()}.getOrDefault(emptyList())}};fun refresh(){viewModelScope.launch{runCatching{repository.refreshRules()}}};fun create(rule:Rule){viewModelScope.launch{runCatching{repository.createRule(rule)};refresh()}}}
