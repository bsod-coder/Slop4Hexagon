package com.slop4hexagon.core.benchmark

import com.slop4hexagon.core.backend.BackendId
import com.slop4hexagon.core.backend.BackendStatus
import com.slop4hexagon.core.backend.OpKind
import com.slop4hexagon.core.json.JsonObjectBuilder
import com.slop4hexagon.core.json.JsonValue

/**
 * JSON serialization for [BenchmarkReport]. Round-trips through
 * [decode] must preserve all fields (verified by unit tests). The exported
 * document is the exact artifact users can share; it must contain only real
 * measured values.
 */
object BenchmarkReportJson {

    fun encode(report: BenchmarkReport): JsonValue.JsonObject {
        val b = JsonObjectBuilder()
        b.put("schemaVersion", BenchmarkReport.SCHEMA_VERSION)
        b.put("id", report.id)
        b.put("createdAtEpochMs", report.createdAtEpochMs)
        b.put("mode", report.mode.name)
        b.put("backendId", report.backendId.name)
        b.put("backendDisplayName", report.backendDisplayName)
        b.put("backendStatusAtStart", report.backendStatusAtStart.name)
        b.put("backendStatusAtEnd", report.backendStatusAtEnd.name)
        b.put("modelId", report.modelId)
        b.put("modelName", report.modelName)
        b.put("executionVerified", report.executionVerified)

        val cfg = JsonObjectBuilder()
        cfg.put("mode", report.config.mode.name)
        cfg.put("warmupIterations", report.config.warmupIterations)
        cfg.put("measuredIterations", report.config.measuredIterations)
        cfg.put("op", report.config.op?.name)
        cfg.put("rows", report.config.rows)
        cfg.put("inner", report.config.inner)
        cfg.put("cols", report.config.cols)
        cfg.put("modelId", report.config.modelId)
        cfg.put("width", report.config.width)
        cfg.put("height", report.config.height)
        cfg.put("steps", report.config.steps)
        cfg.put("seed", report.config.seed)
        cfg.put("denoisingStrength", report.config.denoisingStrength)
        b.put("config", cfg.build())

        val phases = JsonObjectBuilder()
        phases.put("coldStartNanos", report.phases.coldStartNanos)
        phases.put("initNanos", report.phases.initNanos)
        phases.put("warmupNanos", JsonValue.JsonArray(report.phases.warmupNanos.map { JsonValue.JsonNumber(it.toDouble()) }))
        phases.put("inferenceNanos", JsonValue.JsonArray(report.phases.inferenceNanos.map { JsonValue.JsonNumber(it.toDouble()) }))
        b.put("phases", phases.build())

        val stats = report.stats?.let { s ->
            val sb = JsonObjectBuilder()
            sb.put("count", s.count)
            sb.put("minNanos", s.minNanos)
            sb.put("medianNanos", s.medianNanos)
            sb.put("maxNanos", s.maxNanos)
            sb.put("meanNanos", s.meanNanos)
            sb.put("p95Nanos", s.p95Nanos)
            sb.build()
        }
        b.put("stats", stats)

        report.memory?.let { m ->
            val mb = JsonObjectBuilder()
            mb.put("javaHeapUsedBytes", m.javaHeapUsedBytes)
            mb.put("nativeHeapUsedBytes", m.nativeHeapUsedBytes)
            mb.put("totalPssBytes", m.totalPssBytes)
            mb.put("peakRssBytes", m.peakRssBytes)
            mb.put("note", m.note)
            b.put("memory", mb.build())
        }

        report.thermal?.let { t ->
            val tb = JsonObjectBuilder()
            tb.put("thermalStatus", t.thermalStatus)
            tb.put("thermalStatusName", t.thermalStatusName)
            tb.put("note", t.note)
            b.put("thermal", tb.build())
        }

        report.device?.let { d ->
            val db = JsonObjectBuilder()
            db.put("socModel", d.socModel)
            db.put("hardware", d.hardware)
            db.putStrings("supportedAbis", d.supportedAbis)
            db.put("sdkInt", d.sdkInt)
            db.put("androidRelease", d.androidRelease)
            db.put("manufacturer", d.manufacturer)
            db.put("model", d.model)
            db.put("totalMemBytes", d.totalMemBytes)
            db.put("availMemBytes", d.availMemBytes)
            db.put("appVersion", d.appVersion)
            db.put("runtimeDescription", d.runtimeDescription)
            b.put("device", db.build())
        }

        b.putStrings("failures", report.failures)
        b.putStrings("notes", report.notes)
        return b.build()
    }

    fun encodeToString(report: BenchmarkReport): String = encode(report).toPrettyString()

    fun decode(obj: JsonValue.JsonObject): BenchmarkReport {
        val modeName = obj.getString("mode") ?: BenchmarkMode.MICRO_OP.name
        val mode = BenchmarkMode.entries.firstOrNull { it.name == modeName } ?: BenchmarkMode.MICRO_OP

        val cfgObj = obj.getObject("config") ?: JsonValue.JsonObject(emptyMap())
        val opName = cfgObj.getString("op")
        val config = BenchmarkConfig(
            mode = BenchmarkMode.entries.firstOrNull { it.name == cfgObj.getString("mode") } ?: mode,
            warmupIterations = cfgObj.getInt("warmupIterations") ?: BenchmarkConfig.DEFAULT_WARMUP,
            measuredIterations = cfgObj.getInt("measuredIterations") ?: BenchmarkConfig.DEFAULT_MEASURED,
            op = opName?.let { n -> OpKind.entries.firstOrNull { it.name == n } },
            rows = cfgObj.getInt("rows") ?: 0,
            inner = cfgObj.getInt("inner") ?: 0,
            cols = cfgObj.getInt("cols") ?: 0,
            modelId = cfgObj.getString("modelId"),
            width = cfgObj.getInt("width") ?: 0,
            height = cfgObj.getInt("height") ?: 0,
            steps = cfgObj.getInt("steps") ?: 0,
            seed = cfgObj.getLong("seed") ?: 0L,
            denoisingStrength = cfgObj.getDouble("denoisingStrength"),
        )

        val phasesObj = obj.getObject("phases") ?: JsonValue.JsonObject(emptyMap())
        val phases = PhaseTimings(
            coldStartNanos = phasesObj.getLong("coldStartNanos"),
            initNanos = phasesObj.getLong("initNanos"),
            warmupNanos = phasesObj.getArray("warmupNanos")?.items?.map { numberAsLong(it) } ?: emptyList(),
            inferenceNanos = phasesObj.getArray("inferenceNanos")?.items?.map { numberAsLong(it) } ?: emptyList(),
        )

        val stats = obj.getObject("stats")?.let { s ->
            LatencyStats(
                count = s.getInt("count") ?: 0,
                minNanos = s.getDouble("minNanos") ?: 0.0,
                medianNanos = s.getDouble("medianNanos") ?: 0.0,
                maxNanos = s.getDouble("maxNanos") ?: 0.0,
                meanNanos = s.getDouble("meanNanos") ?: 0.0,
                p95Nanos = s.getDouble("p95Nanos") ?: 0.0,
            )
        }

        val memory = obj.getObject("memory")?.let { m ->
            MemorySnapshot(
                javaHeapUsedBytes = m.getLong("javaHeapUsedBytes"),
                nativeHeapUsedBytes = m.getLong("nativeHeapUsedBytes"),
                totalPssBytes = m.getLong("totalPssBytes"),
                peakRssBytes = m.getLong("peakRssBytes"),
                note = m.getString("note") ?: MemorySnapshot(null, null, null, null).note,
            )
        }

        val thermal = obj.getObject("thermal")?.let { t ->
            ThermalSnapshot(
                thermalStatus = t.getInt("thermalStatus"),
                thermalStatusName = t.getString("thermalStatusName"),
                note = t.getString("note") ?: ThermalSnapshot(null, null).note,
            )
        }

        val device = obj.getObject("device")?.let { d ->
            DeviceReport(
                socModel = d.getString("socModel"),
                hardware = d.getString("hardware"),
                supportedAbis = d.getStringList("supportedAbis") ?: emptyList(),
                sdkInt = d.getInt("sdkInt") ?: 0,
                androidRelease = d.getString("androidRelease") ?: "",
                manufacturer = d.getString("manufacturer"),
                model = d.getString("model"),
                totalMemBytes = d.getLong("totalMemBytes"),
                availMemBytes = d.getLong("availMemBytes"),
                appVersion = d.getString("appVersion") ?: "",
                runtimeDescription = d.getString("runtimeDescription") ?: "",
            )
        }

        return BenchmarkReport(
            id = obj.getString("id") ?: throw IllegalArgumentException("Report missing id"),
            createdAtEpochMs = obj.getLong("createdAtEpochMs") ?: 0L,
            mode = mode,
            config = config,
            backendId = BackendId.entries.firstOrNull { it.name == obj.getString("backendId") }
                ?: throw IllegalArgumentException("Report missing backendId"),
            backendDisplayName = obj.getString("backendDisplayName") ?: "",
            backendStatusAtStart = BackendStatus.entries.firstOrNull {
                it.name == obj.getString("backendStatusAtStart")
            } ?: BackendStatus.UNAVAILABLE,
            backendStatusAtEnd = BackendStatus.entries.firstOrNull {
                it.name == obj.getString("backendStatusAtEnd")
            } ?: BackendStatus.UNAVAILABLE,
            modelId = obj.getString("modelId"),
            modelName = obj.getString("modelName"),
            phases = phases,
            stats = stats,
            memory = memory,
            thermal = thermal,
            device = device,
            executionVerified = obj.getBool("executionVerified") ?: false,
            failures = obj.getStringList("failures") ?: emptyList(),
            notes = obj.getStringList("notes") ?: emptyList(),
        )
    }

    fun decodeFromString(text: String): BenchmarkReport = decode(JsonValue.parseObject(text))

    private fun numberAsLong(value: JsonValue): Long =
        (value as? JsonValue.JsonNumber)?.value?.toLong()
            ?: throw IllegalArgumentException("Expected a number")
}
