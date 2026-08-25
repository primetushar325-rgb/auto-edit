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

    /** New layout: projects/<id>/project.json (folder holds media subdirs). */
    private fun file(id: String): File = File(dir, "$id/project.json")

    /** Legacy layout (back-compat reads): projects/<id>.json */
    private fun legacyFile(id: String): File = File(dir, "$id.json")

    fun list(): List<ProjectModel> {
        val out = ArrayList<ProjectModel>()
        dir.listFiles { f -> f.isDirectory }?.forEach { d ->
            val f = File(d, "project.json")
            if (f.exists()) {
                runCatching { ProjectJson.decode(f.readText()) }.getOrNull()?.let { out += it }
            }
        }
        dir.listFiles { f -> f.isFile && f.extension == "json" }?.forEach { f ->
            runCatching { ProjectJson.decode(f.readText()) }.getOrNull()?.let { out += it }
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun load(id: String): ProjectModel? = runCatching {
        val f = file(id)
        if (f.exists()) return@runCatching ProjectJson.decode(f.readText())
        val legacy = legacyFile(id)
        if (legacy.exists()) ProjectJson.decode(legacy.readText()) else null
    }.getOrNull()

    fun save(p: ProjectModel): ProjectModel {
        val updated = p.copy(updatedAt = System.currentTimeMillis())
        file(p.id).parentFile?.mkdirs()
        file(p.id).writeText(ProjectJson.encode(updated))
        runCatching { legacyFile(p.id).delete() }
        return updated
    }

    fun delete(id: String): Boolean {
        val a = file(id).delete()
        val b = legacyFile(id).delete()
        return a || b
    }

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
