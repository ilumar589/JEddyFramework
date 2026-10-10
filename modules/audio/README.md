# jeddy-audio

Module `dev.jeddy.audio`. SDL3_mixer behind a voice pool: logical voices on a fixed set of tracks with priority stealing, buses with ducking, 3D emitters with our rolloff, music with crossfades, ambience zones and zero-copy streaming from paks.

## Key types

- `Audio`, `AudioConfig`, `SoundClip`, `SoundLoader`
- `MusicPlayer`, `Ambience`

## Threading

Open and close a device mixer on the main thread; everything else on the game thread.

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §12.3
- API: `./gradlew :audio:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :audio:test`
