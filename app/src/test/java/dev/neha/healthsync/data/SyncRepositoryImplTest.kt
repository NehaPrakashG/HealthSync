package dev.neha.healthsync.data

import dev.neha.healthsync.data.healthconnect.ChangesPage
import dev.neha.healthsync.data.local.UploadState
import dev.neha.healthsync.data.repository.SyncRepositoryImpl
import dev.neha.healthsync.domain.model.HealthFeature
import dev.neha.healthsync.fakes.FakeBackendApi
import dev.neha.healthsync.fakes.FakeHealthDataSource
import dev.neha.healthsync.fakes.FakeHealthRecordDao
import dev.neha.healthsync.fakes.FakeSyncStateStore
import dev.neha.healthsync.fakes.FakeTimeProvider
import dev.neha.healthsync.fakes.OWN_PACKAGE
import dev.neha.healthsync.fakes.snapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Duration

class SyncRepositoryImplTest {

    private val dataSource = FakeHealthDataSource()
    private val dao = FakeHealthRecordDao()
    private val store = FakeSyncStateStore()
    private val api = FakeBackendApi()
    private val time = FakeTimeProvider()
    private val repo = SyncRepositoryImpl(dataSource, dao, store, api, time)

    private val bothScope = "HEART_RATE,STEPS"

    @Test
    fun `first sync takes a token, imports the 30-day window and uploads`() = runTest {
        dataSource.records = listOf(snapshot("a"), snapshot("b"))

        val report = repo.sync()

        assertTrue(report.fullResync)
        assertEquals(2, report.imported)
        assertEquals(2, report.uploaded)
        assertEquals("token-1", store.tokens[bothScope])
        assertEquals(listOf("a", "b"), api.uploadedIds)
        val (start, end) = dataSource.readRanges.single()
        assertEquals(Duration.ofDays(30), Duration.between(start, end))
    }

    @Test
    fun `history permission widens the initial import window`() = runTest {
        dataSource.history = true

        repo.sync()

        val (start, end) = dataSource.readRanges.single()
        assertEquals(Duration.ofDays(365), Duration.between(start, end))
    }

    @Test
    fun `incremental sync skips own writes and propagates deletions`() = runTest {
        store.tokens[bothScope] = "saved"
        dao.upsert(listOf(snapshot("old").toEntity(UploadState.UPLOADED)))
        dataSource.changePages += ChangesPage(
            upserts = listOf(snapshot("external"), snapshot("mine", origin = OWN_PACKAGE)),
            deletedIds = listOf("old"),
            nextToken = "next",
            hasMore = false,
            tokenExpired = false,
        )

        val report = repo.sync()

        assertFalse(report.fullResync)
        assertEquals(1, report.imported)
        assertEquals(1, report.skippedOwn)
        assertEquals(1, report.deleted)
        assertEquals(listOf("external"), api.uploadedIds)
        assertEquals(listOf("old"), api.deletedIds)
        assertFalse("deleted row is removed after upload", "old" in dao.all)
        assertEquals("next", store.tokens[bothScope])
    }

    @Test
    fun `paged changes are all consumed and token is checkpointed`() = runTest {
        store.tokens[bothScope] = "t0"
        dataSource.changePages += ChangesPage(listOf(snapshot("p1")), emptyList(), "t1", hasMore = true, tokenExpired = false)
        dataSource.changePages += ChangesPage(listOf(snapshot("p2")), emptyList(), "t2", hasMore = false, tokenExpired = false)

        val report = repo.sync()

        assertEquals(2, report.imported)
        assertEquals("t2", store.tokens[bothScope])
    }

    @Test
    fun `expired token falls back to a full import from last sync`() = runTest {
        store.tokens[bothScope] = "stale"
        store.setLastSync(time.now().minus(Duration.ofDays(2)))
        dataSource.records = listOf(snapshot("recovered"))
        dataSource.changePages += ChangesPage(emptyList(), emptyList(), "", hasMore = false, tokenExpired = true)

        val report = repo.sync()

        assertTrue(report.fullResync)
        assertEquals(1, report.imported)
        assertEquals("token-1", store.tokens[bothScope])
        val (start, _) = dataSource.readRanges.single()
        assertEquals(time.now().minus(Duration.ofDays(2)).minus(Duration.ofHours(1)), start)
    }

    @Test
    fun `upload failure keeps records pending and records the error`() = runTest {
        dataSource.records = listOf(snapshot("a"))
        api.failWith = FakeBackendApi.networkError()

        try {
            repo.sync()
            fail("expected IOException")
        } catch (expected: IOException) {
        }

        assertEquals(UploadState.PENDING, dao.all.getValue("a").uploadState)
        assertNotNull("token saved so the pull is not repeated", store.tokens[bothScope])
        assertNotNull(store.currentError)

        // Next run succeeds and drains the outbox.
        api.failWith = null
        dataSource.changePages += ChangesPage(emptyList(), emptyList(), "n", hasMore = false, tokenExpired = false)
        repo.sync()
        assertEquals(UploadState.UPLOADED, dao.all.getValue("a").uploadState)
    }

    @Test
    fun `only granted types are synced, each scope has its own token`() = runTest {
        dataSource.readable = setOf(HealthFeature.STEPS)
        dataSource.records = listOf(snapshot("s"), snapshot("h", feature = HealthFeature.HEART_RATE))

        repo.sync()

        assertEquals(setOf("s"), dao.all.keys)
        assertEquals(setOf("STEPS"), store.tokens.keys)
    }

    @Test
    fun `no readable types means nothing is pulled`() = runTest {
        dataSource.readable = emptySet()

        val report = repo.sync()

        assertEquals(0, report.imported)
        assertTrue(dataSource.readRanges.isEmpty())
    }
}
