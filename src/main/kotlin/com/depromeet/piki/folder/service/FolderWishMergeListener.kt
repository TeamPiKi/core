package com.depromeet.piki.folder.service

import com.depromeet.piki.folder.domain.FolderContents
import com.depromeet.piki.folder.domain.FolderWish
import com.depromeet.piki.folder.repository.FolderRepository
import com.depromeet.piki.folder.repository.FolderWishRepository
import com.depromeet.piki.item.domain.ItemSlotType
import com.depromeet.piki.item.event.ItemSlotMerged
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

// 위시가 같은 상품의 다른 위시에 합쳐지면(#1248) 사라지는 위시의 폴더 연결을 남는 위시로 옮긴다(#1266).
// 합침은 ItemSlots.fill 이 합치기 트랜잭션 안에서 발행한다. 커밋 뒤(@TransactionalEventListener AFTER_COMMIT)에
// 옮기면 그 사이 폴더가 지워진 위시를 가리키고, 옮기다 실패하면 연결이 사라진다. 그래서 동기 @EventListener 로
// 같은 트랜잭션에서 옮기고, 실패하면 합치기도 함께 되돌린다. 메모 이관(WishSlotPort.fill)과 같은 자리다.
@Component
class FolderWishMergeListener(
    private val folderRepository: FolderRepository,
    private val folderWishRepository: FolderWishRepository,
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun on(event: ItemSlotMerged) {
        if (event.slot.type != ItemSlotType.WISH || event.holder.type != ItemSlotType.WISH) return
        val merged = event.slot.id
        val survivor = event.holder.id
        // 폴더 넣기·옮기기와 같은 순서(id 오름차순)로 잠가 동시에 남는 위시를 넣어도 유니크 키에 부딪히지 않는다
        val folders = folderRepository.findAllByIdsForUpdate(folderWishRepository.findFolderIdsByWishId(merged))
        folders.forEach { folder ->
            val move = FolderContents(folder.getId(), folderWishRepository.findWishIdsByFolderId(folder.getId())).replace(merged, survivor)
            folderWishRepository.deleteByFolderIdAndWishIds(folder.getId(), move.takeOut)
            folderWishRepository.saveAll(move.putIntoTarget.map { FolderWish(folderId = folder.getId(), wishId = it) })
        }
    }
}
