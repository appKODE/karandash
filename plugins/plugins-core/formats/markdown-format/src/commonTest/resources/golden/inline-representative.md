**❌ ERROR** — `labels` · Missing required label: 'needs-review'

**How to fix:** Add the label via the MR sidebar.

ℹ️ Labels route MRs through review.
<!-- karandash-finding {"rule":"labels","path":"src/main/kotlin/App.kt","hash":847479755,"fp":"c75ad0760b4095e5","msg":"Missing required label: 'needs-review'","fpv":""} -->

=====

**⚠️ WARNING** — `aiReview` · The retry loop never backs off

<!-- karandash-suggestion -->
```diff
+ repeat(3) { delay(it * 100L); retry() }
```
<!-- /karandash-suggestion -->
<!-- karandash-finding {"rule":"aiReview","path":"src/main/kotlin/App.kt","hash":24252754,"fp":"5c2b73af1c3b6bfb","msg":"The retry loop never backs off","fpv":""} -->

=====

**⚠️ WARNING** — `risk-scoring` · Large change touching payment code
<!-- karandash-finding {"rule":"risk-scoring","path":"src/main/kotlin/App.kt","hash":1402346233,"fp":"f25c8d847a7b56b7","msg":"Large change touching payment code","fpv":""} -->

=====

**ℹ️ INFO** — `style` · Line one

line two with a | pipe
<!-- karandash-finding {"rule":"style","path":"src/main/kotlin/App.kt","hash":-950579721,"fp":"565445d44eda9a69","msg":"Line one\\nline two with a | pipe","fpv":""} -->

=====

**❌ ERROR** — `review-comments` · Null dereference on deleted users

The lookup returns null for soft-deleted rows and the caller uses !!

**How to fix:** Return a fallback name.

ℹ️ Soft-deleted users keep their id.
<!-- karandash-finding {"rule":"review-comments","path":"src/main/kotlin/App.kt","hash":-1548287793,"fp":"7257fa6a73020f8b","msg":"Null dereference on deleted users\\nThe lookup returns null for soft-deleted rows","fpv":""} -->

=====

**ℹ️ INFO** — `review-comments` · Name reads poorly
<!-- karandash-finding {"rule":"review-comments","path":"src/main/kotlin/App.kt","hash":-308844441,"fp":"0ab948b2a51f9393","msg":"Name reads poorly","fpv":""} -->
