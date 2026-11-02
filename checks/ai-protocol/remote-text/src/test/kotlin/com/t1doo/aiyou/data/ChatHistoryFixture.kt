package com.t1doo.aiyou.data

/** Real isolated SQLite and production DDL; legacy send-contract fixtures do not claim Room17 migration. */
internal fun chatHistoryFixture(database:RecordsSqlDatabase):SqlChatHistoryRepository {
    database.transaction {sql->
        if(sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name='chat_messages'").isEmpty())
            ChatMessageSqlSchema.migration16To17.forEach {sql.execute(it)}
        ChatMessageSqlSchema.guards.forEach {sql.execute(it)}
    }
    return SqlChatHistoryRepository(database,ManualTagSqlTest.KEY)
}
