package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

// ITEM_PARSING_COMPLETED body 를 고정 문구 → 변수 "${completionMessage}" 로 바꾼다(#933).
//   전: body "파싱이 완료되었어요" (전 수신자 동일)
//   후: body "${completionMessage}" — dispatch 가 수신자별로 채운다
//       (위시 주인 "위시 저장이 성공했어요" / 토너먼트 등록자 "아이템이 등록됐어요")
//
// 한 snapshot 에 위시 주인·토너먼트 등록자가 함께 붙을 수 있어(공유 #825) body 를 출처별로 갈라야 하는데, 템플릿
// 테이블은 타입당 한 행(PK=type)이라 두 문구를 담을 자리가 없다. 그래서 이 문구는 코드
// (ItemParsingCompletedHandler)가 소유하고 템플릿엔 변수 자리만 둔다 — 이 body 는 백오피스(#252) 편집 대상이
// 아니게 된다(title 의 ${itemName} 은 여전히 백오피스가 편집한다).
//
// 리터럴 dollar-brace(${...})를 SQL 마이그레이션에 두면 Flyway 가 placeholder 로 오인해 파싱이 깨지므로,
// seed(V20260615015148)·직전 변경(V20260811010101)과 같이 JDBC 로 직접 UPDATE 한다.
@Suppress("ClassName")
class V20260816000002__fill_item_parsing_completed_body_completion_message : BaseJavaMigration() {
    override fun migrate(context: Context) {
        context.connection
            .prepareStatement(
                "UPDATE notification_templates SET body_template = ?, updated_at = NOW(6) WHERE type = 'ITEM_PARSING_COMPLETED'",
            ).use { statement ->
                statement.setString(1, "\${completionMessage}")
                statement.executeUpdate()
            }
    }
}
