package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

// ITEM_PARSING_INCOMPLETE 알림 템플릿을 넣는다(#944).
//   title "${itemName}" · body "일부 정보만 찾았어요. 나머지는 직접 채워주세요."
//
// 완료(ITEM_PARSING_COMPLETED)와 문구가 갈려야 해서 타입을 따로 둔다 — 템플릿은 타입당 하나라 한 타입 안에서
// 상태별로 문구를 가를 수 없다. 실패가 아니라 "사용자가 나머지를 채우면 완성된다"는 안내이므로 실패 문구와도 다르다.
//
// title·body 분리는 완료 알림(#913)과 같은 이유다: OS 푸시 제목은 줄바꿈 없이 뒤가 잘려, 이름과 상태를 한 줄에 담으면
// 이름이 길 때 정작 무슨 일인지가 사라진다. body 는 변수 없는 고정 문구라 백오피스(#252)가 그대로 편집한다.
//
// 리터럴 dollar-brace(${...})를 SQL 마이그레이션에 두면 Flyway 가 placeholder 로 오인해 파싱이 깨지므로,
// seed(V20260615015148)·직전 변경들과 같이 JDBC 로 직접 INSERT 한다.
@Suppress("ClassName")
class V20260815055331__seed_item_parsing_incomplete_template : BaseJavaMigration() {
    override fun migrate(context: Context) {
        context.connection
            .prepareStatement(
                "INSERT INTO notification_templates (type, title_template, body_template, updated_at) VALUES (?, ?, ?, NOW(6))",
            ).use { statement ->
                statement.setString(1, "ITEM_PARSING_INCOMPLETE")
                statement.setString(2, "\${itemName}")
                statement.setString(3, "일부 정보만 찾았어요. 나머지는 직접 채워주세요.")
                statement.executeUpdate()
            }
    }
}
