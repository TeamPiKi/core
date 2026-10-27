package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

// 리터럴 dollar-brace 는 SQL 마이그레이션에서 Flyway placeholder 로 읽혀 JDBC 로 쓴다
@Suppress("ClassName")
class V20261019211847__update_tournament_item_added_template_to_candidate : BaseJavaMigration() {
    override fun migrate(context: Context) {
        context.connection
            .prepareStatement("UPDATE notification_templates SET title_template = ?, updated_at = NOW(6) WHERE type = ?")
            .use { statement ->
                statement.setString(1, "\${actorName}님이 토너먼트 후보를 담았어요")
                statement.setString(2, "TOURNAMENT_ITEM_ADDED")
                statement.executeUpdate()
            }
    }
}
