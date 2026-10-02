package com.depromeet.piki.support

import com.depromeet.piki.admin.announcement.AnnouncementImageFetcher
import com.depromeet.piki.admin.announcement.FetchedImage

class StubAnnouncementImageFetcher : AnnouncementImageFetcher {
    val requestedUrls = mutableListOf<String>()

    var behavior: (String) -> FetchedImage = { notStubbed("behavior") }

    override fun fetch(url: String): FetchedImage {
        requestedUrls.add(url)
        return behavior(url)
    }
}
