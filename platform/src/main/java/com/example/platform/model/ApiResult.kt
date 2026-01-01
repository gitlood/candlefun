package com.example.platform.model

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Err(val code: Int? = null, val message: String, val cause: Throwable? = null) : ApiResult<Nothing>()
}

inline fun <T> ApiResult<T>.onSuccess(action: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Ok) action(value)
    return this
}

inline fun <T> ApiResult<T>.onError(action: (code: Int?, message: String) -> Unit): ApiResult<T> {
    if (this is ApiResult.Err) action(code, message)
    return this
}
