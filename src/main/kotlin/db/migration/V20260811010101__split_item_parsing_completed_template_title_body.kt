package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

// ITEM_PARSING_COMPLETED 문구를 title=아이템 이름 / body=상태 로 나눈다(#913).
//   전: title "${itemName} 파싱이 완료되었어요"  · body ""
//   후: title "${itemName}"                      · body "${completionMessage}"
//
// OS 푸시 제목은 줄바꿈 없이 뒤가 잘려서, 이름과 상태를 한 줄에 담으면 이름이 길 때 정작 무슨 일인지가 사라진다.
// body 는 두 줄까지 보이므로 상태를 그쪽으로 옮긴다.
//
// completionMessage 는 문장을 통째로 담는 변수다 — 이 테이블은 타입당 한 행(PK=type)이라 위시용·토너먼트용 body 를
// 따로 둘 자리가 없고, 두 문장이 구조도 달라 공통 뼈대 + 변수로 안 쪼개진다. ItemParsingCompletedHandler 가 등록
// 출처(라우팅)를 보고 채운다.
//
// 리터럴 dollar-brace(${...})를 SQL 마이그레이션에 두면 Flyway 가 placeholder 로 오인해 파싱이 깨지므로,
// seed(V20260615015148)·직전 변경(V20260806230101)과 같이 JDBC 로 직접 UPDATE 한다.
@Suppress("ClassName")
class V20260811010101__split_item_parsing_completed_template_title_body : BaseJavaMigration() {
    override fun migrate(context: Context) {
        context.connection
            .prepareStatement(
                "UPDATE notification_templates SET title_template = ?, body_template = ?, updated_at = NOW(6) " +
                    "WHERE type = 'ITEM_PARSING_COMPLETED'",
            ).use { statement ->
                statement.setString(1, "\${itemName}")
                statement.setString(2, "\${completionMessage}")
                statement.executeUpdate()
            }
    }
}
