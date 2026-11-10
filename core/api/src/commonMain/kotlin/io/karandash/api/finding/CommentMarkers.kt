package io.karandash.api.finding

/** The hidden HTML-comment markers karandash writes into MR comments, defined once for every writer and reader. */
public object CommentMarkers {
    /** Start of every karandash marker. */
    public const val PREFIX: String = "<!-- karandash"

    /** Summary comment marker: platforms find the comment to update by it. */
    public const val SUMMARY: String = "<!-- karandash-validation-result -->"

    /** Inline comment marker (header of an inline finding comment). */
    public const val INLINE: String = "<!-- karandash-inline -->"

    /** Start of the notification state marker line. */
    public const val SUMMARY_STATE: String = "<!-- karandash-state"
}
