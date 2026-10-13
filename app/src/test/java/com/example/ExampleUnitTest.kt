package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testAppModes() {
    val modes = com.example.ui.AppMode.values()
    assertTrue(modes.contains(com.example.ui.AppMode.TEST_TX))
    assertTrue(modes.contains(com.example.ui.AppMode.TX))
    assertTrue(modes.contains(com.example.ui.AppMode.RX))
    assertTrue(modes.contains(com.example.ui.AppMode.UNSELECTED))
  }

  @Test
  fun testLatencyEngineConstants() {
    assertEquals(1280, com.example.relay.LatencyTestEngine.WIDTH)
    assertEquals(720, com.example.relay.LatencyTestEngine.HEIGHT)
    assertEquals(3840, com.example.relay.LatencyTestEngine.AUDIO_CHUNK_BYTES)
  }
}
