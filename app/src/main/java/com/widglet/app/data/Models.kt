package com.widglet.app.data

import kotlinx.serialization.Serializable

/** A photo the widget can show. [uri] is a read-only content Uri (MediaStore or Photo Picker). */
@Serializable
data class Photo(
    val uri: String,
    val dateTaken: Long = 0L,
    val bucketId: Long? = null,
    val bucketName: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0L,
    val displayName: String? = null,
    val isFavorite: Boolean = false,
    val relativePath: String? = null,
)

data class Album(val id: Long, val name: String, val count: Int, val coverUri: String)

@Serializable
data class AlbumRef(val id: Long, val name: String)

enum class TimingMode { INTERVAL, DAILY, SLIDESHOW, UNLOCK, SCREEN_ON, TAP, MANUAL }
enum class Order { SEQUENTIAL, SHUFFLE, NEWEST, OLDEST }
enum class Transition { NONE, CROSSFADE, SLIDE, ZOOM, FLIP, KEN_BURNS }
enum class Speed { FAST, MEDIUM, SLOW }
enum class CutoutBg { TRANSPARENT, SOLID, GRADIENT, BLUR, ORIGINAL }
enum class NoSubjectAction { SHOW_FULL, SKIP }
enum class Quality(val maxPx: Int, val webpQuality: Int) {
    LOW(512, 60), MEDIUM(800, 75), HIGH(1080, 85), MAX(1600, 95)
}
enum class WidgetLayout { SINGLE, STACK, COLLAGE }
enum class Shape { ROUNDED, CIRCLE, SQUIRCLE, HEART, FLOWER, POLAROID, FILM }
enum class Fit { SMART_FILL, FIT, CENTER_CROP }
enum class PhotoFilter { NONE, MONO, DOT_MATRIX }
enum class TextStyle { NORMAL, DOT_MATRIX }
enum class ClockPos { TOP, CENTER, BOTTOM }
enum class TapAction { NEXT, OPEN_PHOTO, OPEN_APP, NONE }
enum class DoubleTapAction { NONE, PREVIOUS, PAUSE }
enum class TimeSlot { MORNING, AFTERNOON, EVENING, NIGHT, WEEKDAY, WEEKEND }

@Serializable
data class TimeRule(val slot: TimeSlot, val albums: List<AlbumRef> = emptyList())

/** Everything a single widget instance can be configured with. All fields have defaults so old JSON keeps loading. */
@Serializable
data class WidgetConfig(
    val name: String = "",
    // Sources
    val albums: List<AlbumRef> = emptyList(),
    val pickedUris: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    // Smart selection
    val onThisDay: Boolean = false,
    val favouritesOnly: Boolean = false,
    val skipScreenshots: Boolean = true,
    val skipSmall: Boolean = false,
    val minSidePx: Int = 600,
    val skipDuplicates: Boolean = true,
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    val preferSubjects: Boolean = false,
    val timeRulesEnabled: Boolean = false,
    val timeRules: List<TimeRule> = emptyList(),
    // Timing
    val timing: TimingMode = TimingMode.INTERVAL,
    val intervalMinutes: Int = 60,
    val slideshowSeconds: Int = 10,
    val order: Order = Order.SHUFFLE,
    val skipInBatterySaver: Boolean = true,
    // Transition
    val transition: Transition = Transition.CROSSFADE,
    val speed: Speed = Speed.MEDIUM,
    // Cutout
    val cutout: Boolean = false,
    val cutoutBg: CutoutBg = CutoutBg.TRANSPARENT,
    val noSubject: NoSubjectAction = NoSubjectAction.SHOW_FULL,
    val quality: Quality = Quality.HIGH,
    val lazyCutouts: Boolean = true,
    val lazyCount: Int = 10,
    val processOnlyCharging: Boolean = false,
    val processOnlyWifi: Boolean = false,
    val shadow: Boolean = true,
    val shadowSoftness: Int = 10,
    val shadowOffset: Int = 4,
    val outline: Boolean = false,
    val outlineWidth: Int = 4,
    val outlineColor: Int = 0xFFFFFFFF.toInt(),
    // Look
    val layout: WidgetLayout = WidgetLayout.SINGLE,
    val collageCount: Int = 4,
    val shape: Shape = Shape.ROUNDED,
    val cornerRadius: Int = 24,
    val padding: Int = 0,
    val fit: Fit = Fit.SMART_FILL,
    val filter: PhotoFilter = PhotoFilter.NONE,
    val bgColor: Int = 0x00000000,
    val bgColor2: Int = 0xFF5B6CFF.toInt(),
    val border: Boolean = false,
    val borderWidth: Int = 2,
    val borderColor: Int = 0xFFFFFFFF.toInt(),
    val showDate: Boolean = false,
    val showAlbum: Boolean = false,
    val textStyle: TextStyle = TextStyle.NORMAL,
    val accentColor: Int = 0xFFFFFFFF.toInt(),
    val clock: Boolean = false,
    val clockPos: ClockPos = ClockPos.TOP,
    val clockMono: Boolean = false,
    val clockDate: Boolean = true,
    val clockColor: Int = 0xFFFFFFFF.toInt(),
    // Interaction
    val tap: TapAction = TapAction.NEXT,
    val doubleTap: DoubleTapAction = DoubleTapAction.NONE,
) {
    val hasSources: Boolean get() = albums.isNotEmpty() || pickedUris.isNotEmpty() ||
        (timeRulesEnabled && timeRules.any { it.albums.isNotEmpty() })

    /** Copy only the look & feel (used by presets and "copy style"). */
    fun withStyleOf(o: WidgetConfig) = copy(
        transition = o.transition, speed = o.speed, cutoutBg = o.cutoutBg,
        shadow = o.shadow, shadowSoftness = o.shadowSoftness, shadowOffset = o.shadowOffset,
        outline = o.outline, outlineWidth = o.outlineWidth, outlineColor = o.outlineColor,
        layout = o.layout, collageCount = o.collageCount, shape = o.shape, cornerRadius = o.cornerRadius,
        padding = o.padding, fit = o.fit, filter = o.filter, bgColor = o.bgColor, bgColor2 = o.bgColor2,
        border = o.border, borderWidth = o.borderWidth, borderColor = o.borderColor,
        showDate = o.showDate, showAlbum = o.showAlbum, textStyle = o.textStyle, accentColor = o.accentColor,
        clock = o.clock, clockPos = o.clockPos, clockMono = o.clockMono, clockDate = o.clockDate, clockColor = o.clockColor,
    )
}

/** Runtime state of a widget (what it is showing now). */
@Serializable
data class WidgetState(
    val currentUri: String? = null,
    val history: List<String> = emptyList(),
    val shuffleSeed: Long = 1L,
    val lastUpdate: Long = 0L,
    val lastTap: Long = 0L,
)

@Serializable
data class StylePreset(val name: String, val style: WidgetConfig)

@Serializable
data class AppSettings(
    val onboarded: Boolean = false,
    val paused: Boolean = false,
)

@Serializable
data class Stats(
    val photosShown: Long = 0,
    val cutoutsMade: Long = 0,
    val cutoutBytes: Long = 0,
    val sourceBytes: Long = 0,
    val noSubject: Long = 0,
    val since: Long = 0,
)

object BuiltInPresets {
    /** Monochrome, dot-matrix type and a red accent, inspired by Nothing OS. */
    val nothing = StylePreset(
        "Nothing-inspired",
        WidgetConfig(
            filter = PhotoFilter.DOT_MATRIX, textStyle = TextStyle.DOT_MATRIX, bgColor = 0xFF000000.toInt(),
            accentColor = 0xFFD71921.toInt(), border = true, borderWidth = 1, borderColor = 0xFF3A3A3A.toInt(),
            cornerRadius = 28, showDate = true, clockMono = true, transition = Transition.CROSSFADE,
            cutoutBg = CutoutBg.SOLID, outline = false, shadow = false,
        ),
    )
    val sticker = StylePreset(
        "Sticker",
        WidgetConfig(
            cutout = true, cutoutBg = CutoutBg.TRANSPARENT, outline = true, outlineWidth = 6, shadow = true,
            shadowSoftness = 14, shadowOffset = 6, transition = Transition.ZOOM, fit = Fit.FIT,
        ),
    )
    val depthClock = StylePreset(
        "Depth clock",
        WidgetConfig(
            cutout = true, cutoutBg = CutoutBg.ORIGINAL, clock = true, clockPos = ClockPos.TOP,
            shadow = false, transition = Transition.CROSSFADE, cornerRadius = 28,
        ),
    )
    val polaroid = StylePreset(
        "Polaroid pile",
        WidgetConfig(layout = WidgetLayout.STACK, shape = Shape.POLAROID, showDate = true, transition = Transition.SLIDE),
    )
    val all = listOf(nothing, sticker, depthClock, polaroid)
}
