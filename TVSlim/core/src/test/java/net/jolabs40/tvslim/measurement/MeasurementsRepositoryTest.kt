package net.jolabs40.tvslim.measurement

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MeasurementsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file() = File(folder.newFolder(), "mesures.json")

    private fun measurement(
        timestamp: Long,
        active: Int = 90,
        disabled: Int = 0,
        free: Long = 500,
    ) = Measurement(
        timestamp = timestamp,
        activePackages = active,
        disabledPackages = disabled,
        totalMemoryMb = 2450,
        freeMemoryMb = free,
    )

    @Test
    fun `the first measurement becomes the baseline and never moves`() = runTest {
        val repository = MeasurementsRepository(file())
        repository.load()

        repository.record(measurement(timestamp = 1_000, disabled = 0, free = 400))
        repository.record(measurement(timestamp = 2_000, disabled = 30, free = 700))
        repository.record(measurement(timestamp = 3_000, disabled = 56, free = 900))

        val history = repository.history.value
        assertEquals(1_000L, history.reference?.timestamp)
        assertEquals(3_000L, history.last?.timestamp)
        assertEquals(56, history.extraDisabledPackages)
        assertEquals(500L, history.extraFreeMemoryMb)
    }

    @Test
    fun `the gain is read back after the app restarts`() = runTest {
        val target = file()
        val first = MeasurementsRepository(target)
        first.load()
        first.record(measurement(timestamp = 1_000, disabled = 0, free = 400))
        first.record(measurement(timestamp = 2_000, disabled = 56, free = 900))

        // A later session: the reason the baseline is persisted.
        val second = MeasurementsRepository(target)
        second.load()

        assertEquals(1_000L, second.history.value.reference?.timestamp)
        assertEquals(56, second.history.value.extraDisabledPackages)
    }

    @Test
    fun `a single measurement has nothing to compare to`() = runTest {
        val repository = MeasurementsRepository(file())
        repository.load()
        repository.record(measurement(timestamp = 1_000))

        assertFalse(repository.history.value.comparable)
        assertEquals(0, repository.history.value.extraDisabledPackages)
    }

    @Test
    fun `an empty snapshot is not recorded`() = runTest {
        val repository = MeasurementsRepository(file())
        repository.load()

        // An unreachable TV reads as all zeros, which would overwrite the baseline.
        repository.record(Measurement(timestamp = 9_000, 0, 0, 0, 0))

        assertTrue(repository.history.value.reference == null)
    }

    @Test
    fun `a memory loss is shown as is`() = runTest {
        val repository = MeasurementsRepository(file())
        repository.load()
        repository.record(measurement(timestamp = 1_000, free = 900))
        repository.record(measurement(timestamp = 2_000, free = 600))

        assertEquals(-300L, repository.history.value.extraFreeMemoryMb)
    }

    @Test
    fun `resetting the baseline starts from the current state`() = runTest {
        val repository = MeasurementsRepository(file())
        repository.load()
        repository.record(measurement(timestamp = 1_000, disabled = 0))
        repository.record(measurement(timestamp = 2_000, disabled = 56))

        repository.resetReference()

        assertEquals(2_000L, repository.history.value.reference?.timestamp)
        assertEquals(0, repository.history.value.extraDisabledPackages)
        assertFalse(repository.history.value.comparable)
    }
}
