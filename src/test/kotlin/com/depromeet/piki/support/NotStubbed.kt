package com.depromeet.piki.support

// 기본 동작이 throw 라 세팅을 빠뜨린 테스트가 이전 테스트의 람다로 통과하지 않음
fun notStubbed(name: String): Nothing = error("stub.$name 을 테스트 본문에서 세팅해야 함")
