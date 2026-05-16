package com.carcompanion.companion.ui.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carcompanion.companion.service.CarCompanionService
import com.carcompanion.companion.ui.theme.CompanionTheme

class SoulDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompanionTheme {
                Scaffold { padding ->
                    SoulDebugScreen(modifier = Modifier.padding(padding).fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun SoulDebugScreen(modifier: Modifier = Modifier) {
    val soul by CarCompanionService.soulSnapshot.collectAsState()
    val emotion by CarCompanionService.emotion.collectAsState()
    val hypothesis by CarCompanionService.hypothesis.collectAsState()
    val moodlets by CarCompanionService.activeMoodlets.collectAsState()
    val quirks by CarCompanionService.activeQuirks.collectAsState()
    val driver by CarCompanionService.driverProfile.collectAsState()
    val history by CarCompanionService.stateHistory.collectAsState()
    val reactions by CarCompanionService.reactionHistory.collectAsState()

    val now = System.currentTimeMillis()
    val scroll = rememberScrollState()

    // Use scroll + height (not weight) so each section gets natural height
    // and the page becomes scrollable when content overflows the screen.
    Column(
        modifier = modifier
            .verticalScroll(scroll)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header row — emotion + hypothesis at a glance
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(emotion.emoji, style = MaterialTheme.typography.displayMedium)
            Column(modifier = Modifier.weight(1f)) {
                Text(emotion.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${hypothesis.emoji} ${hypothesis.label}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "v${com.carcompanion.companion.BuildConfig.VERSION_NAME}" +
                        " · build ${com.carcompanion.companion.BuildConfig.VERSION_CODE}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            QuirkChips(quirks)
        }

        // Top row: 2D emotion plot · personality radar · stats column
        Row(
            modifier = Modifier.fillMaxWidth().height(280.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DashCard(modifier = Modifier.weight(1f)) {
                Text("2D Emotion", style = MaterialTheme.typography.titleSmall)
                EmotionPlot(
                    valence = soul?.valence ?: 0f,
                    arousal = soul?.arousal ?: 0f,
                    emotion = emotion,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
            }
            DashCard(modifier = Modifier.weight(1f)) {
                Text("Personality", style = MaterialTheme.typography.titleSmall)
                val traits = traitsForDisplay()
                PersonalityRadar(
                    traits = traits,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
            }
            DashCard(modifier = Modifier.weight(1f)) {
                Text("Stats", style = MaterialTheme.typography.titleSmall)
                if (soul != null) {
                    StatBar("Mood", soul!!.mood.toFloat())
                    StatBar("Energy", soul!!.energy)
                    StatBar("Hunger", soul!!.hunger,
                        color = MaterialTheme.colorScheme.error)
                    StatBar("Curio", soul!!.curiosity,
                        color = MaterialTheme.colorScheme.tertiary)
                    StatBar("Bond", soul!!.bond,
                        color = MaterialTheme.colorScheme.secondary)
                    StatBar("Stress", soul!!.stress,
                        color = MaterialTheme.colorScheme.error)
                    StatBar("Vit", soul!!.vitality)
                }
            }
        }

        // Middle row: history chart · moodlets
        Row(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DashCard(modifier = Modifier.weight(1.4f)) {
                HistoryChart(points = history, modifier = Modifier.fillMaxSize())
            }
            DashCard(modifier = Modifier.weight(1f)) {
                MoodletList(moodlets = moodlets, nowMs = now)
            }
        }

        // Bottom row: driver · reaction timeline
        Row(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DashCard(modifier = Modifier.weight(1f)) {
                DriverPanel(driver)
            }
            DashCard(modifier = Modifier.weight(1.4f)) {
                ReactionTimeline(entries = reactions, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun DashCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp).fillMaxSize()) {
            content()
        }
    }
}

/**
 * Personality traits aren't exposed on the service yet, but they're constant
 * for a given persona — read them on the fly from the running CharacterEngine
 * via the snapshot we already have.
 *
 * For now we hard-code Kuro defaults; Round 5b will plumb the live traits
 * through the service singleton.
 */
@Composable
private fun traitsForDisplay(): com.carcompanion.companion.data.CoreTraits {
    val flow by CarCompanionService.personalityTraits.collectAsState()
    return flow
}
