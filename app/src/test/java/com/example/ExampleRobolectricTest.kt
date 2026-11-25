package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CallRoom
import com.example.data.DeviceSessionId
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
    assertEquals("PulseCall", appName)
  }

  @Test
  fun `device session id persists across calls`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val first = DeviceSessionId.getOrCreate(context)
    val second = DeviceSessionId.getOrCreate(context)
    assertTrue(first.startsWith("and-"))
    assertEquals(first, second)
  }

  @Test
  fun `call room defaults initialize epochs cleanly`() {
    val room = CallRoom()
    assertEquals(1, room.offerEpoch)
    assertEquals(0, room.answerEpoch)
    assertEquals(0, room.renegotiateEpoch)
    assertEquals("waiting", room.status)
  }
}
