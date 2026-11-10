package io.karandash.platforms.github

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertNull

/** A missing GitHub token is not an init error: reads go anonymous and `checkAuth` reports the missing token. */
class GitHubCredentialsMissingTokenTest {
    @Test
    fun `missing token resolves anonymously`() {
        assertNull(GitHubCredentials.resolve(JsonObject(emptyMap()), { null }).token)
    }
}
