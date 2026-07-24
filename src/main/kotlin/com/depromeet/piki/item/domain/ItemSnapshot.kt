package com.depromeet.piki.item.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import com.depromeet.piki.product.service.ProductSnapshot
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDateTime

// item(정체성=link)의 한 추출 버전. 추출값(name·price·image·currency)·상태(status)·추출시각을 들고,
// item 이 갱신될 때마다 새 행이 쌓여 가격·이름·이미지 이력을 보존한다.
// 4a 부터 추출값·상태는 전적으로 이 버전이 보유한다 — item 은 link(정체성)만 남고, 추출·검증·전이의 무게중심이 여기로 모였다.
// itemId 는 정체성(items)을 raw 로 참조한다 (FK 제약 없음 — 프로젝트 정책). 같은 item 의 여러 버전이 1:N.
// 버전 순서는 id(단조증가)로 충분해 별도 version 컬럼을 두지 않는다.
//
// 전이의 계약 검증(이미 READY·PROCESSING 은 클라이언트가 못 바꿈)도 이 버전이 직접 던진다 — 추출값·상태가 여기로 모이면서
// item 이 들고 있던 자기방어 책임이 함께 옮겨왔다(4a). 여러 버전(v2·v3)은 5단계 갱신부터 쌓인다.
@Entity
@Table(name = "item_snapshots")
class ItemSnapshot(
    @Column(name = "item_id", nullable = false)
    val itemId: Long,
    name: String? = null,
    imageUrl: String? = null,
    currentPrice: Int? = null,
    currency: String? = null,
    status: ItemStatus = ItemStatus.PROCESSING,
    extractedAt: LocalDateTime? = null,
    attemptCount: Int = 0,
) : LongBaseEntity() {
    // 추출 필드 — setter 직접 노출 대신, 의도가 박힌 명령(markReady·markFailed·recover)으로만 바꾼다.
    @Column(name = "name", length = 512)
    var name: String? = name
        protected set

    @Column(name = "image_url", length = 2048)
    var imageUrl: String? = imageUrl
        protected set

    @Column(name = "current_price")
    var currentPrice: Int? = currentPrice
        protected set

    @Column(name = "currency", length = 8)
    var currency: String? = currency
        protected set

    // 이 버전의 추출 생애주기. PROCESSING(추출 중)→READY(완료)/FAILED(실패), FAILED→READY(보정).
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: ItemStatus = status
        protected set

    // 추출이 완료(READY)된 시각. PROCESSING 동안은 비어 있다(null). 정체성 row 생성 시각(created_at)과 구분된다.
    @Column(name = "extracted_at")
    var extractedAt: LocalDateTime? = extractedAt
        protected set

    // 파싱 실행 시도 횟수 (execution at-least-once, #461). claim(markProcessing)에서 1 이 되고, recover 의 재실행(reclaim)마다 +1.
    // recover 가 이 값으로 "상한 도달 → FAILED 종결" 여부를 판단한다(무한 재큐잉 방지). 증가 자체가 updated_at 을
    // 갱신(dirty checking)해 재실행 시점부터 stale 시계를 다시 흐르게 한다 — recover 가 막 재실행한 행을 또 stale 로 오판하지 않는다.
    // 이 값은 소유권 fencing 토큰도 겸한다(#802): claim 시점의 attemptCount 를 워커까지 실어, 재클레임(attempt++)돼 소유권이
    // 넘어간 뒤 뒤늦게 도착한 좀비 워커의 박동(fenced touch)·전이(markReady/markFailed)를 attemptCount 불일치로 걸러낸다.
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = attemptCount
        protected set

    // 엔티티 불변식 — 최후의 보루. 범위·길이만 보고 null 은 통과시킨다.
    // kotlin("plugin.jpa") 가 합성하는 no-arg 생성자가 Hibernate 하이드레이션 시점에 이 init 을 실행하는데,
    // 그 순간 필드는 아직 주입 전이라 전부 null/기본값이다. 그래서 상태-의존·필수값 불변식은 여기 두지 않고
    // (두면 행 하이드레이션이 깨진다), 범위·길이만 본다.
    init {
        validate(name, currentPrice, imageUrl, currency)
    }

    // 추출 필드를 갈아끼우는 코어. 들어온 필드만 갱신하고 생성 때와 같은 불변식을 재검증한다.
    private fun apply(
        name: String? = null,
        currentPrice: Int? = null,
        imageUrl: String? = null,
        currency: String? = null,
    ) {
        val newName = name ?: this.name
        val newCurrentPrice = currentPrice ?: this.currentPrice
        val newImageUrl = imageUrl ?: this.imageUrl
        val newCurrency = currency ?: this.currency
        validate(newName, newCurrentPrice, newImageUrl, newCurrency)
        this.name = newName
        this.currentPrice = newCurrentPrice
        this.imageUrl = newImageUrl
        this.currency = newCurrency
    }

    // PENDING → PROCESSING. 디스패처가 outbox 에서 작업을 집어(claim) 실행을 시작할 때 전이한다.
    // PENDING 이 아닌데 호출되면 디스패처가 잘못된 버전을 집은 코드 버그이므로 check(500).
    // attemptCount 를 1 로 올려 "첫 실행 시도"를 기록한다. 이 전이로 updated_at 이 갱신돼, recover 의 stale 판정이 이 시각을 본다.
    fun markProcessing() {
        check(status == ItemStatus.PENDING) { "PENDING 이 아닌 snapshot(status=$status)은 PROCESSING 으로 claim 할 수 없다" }
        status = ItemStatus.PROCESSING
        attemptCount++
    }

    // PROCESSING 유지 + 시도 횟수 +1. recover 가 stale PROCESSING(워커 크래시·실행 누락 등으로 실행이 끝나지 않은 행)을
    // 재실행할 때 호출한다 — claim-at-least-once 를 execution at-least-once 로 끌어올리는 핵심 전이다(#461).
    // PROCESSING 이 아닌데 호출되면 recover 가 잘못된 행을 집은 코드 버그이므로 check(500).
    // 상태는 그대로 두되 attemptCount 증가가 updated_at 을 갱신(dirty checking)해, 이번 재실행 시점부터 stale 윈도가 다시 흐른다.
    // 상한 도달 여부 판단은 호출부(recover)가 attemptCount 로 한다 — 도메인은 전이만, 정책(상한 수)은 스케줄러가 쥔다.
    fun reclaim() {
        check(status == ItemStatus.PROCESSING) { "PROCESSING 이 아닌 snapshot(status=$status)은 재실행 claim 할 수 없다" }
        attemptCount++
    }

    // PROCESSING → READY. 백그라운드 파싱이 성공해 추출 결과(snapshot)를 채우며 전이한다.
    // 전이 가능 상태가 아닌데 호출되면 워커가 잘못된 버전을 집은 코드 버그이므로 check(500).
    // extractedAt 은 전이 시점의 now() — Wish.delete() 등 도메인이 시간을 만드는 프로젝트 관례를 따른다.
    fun markReady(snapshot: ProductSnapshot) {
        check(status == ItemStatus.PROCESSING) { "PROCESSING 이 아닌 snapshot(status=$status)은 READY 로 전이할 수 없다" }
        apply(
            name = snapshot.name,
            currentPrice = snapshot.currentPrice,
            imageUrl = snapshot.imageUrl,
            currency = snapshot.currency,
        )
        // 추출 결과와 추출시각을 채운 뒤 불변식을 검사한다 — READY 가 보장하는 네 필드(name·price·imageUrl·extractedAt)를
        // 한 자리에서 확정하려고 set 을 검사 앞에 둔다. 추출이 이름을 못 얻었으면 READY 부적격 —
        // 워커가 이 예외를 받아 FAILED 로 흡수한다(PROCESSING 방치 방지).
        this.extractedAt = LocalDateTime.now()
        requireReadyInvariant()
        status = ItemStatus.READY
    }

    // PROCESSING → FAILED. 파싱 실패(상품 아님·신뢰 불가·타임아웃)를 동기 400 대신 상태로 남긴다.
    fun markFailed() {
        check(status == ItemStatus.PROCESSING) { "PROCESSING 이 아닌 snapshot(status=$status)은 FAILED 로 전이할 수 없다" }
        status = ItemStatus.FAILED
    }

    // 클라이언트가 추출 버전을 직접 바꾸는 유일한 통로 — 추출 실패(FAILED) 항목의 수동 보정.
    // FAILED 는 추출이 맺히다 만 미완성 버전이라, 사용자가 채우면 정상 버전이 된 것이므로 READY 로 복구한다.
    // READY(등록 완료)는 기계 추출 사실이라 손으로 못 바꾸고(409), PROCESSING(파싱 중)은 워커 소관이라 끼어들 수 없다(409).
    // 둘 다 멀쩡한 클라이언트가 PATCH 로 닿을 수 있는 계약이므로 이 버전이 직접 던진다 — 4a 에서 item 으로부터 승격받은 책임이다.
    fun recover(
        name: String? = null,
        currentPrice: Int? = null,
        imageUrl: String? = null,
        currency: String? = null,
    ) {
        when (status) {
            ItemStatus.READY -> throw ItemException.alreadyReady()
            // PENDING(아직 claim 전)·PROCESSING(파싱 중) 모두 워커 소관이라 클라이언트가 끼어들 수 없다(409).
            ItemStatus.PENDING, ItemStatus.PROCESSING -> throw ItemException.stillProcessing()
            ItemStatus.FAILED -> {
                apply(name = name, currentPrice = currentPrice, imageUrl = imageUrl, currency = currency)
                // 입력 경계 계약 — 보정 후에도 필수 필드가 없으면 쓸 수 없는 버전이 READY 로 새어 들어간다(400).
                if (this.name.isNullOrBlank()) throw ItemException.nameRequiredForReady()
                this.currentPrice ?: throw ItemException.priceRequiredForReady()
                this.imageUrl ?: throw ItemException.imageRequiredForReady()
                // 보정값과 추출시각을 채운 뒤 markReady 와 같은 불변식을 거쳐 READY 로 전이한다(입력 경계 + 엔티티 불변식 다층 방어).
                this.extractedAt = LocalDateTime.now()
                requireReadyInvariant()
                status = ItemStatus.READY
            }
        }
    }

    // 파싱이 끝나 추출 결과가 채워진 버전인지. 토너먼트 출전·목록 노출처럼 "완성된 버전만" 요구하는 게이트에서 쓴다.
    // PROCESSING(파싱 중)·FAILED(실패)는 false — 이름·가격이 비어 출전에 부적합하다.
    fun isReady(): Boolean = status == ItemStatus.READY

    // 클라이언트 보정(recover) 대상인지 — FAILED 만 보정 가능. 서비스가 S3 업로드 같은 외부 작업 전에
    // 미리 걸러 헛된 비용(orphan 업로드)을 막는 사전 가드용. 도메인 최후 보루는 recover 가 진다.
    fun isFailed(): Boolean = status == ItemStatus.FAILED

    // 추출 작업이 아직 끝나지 않은(진행 중) 버전인지 — PENDING(claim 대기)·PROCESSING(파싱 중). 수동 새로고침의 멱등
    // 가드에서 쓴다: 이미 진행 중이면 새 추출 버전을 만들지 않는다. READY/FAILED 만 새로고침으로 새 버전을 띄운다.
    fun isInProgress(): Boolean = status == ItemStatus.PENDING || status == ItemStatus.PROCESSING

    // READY 불변식 — 유저가 가격·이미지·이름을 보고 아이템을 선택하고, 가격 이력은 추출시각(extractedAt)을 축으로
    // 보여주므로 이 네 필드가 다 있어야 쓸 수 있는 버전이다. READY 로 전이하는 두 경로(markReady·recover)가
    // 추출값과 extractedAt 을 채운 뒤 이 검증을 거쳐 "READY ⟹ 네 필드 non-null" 을 엔티티가 보장한다(최후의 보루).
    // 정상 흐름에선 입력 경계(recover 의 *RequiredForReady, 추출 워커의 FAILED 흡수)가 먼저 거른다.
    // 메시지에 status 를 넣지 않는다 — 이 검사는 status 를 READY 로 바꾸기 전에 호출돼 전이 직전 상태가 찍히면 오해를 부른다.
    private fun requireReadyInvariant() {
        require(!name.isNullOrBlank()) { "READY snapshot 은 name 이 있어야 한다" }
        requireNotNull(currentPrice) { "READY snapshot 은 price 가 있어야 한다" }
        requireNotNull(imageUrl) { "READY snapshot 은 imageUrl 이 있어야 한다" }
        requireNotNull(extractedAt) { "READY snapshot 은 extractedAt 이 있어야 한다" }
    }

    private fun validate(
        name: String?,
        currentPrice: Int?,
        imageUrl: String?,
        currency: String?,
    ) {
        require((currentPrice ?: 0) >= 0) { "currentPrice 는 음수일 수 없다: $currentPrice" }
        require((name?.length ?: 0) <= NAME_MAX_LENGTH) { "name 길이가 ${NAME_MAX_LENGTH}자를 초과했다" }
        require((imageUrl?.length ?: 0) <= IMAGE_URL_MAX_LENGTH) { "imageUrl 길이가 ${IMAGE_URL_MAX_LENGTH}자를 초과했다" }
        require((currency?.length ?: 0) <= CURRENCY_MAX_LENGTH) { "currency 길이가 ${CURRENCY_MAX_LENGTH}자를 초과했다" }
    }

    companion object {
        const val NAME_MAX_LENGTH = 512
        const val IMAGE_URL_MAX_LENGTH = 2048
        const val CURRENCY_MAX_LENGTH = 8

        // 등록 시작점 — 추출 전 PENDING 버전(outbox 적재). URL·이미지 두 경로가 공유한다(이미지 입력도 S3 raw 로 durable
        // 적재되므로 같은 outbox 에 태운다). 등록은 이 행을 커밋만 하고 즉시 반환하며, 디스패처가 PENDING 을 집어
        // markProcessing 으로 claim 한 뒤 워커가 파싱한다. @Async 유실(인스턴스 재시작 등)과 무관하게 DB 의 PENDING 행이
        // 작업의 진실 원천이라, 반드시 한 번은 claim 돼 실행이 시작된다(최소 1회 실행 보장).
        fun pending(itemId: Long): ItemSnapshot = ItemSnapshot(itemId = itemId, status = ItemStatus.PENDING)
    }
}
