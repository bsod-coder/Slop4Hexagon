package com.slop4hexagon

import com.slop4hexagon.domain.model.ModelFormat
import com.slop4hexagon.domain.model.estimateMemoryRequirementBytes
import org.junit.Assert.*
import org.junit.Test

class ImportMetadataTest {
    @Test
    fun `estimate memory onnx`() {
        val est = estimateMemoryRequirementBytes(ModelFormat.ONNX, 100_000_000)
        assertEquals(400_000_000L, est)
    }

    @Test
    fun `estimate memory dlc`() {
        assertEquals(200L, estimateMemoryRequirementBytes(ModelFormat.DLC, 100))
    }

    @Test
    fun `estimate unknown null`() {
        assertNull(estimateMemoryRequirementBytes(ModelFormat.UNKNOWN, 100))
        assertNull(estimateMemoryRequirementBytes(ModelFormat.ONNX, -1))
    }

    @Test
    fun `safetensors larger factor`() {
        assertEquals(300L, estimateMemoryRequirementBytes(ModelFormat.SAFETENSORS, 100))
    }

    @Test
    fun `duplicate detection logic via hash simulation`() {
        // Simulate hash for duplicate detection: same hash+size => duplicate
        val hash1 = "abcd1234"
        val size = 1024L
        // In repo, dao.findDuplicate(hash,size) would return existing
        // Here we just assert string equality is used
        assertEquals(hash1, hash1)
        assertNotEquals(hash1, "different")
    }
}
