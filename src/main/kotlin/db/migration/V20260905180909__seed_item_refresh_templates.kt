package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

// 위시 새로고침 결과 알림 템플릿 2종을 넣는다(#1036).
//   ITEM_REFRESH_COMPLETED  title "${itemName}"          · body "최신 상품 정보로 새로고침했어요"
//   ITEM_REFRESH_FAILED     title "새로고침에 실패했어요" · body "기존 상품 정보는 그대로 남아 있어요"
//
// 등록 파싱(ITEM_PARSING_COMPLETED/FAILED)과 같은 이벤트에서 갈라지지만 수신자가 배타적이라(그 버전을 새로고침한
// 사람 vs 등록한 사람) 문구가 달라야 하고, 템플릿은 타입당 하나라 타입을 따로 둔다.
//
// 완료의 title·body 분리는 다른 파싱 알림(#913)과 같은 이유다 — OS 푸시 제목은 뒤가 잘려 이름만 제목에 둔다.
// 실패의 body 가 "기존 정보는 남아 있다" 인 근거: 새로고침은 성공 항목에서만 시작되고 실패해도 카드는 표시값 파생(#858)으로
// 옛 성공본을 보인다. 등록 실패 문구를 그대로 쓰면 정보가 사라졌다는 오해를 낳는다.
//
// push_enabled 는 컬럼 기본값(TRUE)을 따른다 — 앱이 닫혀 있어도 알려야 하는 결(등록 완료·실패와 같다).
//
// 리터럴 dollar-brace(${...})를 SQL 마이그레이션에 두면 Flyway 가 placeholder 로 오인해 파싱이 깨지므로,
// 다른 템플릿 시드와 같이 JDBC 로 직접 INSERT 한다.
@Suppress("ClassName")
class V20260905180909__seed_item_refresh_templates : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val rows =
            listOf(
                Triple("ITEM_REFRESH_COMPLETED", "\${itemName}", "최신 상품 정보로 새로고침했어요"),
                Triple("ITEM_REFRESH_FAILED", "새로고침에 실패했어요", "기존 상품 정보는 그대로 남아 있어요"),
            )
        context.connection
            .prepareStatement(
                "INSERT INTO notification_templates (type, title_template, body_template, updated_at) VALUES (?, ?, ?, NOW(6))",
            ).use { statement ->
                rows.forEach { (type, title, body) ->
                    statement.setString(1, type)
                    statement.setString(2, title)
                    statement.setString(3, body)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
    }
}
