plugins {
    id("dev.jeddy.game")
}

version = "0.1.0"

dependencies {
    implementation("dev.jeddy:jeddy-app:0.1.0-SNAPSHOT")
}

application {
    mainModule = "com.example.mygame"
    mainClass = "com.example.mygame.MyGame"
}

// ./gradlew distImage builds build/dist/my-game (runtime, launchers, paks); distImageZip and
// jpackage (-Pjeddy.package.type=deb|rpm|msi|exe) wrap it. A training run makes an AOT cache for
// a faster start; it needs a display for a windowed game:
//   trainingJvmArguments = listOf("-Djeddy.app.quitAfterFrames=600")
jeddyPackaging {
    name = "my-game"
    vendor = "Example"
}
