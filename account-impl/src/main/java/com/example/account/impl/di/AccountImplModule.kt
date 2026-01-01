package com.example.account.impl.di

import com.example.account.domain.inventory.InventoryStateRepository
import com.example.account.impl.config.InventoryWalletConfig
import com.example.account.impl.inventory.CsvInventoryStateRepository
import com.example.account.impl.inventory.CsvWalletStore
import org.koin.dsl.module

val accountImplModule = module {
    single { InventoryWalletConfig.default() }
    single { CsvWalletStore(get<InventoryWalletConfig>().walletCsvPath) }
    single<InventoryStateRepository> { CsvInventoryStateRepository(get(), get()) }
}
