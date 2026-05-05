package maestro.drivers

import com.google.common.truth.Truth.assertThat
import device.IOSDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ios.IOSDeviceErrors
import ios.xctest.XCTestIOSDevice
import maestro.DeviceUnreachableException
import maestro.MaestroException
import maestro.utils.ScreenshotUtils
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.api.DeviceInfo
import xcuitest.installer.XCTestInstaller
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class IOSDriverTest {

    @Test
    fun `IOSDeviceErrors Unreachable from the device is translated to DeviceUnreachableException`() {
        val cause = SocketTimeoutException("Read timed out")
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } throws IOSDeviceErrors.Unreachable("deviceInfo", cause)

        val driver = IOSDriver(iosDevice)

        val thrown = assertThrows<DeviceUnreachableException> { driver.deviceInfo() }
        assertThat(thrown.callName).isEqualTo("deviceInfo")
        assertThat(thrown.cause).isInstanceOf(IOSDeviceErrors.Unreachable::class.java)
        assertThat(thrown.cause?.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `IOSDriver does not cache - subsequent calls invoke the device again`() {
        // Fail-fast for the dead-runner case lives at the transport layer (XCTestDriverClient).
        // IOSDriver is now a thin translator: it converts each IOSDeviceErrors.Unreachable into
        // a DeviceUnreachableException without short-circuiting on its own. When the underlying
        // device keeps throwing (mimicking a still-tripped transport latch), the driver translates
        // each call independently.
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } throws IOSDeviceErrors.Unreachable("deviceInfo", SocketTimeoutException())

        val driver = IOSDriver(iosDevice)

        assertThrows<DeviceUnreachableException> { driver.deviceInfo() }
        assertThrows<DeviceUnreachableException> { driver.deviceInfo() }
        verify(exactly = 2) { iosDevice.deviceInfo() }
    }

    @Test
    fun `non-transport exceptions still translate to their MaestroException counterparts`() {
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } throws IOSDeviceErrors.AppCrash("crashed")

        val driver = IOSDriver(iosDevice)

        assertThrows<MaestroException.AppCrash> { driver.deviceInfo() }
        assertThrows<MaestroException.AppCrash> { driver.deviceInfo() }
        verify(exactly = 2) { iosDevice.deviceInfo() }
    }

    @Test
    fun `successful calls pass through unchanged`() {
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } returns DeviceInfo(
            widthPixels = 1170,
            heightPixels = 2532,
            widthPoints = 390,
            heightPoints = 844,
        )

        val driver = IOSDriver(iosDevice)

        driver.deviceInfo()
        driver.deviceInfo()
        driver.deviceInfo()

        verify(exactly = 3) { iosDevice.deviceInfo() }
    }

    @Test
    fun `waitUntilScreenIsStatic respects user timeout when XCTest screenshot HTTP is slow`() {
        // Bug: MaestroTimer.retryUntilTrue (called by ScreenshotUtils.waitUntilScreenIsStatic)
        // checks elapsed time only BETWEEN iterations of block(). One iteration calls
        // driver.takeScreenshot twice. When the XCUITest /screenshot endpoint is slow
        // (active animation, AX-busy contexts), one full iteration takes >> timeoutMs and
        // the user-supplied timeout is effectively ignored — actual elapsed ≈ 2 × per-call cost.
        //
        // Path exercised here, real all the way down to the socket — slowness lives where
        // the real bug lives (the iOS XCUITest server taking a long time to respond), NOT
        // in OkHttp interceptor application code (which Call.timeout() explicitly does not
        // cover, per OkHttp docs). MockWebServer.setHeadersDelay produces real on-the-wire
        // delay so any future Call.timeout() fix would actually be exercised here.
        //
        //   ScreenshotUtils.waitUntilScreenIsStatic
        //     -> driver.takeScreenshot              (real IOSDriver)
        //       -> iosDevice.takeScreenshot          (real XCTestIOSDevice)
        //         -> client.screenshot               (real XCTestDriverClient + default OkHttpClient)
        //           -> HTTP GET against MockWebServer (real socket I/O, headers delayed)

        val timeoutMs = 200L
        val perRequestDelayMs = 1500L

        val mockServer = MockWebServer()
        try {
            // Enqueue many varied PNGs so consecutive screenshots compare as different —
            // simulates a non-settling animation. Without this, screenshots would match
            // on the first iteration and the timing assertion would become vacuous.
            repeat(8) { i ->
                val img = BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB)
                for (x in 0 until 20) for (y in 0 until 20) {
                    img.setRGB(x, y, ((i + 1) * 67890 + x * 31 + y) and 0xFFFFFF)
                }
                val baos = ByteArrayOutputStream()
                ImageIO.write(img, "png", baos)
                mockServer.enqueue(
                    MockResponse()
                        .setHeadersDelay(perRequestDelayMs, TimeUnit.MILLISECONDS)
                        .setHeader("Content-Type", "image/png")
                        .setBody(Buffer().write(baos.toByteArray()))
                )
            }
            mockServer.start()

            val driverClient = XCTestDriverClient(
                installer = NoopInstaller,
                client = XCTestClient(mockServer.hostName, mockServer.port),
            )
            val xcTestDevice = XCTestIOSDevice(
                deviceId = "test-device",
                client = driverClient,
                getInstalledApps = { emptySet() },
            )
            val driver = IOSDriver(xcTestDevice)

            val start = System.currentTimeMillis()
            ScreenshotUtils.waitUntilScreenIsStatic(
                timeoutMs = timeoutMs,
                threshold = 0.005,
                driver = driver,
            )
            val elapsedMs = System.currentTimeMillis() - start

            assertThat(elapsedMs).isLessThan(timeoutMs + 500)
        } finally {
            mockServer.shutdown()
        }
    }

    private object NoopInstaller : XCTestInstaller {
        override fun start(): XCTestClient = error("not used")
        override fun uninstall(): Boolean = error("not used")
        override fun isChannelAlive(): Boolean = error("not used")
        override fun close() {}
    }
}
