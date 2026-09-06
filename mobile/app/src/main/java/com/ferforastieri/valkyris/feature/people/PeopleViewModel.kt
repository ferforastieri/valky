package com.ferforastieri.valkyris.feature.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.action.MobileActionGate
import com.ferforastieri.valkyris.core.model.PersonLocation
import com.ferforastieri.valkyris.core.model.TrackedPerson
import com.ferforastieri.valkyris.core.model.TrackedPlace
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val repository: ValkyrisRepository,
    private val actionGate: MobileActionGate,
) : ViewModel() {
    val people = repository.people
    val me = repository.me
    val places = repository.places
    private val _history = MutableStateFlow<List<PersonLocation>>(emptyList())
    val history = _history.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init { refresh() }
    fun refresh() = viewModelScope.launch {
        runCatching { repository.refreshUsers() }
        runCatching { repository.refreshMe() }
        runCatching { repository.refreshPlaces() }
    }
    fun history(person: TrackedPerson) = viewModelScope.launch { _history.value = runCatching { repository.api.userHistory(person.id) }.getOrDefault(emptyList()) }
    fun createPerson(person: TrackedPerson, done: (Boolean) -> Unit) = action(done) { repository.createPerson(person) }
    fun updatePerson(person: TrackedPerson, done: (Boolean) -> Unit) = action(done) { repository.updatePerson(person.id, person) }
    fun deletePerson(person: TrackedPerson, done: (Boolean) -> Unit = {}) = action(done) { repository.deletePerson(person.id) }
    fun updateMe(person: TrackedPerson, done: (Boolean) -> Unit) = action(done) { repository.updateMe(person) }
    fun updateUser(person: TrackedPerson, done: (Boolean) -> Unit) = action(done) { repository.updateUser(person.id, person) }
    fun createPlace(place: TrackedPlace, done: (Boolean) -> Unit) = action(done) { repository.createPlace(place) }
    fun updatePlace(place: TrackedPlace, done: (Boolean) -> Unit) = action(done) { repository.updatePlace(place.id, place) }
    fun deletePlace(place: TrackedPlace, done: (Boolean) -> Unit = {}) = action(done) { repository.deletePlace(place.id) }

    private fun action(done: (Boolean) -> Unit, block: suspend () -> Unit) {
        if (!actionGate.tryAcquire()) return
        _busy.value = true
        viewModelScope.launch {
            try { val result = runCatching { block() }; if (result.isSuccess) refresh(); done(result.isSuccess) }
            finally { _busy.value = false; actionGate.release() }
        }
    }
}
