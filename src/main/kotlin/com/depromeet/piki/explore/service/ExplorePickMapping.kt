package com.depromeet.piki.explore.service

import com.depromeet.piki.explore.domain.ExploreItem
import com.depromeet.piki.explore.domain.ExplorePick
import com.depromeet.piki.explore.repository.ExplorePickRow

internal fun ExplorePickRow.toPick(item: ExploreItem): ExplorePick =
    ExplorePick(
        pickId = pickId,
        pickerId = pickerId,
        tournamentId = tournamentId,
        tournamentName = tournamentName,
        candidateId = candidateId,
        pickedAt = pickedAt,
        item = item,
    )
