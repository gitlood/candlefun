package com.example.platform.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiResultTest {
    @Test
    fun `onSuccess invokes action for ok`() {
        var captured: Int? = null
        val result: ApiResult<Int> = ApiResult.Ok(42)
        result.onSuccess { captured = it }
        assertEquals(42, captured)
    }

    @Test
    fun `onError invokes action for err`() {
        var message: String? = null
        val result: ApiResult<Int> = ApiResult.Err(code = 500, message = "boom")
        result.onError { _, msg -> message = msg }
        assertTrue(message?.contains("boom") == true)
    }
}
