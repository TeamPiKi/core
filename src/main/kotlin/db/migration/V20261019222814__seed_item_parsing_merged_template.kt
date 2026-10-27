package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

// 리터럴 dollar-brace 는 SQL 마이그레이션에서 Flyway placeholder 로 읽혀 JDBC 로 쓴다
@Suppress("ClassName")
class V20261019222814__seed_item_parsing_merged_template : BaseJavaMigration() {
    override fun migrate(context: Context) {
        context.connection
            .prepareStatement("INSERT INTO notification_templates (type, title_template, body_template, updated_at) VALUES (?, ?, ?, NOW(6))")
            .use { statement ->
                statement.setString(1, "ITEM_PARSING_MERGED")
                statement.setString(2, "\${itemName}")
                statement.setString(3, "\${mergedMessage}")
                statement.executeUpdate()
            }
    }
}
