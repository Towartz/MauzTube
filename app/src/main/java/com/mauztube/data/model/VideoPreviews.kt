package com.mauztube.data.model

/**
 * Anteprime (titolo, miniatura, canale) dei video toccati in una lista: il player le mostra
 * subito mentre l'estrazione è in corso, così l'apertura risulta immediata.
 */
object VideoPreviews {
    private val map = object : LinkedHashMap<String, VideoItem>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoItem>) = size > 200
    }

    @Synchronized fun put(video: VideoItem) { map[video.id] = video }
    @Synchronized fun get(videoId: String): VideoItem? = map[videoId]
}

/**
 * Anteprime dei canali (nome, avatar, iscritti, badge verificato): la schermata del canale
 * mostra subito l'intestazione mentre i video vengono caricati.
 */
object ChannelPreviews {
    private val map = object : LinkedHashMap<String, ChannelDetails>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChannelDetails>) = size > 100
    }

    @Synchronized fun put(
        url: String,
        name: String,
        avatarUrl: String?,
        subscriberCount: Long = -1L,
        verified: Boolean = false,
    ) {
        val existing = map[url]
        map[url] = existing?.copy(
            name = name.ifBlank { existing.name },
            avatarUrl = avatarUrl ?: existing.avatarUrl,
            subscriberCount = if (subscriberCount >= 0) subscriberCount else existing.subscriberCount,
            verified = verified || existing.verified,
        ) ?: ChannelDetails(
            url = url,
            name = name,
            avatarUrl = avatarUrl,
            bannerUrl = null,
            subscriberCount = subscriberCount,
            description = null,
            videos = Paged(emptyList(), null),
            verified = verified,
        )
    }

    @Synchronized fun get(url: String): ChannelDetails? = map[url]
}
