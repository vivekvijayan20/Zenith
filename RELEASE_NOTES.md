## What's new in 16.0.0

### Lyrics
- Share lyrics as an animated video that stays in sync with the song.
- The lyrics share card is flatter, has an optional logo, and follows your selected LiquidFlow lyric effect.
- YouTube Music timed lyrics are now available through the Android Music client.
- Local offline tracks read embedded and sidecar LRC, eLRC and TTML lyrics.
- Lyric translation now handles right-to-left languages such as Arabic.
- LiquidFlow splits lyrics by grapheme cluster, so Indic scripts no longer overlap, and unsung letters stay unlit until their line begins.

### Canvas
- Community Canvas now runs on the new Zenith Canvas service. Upload your own looping motion covers, like other listeners' work, and manage your uploads in one place.
- Canvas providers can be reordered to set your preferred priority.
- The Apple Music canvas bottom fade follows a curved profile, similar to Apple Music, with a softer tail.
- Transitions into and out of lyrics and the queue are smoother, with continuous blur, no tearing line, and the clip kept in place.
- The Spotify canvas cover fades in place and blends into the clip's own blurred frame.
- Canvas playback pauses while the player docks, and the paused frame is carried into the lyrics and queue headers.

### Discover
- New releases and Charts pages, with shortcut cards on the New tab.
- Search category cards are shuffled once per app launch.
- Explore shortcut cards are now clearly visible in the light theme.

### Library and albums
- Liked songs can be kept downloaded.
- Album headers show an Apple Hi-Res Lossless badge, and the quality label includes a lossless icon.
- High-resolution YouTube covers, a smoother player cover hold-over, and per-track covers for local files.
- Album pages tint from the dominant hue when the edge colour averages to grey.

### Player and queue
- Long-press a queue row to open its track menu.
- The queue swipe action has a new iOS-style design.
- The play/pause button responds to taps immediately and morphs faster, and its loading animation now runs as a sheen on the seek bar.
- Control icons are thinner, and the AirPlay triangle has a wider base.
- Mesh backdrops are softer for white covers and blend the cover's seam colours, ignoring letterbox bars.
- Video tracks with still covers keep a square mini player cover and can use full-bleed artwork.

### Glass interface
- The glass nav bar and player are shorter, with more realistic edge refraction, and the nav bar size can be adjusted manually.
- The glass tab indicator has a liquid rubber-band stretch at the pill ends and stays confined within the pill.
- Tapping the active tab scrolls smoothly to the top.
- The search field expands to full width while the keyboard is open, and the Home circle fades out.
- Panel mini rows now match the glass nav bar player.

### App icon and splash
- New Dark Glass launcher icon option.
- The splash screen uses an Apple Music style gradient with the dark glass Z logo, drawn from the first frame.

### Performance
- Minimizing and expanding the player is much smoother on songs with a canvas when the legacy mesh backdrop is enabled.
- Incoming AutoMix artwork, backdrop and canvas are prefetched, and the skip crossfade arms faster, so the next button responds quicker.
- Track analysis runs at background priority unless performance mode is on.
- Canvas stills are allocated off the main thread.
- Mid-track quality upgrades are skipped during the last 45 seconds of a track.

### Fixes and polish
- Fixed playlist playback order, notification transport slots, and Latin accent matching in lyrics.
- Fixed the queue not scrolling the last track fully into view, and neighbouring rows reappearing after a swipe removal.
- Fixed the mini player jump when the search field collapses.
- Fixed the Play and Shuffle labels clipping in the release header.
- Removed dead code and unused imports.
