package com.qkt.venued

import com.qkt.venued.adapter.VenueAdapterFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader

/**
 * Finds the adapter factory for an `adapter.type`: the adapters built into the gateway, and those in
 * the jars of [pluginsDir] (each registering a [VenueAdapterFactory] under
 * `META-INF/services/com.qkt.venued.adapter.VenueAdapterFactory`). Two factories claiming one type
 * are refused, so a plugin can never silently replace another.
 */
class AdapterLoader(
    pluginsDir: Path?,
) {
    private val loader: ClassLoader =
        pluginsDir
            ?.takeIf { Files.isDirectory(it) }
            ?.let { dir -> Files.list(dir).use { files -> files.filter { it.toString().endsWith(".jar") }.toList() } }
            ?.takeIf { it.isNotEmpty() }
            ?.let { jars -> URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), javaClass.classLoader) }
            ?: javaClass.classLoader

    /** Every factory found, by type. */
    val factories: Map<String, VenueAdapterFactory> =
        ServiceLoader
            .load(VenueAdapterFactory::class.java, loader)
            .toList()
            .groupBy { it.type }
            .mapValues { (type, found) ->
                require(found.size == 1) { "adapter type '$type' is provided by ${found.map { it.javaClass.name }}" }
                found.single()
            }

    /** The factory of [type]; fails naming the types installed. */
    fun factory(type: String): VenueAdapterFactory =
        factories[type]
            ?: error("no adapter of type '$type' is installed (installed: ${factories.keys.sorted().joinToString()})")
}
