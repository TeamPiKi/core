package com.depromeet.piki.support

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * item 스냅샷 원본 접근을 기계로 동결하는 메타 테스트.
 *
 * 같은 item 에 값이 둘이다 — 포인터(위시·출전이 가리키는 버전)와 표시값(ItemDisplayService, 최신 기계 READY
 * 우선 파생). 사용자가 화면에서 보고 행동한 결과에 관여하는 읽기(출전 판정·수기 수정 base·새로고침 판정)는
 * 표시값을 거쳐야 하고, 원본이 정당한 갈래는 넷뿐이다 — 쓰기 / 정체성(itemId만) / 버전 자체가 대상(알림·이력) /
 * 표시값 파생의 입력. 이 구분은 타입이 같아 컴파일러가 못 잡고, 단일 사용자 테스트에선 둘이 같아 테스트도 못
 * 잡는다(#1006 담기 게이트가 실제로 밟은 함정). 그래서 소비자 집합을 여기 동결한다 — 새 소비 클래스는 이 목록에
 * 자기 갈래를 적어야 하고, 그 순간 위 규칙을 읽게 된다. 갈래 판정 자체는 사람 리뷰 몫이다(오탐 없는 기계 판정 불가).
 */
class SnapshotAccessConventionTest {
    /** 원본 저장소(ItemSnapshotRepository)를 직접 쓰는 것이 허용된 item 패키지 밖 클래스(경로 접미사)와 그 갈래. */
    private val allowedRawConsumers =
        mapOf(
            "notification/handler/ItemParsingCompletedHandler.kt" to "버전 자체 — 방금 전이된 그 버전의 이름으로 알림 문구를 만든다",
            "notification/handler/ItemParsingFailedHandler.kt" to "버전 자체 — 위와 동일",
            "notification/handler/ItemParsingIncompleteHandler.kt" to "버전 자체 — 위와 동일",
            "notification/handler/TournamentItemDeletedHandler.kt" to "버전 자체 — 삭제된 출전 카드가 보던 버전의 이름",
            "tournament/service/TournamentService.kt" to
                "정체성(itemId 추출)·표시값 입력. 출전 판정은 requireEntryEligible(표시값)이 진다",
            "tournament/service/TournamentItemPersistenceService.kt" to
                "쓰기(PENDING·MANUAL 적재)·정체성. 수정 base 는 표시값(editBasisOf)",
            "wishlist/service/WishlistService.kt" to "가격 이력(버전 자체)·표시값 입력",
            "wishlist/service/WishPersistenceService.kt" to
                "쓰기(PENDING·MANUAL 적재)·진행 중 합류 조회. 수정 base·새로고침 판정은 표시값",
        )

    @Test
    fun `item 패키지 밖에서 스냅샷 원본 저장소를 직접 쓰는 클래스는 동결 목록에 갈래와 함께 등록돼 있어야 한다`() {
        val offenders =
            rawConsumers.filterNot { file ->
                allowedRawConsumers.keys.any { file.invariantSeparatorsPath.endsWith(it) }
            }
        if (offenders.isNotEmpty()) {
            fail(
                "ItemSnapshotRepository 를 직접 쓰는 새 클래스가 있다. 사용자 화면 행동에 관여하는 읽기면 " +
                    "ItemDisplayService(표시값)를 거치고, 원본이 정당한 갈래(쓰기·정체성·버전 자체·표시값 입력)면 " +
                    "이 테스트의 allowedRawConsumers 에 갈래를 적고 등록하라:\n" +
                    offenders.joinToString("\n") { it.path },
            )
        }
    }

    @Test
    fun `동결 목록에는 실제 소비자만 남는다 - 소비를 멈춘 클래스는 목록에서 지운다`() {
        val stale =
            allowedRawConsumers.keys.filterNot { key ->
                rawConsumers.any { it.invariantSeparatorsPath.endsWith(key) }
            }
        if (stale.isNotEmpty()) {
            fail("소비를 멈췄는데 목록에 남은 항목(목록이 낡으면 동결이 무의미해진다): " + stale.joinToString(", "))
        }
    }

    companion object {
        private const val RAW_IMPORT = "import com.depromeet.piki.item.repository.ItemSnapshotRepository"

        // 두 테스트가 같은 집합을 봐야 동결이 성립하므로 스캔·필터를 한 번만 계산해 공유한다 — JUnit 은 테스트마다
        // 인스턴스를 새로 만들어 인스턴스 lazy 로는 1회가 안 된다. startsWith 매칭이라 별칭 import(as X)도 잡는다.
        private val rawConsumers: List<File> by lazy {
            val root = File("src/main/kotlin")
            check(root.isDirectory) { "메인 소스 루트를 찾지 못했다: ${root.absolutePath}" }
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.invariantSeparatorsPath.contains("/com/depromeet/piki/item/") }
                .filter { file -> file.useLines { lines -> lines.any { it.trim().startsWith(RAW_IMPORT) } } }
                .toList()
        }
    }
}
