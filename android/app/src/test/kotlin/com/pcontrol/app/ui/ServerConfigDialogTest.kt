package com.pcontrol.app.ui

import android.text.InputType
import com.pcontrol.app.MainActivity
import com.pcontrol.app.R
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stage 4 dialog contract: token is masked and the reveal affordance exists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ServerConfigDialogTest {
    @Test
    fun serverConfigLayoutMasksTokenAndProvidesRevealToggle() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create().start().resume().get()
        val content = activity.layoutInflater.inflate(R.layout.dialog_server_config, null)
        val token = content.findViewById<android.widget.EditText>(R.id.input_server_token)
        val tokenLayout = content.findViewById<TextInputLayout>(R.id.input_server_token_layout)

        assertNotNull(token)
        assertTrue(token.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0)
        assertEquals(TextInputLayout.END_ICON_PASSWORD_TOGGLE, tokenLayout.endIconMode)
        assertNotNull(tokenLayout.endIconContentDescription)
    }

    @Test
    fun invalidConfigurationIsRejectedBeforePersistence() {
        val result = validateServerConfiguration("https://", "")
        assertTrue(!result.isOk)
        assertEquals(ServerConfigError.URL_NO_HOST, result.error)
    }
}