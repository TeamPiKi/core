package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

// ITEM_PARSING_COMPLETED 알림 문구에 아이템 이름 변수를 넣는다(#895): "상품 정보가 저장됐어요" -> "${itemName} 파싱이 완료되었어요".
// title_template 이 이 알림의 표시 문구다(body 는 빈 값). itemName 은 ItemParsingCompletedHandler 가 발송 시점에 채운다.
// 리터럴 dollar-brace(${itemName})를 SQL 마이그레이션에 두면 Flyway 가 placeholder 로 오인해 파싱이 깨지므로,
// seed(V20260615015148)와 같이 JDBC 로 직접 UPDATE 한다. 이후 문구 미세조정은 백오피스(#252)에서 가능하다.
@Suppress("ClassName")
class V20260806230101__update_item_parsing_completed_template_item_name : BaseJavaMigration() {
    override fun migrate(context: Context) {
        context.connection
            .prepareStatement(
                "UPDATE notification_templates SET title_template = ?, updated_at = NOW(6) WHERE type = 'ITEM_PARSING_COMPLETED'",
            ).use { statement ->
                statement.setString(1, "\${itemName} 파싱이 완료되었어요")
                statement.executeUpdate()
            }
    }
}
