package app.epistola.suite.htmx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.function.ServerRequest

class HtmxDslTest {

    @Nested
    inner class ModelBuilderTest {
        @Test
        fun `builds empty model when no values added`() {
            val model = ModelBuilder().build()
            assertThat(model).isEmpty()
        }

        @Test
        fun `builds model with single value`() {
            val model = ModelBuilder().apply {
                "key" to "value"
            }.build()

            assertThat(model).containsEntry("key", "value")
        }

        @Test
        fun `builds model with multiple values`() {
            val model = ModelBuilder().apply {
                "name" to "Test"
                "count" to 42
                "active" to true
            }.build()

            assertThat(model)
                .containsEntry("name", "Test")
                .containsEntry("count", 42)
                .containsEntry("active", true)
        }

        @Test
        fun `nullable value is stored as null, not silently discarded`() {
            // Regression: with value: Any (non-null), overload resolution silently
            // selected kotlin.to (Pair extension) for nullable expressions and
            // the entry never reached the model. The DSL must accept null so that
            // the entry's absence vs explicit-null is observable in templates.
            val nullable: String? = null

            val model = ModelBuilder().apply {
                "present" to "value"
                "missing" to nullable
            }.build()

            assertThat(model)
                .containsKey("missing")
                .containsEntry("missing", null)
                .containsEntry("present", "value")
        }
    }

    @Nested
    inner class HtmxFragmentTest {
        @Test
        fun `creates fragment with template only`() {
            val fragment = HtmxFragment("templates/list", null, emptyMap())

            assertThat(fragment.template).isEqualTo("templates/list")
            assertThat(fragment.fragmentName).isNull()
            assertThat(fragment.isOob).isFalse()
        }

        @Test
        fun `creates fragment with template and fragment name`() {
            val fragment = HtmxFragment("templates/list", "rows", mapOf("items" to listOf(1, 2, 3)))

            assertThat(fragment.template).isEqualTo("templates/list")
            assertThat(fragment.fragmentName).isEqualTo("rows")
            assertThat(fragment.model).containsEntry("items", listOf(1, 2, 3))
        }

        @Test
        fun `creates OOB fragment`() {
            val fragment = HtmxFragment("components/toast", "success", emptyMap(), isOob = true)

            assertThat(fragment.isOob).isTrue()
        }
    }

    @Nested
    inner class HtmxResponseBuilderTest {
        @Test
        fun `non-HTMX request with redirect handler returns redirect`() {
            val request = createNonHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list", "rows")
                onNonHtmx { redirect("/templates") }
            }.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.SEE_OTHER)
            assertThat(response.headers().getFirst("Location")).isEqualTo("/templates")
        }

        @Test
        fun `fragment model lambdas are not evaluated when the non-HTMX branch discards the fragments`() {
            val request = createNonHtmxRequest()
            var evaluations = 0

            HtmxResponseBuilder(request).apply {
                fragment("templates/list", "rows") {
                    evaluations++
                    "items" to listOf("a")
                }
                oob("templates/list", "count") {
                    evaluations++
                    "total" to 1
                }
                onNonHtmx { redirect("/templates") }
            }.build()

            // The non-HTMX / boosted / history-restore branch renders the
            // onNonHtmx page and throws every fragment away — the (potentially
            // query-issuing) model lambdas must not run for it.
            assertThat(evaluations).isZero()
        }

        @Test
        fun `HTMX request with single fragment returns OK`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list", "rows") {
                    "items" to listOf("a", "b")
                }
            }.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.OK)
        }

        @Test
        fun `trigger header is set correctly`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list")
                trigger("itemCreated")
            }.build()

            assertThat(response.headers().getFirst("HX-Trigger")).isEqualTo("itemCreated")
        }

        @Test
        fun `multiple triggers accumulate onto one header`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list")
                trigger("closeDialog")
                trigger("dialogSuccess")
            }.build()

            assertThat(response.headers().getFirst("HX-Trigger"))
                .isEqualTo("""{"closeDialog": null, "dialogSuccess": null}""")
        }

        @Test
        fun `trigger with detail is set correctly`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list")
                trigger("itemCreated", """{"id": 123}""")
            }.build()

            assertThat(response.headers().getFirst("HX-Trigger"))
                .isEqualTo("""{"itemCreated": {"id": 123}}""")
        }

        @Test
        fun `pushUrl header is set correctly`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list")
                pushUrl("/templates?page=2")
            }.build()

            assertThat(response.headers().getFirst("HX-Push-Url")).isEqualTo("/templates?page=2")
        }

        @Test
        fun `replaceUrl header is set correctly`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list")
                replaceUrl("/templates")
            }.build()

            assertThat(response.headers().getFirst("HX-Replace-Url")).isEqualTo("/templates")
        }

        @Test
        fun `reswap header is set correctly`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list")
                reswap(HxSwap.NONE)
            }.build()

            assertThat(response.headers().getFirst("HX-Reswap")).isEqualTo("none")
        }

        @Test
        fun `retarget header is set correctly`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list")
                retarget("#other-element")
            }.build()

            assertThat(response.headers().getFirst("HX-Retarget")).isEqualTo("#other-element")
        }

        @Test
        fun `status overrides the response status`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list", "rows")
                status(409)
            }.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT)
        }

        @Test
        fun `globalFormError sets error status and disables the primary swap`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                globalFormError("create-tenant-error", "Something went wrong")
            }.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            assertThat(response.headers().getFirst("HX-Reswap")).isEqualTo("none")
        }

        @Test
        fun `globalFormError with explicit status uses it`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                globalFormError("create-tenant-error", "Nope", statusCode = 409)
            }.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT)
        }

        @Test
        fun `multiple headers can be set`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list")
                trigger("saved")
                pushUrl("/new-url")
                reswap(HxSwap.OUTER_HTML)
            }.build()

            assertThat(response.headers().getFirst("HX-Trigger")).isEqualTo("saved")
            assertThat(response.headers().getFirst("HX-Push-Url")).isEqualTo("/new-url")
            assertThat(response.headers().getFirst("HX-Reswap")).isEqualTo("outerHTML")
        }
    }

    @Nested
    inner class DialogLifecycleHelpersTest {
        @Test
        fun `dialogSuccess closes the dialog, refreshes the list OOB, and disables the primary swap`() {
            val request = createHtmxRequest()

            // Note: the caller does NOT pass `"oob" to true` — dialogSuccess
            // injects it so the list fragment always renders its OOB swap.
            val builder = HtmxResponseBuilder(request).apply {
                dialogSuccess("environments/list", "rows", "/tenants/acme/environments") {
                    "environments" to listOf("a", "b")
                }
            }
            val response = builder.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.OK)
            // Both the generic close and the distinct success event ride one
            // header: closeDialog closes the dialog; dialogSuccess carries the
            // "list was just OOB-refreshed unfiltered" meaning for listeners
            // (e.g. the app-shell search-box reset) that must NOT fire on the
            // non-success closeDialog closes other handlers emit.
            assertThat(response.headers().getFirst("HX-Trigger"))
                .isEqualTo("""{"closeDialog": null, "dialogSuccess": null}""")
            // Never swaps the primary target (the list refreshes via the OOB
            // fragment, and the dialog closes on the trigger).
            assertThat(response.headers().getFirst("HX-Reswap")).isEqualTo("none")
            // No retarget: the list moves out-of-band, nothing is retargeted.
            assertThat(response.headers().getFirst("HX-Retarget")).isNull()
            // Puts the address bar back on the list via HTMX's own history machinery
            // (HX-Replace-Url) — NOT a raw replaceState — so HTMX's cached list
            // snapshot stays consistent and Back after a create shows the fresh list
            // (CR3), not the stale pre-open one.
            assertThat(response.headers().getFirst("HX-Replace-Url")).isEqualTo("/tenants/acme/environments")
            // dialogSuccess injects the OOB flag itself, so the list fragment's
            // hx-swap-oob renders even when the caller never sets it.
            val oobFragment = builder.emittedFragments.single { it.isOob }
            assertThat(oobFragment.model).containsEntry("oob", true)
        }

        @Test
        fun `dialogRedirect emits HX-Redirect and 200 with no fragment`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                dialogRedirect("/x")
            }.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.OK)
            // HX-Redirect drives a full-page client-side navigation to the created
            // resource; no fragment/body is rendered (the dialog goes with the page).
            assertThat(response.headers().getFirst("HX-Redirect")).isEqualTo("/x")
        }

        @Test
        fun `dialogReveal keeps the dialog open - retargets, outerHTML, no closeDialog`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                dialogReveal("api-keys/created", "created-panel", "#api-key-form-area") {
                    "plaintextKey" to "ep_secret_123"
                }
            }.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.OK)
            assertThat(response.headers().getFirst("HX-Retarget")).isEqualTo("#api-key-form-area")
            assertThat(response.headers().getFirst("HX-Reswap")).isEqualTo("outerHTML")
            // The crux: success that stays open sends NO closeDialog trigger.
            assertThat(response.headers().getFirst("HX-Trigger")).isNull()
        }

        @Test
        fun `dialogFieldErrors retargets the inner form (not the dialog) so the re-render never lands in the list`() {
            val request = createHtmxRequest()
            val formData = FormData(
                formData = mapOf("slug" to "bad slug", "name" to "Env"),
                errors = mapOf("slug" to "Invalid environment ID format"),
            )

            val response = HtmxResponseBuilder(request).apply {
                dialogFieldErrors(
                    template = "environments/dialog",
                    fragmentName = "environment-form",
                    formTarget = "#create-environment-form",
                    formData = formData,
                )
            }.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            // Targets the inner <form>, NOT the <dialog>: outerHTML-swapping the
            // dialog would drop it from the top layer (backdrop lost, no reopen).
            assertThat(response.headers().getFirst("HX-Retarget")).isEqualTo("#create-environment-form")
            assertThat(response.headers().getFirst("HX-Reswap")).isEqualTo("outerHTML")
            // No closeDialog — the dialog stays open showing the inline errors.
            assertThat(response.headers().getFirst("HX-Trigger")).isNull()
        }

        @Test
        fun `dialogFieldErrors honours an explicit status`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                dialogFieldErrors(
                    template = "environments/dialog",
                    fragmentName = "environment-form",
                    formTarget = "#create-environment-form",
                    formData = FormData(emptyMap(), mapOf("slug" to "taken")),
                    statusCode = 409,
                )
            }.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT)
        }

        @Test
        fun `dialogFormError swaps only the OOB error slot and disables the primary swap (uploads)`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                dialogFormError("upload-font-error", "That font file is not a valid TTF/OTF.")
            }.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            // OOB-only: the form body (and its file input) is never re-rendered.
            assertThat(response.headers().getFirst("HX-Reswap")).isEqualTo("none")
            assertThat(response.headers().getFirst("HX-Retarget")).isNull()
            assertThat(response.headers().getFirst("HX-Trigger")).isNull()
        }

        @Test
        fun `dialogFormError honours an explicit status`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                dialogFormError("upload-font-error", "Too large.", statusCode = 409)
            }.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT)
        }

        @Test
        fun `dialogFieldErrorsOob swaps only the OOB field spans and never re-renders the form body (uploads)`() {
            val request = createHtmxRequest()

            val builder = HtmxResponseBuilder(request).apply {
                dialogFieldErrorsOob(
                    template = "fonts/new",
                    fragmentName = "field-errors",
                    errors = mapOf("slug" to "Slug is required", "faces" to "At least one face file is required"),
                )
            }
            val response = builder.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            // OOB-only: the form body (file inputs + added face rows) is never
            // re-rendered — only the per-field message spans are swapped.
            assertThat(response.headers().getFirst("HX-Reswap")).isEqualTo("none")
            assertThat(response.headers().getFirst("HX-Retarget")).isNull()
            // No closeDialog — the dialog stays open showing the inline errors.
            assertThat(response.headers().getFirst("HX-Trigger")).isNull()
            // The errors map reaches the OOB field-errors fragment model.
            val oob = builder.emittedFragments.single { it.isOob }
            assertThat(oob.template).isEqualTo("fonts/new")
            assertThat(oob.fragmentName).isEqualTo("field-errors")
            assertThat(oob.model).containsEntry(
                "errors",
                mapOf("slug" to "Slug is required", "faces" to "At least one face file is required"),
            )
        }

        @Test
        fun `dialogFieldErrorsOob honours an explicit status`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                dialogFieldErrorsOob("fonts/new", "field-errors", mapOf("faces" to "bad"), statusCode = 409)
            }.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT)
        }
    }

    @Nested
    inner class HxSwapTest {
        @Test
        fun `all swap modes have correct values`() {
            assertThat(HxSwap.INNER_HTML.value).isEqualTo("innerHTML")
            assertThat(HxSwap.OUTER_HTML.value).isEqualTo("outerHTML")
            assertThat(HxSwap.BEFORE_BEGIN.value).isEqualTo("beforebegin")
            assertThat(HxSwap.AFTER_BEGIN.value).isEqualTo("afterbegin")
            assertThat(HxSwap.BEFORE_END.value).isEqualTo("beforeend")
            assertThat(HxSwap.AFTER_END.value).isEqualTo("afterend")
            assertThat(HxSwap.DELETE.value).isEqualTo("delete")
            assertThat(HxSwap.NONE.value).isEqualTo("none")
        }
    }

    @Nested
    inner class RedirectTest {
        @Test
        fun `redirect creates 303 See Other response`() {
            val response = redirect("/templates")

            assertThat(response.statusCode()).isEqualTo(HttpStatus.SEE_OTHER)
            assertThat(response.headers().getFirst("Location")).isEqualTo("/templates")
        }
    }

    private fun createHtmxRequest(): ServerRequest {
        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("HX-Request", "true")
        return ServerRequest.create(mockRequest, emptyList())
    }

    private fun createNonHtmxRequest(): ServerRequest {
        val mockRequest = MockHttpServletRequest()
        return ServerRequest.create(mockRequest, emptyList())
    }
}
