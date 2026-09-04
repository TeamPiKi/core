package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

// ITEM_PARSING_RECOVERED 알림 템플릿을 넣는다(#1028).
//   title "${itemName}" · body "상품 정보가 새로 확인돼 채워졌어요"
//
// 실패·미완으로 멈춰 있던 카드가 다른 사람의 성공 파싱으로 채워졌음을 알린다. 완료 알림과 수신자가 배타적이라
// (그 버전을 기다린 사람 vs 다른 버전에 멈춰 있던 사람) 문구가 갈려야 하고, 템플릿은 타입당 하나라 타입을 따로 둔다.
//
// 문구가 원인을 말하지 않는 이유:
//   - "서버가 재시도해서" 는 거짓이다 — recover 는 stale PROCESSING 만 되살리고 FAILED 를 PENDING 으로 되돌리는
//     코드가 없다. 게다가 이 문구는 "실패해도 기다리면 서버가 해준다" 를 학습시켜, 아무도 안 고치면 영원히 죽어 있는
//     다수 케이스에서 사용자가 손을 놓게 만든다.
//   - "다른 분이 등록해서" 는 사실이지만 다른 사용자의 행동을 노출하고 링크 공유(#825) 구조를 처음으로 드러낸다.
//     사용자에게 필요한 인과는 "누가 했나" 가 아니라 "무슨 일이 일어났나" 다.
//
// push_enabled 는 컬럼 기본값(TRUE)을 따른다 — 앱이 닫혀 있어도 알려야 하는 결(완료·실패·미완과 같다).
//
// 리터럴 dollar-brace(${...})를 SQL 마이그레이션에 두면 Flyway 가 placeholder 로 오인해 파싱이 깨지므로,
// 다른 템플릿 시드와 같이 JDBC 로 직접 INSERT 한다.
@Suppress("ClassName")
class V20260904103000__seed_item_parsing_recovered_template : BaseJavaMigration() {
    override fun migrate(context: Context) {
        context.connection
            .prepareStatement(
                "INSERT INTO notification_templates (type, title_template, body_template, updated_at) VALUES (?, ?, ?, NOW(6))",
            ).use { statement ->
                statement.setString(1, "ITEM_PARSING_RECOVERED")
                statement.setString(2, "\${itemName}")
                statement.setString(3, "상품 정보가 새로 확인돼 채워졌어요")
                statement.executeUpdate()
            }
    }
}
