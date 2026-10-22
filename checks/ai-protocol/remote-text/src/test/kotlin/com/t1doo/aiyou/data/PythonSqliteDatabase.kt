package com.t1doo.aiyou.data

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A transport for actual SQL, with one independently owned Python PID per connection. */
internal class PythonSqliteDatabase(
    private val databasePath: Path,
    private val bridgePath: Path,
    initialize: Boolean,
    schemaVersion: Int = 3,
    schemaFile:Path?=null,
) : RecordsSqlDatabase, Closeable {
    private val process: Process
    private val responses = LinkedBlockingQueue<String>()
    private val sequence = AtomicLong()
    private val monitor = Any()
    private val writer: java.io.BufferedWriter
    val pid: Long
    val sqliteVersion: String
    @Volatile var beforeSql: ((String) -> Unit)? = null
    @Volatile var afterSql: ((String) -> Unit)? = null
    @Volatile var beforeCommit: (() -> Unit)? = null
    private var transactionDepth = 0
    private var ended = false

    init {
        val schema = schemaFile ?: repositoryRoot().resolve("core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/$schemaVersion.json")
        process = ProcessBuilder("python3", "-u", bridgePath.toString(), databasePath.toString(), schema.toString(),
            if (initialize) "initialize" else "open").start()
        writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
        Thread({
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.length > 8 * 1024 * 1024) responses.offer("BRIDGE_OVERSIZE")
                    else responses.offer(line)
                }
            }
            responses.offer("BRIDGE_EOF")
        }, "owned-sqlite-stdout-${process.pid()}").apply { isDaemon = true; start() }
        Thread({
            process.errorStream.use { stream ->
                val bytes = ByteArray(1024)
                while (stream.read(bytes) >= 0) Unit // drain without logging source data or exception traces
            }
        }, "owned-sqlite-stderr-${process.pid()}").apply { isDaemon = true; start() }
        try {
            val hello = takeLine().split('\t')
            check(hello.size == 5 && hello[0] == "HELLO" && hello[3] == "wal" && hello[4] == "1") {
                "Real SQLite bridge initialization failed"
            }
            pid = hello[1].toLong()
            check(pid == process.pid())
            sqliteVersion = hello[2]
            // Same guards as production cold-open. No hand-copied REPLACE/epoch rules in fixtures.
            transaction { sql ->
                val tables=sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('attachments','local_search_epoch')").map{it.single() as String}.toSet()
                if("attachments" in tables)AttachmentIntentSqlSchema.guards.forEach{sql.execute(it)}
                if(tables.containsAll(listOf("attachments","local_search_epoch")))ReadyImageSqlSchema.epochGuards.forEach{sql.execute(it)}
            }
        } catch (failure: Throwable) {
            try { writer.close() } finally {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    check(process.waitFor(1, TimeUnit.SECONDS)) { "Owned failed SQLite PID did not exit" }
                }
            }
            throw failure
        }
    }

    override fun <T> transaction(block: (RecordsSqlConnection) -> T): T = synchronized(monitor) {
        check(!ended)
        val connection = Connection()
        // Nested product calls use real SQLite savepoints inside the owned outer transaction.
        // This is transaction plumbing; authorization still runs only in product Kotlin/SQL.
        val outermost = transactionDepth == 0
        val savepoint = "owned_nested_${sequence.incrementAndGet()}"
        connection.execute(if (outermost) "BEGIN IMMEDIATE" else "SAVEPOINT $savepoint")
        transactionDepth++
        try {
            val result = block(connection)
            if (outermost) beforeCommit?.invoke()
            connection.execute(if (outermost) "COMMIT" else "RELEASE SAVEPOINT $savepoint")
            result
        } catch (failure: Throwable) {
            try {
                connection.execute(if (outermost) "ROLLBACK" else "ROLLBACK TO SAVEPOINT $savepoint")
                if (!outermost) connection.execute("RELEASE SAVEPOINT $savepoint")
            } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        } finally {
            transactionDepth--
        }
    }

    fun read(sql: String, vararg arguments: Any?): List<List<Any?>> = transaction { it.query(sql, arguments.toList()) }
    fun write(sql: String, vararg arguments: Any?): Int = transaction { it.execute(sql, arguments.toList()) }

    private inner class Connection : RecordsSqlConnection {
        override fun query(sql: String, args: List<Any?>): List<List<Any?>> {
            val fields = command("Q", sql, args)
            check(fields[1] == "Q")
            var at = 3
            val rows = List(fields[2].toInt()) {
                val columns = fields[at++].toInt()
                List(columns) { decode(fields[at++]) }
            }
            check(at == fields.size)
            afterSql?.invoke(sql)
            return rows
        }

        override fun execute(sql: String, args: List<Any?>): Int {
            val fields = command("X", sql, args)
            check(fields.size == 3 && fields[1] == "O")
            afterSql?.invoke(sql)
            return fields[2].toInt()
        }
    }

    private fun command(operation: String, sql: String, args: List<Any?>): List<String> {
        beforeSql?.invoke(sql)
        val id = sequence.incrementAndGet().toString()
        writer.write((listOf(id, operation, encode(sql), args.size.toString()) + args.map(::encode)).joinToString("\t"))
        writer.newLine(); writer.flush()
        val fields = takeLine().split('\t')
        check(fields.size >= 3 && fields[0] == id) { "SQLite bridge response mismatch" }
        if (fields[1] == "E") throw SqliteBridgeFailure(fields[2].toInt(), fields.getOrElse(3) { "SQLITE_ERROR" })
        return fields
    }

    private fun takeLine(): String = responses.poll(2, TimeUnit.SECONDS)
        ?: throw IllegalStateException("Owned SQLite process exceeded response deadline")

    /** Test owner only: exercise physical rekeying between product SQL leases, never during one. */
    fun vacuumForSyntheticTest()=synchronized(monitor){check(!ended&&transactionDepth==0);Connection().execute("VACUUM");Unit}

    override fun close() = synchronized(monitor) {
        if (ended) return@synchronized
        ended = true
        try {
            if (process.isAlive) {
                val id = sequence.incrementAndGet().toString()
                writer.write("$id\tC"); writer.newLine(); writer.flush()
                check(takeLine() == "$id\tO\t0")
            }
        } finally {
            try { writer.close() } finally {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    check(process.waitFor(1, TimeUnit.SECONDS)) { "Owned SQLite PID did not exit" }
                }
                check(!process.isAlive)
            }
        }
    }

    companion object {
        fun repositoryRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first {
            Files.isRegularFile(it.resolve("core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/3.json"))
        }

        private fun encode(value: Any?): String = when (value) {
            null -> "N"
            is Int, is Long -> "I:$value"
            is String -> {
                val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
                val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                "S:" + Base64.getEncoder().encodeToString(bytes)
            }
            is ByteArray -> "B:" + Base64.getEncoder().encodeToString(value)
            else -> error("Unsupported SQLite binding type")
        }

        private fun decode(value: String): Any? {
            if (value == "N") return null
            return when (value.take(2)) {
                "I:" -> value.drop(2).toLong()
                "S:" -> Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Base64.getDecoder().decode(value.drop(2)))).toString()
                "B:" -> Base64.getDecoder().decode(value.drop(2))
                else -> error("Unsupported SQLite result type")
            }
        }
    }
}

internal class SqliteBridgeFailure(val code: Int, val category: String) : RuntimeException("SQLite bridge failure: $category")

/** Only owned synthetic corruption tests cross the live immutable UPDATE guard. */
internal fun PythonSqliteDatabase.injectSyntheticAttachmentCorruption(sql:String,vararg args:Any?) = transaction { connection ->
    require(sql.startsWith("UPDATE attachments SET "))
    connection.execute("DROP TRIGGER immutable_attachment_intent")
    connection.execute(sql,args.toList())
    AttachmentIntentSqlSchema.guards.forEach{connection.execute(it)}
}
