package com.tiredvpn.android.ui

import android.content.Context
import com.google.android.material.textfield.TextInputEditText
import com.tiredvpn.android.R
import com.tiredvpn.android.vpn.ServerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The form, not the validator.
 *
 * [InputValidation] can be perfect and the screen still store 993, because the
 * screen never asked it. So these tests type into the real fields, press the
 * real Save button, and ask [ServerRepository] what was written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerConfigFormTest {

    private lateinit var context: Context
    private lateinit var activity: ServerConfigActivity

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ServerRepository.getServers(context).forEach { ServerRepository.deleteServer(context, it.id) }
        activity = Robolectric.buildActivity(ServerConfigActivity::class.java).setup().get()
    }

    private fun field(id: Int) = activity.findViewById<TextInputEditText>(id)

    private fun fillForm(address: String = "ams.example", port: String, secret: String = "k") {
        field(R.id.serverAddressInput).setText(address)
        field(R.id.serverPortInput).setText(port)
        field(R.id.secretInput).setText(secret)
    }

    private fun save() = activity.findViewById<android.view.View>(R.id.saveButton).performClick()

    private fun stored() = ServerRepository.getServers(context)

    @Test
    fun `a good port is stored`() {
        fillForm(port = "995")
        save()

        assertEquals(1, stored().size)
        assertEquals(995, stored().first().serverPort)
    }

    @Test
    fun `an empty port is refused instead of becoming 993`() {
        fillForm(port = "")
        save()

        assertTrue("nothing may be stored", stored().isEmpty())
        assertNotNull("the field must say why", field(R.id.serverPortInput).error)
    }

    @Test
    fun `junk in the port field is refused`() {
        fillForm(port = "eight")
        save()

        assertTrue(stored().isEmpty())
        assertNotNull(field(R.id.serverPortInput).error)
    }

    @Test
    fun `a port outside the range is refused`() {
        fillForm(port = "70000")
        save()
        assertTrue(stored().isEmpty())
        assertNotNull(field(R.id.serverPortInput).error)

        field(R.id.serverPortInput).setText("0")
        save()
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `every bad field is marked at once`() {
        fillForm(address = "", port = "-5", secret = "")
        save()

        assertTrue(stored().isEmpty())
        assertNotNull(field(R.id.serverAddressInput).error)
        assertNotNull(field(R.id.serverPortInput).error)
        assertNotNull(field(R.id.secretInput).error)
    }

    @Test
    fun `the error clears as soon as the user types`() {
        fillForm(port = "70000")
        save()
        assertNotNull(field(R.id.serverPortInput).error)

        field(R.id.serverPortInput).setText("995")

        assertNull("a stale error is worse than none", field(R.id.serverPortInput).error)
    }
}
