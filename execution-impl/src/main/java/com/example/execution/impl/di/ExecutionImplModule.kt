package com.example.execution.impl.di

import com.example.execution.domain.AccountStateRepository
import com.example.execution.domain.ExecutionCredentialsProvider
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.inventory.InventoryStateRepository
import com.example.execution.impl.BinanceAccountStateRepository
import com.example.execution.impl.BinanceExecutionGateway
import com.example.execution.impl.EnvExecutionCredentialsProvider
import com.example.execution.impl.SimAccountStateRepository
import com.example.execution.impl.SimExecutionGateway
import com.example.execution.impl.config.InventoryWalletConfig
import com.example.execution.impl.inventory.CsvInventoryStateRepository
import com.example.execution.impl.inventory.CsvWalletStore
import com.example.network.interfaces.BinanceTestNetApiService
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

val executionImplModule = module {
    single { InventoryWalletConfig.default() }
    single { CsvWalletStore(get<InventoryWalletConfig>().walletCsvPath) }
    single<InventoryStateRepository> { CsvInventoryStateRepository(get(), get()) }

    single<ExecutionCredentialsProvider> { EnvExecutionCredentialsProvider() }
    single<BinanceTestNetApiService> {
        val creds = get<ExecutionCredentialsProvider>().testnet()
        get { parametersOf(creds.apiKey, creds.secretKey) }
    }

    single<AccountStateRepository> { BinanceAccountStateRepository(get()) }
    single<ExecutionGateway> { BinanceExecutionGateway(get(), get()) }

    factory { SimAccountStateRepository() }
    factory { SimExecutionGateway(get()) }
}
