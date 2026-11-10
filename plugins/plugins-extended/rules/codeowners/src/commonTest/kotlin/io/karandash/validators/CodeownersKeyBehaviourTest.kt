package io.karandash.validators

import io.karandash.models.Approval
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.User
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class CodeownersKeyBehaviourTest {
    private val mr = createTestMergeRequest(changes = listOf(FileChange("src/A.kt", ChangeType.MODIFIED)))

    @Test
    fun `path selects the CODEOWNERS file that is read`() {
        val asked = mutableListOf<String>()
        val validator =
            CodeownersValidator(CodeownersConfig(path = "docs/OWNERS")) { path ->
                asked.add(path)
                null
            }
        validator.validate(mr)
        assertEquals(listOf("docs/OWNERS"), asked)
    }

    @Test
    fun `requireOwnerApproval flags a change without an owner approval`() {
        val reader = { _: String -> "src/ @alice" }
        assertEquals(1, CodeownersValidator(CodeownersConfig(requireOwnerApproval = true), codeownersReader = reader).validate(mr).size)
        assertEquals(
            emptyList(),
            CodeownersValidator(CodeownersConfig(requireOwnerApproval = false), codeownersReader = reader).validate(mr)
        )
        val approved = mr.copy(approvals = listOf(Approval(User("alice", "Alice"))))
        assertEquals(emptyList(), CodeownersValidator(CodeownersConfig(), codeownersReader = reader).validate(approved))
    }
}
