@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlinx.cinterop.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Add labels to a merge request.
 *
 * @param projectId Null-terminated UTF-8 string with the project identifier.
 * @param mrId Null-terminated UTF-8 string with the merge request identifier.
 * @param labelsJson Null-terminated UTF-8 JSON array of label strings.
 * @return 1 on success, 0 on failure.
 */
@CName("karandash_add_labels")
fun addLabels(
    projectId: CPointer<ByteVar>,
    mrId: CPointer<ByteVar>,
    labelsJson: CPointer<ByteVar>
): Int {
    val project = projectId.toKStringFromUtf8()
    val id = mrId.toKStringFromUtf8()
    val labelsStr = labelsJson.toKStringFromUtf8()

    return try {
        val labels = pluginJson.decodeFromString(ListSerializer(String.serializer()), labelsStr)
        // Real implementation: call platform API to add labels to MR $id in $project
        logToStderr("KotlinNativeMutator: adding labels $labels to MR $id in project $project")
        1
    } catch (e: IllegalArgumentException) {
        // Malformed labels JSON (SerializationException is an IllegalArgumentException): report failure.
        logToStderr("KotlinNativeMutator: cannot decode labels: ${e.message}")
        0
    }
}

/**
 * Remove labels from a merge request.
 *
 * @param projectId Null-terminated UTF-8 string with the project identifier.
 * @param mrId Null-terminated UTF-8 string with the merge request identifier.
 * @param labelsJson Null-terminated UTF-8 JSON array of label strings.
 * @return 1 on success, 0 on failure.
 */
@CName("karandash_remove_labels")
fun removeLabels(
    projectId: CPointer<ByteVar>,
    mrId: CPointer<ByteVar>,
    labelsJson: CPointer<ByteVar>
): Int {
    val project = projectId.toKStringFromUtf8()
    val id = mrId.toKStringFromUtf8()
    val labelsStr = labelsJson.toKStringFromUtf8()

    return try {
        val labels = pluginJson.decodeFromString(ListSerializer(String.serializer()), labelsStr)
        // Real implementation: call platform API to remove labels from MR $id in $project
        logToStderr("KotlinNativeMutator: removing labels $labels from MR $id in project $project")
        1
    } catch (e: IllegalArgumentException) {
        // Malformed labels JSON (SerializationException is an IllegalArgumentException): report failure.
        logToStderr("KotlinNativeMutator: cannot decode labels: ${e.message}")
        0
    }
}

/**
 * Set the draft status of a merge request.
 *
 * @param projectId Null-terminated UTF-8 string with the project identifier.
 * @param mrId Null-terminated UTF-8 string with the merge request identifier.
 * @param draft 1 to mark as draft, 0 to unmark.
 * @return 1 on success, 0 on failure.
 */
@CName("karandash_set_draft")
fun setDraft(
    projectId: CPointer<ByteVar>,
    mrId: CPointer<ByteVar>,
    draft: Int
): Int {
    val project = projectId.toKStringFromUtf8()
    val id = mrId.toKStringFromUtf8()
    val isDraft = draft != 0
    // Real implementation: call platform API to set draft=$isDraft on MR $id in $project
    logToStderr("KotlinNativeMutator: setting draft=$isDraft on MR $id in project $project")
    return 1
}

/**
 * Update the title of a merge request.
 *
 * @param projectId Null-terminated UTF-8 string with the project identifier.
 * @param mrId Null-terminated UTF-8 string with the merge request identifier.
 * @param title Null-terminated UTF-8 string with the new title.
 * @return 1 on success, 0 on failure.
 */
@CName("karandash_update_title")
fun updateTitle(
    projectId: CPointer<ByteVar>,
    mrId: CPointer<ByteVar>,
    title: CPointer<ByteVar>
): Int {
    val project = projectId.toKStringFromUtf8()
    val id = mrId.toKStringFromUtf8()
    val newTitle = title.toKStringFromUtf8()
    logToStderr("KotlinNativeMutator: updating title to '$newTitle' on MR $id in project $project")
    return 1
}

/**
 * Update the description of a merge request.
 *
 * @param projectId Null-terminated UTF-8 string with the project identifier.
 * @param mrId Null-terminated UTF-8 string with the merge request identifier.
 * @param description Null-terminated UTF-8 string with the new description.
 * @return 1 on success, 0 on failure.
 */
@CName("karandash_update_description")
fun updateDescription(
    projectId: CPointer<ByteVar>,
    mrId: CPointer<ByteVar>,
    description: CPointer<ByteVar>
): Int {
    val project = projectId.toKStringFromUtf8()
    val id = mrId.toKStringFromUtf8()
    val newDesc = description.toKStringFromUtf8()
    logToStderr("KotlinNativeMutator: updating description (${newDesc.length} chars) on MR $id in project $project")
    return 1
}
