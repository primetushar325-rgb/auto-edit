package com.autoedit.engine

import java.io.File

/**
 * Local project storage: one JSON file per project inside the app's private dir.
 * Private dir => no storage permissions needed, user media never leaves the device.
 */
class ProjectRepository(private val dir: File) {

    init {
        dir.mkdirs()
    }

    private fun file(id: String): File = File(dir, "$id.json")

    fun list(): List<ProjectModel> {
        val files = dir.listFiles { f -> f.isFile && f.extension == "json" } ?: return emptyList()
        return files
            .mapNotNull { f -> runCatching { ProjectJson.decode(f.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    fun load(id: String): ProjectModel? = runCatching {
        val f = file(id)
        if (f.exists()) ProjectJson.decode(f.readText()) else null
    }.getOrNull()

    fun save(p: ProjectModel): ProjectModel {
        val updated = p.copy(updatedAt = System.currentTimeMillis())
        file(p.id).writeText(ProjectJson.encode(updated))
        return updated
    }

    fun delete(id: String): Boolean = file(id).delete()

    fun rename(id: String, newName: String): Boolean {
        val p = load(id) ?: return false
        return save(p.copy(name = newName)) != null
    }

    fun nextProjectNumber(): Int {
        val max = list().mapNotNull { p -> p.name.removePrefix("Project ").toIntOrNull() }
            .maxOrNull() ?: 0
        return max + 1
    }
}
