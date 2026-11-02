package com.depromeet.piki.wishlist.domain

// 폴더를 지정해 담은 링크가 이미 내 위시일 때, 그 위시를 폴더에 넣은 결과(#1266).
// 새로 담긴 위시에는 쓰지 않는다 — 값이 있으면 "기존 위시" 라는 뜻이라 클라가 안내 문구를 가른다.
enum class WishFolderResult {
    // 기존 위시를 이 폴더에 넣음. 클라는 "이 폴더에 담았어요" 안내
    ADDED,

    // 이미 이 폴더에 있어 바꾼 것이 없음. 클라는 그 카드로 스크롤·하이라이트
    ALREADY_IN_FOLDER,
}
