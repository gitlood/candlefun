package com.example.execution.impl.config

import java.io.File


data class InventoryWalletConfig(
    val walletCsvPath: String = defaultWalletPath(),
    val autoPersist: Boolean = true,
    val persistEveryMs: Long = 1_000L
) {
    companion object {
        fun default(): InventoryWalletConfig = InventoryWalletConfig()
    }
}

private fun defaultWalletPath(): String {
    val env = System.getenv("AVELLANEDA_WALLET_PATH")
    if (!env.isNullOrBlank()) return env

    val root = findProjectRoot()
    return File(root, "avellaneda_wallet.csv").absolutePath
}

private fun findProjectRoot(): File {
    var dir = File(System.getProperty("user.dir"))
    while (true) {
        if (File(dir, "settings.gradle.kts").exists()) return dir
        val parent = dir.parentFile ?: return dir
        dir = parent
    }
}
