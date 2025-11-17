package eu.idura.verify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CacheResultUnitTest {
  @Before
  fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

  @After
  fun tearDown() = Dispatchers.resetMain()

  @Test
  fun `loading twice in parallel calls load function once`() =
    runTest {
      var callCount = 0
      val get =
        cacheResult(MainScope()) {
          delay(50)
          callCount++
        }

      val res1 = async { get() }
      val res2 = async { get() }
      assert(res1.isActive)

      res1
        .join()
      res2
        .join()

      assertEquals(1, callCount)
    }

  @Test
  fun `loading twice in series calls load function once`() =
    runTest {
      var callCount = 0

      val get =
        cacheResult(MainScope()) {
          delay(50)
          ++callCount
        }

      val res1 = get()
      val res2 = get()

      assertEquals(1, res1)
      assertEquals(1, res2)
      assertEquals(1, callCount)
    }

  @Test
  fun `retries if first execution fails`() =
    runTest {
      var callCount = 0

      val get =
        cacheResult(MainScope()) {
          if (++callCount == 1) {
            throw Exception("foo")
          }
        }

      // Cannot get junit assertThrows to work with suspend functions..
      var exception: Exception? = null
      try {
        get()
      } catch (ex: Exception) {
        exception = ex
      }
      assert(exception != null)
      assertEquals(exception!!.message, "foo")

      get()

      assertEquals(2, callCount)
    }
}
