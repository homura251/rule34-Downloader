package com.homura251.rule34downloader.domain

import org.junit.Assert.*
import org.junit.Test

class FolderRestorePlanTest {
    private fun directory(name: String, id: Long = 42) = SavedDownloadDirectory(name, id, 3)
    private fun collection(name: String, paused: Boolean = false, state: SyncState = SyncState.COMPLETE) =
        CollectionRecord(name, 7, 100, paused, syncState = state)

    @Test fun restoresSubscriptionsFromEveryAuthorDirectoryAfterDatabaseLoss() {
        val plan = FolderRestorePlan.create(listOf(directory("author_a"), directory("author_b", 64)), emptyList(), true)
        assertEquals(listOf(RestoredSubscription("author_a", 42), RestoredSubscription("author_b", 64)), plan.additions)
        assertEquals(setOf("author_a", "author_b"), plan.targets)
    }
    @Test fun completedAndErrorSubscriptionsAreScheduledWithoutRecreatingThem() {
        val existing = listOf(collection("complete"), collection("failed", state = SyncState.ERROR))
        val plan = FolderRestorePlan.create(listOf(directory("complete"), directory("failed")), existing, true)
        assertTrue(plan.additions.isEmpty())
        assertEquals(setOf("complete", "failed"), plan.targets)
    }
    @Test fun explicitPauseSurvivesAssociationAndMissingFolder() {
        val plan = FolderRestorePlan.create(emptyList(), listOf(collection("paused", true), collection("ready")), true)
        assertEquals(1, plan.paused)
        assertEquals(setOf("ready"), plan.targets)
    }
    @Test fun singleAuthorAssociationDoesNotScheduleUnrelatedSubscriptions() {
        val plan = FolderRestorePlan.create(listOf(directory("selected")), listOf(collection("selected"), collection("other")), false)
        assertEquals(setOf("selected"), plan.targets)
    }
    @Test fun existingSanitizedFolderKeepsItsOriginalTagAndPauseIntent() {
        val plan = FolderRestorePlan.create(listOf(directory("name_a")), listOf(collection("name:a", true)), false)
        assertTrue(plan.additions.isEmpty()); assertTrue(plan.targets.isEmpty()); assertEquals(1, plan.paused)
    }
    @Test fun poolsAndUnrecognizableDirectoriesAreReportedRatherThanInventingAuthors() {
        val plan = FolderRestorePlan.create(listOf(directory("pool_123"), directory("unknown_artist"), directory("my photos")), emptyList(), true)
        assertTrue(plan.additions.isEmpty()); assertTrue(plan.targets.isEmpty())
        assertEquals(1, plan.poolDirectories); assertEquals(2, plan.unrecognizedDirectories)
    }
    @Test fun emptyDirectoriesDoNotCreateSubscriptionsAndRepeatedNamesAreDeduplicated() {
        val plan = FolderRestorePlan.create(listOf(SavedDownloadDirectory("empty", null, 0), directory("artist"), directory("artist")), emptyList(), true)
        assertEquals(1, plan.additions.size); assertEquals(setOf("artist"), plan.targets)
    }
    @Test fun hashNamedMediaCanRestoreAnAuthorWithoutAPostIdInItsFilename() {
        val plan = FolderRestorePlan.create(listOf(SavedDownloadDirectory("artist", null, 3)), emptyList(), true)
        assertEquals(listOf(RestoredSubscription("artist", 0)), plan.additions)
        assertEquals(setOf("artist"), plan.targets)
    }
}
