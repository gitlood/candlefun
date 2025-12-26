package com.example.execution.impl.di

import com.example.account.domain.AccountStateRepository
import com.example.execution.domain.ExecutionCredentialsProvider
import com.example.execution.domain.ExecutionGateway
import com.example.execution.impl.BinanceAccountStateRepository
import com.example.execution.impl.BinanceExecutionGateway
import com.example.execution.impl.EnvExecutionCredentialsProvider
import com.example.execution.impl.FuturesAccountStateRepository
import com.example.execution.impl.FuturesExecutionGateway
import com.example.execution.impl.SimAccountStateRepository
import com.example.execution.impl.SimExecutionGateway
import com.example.network.futures.interfaces.BinanceFuturesTestNetApiService
import com.example.network.interfaces.BinanceTestNetApiService
import org.koin.core.qualifier.named
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

val executionImplModule = module {
    single<ExecutionCredentialsProvider> { EnvExecutionCredentialsProvider() }
    single(named("testnetApi")) {
        val creds = get<ExecutionCredentialsProvider>().testnet()
        get<BinanceTestNetApiService> { parametersOf(creds.apiKey, creds.secretKey) }
    }
    single(named("futuresTestnetApi")) {
        val creds = get<ExecutionCredentialsProvider>().testnet()
        get<BinanceFuturesTestNetApiService> { parametersOf(creds.apiKey, creds.secretKey) }
    }

    single<AccountStateRepository> { BinanceAccountStateRepository(get(named("testnetApi"))) }
    single<ExecutionGateway> { BinanceExecutionGateway(get(named("testnetApi")), get()) }
    single<AccountStateRepository>(named("futuresAccount")) {
        FuturesAccountStateRepository(get(named("futuresTestnetApi")))
    }
    single<ExecutionGateway>(named("futuresExecution")) {
        FuturesExecutionGateway(get(named("futuresTestnetApi")), get(named("futuresAccount")))
    }

    factory { SimAccountStateRepository() }
    factory { SimExecutionGateway(get(), get()) }
}
