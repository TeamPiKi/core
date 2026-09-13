package db.migration

import com.depromeet.piki.tournament.migration.CloneFlattenBackfill
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

// #1027 Phase 2 — CLONE 이 들고 있던 플레이 상태를 참여 행(tournament_users.status/completed_at + 이력)으로 평탄화.
// 분류·병합·재지향·방어 로직은 CloneFlattenBackfill 이 갖는다(데이터 시딩→실행→단언으로 테스트 가능하게 분리).
// Java 마이그레이션은 기본 트랜잭션 안에서 돌아, 백필 중 예외가 나면 전체 롤백된다(반쯤 평탄화 방지).
//
// 순수 DML(UPDATE/soft-delete)이라 DDL 을 섞지 않는다 — 롤백 보장 + additive 스키마(Phase 1 컬럼)와 분리.
// 파괴적 제거(source_tournament_id·클론 행·금지 코드)는 Phase 4 다. 여기선 클론 행을 리다이렉트용으로 남긴다.
@Suppress("ClassName")
class V20260906205407__flatten_clones_into_tournament_users : BaseJavaMigration() {
    override fun migrate(context: Context) {
        CloneFlattenBackfill(context.connection).run()
    }
}
