package com.t1doo.aiyou.records

import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class ReminderColdSchemaTest {
    @Test fun actualSchemaValidatorRejectsVersionIdentityGuardAndColumnsWithoutRepairOrDataWrites(){
        for(change in listOf("PRAGMA user_version=14","UPDATE room_master_table SET identity_hash='synthetic-wrong-schema' WHERE id=42",
            "DROP TRIGGER immutable_manual_task_identity","ALTER TABLE reminder_runtime_meta ADD COLUMN unexpected TEXT")){
            NativeSqlFixture().use{f->
                f.task("synthetic",true);f.validate()
                val applied=if(change.startsWith("DROP TRIGGER"))f.sql.transaction{sql->
                    val trigger=sql.query("SELECT name FROM sqlite_master WHERE type='trigger' AND tbl_name='manual_tasks'").single().single() as String
                    "DROP TRIGGER $trigger"
                }else change
                f.sql.transaction{it.execute(applied)}
                val before=f.sql.transaction{it.query("SELECT type,name,sql FROM sqlite_master ORDER BY type,name")}
                val taskBefore=f.stores.resources.tasks.runtimeView("synthetic")!!.task
                assertTrue(runCatching{f.validate()}.isFailure)
                assertEquals(before,f.sql.transaction{it.query("SELECT type,name,sql FROM sqlite_master ORDER BY type,name")})
                assertEquals(taskBefore,f.stores.resources.tasks.runtimeView("synthetic")!!.task)
                assertEquals(0,f.opened.get())
            }
        }
    }
    @Test fun sqlcipherRawExceptionAndPrivatePathAreReplacedByFixedStatus(){
        com.t1doo.aiyou.data.SqlCipherPrivacyLog.install()
        net.zetetic.database.Logger.e("synthetic-sensitive-tag","/synthetic/private/records.db",IllegalStateException("synthetic-sensitive-payload"))
        val logs=org.robolectric.shadows.ShadowLog.getLogsForTag("AIyouStorage")
        assertTrue(logs.isNotEmpty());assertTrue(logs.all{it.msg=="Encrypted storage operation unavailable"&&it.throwable==null})
    }

}
