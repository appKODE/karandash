package io.karandash.http

import io.ktor.http.encodeURLParameter

/**
 * Encodes one URL path segment: every reserved character, `/` included, is percent-encoded. A GitLab project path
 * (`group/sub/project`) or a Gerrit project name becomes a single segment (`group%2Fsub%2Fproject`).
 */
object PathSegmentEncoder {
    fun encode(segment: String): String = segment.encodeURLParameter()
}
