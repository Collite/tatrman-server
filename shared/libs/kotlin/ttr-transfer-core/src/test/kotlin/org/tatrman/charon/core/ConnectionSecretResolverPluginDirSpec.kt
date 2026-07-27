// SPDX-License-Identifier: Apache-2.0
package org.tatrman.charon.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * CH-D8 (review-075 F4) — the open plugin-dir host in [ConnectionSecretResolver.discover].
 *
 * A deployment binds a secret-store adapter by **mounting its jar** into `CHARON_PLUGIN_DIR`, not
 * by baking it into the open image. These pin the four host guarantees against a real mounted jar
 * built at test time (the same shape svarog's PluginHost is tested with: the SPI type is shared with
 * the parent classloader, so a parent-visible provider class loads through the child loader).
 */
class ConnectionSecretResolverPluginDirSpec :
    StringSpec({

        // Write a jar into [dir] whose only entry is a META-INF/services file naming [providerFqcn].
        fun writeServicesJar(
            dir: Path,
            providerFqcn: String,
            jarName: String = "adapter.jar",
        ) {
            JarOutputStream(Files.newOutputStream(dir.resolve(jarName))).use { out ->
                out.putNextEntry(JarEntry("META-INF/services/${ConnectionSecretResolver::class.java.name}"))
                out.write("$providerFqcn\n".toByteArray())
                out.closeEntry()
            }
        }

        "null / absent / empty plugin dir → the env-var default (today's behaviour)" {
            ConnectionSecretResolver.discover(pluginDir = null) shouldBeSameInstanceAs EnvSecretResolver
            val empty = Files.createTempDirectory("charon-plugins-empty")
            val absent = empty.resolve("nope")
            ConnectionSecretResolver.discover(pluginDir = empty) shouldBeSameInstanceAs EnvSecretResolver
            ConnectionSecretResolver.discover(pluginDir = absent) shouldBeSameInstanceAs EnvSecretResolver
        }

        "a plugin jar declaring a provider → that provider wins" {
            val dir = Files.createTempDirectory("charon-plugins-ok")
            writeServicesJar(dir, PluginDirTestResolver::class.java.name)
            val resolver = ConnectionSecretResolver.discover(pluginDir = dir)
            resolver.shouldBeInstanceOf<PluginDirTestResolver>()
            resolver.resolve("TTR_CONN_PLUGIN") shouldBe "from-plugin"
        }

        "require-secret-resolver + empty dir still fails closed" {
            val empty = Files.createTempDirectory("charon-plugins-req")
            shouldThrow<IllegalStateException> {
                ConnectionSecretResolver.discover(pluginDir = empty, requireProvider = true)
            }
        }

        "a jar whose provider class is missing → skipped, never a silent crash" {
            val dir = Files.createTempDirectory("charon-plugins-bad")
            writeServicesJar(dir, "org.tatrman.charon.core.NoSuchResolver")
            // skipped → no provider → the env default when not required …
            ConnectionSecretResolver.discover(pluginDir = dir) shouldBeSameInstanceAs EnvSecretResolver
            // … and still fails closed when required (the skip is not a silent success).
            shouldThrow<IllegalStateException> {
                ConnectionSecretResolver.discover(pluginDir = dir, requireProvider = true)
            }
        }
    })
