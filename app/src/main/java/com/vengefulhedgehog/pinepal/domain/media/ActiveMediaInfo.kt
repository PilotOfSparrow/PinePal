package com.vengefulhedgehog.pinepal.domain.media

data class ActiveMediaInfo(
  val title: String?,
  val album: String?,
  val artist: String?,
  val totalDuration: Long, // ms
  val currentPlayPosition: Long, // How long track has been played in ms
  val isPlaying: Boolean,
) {
  val encodedTitle: ByteArray
    get() = title.orEmpty().take(20).encodeToByteArray()

  val encodedAlbum: ByteArray
    get() = album.orEmpty().encodeToByteArray()

  val encodeArtist: ByteArray
    get() = artist.orEmpty().encodeToByteArray()

  val encodedStatus: ByteArray
    get() = byteArrayOf(
      if (isPlaying) {
        0x01
      } else {
        0x00
      }
    )
}
