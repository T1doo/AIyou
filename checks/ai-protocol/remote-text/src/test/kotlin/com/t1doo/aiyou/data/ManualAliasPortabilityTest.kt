package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual existing export/backup codecs and independently keyed SQL owners, synthetic records only. */
class ManualAliasPortabilityTest {
    private fun alias(people: SqlManualPeopleRepository, id: String, names: List<String> = listOf("小林"),
        aliases: List<ManualPersonAlias> = listOf(ManualPersonAlias("阿林", "小林"))) =
        people.replace(ManualPeopleMutation(people.read(id), names, uuid(), aliases))

    @Test(timeout = 30_000) fun openFormatsDeclareRecordLocalMetadataWithoutRewritingOriginalAndInvalidateOldPreview() = runBlocking {
        ManualPeoplePortabilityTest.PeopleMediaFixture().use { f ->
            val id = f.seed(); alias(f.people!!, id)
            val snapshot = f.originals.capture(listOf(SourceVersion(id, 1)), uuid(), 50)
            for (format in listOf(OriginalExportFormat.JSONL, OriginalExportFormat.MARKDOWN)) {
                val payload = OriginalExportCodec.render(snapshot, format)
                try {
                    val text = payload.copyBytes().toString(Charsets.UTF_8)
                    assertTrue(text.contains("manualAliases")); assertTrue(text.contains("record"))
                    if (format == OriginalExportFormat.JSONL) {
                        val rows = text.lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
                        assertEquals(4, rows.first()["formatVersion"]!!.jsonPrimitive.int)
                        val record = rows[1]; assertEquals(snapshot.records.single().entry.originalText, record["originalText"]!!.jsonPrimitive.content)
                        val relation = record["manualAliases"]!!.jsonArray.single().jsonObject
                        assertEquals("阿林", relation["alias"]!!.jsonPrimitive.content); assertEquals("小林", relation["name"]!!.jsonPrimitive.content)
                        assertEquals("record", relation["scope"]!!.jsonPrimitive.content)
                        assertEquals(OriginalExports.hash(text.lines()[1] + "\n"), rows.last()["recordsSha256"]!!.jsonPrimitive.content)
                    }
                } finally { payload.release() }
            }
            val activities = RecordPackageActivities(emptyList(), emptyList(), listOf(snapshot.records.single().sourceGeneration))
            val activity = OriginalActivityExportCodec.render(snapshot, activities, OriginalExportFormat.JSONL)
            try {
                val text = activity.copyBytes().toString(Charsets.UTF_8)
                assertEquals(3, Json.parseToJsonElement(text.lineSequence().first()).jsonObject["formatVersion"]!!.jsonPrimitive.int)
                assertTrue(text.contains("manualAliases"))
            } finally { activity.release() }
            val preview = f.media.preview(snapshot)
            val prepared = OpenRecordZipCodec.prepare(preview, { error("No images") }, { f.media.isCurrent(preview) })
            val bytes = ByteArrayOutputStream(); try { prepared.writeTo(bytes) { true } } finally { prepared.release() }
            val files = mutableMapOf<String, ByteArray>()
            ZipInputStream(bytes.toByteArray().inputStream()).use { z -> while (true) { val entry = z.nextEntry ?: break; files[entry.name] = z.readBytes() } }
            val manifest = Json.parseToJsonElement(files.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject
            assertEquals(3, manifest["formatVersion"]!!.jsonPrimitive.int)
            assertTrue(manifest["scope"]!!.jsonArray.any { it.jsonPrimitive.content == "manualAliases" })
            assertTrue(files.getValue("records.jsonl").toString(Charsets.UTF_8).contains("阿林"))
            val before = f.people!!.read(id)
            f.people!!.replace(ManualPeopleMutation(before, before.names, uuid(), emptyList()))
            assertFalse(f.originals.isCurrent(snapshot)); assertFalse(f.media.isCurrent(preview))
        }
    }

    @Test(timeout = 30_000) fun v6ExtendedCurrentAndHistoryRoundTripMaximumUtf8AliasMetadataBeyondOldDigestLimit() = runBlocking {
        for (history in listOf(false, true)) ManualPeoplePortabilityTest.PeopleMediaFixture().use { source ->
            ManualPeoplePortabilityTest.PeopleMediaFixture().use { target ->
                val id = source.seed(); source.revise(id)
                val names = List(12) { "人" + it + "🙂".repeat(36) }
                val aliases = List(12) { ManualPersonAlias("别" + it + "🙂".repeat(36), names[it]) }
                alias(source.people!!, id, names, aliases)
                assertTrue((source.base.db.read("SELECT ciphertext FROM entry_manual_people").single().single() as ByteArray).size > 4096)
                val snapshot = source.originals.capture(listOf(SourceVersion(id, 2)), uuid(), 50)
                val bytes = source.export(if (history) source.media.previewHistory(snapshot) else source.media.preview(snapshot))
                val pack = target.media.open({ bytes.inputStream() }, password())
                try {
                    assertEquals(aliases, pack.records.single().peopleAliases)
                    val copy = uuid(); target.media.restore(pack, listOf(copy), 100)
                    assertEquals(aliases, target.people!!.read(copy).aliases)
                    assertEquals(names, target.people!!.read(copy).names)
                    assertEquals("AI_NONE", target.base.db.read("SELECT privacyPolicy FROM entries WHERE id=?", copy).single().single())
                    target.reopen(); assertEquals(aliases, target.people!!.read(copy).aliases)
                } finally { pack.release() }
            }
        }
    }

    @Test(timeout = 30_000) fun fullBackupTranslatesCompletePayloadAcrossKeysAndDoneReplayCannotResurrectRevokedAlias() = runBlocking {
        FullFixture().use { source -> FullFixture(99).use { target ->
            val id = source.entry(); val people = SqlManualPeopleRepository(source.gateway, source.key)
            try { alias(people, id) } finally { people.wipeKey() }
            val snapshot = source.backup.capture(uuid(), 100)
            val prepared = source.backup.seal(snapshot, FullBackupTest.password()); val output = ByteArrayOutputStream()
            try { prepared.writeTo(output) { true } } finally { prepared.release(); snapshot.release() }
            val bytes = output.toByteArray(); val pack = target.backup.open({ bytes.inputStream() }, FullBackupTest.password())
            try {
                assertFalse(target.backup.restore(pack) { true }.replayed)
                val restored = SqlManualPeopleRepository(target.gateway, target.key)
                try {
                    val before = restored.read(id); assertEquals(listOf(ManualPersonAlias("阿林", "小林")), before.aliases)
                    assertFalse((source.db.read("SELECT ciphertext FROM entry_manual_people").single().single() as ByteArray)
                        .contentEquals(target.db.read("SELECT ciphertext FROM entry_manual_people").single().single() as ByteArray))
                    restored.replace(ManualPeopleMutation(before, before.names, uuid(), emptyList()))
                    assertTrue(target.backup.restore(pack) { true }.replayed)
                    assertTrue(restored.read(id).aliases.isEmpty())
                } finally { restored.wipeKey() }
            } finally { pack.release() }
        } }
    }

    @Test(timeout = 30_000) fun newCopyRestoreReplayDoesNotUndoAliasRevokeAndLegacyV6StillReadsWithoutAliases() = runBlocking {
        ManualPeoplePortabilityTest.PeopleMediaFixture().use { source -> ManualPeoplePortabilityTest.PeopleMediaFixture().use { target ->
            val id = source.seed(); alias(source.people!!, id)
            val snapshot = source.originals.capture(listOf(SourceVersion(id, 1)), uuid(), 50)
            val bytes = source.export(source.media.preview(snapshot)); val copy = uuid()
            target.restore(bytes, copy); val before = target.people!!.read(copy)
            target.people!!.replace(ManualPeopleMutation(before, before.names, uuid(), emptyList()))
            assertTrue(target.restore(bytes, uuid()).replayed); assertTrue(target.people!!.read(copy).aliases.isEmpty())
            val current = source.people!!.read(id)
            source.people!!.replace(ManualPeopleMutation(current, current.names, uuid(), emptyList()))
            val legacy = source.originals.capture(listOf(SourceVersion(id, 1)), uuid(), 60)
            val oldFormat = source.export(source.media.preview(legacy))
            val pack = target.media.open({ oldFormat.inputStream() }, password())
            try { assertTrue(pack.records.single().peopleAliases.isEmpty()); assertEquals(listOf("小林"), pack.records.single().people) }
            finally { pack.release() }
        } }
    }
    companion object {
        private fun uuid() = UUID.randomUUID().toString()
        private fun password() = "synthetic manual people package 42".toCharArray()
    }
}
