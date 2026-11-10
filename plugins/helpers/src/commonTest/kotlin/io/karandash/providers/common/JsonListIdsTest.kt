package io.karandash.providers.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonListIdsTest {
    @Test
    fun readsFieldFromPlainAndEnvelopedLists() {
        assertEquals(listOf("3", "1"), jsonListIds("""[{"iid":3},{"x":0},{"iid":1}]""", "iid"))
        assertEquals(listOf("7"), jsonListIds("""{"values":[{"id":7}]}""", "id", envelope = "values"))
        assertEquals(emptyList(), jsonListIds("""{"other":[]}""", "id", envelope = "values"))
        assertEquals(listOf("2"), jsonListIds("""[{"n":1,"b":"x"},{"n":2,"b":"main"}]""", "n") { it["b"].toString() == "\"main\"" })
    }

    @Test
    fun notJsonIsIllegalState() {
        assertFailsWith<IllegalStateException> { jsonListIds("<html>", "id") }
    }
}
