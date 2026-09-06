package com.ferforastieri.valkyris.core.network

import com.ferforastieri.valkyris.core.model.Camera
import com.ferforastieri.valkyris.core.model.CreateCameraRequest
import com.ferforastieri.valkyris.core.model.Rule
import com.ferforastieri.valkyris.core.model.ValkyrisEvent
import com.ferforastieri.valkyris.core.model.TrackedPerson
import com.ferforastieri.valkyris.core.model.TrackedPlace
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// This repository intentionally keeps no persistent/offline cache. Every refresh
// reads the current data from the Valkyris API.
@Singleton
class ValkyrisRepository @Inject constructor(
    val api: ValkyrisApi,
) {
    private val _cameras = MutableStateFlow<List<Camera>>(emptyList())
    val cameras = _cameras.asStateFlow()
    private val _events = MutableStateFlow<List<ValkyrisEvent>>(emptyList())
    val events = _events.asStateFlow()
    private val _rules = MutableStateFlow<List<Rule>>(emptyList())
    val rules = _rules.asStateFlow()
    private val _people = MutableStateFlow<List<TrackedPerson>>(emptyList())
    val people = _people.asStateFlow()
    private val _me = MutableStateFlow<TrackedPerson?>(null)
    val me = _me.asStateFlow()
    private val _places = MutableStateFlow<List<TrackedPlace>>(emptyList())
    val places = _places.asStateFlow()

    suspend fun refreshCameras(): List<Camera> {
        return api.cameras().also { _cameras.value = it }
    }

    suspend fun createCamera(input: CreateCameraRequest): Camera {
        return api.createCamera(input).also { camera ->
            _cameras.update { current -> (current + camera).distinctBy(Camera::id) }
        }
    }

    suspend fun updateCamera(id: String, input: CreateCameraRequest): Camera {
        return api.updateCamera(id, input).also { camera ->
            _cameras.update { current -> current.map { if (it.id == id) camera else it } }
        }
    }

    suspend fun deleteCamera(id: String) {
        api.deleteCamera(id)
        _cameras.update { current -> current.filterNot { it.id == id } }
    }

    suspend fun refreshEvents(): List<ValkyrisEvent> {
        return api.events().also { _events.value = it }
    }

    suspend fun refreshRules(): List<Rule> {
        return api.rules().also { _rules.value = it }
    }

    suspend fun acknowledge(eventId: String) {
        api.acknowledge(eventId)
    }

    suspend fun acknowledgeAll() {
        api.acknowledgeAll()
    }

    suspend fun createRule(rule: Rule): Rule {
        return api.createRule(rule).also { created ->
            _rules.update { current -> (current + created).distinctBy(Rule::id) }
        }
    }

    suspend fun updateRule(id: String, rule: Rule): Rule {
        return api.updateRule(id, rule).also { updated ->
            _rules.update { current -> current.map { if (it.id == id) updated else it } }
        }
    }

    suspend fun deleteRule(id: String) {
        api.deleteRule(id)
        _rules.update { current -> current.filterNot { it.id == id } }
    }

    suspend fun refreshPeople() = api.people().also { _people.value = it }
    suspend fun refreshUsers() = api.users().also { _people.value = it }
    suspend fun refreshMe() = api.me().also { _me.value = it }
    suspend fun refreshPlaces() = api.places().also { _places.value = it }
    suspend fun createPerson(person: TrackedPerson) = api.createPerson(person).also { created -> _people.update { (it + created).distinctBy(TrackedPerson::id) } }
    suspend fun updatePerson(id: String, person: TrackedPerson) = api.updatePerson(id, person).also { updated -> _people.update { it.map { current -> if (current.id == id) updated else current } } }
    suspend fun deletePerson(id: String) { api.deletePerson(id); _people.update { it.filterNot { person -> person.id == id } } }
    suspend fun updateMe(person: TrackedPerson) = api.updateMe(person).also { updated ->
        _me.value = updated
        _people.update { people -> people.map { if (it.id == updated.id) updated else it } }
    }
    suspend fun updateUser(id: String, person: TrackedPerson) = api.updateUser(id, person).also { updated ->
        _people.update { people -> people.map { if (it.id == id) updated else it } }
        if (_me.value?.id == id) _me.value = updated
    }
    suspend fun createPlace(place: TrackedPlace) = api.createPlace(place).also { created -> _places.update { (it + created).distinctBy(TrackedPlace::id) } }
    suspend fun updatePlace(id: String, place: TrackedPlace) = api.updatePlace(id, place).also { updated -> _places.update { it.map { current -> if (current.id == id) updated else current } } }
    suspend fun deletePlace(id: String) { api.deletePlace(id); _places.update { it.filterNot { place -> place.id == id } } }
}
