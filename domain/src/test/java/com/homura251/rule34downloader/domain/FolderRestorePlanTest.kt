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
        val plan = FolderRestorePlan.create(listOf(directory("pool_123"), directory("unknown_artist"), directory("not*an_artist")), emptyList(), true)
        assertTrue(plan.additions.isEmpty()); assertTrue(plan.targets.isEmpty())
        assertEquals(1, plan.poolDirectories); assertEquals(2, plan.unrecognizedDirectories)
    }
    @Test fun emptyAuthorDirectoriesRestoreSubscriptionsAndRepeatedNamesAreDeduplicated() {
        val plan = FolderRestorePlan.create(listOf(SavedDownloadDirectory("empty", null, 0), directory("artist"), directory("artist")), emptyList(), true)
        assertEquals(2, plan.additions.size); assertEquals(setOf("empty", "artist"), plan.targets)
        assertEquals(RestoredSubscription("empty", 0), plan.additions.first())
    }
    @Test fun renamedMediaDoesNotPreventRecoveryFromAnAuthorDirectoryName() {
        val plan = FolderRestorePlan.create(listOf(SavedDownloadDirectory("artist", null, 0, 3)), emptyList(), false)
        assertEquals(listOf(RestoredSubscription("artist", 0)), plan.additions)
        assertEquals(setOf("artist"), plan.targets)
        assertEquals(0, plan.unrecognizedDirectories)
    }
    @Test fun spacedAuthorNamesNormalizeLikeManuallyAddedTagsAndKeepPauseIntent() {
        val dirs = listOf(SavedDownloadDirectory("artist one", null, 0), directory("artist_one"),
            SavedDownloadDirectory("paused artist", null, 0))
        val plan = FolderRestorePlan.create(dirs, listOf(collection("paused_artist", true)), false)
        assertEquals(listOf(RestoredSubscription("artist_one", 42)), plan.additions)
        assertEquals(setOf("artist_one"), plan.targets)
        assertEquals(1, plan.paused)
        assertEquals(0, plan.unrecognizedDirectories)
    }
    @Test fun emptyLibraryRootsAndMediaContainersAreNotMistakenForAuthors() {
        val plan = FolderRestorePlan.create(listOf("Rule34 Downloader", "Download", "images", "authors", "backup")
            .map { SavedDownloadDirectory(it, null, 0) }, emptyList(), true)
        assertTrue(plan.additions.isEmpty()); assertTrue(plan.targets.isEmpty())
        assertEquals(0, plan.unrecognizedDirectories)
    }
    @Test fun hashNamedMediaCanRestoreAnAuthorWithoutAPostIdInItsFilename() {
        val plan = FolderRestorePlan.create(listOf(SavedDownloadDirectory("artist", null, 3)), emptyList(), true)
        assertEquals(listOf(RestoredSubscription("artist", 0)), plan.additions)
        assertEquals(setOf("artist"), plan.targets)
    }
    @Test fun automaticDiscoveryAddsNewAuthorsWithoutRevivingRemovedSubscriptions() {
        val dirs = listOf(directory("removed"), directory("new"), directory("kept"))
        val plan = FolderRestorePlan.create(dirs, listOf(collection("kept")), false, setOf("removed", "kept"))
        assertEquals(listOf(RestoredSubscription("new", 42)), plan.additions)
        assertEquals(setOf("new", "kept"), plan.targets)
        assertEquals(setOf("removed", "new", "kept"), plan.discoveredAuthors)
        assertEquals(1, plan.removedAuthors)
    }
    @Test fun anExplicitRestoreAndAnInterruptedRestoreCanRecreatePreviouslyRemovedAuthors() {
        val plan = FolderRestorePlan.create(listOf(directory("removed")), emptyList(), false,
            setOf("removed"), setOf("removed"))
        assertEquals(listOf(RestoredSubscription("removed", 42)), plan.additions)
        assertEquals(setOf("removed"), plan.targets)
        assertEquals(0, plan.removedAuthors)
    }
    @Test fun savedOriginalTagsSurviveSanitizedFolderNamesAndCaseChanges() {
        val dirs = listOf(directory("name_a"), directory("NAME_A"))
        val skipped = FolderRestorePlan.create(dirs, emptyList(), false, setOf("name:a"))
        assertTrue(skipped.additions.isEmpty()); assertTrue(skipped.targets.isEmpty())
        assertEquals(setOf("name:a"), skipped.discoveredAuthors)
        assertEquals(1, skipped.removedAuthors)
        val explicit = FolderRestorePlan.create(dirs, emptyList(), false, setOf("name:a"), setOf("name:a"))
        assertEquals(listOf(RestoredSubscription("name:a", 42)), explicit.additions)
    }
    @Test fun newCaseVariantDirectoriesProduceOnlyOneSubscription() {
        val plan = FolderRestorePlan.create(listOf(directory("Artist"), directory("artist")), emptyList(), false)
        assertEquals(1, plan.additions.size)
        assertEquals(setOf("Artist"), plan.targets)
    }
}
