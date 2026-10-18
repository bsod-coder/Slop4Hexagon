package com.slop4hexagon.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPlannerTest {

    private fun inspection(format: ModelFormat): ModelInspection = ModelInspection(
        format = format,
        magicConfirmed = true,
        weightBytes = 100,
        estimatedMemoryBytes = 115,
        tensorCount = 1,
        tensors = emptyList(),
        components = emptyList(),
        formatWarnings = emptyList(),
        inspectionWarnings = emptyList(),
        compatibility = CompatibilityMatrix.forFormat(format, true),
        isSinglePipelineFile = false,
    )

    private fun descriptor(
        id: String,
        name: String,
        size: Long,
        sha: String?,
    ) = ModelDescriptor(
        id = id,
        displayName = name,
        originalFileName = name,
        sizeBytes = size,
        sha256 = sha,
        importedAtEpochMs = 0,
        inspection = inspection(ModelFormat.SAFETENSORS),
    )

    @Test
    fun identicalHashAndSizeIsDuplicate() {
        val existing = listOf(descriptor("1", "a.safetensors", 500, "ABC123"))
        val decision = ImportPlanner.plan("copy.safetensors", 500, "abc123", inspection(ModelFormat.SAFETENSORS), existing)
        assertTrue(decision is ImportDecision.Duplicate)
        assertEquals("1", (decision as ImportDecision.Duplicate).existingId)
    }

    @Test
    fun sameHashDifferentSizeIsNotDuplicate() {
        val existing = listOf(descriptor("1", "a.safetensors", 500, "abc123"))
        val decision = ImportPlanner.plan("b.safetensors", 600, "abc123", inspection(ModelFormat.SAFETENSORS), existing)
        assertTrue(decision is ImportDecision.Proceed)
    }

    @Test
    fun missingHashNeverProducesHardDuplicate() {
        val existing = listOf(descriptor("1", "a.safetensors", 500, null))
        val decision = ImportPlanner.plan("a.safetensors", 500, null, inspection(ModelFormat.SAFETENSORS), existing)
        assertTrue(decision is ImportDecision.Proceed)
        val warnings = (decision as ImportDecision.Proceed).duplicateWarnings
        assertTrue(warnings.any { it.contains("already exists") })
    }

    @Test
    fun unknownFormatIsRefused() {
        val decision = ImportPlanner.plan("weird.bin", 10, "aa", inspection(ModelFormat.UNKNOWN), emptyList())
        assertTrue(decision is ImportDecision.UnsupportedFormat)
    }

    @Test
    fun weightsBundleImportWarnsAboutExecution() {
        val decision = ImportPlanner.plan("model.safetensors", 10, "aa", inspection(ModelFormat.SAFETENSORS), emptyList())
        assertTrue(decision is ImportDecision.Proceed)
        assertTrue((decision as ImportDecision.Proceed).duplicateWarnings.any { it.contains("weights bundle") })
    }
}
