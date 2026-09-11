package dev.repomind.core.model

import java.security.MessageDigest

/**
 * Shared SHA-256 hashing utilities used by multiple modules.
 *
 * These are deliberately in `core:model` (the lowest-level module) so that
 * higher-level modules (classpath, index, etc.) can all import from one place
 * instead of duplicating the implementation.
 */
fun sha256Of(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }

fun sha256Of(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
