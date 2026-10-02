package com.hainesy.karoogarage

import com.hainesy.karoogarage.AuthClient.LoginStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Home Assistant's login flow, parsed. This is what turns HA's JSON into "logged in",
 * "enter your code" or a readable error on a 2-inch touchscreen, so a misparse means the
 * rider simply cannot log in. Payload shapes are HA's `/auth/login_flow` responses.
 */
class LoginFlowTest {

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    // --- parseLoginStep -----------------------------------------------------------------

    @Test
    fun `create_entry with a code is success`() {
        assertEquals(
            LoginStep.Success("abc123"),
            AuthClient.parseLoginStep(obj("""{"type":"create_entry","flow_id":"f1","result":"abc123"}""")),
        )
    }

    @Test
    fun `create_entry without a code is a failure, not a silent success`() {
        val step = AuthClient.parseLoginStep(obj("""{"type":"create_entry","flow_id":"f1"}"""))
        assertEquals(LoginStep.Failed("f1", "login succeeded but no code returned"), step)
    }

    @Test
    fun `the mfa form asks for a code and keeps the flow id`() {
        assertEquals(
            LoginStep.MfaRequired("f1"),
            AuthClient.parseLoginStep(obj("""{"type":"form","flow_id":"f1","step_id":"mfa","errors":{}}""")),
        )
    }

    @Test
    fun `a base error is humanised`() {
        val step = AuthClient.parseLoginStep(
            obj("""{"type":"form","flow_id":"f1","step_id":"init","errors":{"base":"invalid_auth"}}"""),
        )
        assertEquals(LoginStep.Failed("f1", "wrong username or password"), step)
    }

    @Test
    fun `an error on the mfa step wins over re-asking for the code`() {
        val step = AuthClient.parseLoginStep(
            obj("""{"type":"form","flow_id":"f1","step_id":"mfa","errors":{"base":"invalid_code"}}"""),
        )
        assertEquals(LoginStep.Failed("f1", "wrong verification code"), step)
    }

    @Test
    fun `abort reports HA's reason`() {
        assertEquals(
            LoginStep.Failed(null, "login_expired"),
            AuthClient.parseLoginStep(obj("""{"type":"abort","flow_id":"f1","reason":"login_expired"}""")),
        )
    }

    @Test
    fun `an unknown response type fails with the type named`() {
        assertEquals(
            LoginStep.Failed("f1", "unexpected response type: external_step"),
            AuthClient.parseLoginStep(obj("""{"type":"external_step","flow_id":"f1"}""")),
        )
    }

    @Test
    fun `an unknown error code is passed through rather than hidden`() {
        assertEquals("rate_limited", AuthClient.humaniseError("rate_limited"))
    }

    // --- mfaModuleToSelect ----------------------------------------------------------------

    private fun selectStep(options: String, errors: String = "{}") = obj(
        """{"type":"form","flow_id":"f1","step_id":"select_mfa_module","errors":$errors,
            "data_schema":[{"name":"multi_factor_auth_module","options":$options}]}""",
    )

    @Test
    fun `totp is preferred when offered`() {
        assertEquals(
            "totp",
            AuthClient.mfaModuleToSelect(selectStep("""[["notify","Notify"],["totp","Authenticator app"]]""")),
        )
    }

    @Test
    fun `otherwise the first module is chosen`() {
        assertEquals("notify", AuthClient.mfaModuleToSelect(selectStep("""[["notify","Notify"]]""")))
    }

    @Test
    fun `not the module-selection step returns null`() {
        assertNull(AuthClient.mfaModuleToSelect(obj("""{"type":"form","flow_id":"f1","step_id":"mfa"}""")))
        assertNull(AuthClient.mfaModuleToSelect(obj("""{"type":"create_entry","result":"x"}""")))
    }

    @Test
    fun `a selection step carrying errors is not auto-answered again`() {
        // Re-submitting the same choice into an error would loop.
        assertNull(AuthClient.mfaModuleToSelect(selectStep("""[["totp","App"]]""", """{"base":"x"}""")))
    }

    @Test
    fun `a malformed schema returns null rather than throwing`() {
        assertNull(AuthClient.mfaModuleToSelect(obj("""{"type":"form","step_id":"select_mfa_module","data_schema":"oops"}""")))
        assertNull(AuthClient.mfaModuleToSelect(selectStep("[]")))
    }
}
