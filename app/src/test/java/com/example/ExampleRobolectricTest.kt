package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.persona.PersonaConfig
import com.example.ui.components.toPersianDigits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("حامد", appName)

        val version = context.getString(R.string.app_version_display)
        assertEquals("Hamed v1.1", version)

        val credit = context.getString(R.string.about_credit)
        assertEquals("ساخت حامی", credit)
    }

    @Test
    fun `verify four personas exist and are properly configured`() {
        val personas = PersonaConfig.ALL_PERSONAS
        assertEquals(4, personas.size)

        val ids = personas.map { it.id }.toSet()
        assertTrue(ids.contains("keyvan"))
        assertTrue(ids.contains("arash"))
        assertTrue(ids.contains("mahtab"))
        assertTrue(ids.contains("niloufar"))

        for (persona in personas) {
            assertTrue("Name must not be blank", persona.name.isNotBlank())
            assertTrue("System instruction must be detailed Persian instruction", persona.systemInstruction.length > 50)
            assertTrue("Sample prompts should not be empty", persona.samplePrompts.isNotEmpty())
        }
    }

    @Test
    fun `verify persian digits conversion`() {
        assertEquals("۱۲:۴۵", toPersianDigits("12:45"))
        assertEquals("۰۱۲۳۴۵۶۷۸۹", toPersianDigits("0123456789"))
    }
}
