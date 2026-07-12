package com.pcontrol.app.ui

import android.content.DialogInterface
import android.text.InputType
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputLayout
import com.pcontrol.app.MainActivity
import com.pcontrol.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Stage 4 dialog contract: token is masked, reveal exists, and invalid input
 *  keeps the dialog open without persisting.
 *
 *  IMPORTANT: `SecretPrefs` depends on the Android KeyStore, which is not
 *  available in Robolectric. These tests open the dialog by directly inflating
 *  `dialog_server_config.xml` and wiring the same validation listener, rather
 *  than clicking the "Configure server" button (which would crash). The
 *  production button click path (`showServerConfigDialog`) also calls
 *  `SecretPrefs` — that integration is validated by the manual build + device
 *  checklist in Stage 7. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ServerConfigDialogTest {

    /** Build a dialog with the production layout and validate-on-save logic,
     *  bypassing SecretPrefs/AndroidKeyStore. */
    private fun buildDialog(): Pair<androidx.appcompat.app.AlertDialog, ViewBindings> {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create().start().resume().get()
        val content = activity.layoutInflater.inflate(R.layout.dialog_server_config, null)
        val urlLayout = content.findViewById<TextInputLayout>(R.id.input_server_url_layout)
        val tokenLayout = content.findViewById<TextInputLayout>(R.id.input_server_token_layout)
        val inputUrl =
            content.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_server_url)
        val inputToken =
            content.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_server_token)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dialog_server_title)
            .setView(content)
            .setPositiveButton(R.string.dialog_server_save, null)
            .setNegativeButton(R.string.dialog_server_cancel, null)
            .create()

        // Same listener as production showServerConfigDialog().
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val result = validateServerConfiguration(
                    inputUrl.text?.toString().orEmpty(), inputToken.text?.toString().orEmpty()
                )
                urlLayout.error = null
                tokenLayout.error = null
                if (result.isOk) {
                    // In production this calls SecretPrefs.setServerUrl / setDeviceToken.
                    // In this test we verify the dialog dismisses — persistence is
                    // validated by the device checklist (Stage 7).
                    dialog.dismiss()
                } else {
                    when (result.error) {
                        ServerConfigError.URL_BLANK, ServerConfigError.URL_BAD_SCHEME,
                        ServerConfigError.URL_NO_HOST, ServerConfigError.URL_QUERY_OR_FRAGMENT -> {
                            urlLayout.error = activity.getString(when (result.error) {
                                ServerConfigError.URL_BLANK -> R.string.dialog_server_url_error_blank
                                ServerConfigError.URL_BAD_SCHEME -> R.string.dialog_server_url_error_scheme
                                ServerConfigError.URL_NO_HOST -> R.string.dialog_server_url_error_host
                                else -> R.string.dialog_server_url_error_query
                            })
                            inputUrl.requestFocus()
                        }
                        ServerConfigError.TOKEN_BLANK -> {
                            tokenLayout.error = activity.getString(R.string.dialog_server_token_error_blank)
                            inputToken.requestFocus()
                        }
                        null -> Unit
                    }
                }
            }
        }
        dialog.show()
        ShadowLooper.idleMainLooper() // ensure OnShowListener fires

        val saveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        return dialog to ViewBindings(urlLayout, tokenLayout, inputUrl, inputToken, saveButton)
    }

    private data class ViewBindings(
        val urlLayout: TextInputLayout,
        val tokenLayout: TextInputLayout,
        val inputUrl: com.google.android.material.textfield.TextInputEditText,
        val inputToken: com.google.android.material.textfield.TextInputEditText,
        val saveButton: Button,
    )

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
    fun invalidUrlKeepsDialogOpenAndShowsFieldError() {
        val (dialog, bindings) = buildDialog()
        bindings.inputUrl.setText("not-a-url")
        bindings.inputToken.setText("abc")
        bindings.saveButton.performClick()
        ShadowLooper.idleMainLooper() // process click listener

        assertTrue("dialog must stay open on invalid input", dialog.isShowing)
        val errorText = bindings.urlLayout.error?.toString()
        assertNotNull("URL field must show an error message", errorText)
    }

    @Test
    fun invalidTokenKeepsDialogOpenAndShowsFieldError() {
        val (dialog, bindings) = buildDialog()
        bindings.inputUrl.setText("https://example.com")
        bindings.inputToken.setText("")
        bindings.saveButton.performClick()
        ShadowLooper.idleMainLooper() // process click listener

        assertTrue("dialog must stay open on blank token", dialog.isShowing)
        val errorText = bindings.tokenLayout.error?.toString()
        assertNotNull("token field must show an error message", errorText)
    }

    @Test
    fun validInputDismissesDialog() {
        val (dialog, bindings) = buildDialog()
        bindings.inputUrl.setText("https://pcontrol.example.com")
        bindings.inputToken.setText("test-device-token-123")
        bindings.saveButton.performClick()
        ShadowLooper.idleMainLooper() // process click listener + dismiss

        // Dialog must dismiss.
        assertFalse("dialog must dismiss on valid input", dialog.isShowing)
    }
}