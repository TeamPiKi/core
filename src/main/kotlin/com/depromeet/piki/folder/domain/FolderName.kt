package com.depromeet.piki.folder.domain

import java.text.BreakIterator

// 폴더 이름. 앞뒤 공백을 지운 뒤 화면에 보이는 글자 수로 1-20자를 잰다 — 이모지·결합 문자도 1자.
// String.length 로 재면 이모지 하나가 2자 이상으로 세져 디자인의 "이모지 1자" 와 어긋난다.
// 같은 이름은 허용한다(디자인 기준).
@JvmInline
value class FolderName private constructor(
    val value: String,
) {
    companion object {
        const val MAX_LENGTH = 20

        // 한 글자가 코드 포인트 여럿일 수 있어(가족 이모지 등) 칸은 글자 수보다 넉넉히 둔다. 마이그레이션의 VARCHAR(100) 과 같은 값
        const val MAX_CODE_POINTS = 100

        fun of(raw: String?): FolderName {
            // Kotlin trim 은 유니코드 공백(전각 공백 포함)까지 지운다
            val stripped = raw?.trim().orEmpty()
            val length = graphemeCount(stripped)
            if (length !in 1..MAX_LENGTH) throw FolderException.invalidName()
            if (stripped.codePointCount(0, stripped.length) > MAX_CODE_POINTS) throw FolderException.invalidName()
            return FolderName(stripped)
        }

        private fun graphemeCount(text: String): Int {
            val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
            return generateSequence { iterator.next() }.takeWhile { it != BreakIterator.DONE }.count()
        }
    }
}
