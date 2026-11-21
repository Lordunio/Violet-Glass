package pl.violetglass.launcher.pet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class PetEngineTest {
    @Test fun scheduledSleepStaysAtTheSelectedWidgetWhenChangingDesktops() {
        val pet = engine()
        val dock = PlatformSurface("dock:bottom", Rect(0f, 800f, 600f, 900f), PlatformKind.DOCK)
        val bed = PlatformSurface("widget:bed", Rect(110f, 260f, 290f, 350f), PlatformKind.WIDGET)
        val config = PetSettings(restPlace = petRestItem("bed"))
        fun tick() = pet.tick(.05f, PetWeather.NONE, Offset.Zero, config)
        pet.surfaces(listOf(dock), page = 0, restPage = 1)
        pet.environment(PowerState(plugged = true), night = false, sleep = true)
        pet.notifications(1)
        tick()
        assertEquals(1, pet.state.sleepingDesktop)
        assertFalse(pet.state.visible)
        pet.surfaces(listOf(dock, bed), page = 1, restPage = 1)
        assertTrue(pet.state.visible)
        assertEquals(PetMode.SLEEPING, pet.state.mode)
        val sleepingFeet = Offset(bed.bounds.center.x, bed.bounds.top)
        assertEquals(sleepingFeet, pet.state.feet)
        pet.surfaces(listOf(dock), page = 0, restPage = 1)
        tick()
        assertFalse(pet.state.visible)
        assertEquals(sleepingFeet, pet.state.feet)
        pet.surfaces(listOf(dock, bed), page = 1, restPage = 1)
        assertEquals(sleepingFeet, pet.state.feet)
        assertEquals(bed.id, pet.state.platformId)
        pet.environment(PowerState(), night = false, sleep = false)
        tick()
        assertNull(pet.state.sleepingDesktop)
        assertNotEquals(PetMode.SLEEPING, pet.state.mode)
    }

    @Test fun sleepScheduleSupportsMidnightAndExactWakeMinute() {
        val schedule = PetSettings(sleepAt = 22 * 60 + 30, wakeAt = 6 * 60 + 15)
        assertFalse(schedule.scheduledSleep(22 * 60 + 29))
        assertTrue(schedule.scheduledSleep(22 * 60 + 30))
        assertTrue(schedule.scheduledSleep(0))
        assertTrue(schedule.scheduledSleep(6 * 60 + 14))
        assertFalse(schedule.scheduledSleep(6 * 60 + 15))
        assertTrue(schedule.copy(sleepAt = 60, wakeAt = 120).scheduledSleep(90))
        assertFalse(schedule.copy(wakeAt = schedule.sleepAt).scheduledSleep(0))
    }

    @Test fun latestTapDuringFlightIsPlannedFromTheLandingPlatform() {
        val pet = engine()
        val first = PlatformSurface("widget:first", Rect(130f, 520f, 240f, 650f), PlatformKind.WIDGET)
        val last = PlatformSurface("icon:last", Rect(450f, 480f, 510f, 540f))
        pet.surfaces(listOf(
            PlatformSurface("dock", Rect(0f, 800f, 600f, 900f), PlatformKind.DOCK),
            first,
            last,
        ))
        fun tick() = pet.tick(1f / 120, PetWeather.NONE, Offset.Zero)
        pet.target(Offset(first.bounds.center.x, first.bounds.top))
        repeat(960) { if(pet.state.mode != PetMode.HOPPING) tick() }
        assertEquals(PetMode.HOPPING, pet.state.mode)
        pet.target(Offset(360f, 800f))
        pet.target(Offset(last.bounds.center.x, last.bounds.top))
        assertEquals(first.id, pet.state.targetPlatform)
        repeat(960) { if(pet.state.platformId != last.id) tick() }
        assertEquals(last.id, pet.state.platformId)
    }

    @Test fun movingFavoriteDuringRestStartsAVisitAtItsNewPosition() {
        val pet = engine()
        val dock = PlatformSurface("dock", Rect(0f, 800f, 600f, 900f), PlatformKind.DOCK)
        val favorite = PlatformSurface("widget:favorite", Rect(120f, 400f, 360f, 500f), PlatformKind.WIDGET)
        val settings = PetSettings(motion = PetMotion.FAST_HOPS, restPlace = petRestItem("favorite"))
        fun tick() = pet.tick(1f / 120, PetWeather.NONE, Offset.Zero, settings)
        fun restsAt(x: Float) = pet.state.platformId == favorite.id && pet.state.targetPlatform == null &&
            kotlin.math.abs(pet.state.feet.x - x) < 2
        pet.surfaces(listOf(dock, favorite))
        repeat(10800) { if(!restsAt(240f)) tick() }
        assertTrue(restsAt(240f))
        val moved = favorite.copy(bounds = Rect(220f, 380f, 460f, 480f))
        pet.surfaces(listOf(dock, moved))
        repeat(240) { tick() }
        assertTrue(restsAt(340f))
        assertEquals(380f, pet.state.feet.y, .01f)
    }

    @Test fun landingOnAHigherWidgetKeepsTheFavoriteAsTheDestination() {
        val pet = engine()
        val dock = PlatformSurface("dock", Rect(0f, 800f, 600f, 900f), PlatformKind.DOCK)
        val favorite = PlatformSurface("widget:favorite", Rect(200f, 350f, 380f, 430f), PlatformKind.WIDGET)
        val higher = PlatformSurface("widget:higher", Rect(220f, 330f, 360f, 350f), PlatformKind.WIDGET)
        val settings = PetSettings(motion = PetMotion.CALM, restPlace = petRestItem("favorite"))
        fun tick() = pet.tick(1f / 120, PetWeather.NONE, Offset.Zero, settings)
        pet.surfaces(listOf(dock))
        repeat(3240) { tick() }
        pet.surfaces(listOf(dock, favorite, higher))
        repeat(7200) { if(pet.state.platformId != higher.id) tick() }
        assertEquals(higher.id, pet.state.platformId)
        assertEquals(favorite.id, pet.state.targetPlatform)
        repeat(3600) { if(pet.state.platformId != favorite.id) tick() }
        assertEquals(favorite.id, pet.state.platformId)
    }
    @Test fun favoriteWidgetIsUsedAfterRoamingAndFollowsLayoutChanges() {
        val pet = engine()
        val dock = PlatformSurface("dock:bottom", Rect(0f, 800f, 600f, 900f), PlatformKind.DOCK)
        val widget = PlatformSurface("widget:calendar", Rect(110f, 260f, 290f, 350f), PlatformKind.WIDGET)
        val settings = PetSettings(motion = PetMotion.FAST_HOPS, restPlace = petRestItem("calendar"))
        fun tick() = pet.tick(1f / 120, PetWeather.NONE, Offset.Zero, settings)
        fun restsAt(surface: PlatformSurface): Boolean = pet.state.platformId == surface.id &&
            pet.state.targetPlatform == null && kotlin.math.abs(pet.state.feet.x - surface.bounds.center.x) < 2
        fun waitForRest(surface: PlatformSurface) {
            repeat(10_800) { tick(); if(restsAt(surface)) return }
            fail("Did not rest at ${surface.id}")
        }
        pet.surfaces(listOf(dock, widget))
        waitForRest(widget)
        val feet = pet.state.feet
        repeat(240) { tick(); assertEquals(feet, pet.state.feet) }
        pet.surfaces(listOf(dock))
        repeat(480) { tick(); assertNotEquals(widget.id, pet.state.targetPlatform) }
        val moved = widget.copy(bounds = Rect(300f, 200f, 490f, 300f))
        pet.surfaces(listOf(dock, moved))
        waitForRest(moved)
        assertEquals(moved.bounds.top, pet.state.feet.y, .01f)
    }
    @Test fun fastHopsKeepTravellingBetweenPlatformsInsteadOfBouncingInPlace() {
        val pet = engine()
        pet.surfaces(listOf(PlatformSurface("dock", Rect(0f, 800f, 600f, 900f), PlatformKind.DOCK)) +
            (0 until 6).map { PlatformSurface("icon:$it", Rect(it * 100f + 24f, 720f, it * 100f + 76f, 772f)) })
        val settings = PetSettings(motion = PetMotion.FAST_HOPS)
        var startX = 0f; var startPlatform: String? = null; var jumping = false; var hops = 0
        val visited = mutableSetOf<String>()
        repeat(7200) {
            val before = pet.state
            pet.tick(1f / 120, PetWeather.NONE, Offset.Zero, settings)
            if(pet.state.mode == PetMode.HOPPING && !jumping) {
                startX = before.feet.x; startPlatform = before.platformId; jumping = true
            } else if(jumping && pet.state.mode != PetMode.HOPPING) {
                assertTrue("Hop must move horizontally: ${pet.state.feet.x - startX}", kotlin.math.abs(pet.state.feet.x - startX) >= pet.state.sizePx * .6f)
                assertNotEquals("Hop must reach another ledge", startPlatform, pet.state.platformId)
                visited += pet.state.platformId!!; hops++; jumping = false
            }
        }
        assertTrue("Fast roaming must produce repeated useful hops: $hops", hops >= 10)
        assertTrue("Should explore multiple icon tops", visited.size >= 3)
    }
    @Test fun fastModeOnOneLedgeWalksWithoutVerticalHops() {
        val pet = engine()
        repeat(3600) {
            pet.tick(1f / 120, PetWeather.NONE, Offset.Zero, PetSettings(motion = PetMotion.FAST_HOPS))
            assertNotEquals(PetMode.HOPPING, pet.state.mode)
            assertEquals(800f, pet.state.feet.y, .01f)
        }
    }
    @Test fun fastDropDoesNotImmediatelyLandBackOnItsTakeoffLedge() {
        val pet = engine()
        pet.viewport(600f, 1600f, 1f, 20f)
        pet.surfaces(listOf(PlatformSurface("dock", Rect(0f, 800f, 600f, 850f), PlatformKind.DOCK),
            PlatformSurface("lower", Rect(224f, 1200f, 276f, 1252f))))
        val settings = PetSettings(motion = PetMotion.FAST_HOPS)
        pet.tick(0f, PetWeather.NONE, Offset.Zero, settings)
        pet.target(Offset(250f, 1200f))
        var airborne = false
        var landed = false
        repeat(600) {
            if(!landed) {
                pet.tick(1f / 120, PetWeather.NONE, Offset.Zero, settings)
                if(pet.state.mode == PetMode.HOPPING) airborne = true
                else if(airborne) {
                    assertEquals("lower", pet.state.platformId)
                    landed = true
                }
            }
        }
        assertTrue(landed)
    }
    @Test fun fastModeRejectsNarrowVerticallyAlignedPlatforms() {
        val pet = engine()
        pet.surfaces(listOf(PlatformSurface("dock", Rect(20f, 800f, 64f, 850f), PlatformKind.DOCK),
            PlatformSurface("icon", Rect(20f, 720f, 64f, 764f))))
        repeat(3600) {
            pet.tick(1f / 120, PetWeather.NONE, Offset.Zero, PetSettings(motion = PetMotion.FAST_HOPS))
            assertNotEquals(PetMode.HOPPING, pet.state.mode)
        }
    }
    private fun engine(): PetEngine = PetEngine(7).apply {
        restore(PetStats(lastUpdatedTimestamp = 1000)); viewport(600f, 1000f, 1f, 20f)
        surfaces(listOf(PlatformSurface("dock", Rect(0f, 800f, 600f, 900f), PlatformKind.DOCK)))
    }
    @Test fun pausedStatsUseElapsedTimeOnceClampAndIgnoreBackwardsClock() {
        val initial = PetStats(50f, 50f, 50f, 1000)
        val later = initial.advance(1000 + 4 * 3_600_000L)
        assertEquals(45.2f, later.energy, .001f); assertEquals(44f, later.happiness, .001f)
        assertEquals(46.8f, later.cleanliness, .001f)
        assertEquals(later, later.advance(later.lastUpdatedTimestamp))
        assertEquals(initial, initial.advance(0))
        val clamped = initial.advance(Long.MAX_VALUE)
        assertEquals(0f, clamped.energy, 0f); assertEquals(0f, clamped.happiness, 0f)
        assertEquals(100f, initial.advance(1000 + 24 * 3_600_000L, plugged = true).energy, 0f)
    }
    @Test fun petStartsOnDockAndKeepsTheSafetyFloorAboveNavigation() {
        val engine = engine()
        assertEquals("dock", engine.state.platformId)
        assertEquals(800f, engine.state.feet.y, .001f)
        assertTrue(engine.state.feet.y - engine.state.sizePx >= 450f)
        engine.surfaces(listOf(PlatformSurface("face", Rect(0f, 150f, 500f, 210f))))
        assertEquals("floor", engine.state.platformId)
        engine.tick(0f, PetWeather.NONE, Offset.Zero, PetSettings(effectArea = PetEffectArea.LOWER_SCREEN))
        engine.target(Offset(200f, 50f))
        assertTrue(engine.state.ripple!!.y >= 450f)
    }
    @Test fun platformGeometryBeforeViewportAndTemporarilyFilteredByRotationIsRetained() {
        val engine = PetEngine().apply { restore(PetStats(lastUpdatedTimestamp = 1000)) }
        engine.surfaces(listOf(PlatformSurface("dock", Rect(0f, 800f, 600f, 900f), PlatformKind.DOCK)))
        assertFalse(engine.state.visible)
        engine.viewport(600f, 1000f, 1f, 20f)
        assertEquals("dock", engine.state.platformId)
        engine.viewport(300f, 2000f, 1f, 20f)
        assertEquals("floor", engine.state.platformId)
        engine.viewport(600f, 1000f, 1f, 20f)
        assertEquals("dock", engine.state.platformId)
        assertEquals(800f, engine.state.feet.y, .001f)
    }
    @Test fun sixtyAndOneHundredTwentyHzHaveTheSameMovement() {
        val sixty = engine(); val fast = engine()
        sixty.target(Offset(450f, 800f)); fast.target(Offset(450f, 800f))
        repeat(60) { sixty.tick(1f / 60, PetWeather.NONE, Offset.Zero) }
        repeat(120) { fast.tick(1f / 120, PetWeather.NONE, Offset.Zero) }
        assertEquals(sixty.state.feet.x, fast.state.feet.x, .1f)
        assertEquals(sixty.state.feet.y, fast.state.feet.y, .1f)
    }
    @Test fun everyMotionCanReachAnUpperWidgetAndTopRowWithoutIntermediateLedges() {
        for(motion in PetMotion.entries) {
            val pet = engine()
            val upper = PlatformSurface("widget:calendar", Rect(100f, 140f, 400f, 340f), PlatformKind.WIDGET)
            val topIcon = PlatformSurface("icon:top", Rect(500f, 70f, 552f, 122f))
            pet.surfaces(listOf(PlatformSurface("dock", Rect(0f, 800f, 600f, 900f), PlatformKind.DOCK), upper, topIcon))
            val settings = PetSettings(motion = motion)
            pet.tick(0f, PetWeather.NONE, Offset.Zero, settings)
            for(target in listOf(upper, topIcon)) {
                pet.target(Offset(target.bounds.center.x, target.bounds.top))
                assertEquals(target.id, pet.state.targetPlatform)
                var arrived = false
                repeat(3600) {
                    if(!arrived) {
                        pet.tick(1f / 120, PetWeather.NONE, Offset.Zero, settings)
                        arrived = pet.state.platformId == target.id && pet.state.mode != PetMode.HOPPING
                        assertTrue("Arc must stay within the screen", pet.state.feet.y - pet.state.sizePx >= -2f)
                    }
                }
                assertTrue("$motion must reach ${target.id}", arrived)
                assertEquals(target.bounds.top, pet.state.feet.y, .1f)
            }
        }
    }
    @Test fun aSparseDesktopAllowsJumpingAcrossMoreThanOneColumn() {
        val pet = engine()
        pet.surfaces(listOf(PlatformSurface("dock", Rect(0f, 800f, 44f, 850f), PlatformKind.DOCK),
            PlatformSurface("widget:far", Rect(500f, 180f, 590f, 350f), PlatformKind.WIDGET)))
        val settings = PetSettings(motion = PetMotion.FAST_HOPS)
        pet.tick(0f, PetWeather.NONE, Offset.Zero, settings)
        pet.target(Offset(545f, 180f))
        var arrived = false
        repeat(1200) { if(!arrived) { pet.tick(1f / 120, PetWeather.NONE, Offset.Zero, settings); arrived = pet.state.platformId == "widget:far" && pet.state.mode != PetMode.HOPPING } }
        assertTrue(arrived)
    }
    @Test fun autonomousRoamingAndLowTapsUseDockInsteadOfSafetyFloorBelowIt() {
        val engine = engine()
        engine.target(Offset(400f, 975f))
        assertEquals("dock", engine.state.targetPlatform)
        repeat(3600) {
            engine.tick(1f / 120, PetWeather.NONE, Offset.Zero)
            assertEquals("dock", engine.state.platformId)
            assertEquals(800f, engine.state.feet.y, .001f)
        }
    }
    @Test fun petHopsUpToAdjacentFolderTopAndLandsWithoutTunnelling() {
        val engine = PetEngine(2).apply {
            restore(PetStats(lastUpdatedTimestamp = 1000)); viewport(600f, 1000f, 1f, 20f)
            surfaces(listOf(PlatformSurface("dock", Rect(0f, 800f, 44f, 880f), PlatformKind.DOCK),
                PlatformSurface("folder", Rect(70f, 720f, 114f, 764f))))
        }
        engine.target(Offset(92f, 720f))
        var jumped = false
        repeat(480) { engine.tick(1f / 120, PetWeather.NONE, Offset.Zero); if(engine.state.mode == PetMode.HOPPING) jumped = true }
        assertTrue(jumped)
        assertEquals("folder", engine.state.platformId)
        assertEquals(720f, engine.state.feet.y, .5f)
        assertTrue(engine.state.feet.x in 70f..114f)
    }
    @Test fun disappearingPlatformAndRotationRebaseWithoutFallingOutsideViewport() {
        val engine = engine()
        engine.surfaces(emptyList())
        assertEquals("floor", engine.state.platformId)
        engine.viewport(1000f, 600f, 1f, 20f)
        repeat(120) { engine.tick(.1f, PetWeather.RAIN, Offset(1f, 1f)) }
        assertTrue(engine.state.feet.x in 0f..1000f)
        assertTrue(engine.state.feet.y <= 575f)
    }
    @Test fun exhaustedPetRecoversFromLegacyZeroEvenWhileTheLauncherIsClosed() {
        val start = 1000L
        val hour = 3_600_000L
        val empty = PetStats(energy = 0f, lastUpdatedTimestamp = start)
        val resting = empty.advance(start + 4 * hour)
        assertEquals(12f, resting.energy, 0f)
        assertTrue(resting.needsRest)
        val recovered = resting.advance(start + 8 * hour)
        assertEquals(24f, recovered.energy, 0f)
        assertFalse(recovered.needsRest)
        assertEquals(24f, empty.advance(start + 2 * hour, plugged = true).energy, 0f)
    }
    @Test fun exhaustedPetSleepsDuringTheDayAndPettingRestoresEnergy() {
        val engine = engine()
        engine.restore(PetStats(energy = 0f, lastUpdatedTimestamp = 1000))
        engine.environment(PowerState(80, false), false)
        engine.tick(.02f, PetWeather.NONE, Offset.Zero)
        assertEquals(PetMode.SLEEPING, engine.state.mode)
        engine.pat(2000)
        engine.tick(.02f, PetWeather.NONE, Offset.Zero)
        assertEquals(24f, engine.state.stats.energy, 0f)
        assertFalse(engine.state.stats.needsRest)
        assertEquals(PetMode.HAPPY, engine.state.mode)
    }
    @Test fun overnightRestChargesEvenANonExhaustedPetWithoutDoubleCounting() {
        val start = 1_000L
        val morning = start + 8 * 3_600_000L
        val before = PetStats(energy = 12f, lastUpdatedTimestamp = start)
        val after = before.advance(morning, away = true)
        assertEquals(76f, after.energy, 0f)
        assertEquals(after, after.advance(morning, away = true))
        assertEquals(100f, before.advance(morning + 8 * 3_600_000L, away = true).energy, 0f)
        assertEquals(70f, before.copy(energy = 6f).advance(morning, away = true).energy, 0f)
    }

    @Test fun pagerTransitionKeepsThePetInPlaceWhileEffectsContinue() {
        val engine = engine()
        engine.target(Offset(540f, 850f))
        val start = engine.state.feet
        val startTime = engine.state.animationSeconds
        repeat(30) { engine.tick(.02f, PetWeather.FIREFLIES, Offset.Zero, moveCharacter = false) }
        assertEquals(start, engine.state.feet)
        assertTrue(engine.state.animationSeconds > startTime)
    }

    @Test fun powerNightPettingAndNotificationSelectRealStates() {
        val engine = engine()
        engine.environment(PowerState(8, false), false); engine.tick(.02f, PetWeather.NONE, Offset.Zero)
        assertEquals(PetMode.SLEEPING, engine.state.mode)
        engine.environment(PowerState(8, true), night = true, sleep = false); engine.tick(.02f, PetWeather.NONE, Offset.Zero)
        assertEquals(PetMode.EATING, engine.state.mode)
        engine.environment(PowerState(80, false), false); engine.pat(2000); engine.tick(.02f, PetWeather.NONE, Offset.Zero)
        assertEquals(PetMode.HAPPY, engine.state.mode)
        engine.notification(); repeat(30) { engine.tick(.1f, PetWeather.NONE, Offset.Zero) }
        assertEquals(PetMode.COURIER, engine.state.mode)
        engine.clean(3000); assertEquals(100f, engine.state.stats.cleanliness, 0f)
    }
    @Test fun rainBouncesOnAPlatformAndAllParticlesRemainBelowPortrait() {
        val field = ParticleField(2)
        val area = Rect(0f, 450f, 600f, 980f)
        field.reset(area); field.x[0] = 100f; field.y[0] = 699f; field.vy[0] = 200f
        field.step(.02f, PetWeather.RAIN, listOf(PlatformSurface("folder", Rect(0f, 700f, 600f, 750f))), area)
        assertEquals(700f, field.y[0], .01f); assertTrue(field.vy[0] < 0)
        repeat(500) { field.step(1f / 120, PetWeather.RAIN, emptyList(), area) }
        assertTrue(field.y.all { it in 450f..980f })
    }
    @Test fun parallaxIsRelativeFilteredBoundedAndUnwrapsAngles() {
        val filter = ParallaxFilter()
        assertEquals(Offset.Zero, filter.update(0f, 3.13f, 1))
        val nearby = filter.update(0f, -3.13f, 20_000_001)
        assertTrue(kotlin.math.abs(nearby.x) < .2f)
        var value = nearby
        repeat(40) { value = filter.update(1f, 1f, (it + 2) * 20_000_000L) }
        assertTrue(value.x in -1f..1f && value.y in -1f..1f)
        assertEquals(value, filter.update(Float.NaN, 0f, 1_000_000_000))
    }
    @Test fun overlappingNearlyLevelPlatformsAreWalkedWithoutVerticalBouncing() {
        val engine = engine()
        engine.surfaces(listOf(PlatformSurface("dock", Rect(0f, 800f, 600f, 900f), PlatformKind.DOCK),
            PlatformSurface("folder", Rect(70f, 798f, 114f, 842f))))
        engine.target(Offset(92f, 798f))
        repeat(480) { engine.tick(1f / 120, PetWeather.NONE, Offset.Zero); assertNotEquals(PetMode.HOPPING, engine.state.mode) }
        assertEquals("folder", engine.state.platformId)
    }
    @Test fun losingTheDestinationDuringAJumpDoesNotRetryTheSameJumpForever() {
        val engine = engine()
        val dock = PlatformSurface("dock", Rect(0f, 800f, 600f, 900f), PlatformKind.DOCK)
        engine.surfaces(listOf(dock, PlatformSurface("folder", Rect(70f, 720f, 114f, 764f))))
        engine.target(Offset(92f, 720f))
        repeat(300) { if(engine.state.mode != PetMode.HOPPING) engine.tick(1f / 120, PetWeather.NONE, Offset.Zero) }
        assertEquals(PetMode.HOPPING, engine.state.mode)
        engine.surfaces(listOf(dock))
        repeat(240) { engine.tick(1f / 120, PetWeather.NONE, Offset.Zero) }
        assertEquals("dock", engine.state.platformId)
        assertNull(engine.state.targetPlatform)
        repeat(480) { engine.tick(1f / 120, PetWeather.NONE, Offset.Zero); assertNotEquals(PetMode.HOPPING, engine.state.mode) }
    }
    @Test fun calmAutonomousMovementSpacesOutHopsAndKeepsWalkingDominant() {
        val engine = engine()
        engine.surfaces(listOf(PlatformSurface("dock", Rect(0f, 800f, 600f, 900f), PlatformKind.DOCK),
            PlatformSurface("folder", Rect(70f, 720f, 114f, 764f))))
        var hops = 0; var wasHopping = false; var hopFrames = 0; var walkFrames = 0
        repeat(10800) {
            engine.tick(1f / 120, PetWeather.NONE, Offset.Zero, PetSettings(motion = PetMotion.CALM))
            val hopping = engine.state.mode == PetMode.HOPPING
            if(hopping && !wasHopping) hops++
            if(hopping) hopFrames++
            if(engine.state.mode == PetMode.WALKING) walkFrames++
            wasHopping = hopping
        }
        assertTrue("Only occasional autonomous hops over 90 seconds: $hops", hops <= 7)
        assertTrue(walkFrames > hopFrames)
    }
    @Test fun fullScreenEffectsCanReachTheTopAndLowerModeRestoresPortraitProtection() {
        val engine = engine()
        engine.tick(.01f, PetWeather.SPARKLES, Offset.Zero, PetSettings(effectArea = PetEffectArea.FULL_SCREEN))
        assertTrue(engine.particles.y.take(engine.particles.activeCount).any { it < 450f })
        engine.tick(.01f, PetWeather.SNOW, Offset.Zero, PetSettings(effectArea = PetEffectArea.LOWER_SCREEN))
        assertTrue(engine.particles.y.all { it in 450f..980f })
        engine.target(Offset(150f, 20f)); assertTrue(engine.state.ripple!!.y >= 450)
    }
    @Test fun particleDensityAndSpeedRespectConfigurationAndInvalidValuesAreBounded() {
        val low = ParticleField(1); val high = ParticleField(1)
        val area = Rect(0f, 0f, 600f, 1000f)
        low.reset(area); high.reset(area)
        low.x[0] = 100f; high.x[0] = 100f; low.y[0] = 500f; high.y[0] = 500f
        low.step(.1f, PetWeather.SNOW, emptyList(), area, .1f, .5f)
        high.step(.1f, PetWeather.SNOW, emptyList(), area, 1f, 2f)
        assertTrue(high.activeCount > low.activeCount)
        assertTrue(high.y[0] - 500 > (low.y[0] - 500) * 3)
        val normalized = PetSettings(effectIntensity = Float.NaN, effectSpeed = 100f, effectSize = -1f, effectOpacity = Float.POSITIVE_INFINITY).normalized()
        assertEquals(.5f, normalized.effectIntensity, 0f); assertEquals(2f, normalized.effectSpeed, 0f)
        assertEquals(.5f, normalized.effectSize, 0f); assertEquals(.6f, normalized.effectOpacity, 0f)
    }
    @Test fun robotAppearanceDoesNotChangePetSizeOrCollisionPlatforms() {
        val engine = engine(); val before = engine.state
        engine.tick(.02f, PetWeather.NONE, Offset.Zero, PetSettings(appearance = PetAppearance.HOME_ROBOT))
        assertEquals(PetAppearance.HOME_ROBOT, engine.state.appearance)
        assertEquals(before.sizePx, engine.state.sizePx, 0f); assertEquals(before.platformId, engine.state.platformId)
    }
    @Test fun messageIndicatorPersistsBeyondAnimationAndClearsOnlyWithActiveNotifications() {
        val engine = engine()
        engine.notifications(3)
        repeat(3600) { engine.tick(1f / 120, PetWeather.NONE, Offset.Zero) }
        assertEquals(PetMode.COURIER, engine.state.mode)
        assertEquals(3, engine.state.notificationCount)
        engine.pat(5000); engine.tick(.02f, PetWeather.NONE, Offset.Zero)
        assertEquals(PetMode.COURIER, engine.state.mode)
        engine.environment(PowerState(80, true), false); engine.tick(.02f, PetWeather.NONE, Offset.Zero)
        assertTrue(engine.state.charging); assertEquals(PetMode.COURIER, engine.state.mode)
        engine.notifications(0); repeat(30) { engine.tick(.1f, PetWeather.NONE, Offset.Zero) }
        assertEquals(PetMode.EATING, engine.state.mode)
    }
    @Test fun messageAppSelectionSupportsAllSelectedNoneAndDisabled() {
        val active = mapOf("chat.one" to 2, "chat.two" to 1, "other" to 4)
        assertEquals(7, PetSettings().messageCount(active))
        val selected = PetSettings(allMessageApps = false, messageApps = setOf("chat.one", "chat.two"))
        assertEquals(3, selected.messageCount(active))
        assertEquals(0, selected.copy(messageApps = emptySet()).messageCount(active))
        assertEquals(0, selected.copy(messagesEnabled = false).messageCount(active))
        assertEquals(1, selected.messageCount(mapOf("chat.two" to 1)))
    }
    @Test fun rainLeavesTheDockAfterOneBounceInsteadOfAccumulatingOnIt() {
        val field = ParticleField(3, 1)
        val area = Rect(0f, 0f, 600f, 1000f)
        val dock = PlatformSurface("dock", Rect(0f, 800f, 600f, 900f), PlatformKind.DOCK)
        field.reset(area); field.x[0] = 100f; field.y[0] = 799f; field.vy[0] = 200f
        field.step(.02f, PetWeather.RAIN, listOf(dock), area, 1f)
        assertEquals(800f, field.y[0], .01f); assertTrue(field.vy[0] < 0)
        val impactX = field.splashX[0]
        repeat(20) { field.step(1f / 120, PetWeather.RAIN, listOf(dock), area, 1f) }
        assertEquals(impactX, field.splashX[0], 0f)
        assertEquals(800f, field.splashY[0], 0f)
        repeat(60) { field.step(1f / 120, PetWeather.RAIN, listOf(dock), area, 1f) }
        assertTrue("Bounced drop must respawn near top", field.y[0] < 100f)
        assertEquals(0f, field.splash[0], 0f)
    }
    @Test fun extendedRainDoesNotPoolIntoAHorizontalLineOnTheDock() {
        val field = ParticleField(8)
        val area = Rect(0f, 0f, 600f, 1000f)
        val dock = PlatformSurface("dock", Rect(0f, 800f, 600f, 900f), PlatformKind.DOCK)
        field.reset(area)
        repeat(7200) { field.step(1f / 120, PetWeather.RAIN, listOf(dock), area, 1f) }
        assertTrue(field.y.count { it in 790f..820f } < field.count / 2)
        field.step(.01f, PetWeather.SNOW, listOf(dock), area, 1f)
        assertTrue(field.splash.all { it == 0f })
    }
}
