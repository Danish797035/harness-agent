package com.meenai.mina.ui

import android.content.Context

/** Three steps to get started for the first time. */
enum class OnboardingStep { Welcome, Model, Phone }

/**
 * What steps to take when opening for the first time (pure function, easy for testing). Just like the
 * mobile phone guide, press "Have you seen it" to judge, instead of playing it every time:
 * - -Welcome and configure the model: Do not leave until you have a master model and have not configured
 * a model in the onboarding process. Those who have already configured models (including old users) can
 * skip it directly.
 * - - Mobile phone operation: The system is new enough, but the mobile phone operation is not ready yet,
 * and the guide is out of date without viewing it.
 */
object Onboarding {
    data class Inputs(
        val hasMainModel: Boolean,
        val modelSetupDone: Boolean,
        /** The system is new enough and mobile phone operation is not yet ready. */
        val phoneNeeded: Boolean,
        val phoneGuideSeen: Boolean,
    )

    fun start(i: Inputs): OnboardingStep? = when {
        !i.hasMainModel && !i.modelSetupDone -> OnboardingStep.Welcome
        i.phoneNeeded && !i.phoneGuideSeen -> OnboardingStep.Phone
        else -> null
    }

    /** [step] The next step after completion; if there is no more, it is null (enter the dialogue). */
    fun next(step: OnboardingStep, i: Inputs): OnboardingStep? = when (step) {
        OnboardingStep.Welcome -> OnboardingStep.Model
        OnboardingStep.Model -> if (i.phoneNeeded && !i.phoneGuideSeen) OnboardingStep.Phone else null
        OnboardingStep.Phone -> null
    }

    /** Bottom pagination point: Which step among the three steps. */
    fun dot(step: OnboardingStep): Int = step.ordinal
    const val DOTS = 3
}

/** Have you completed the "Configuration Model" in the getting started process? It exists on this machine. */
class OnboardingState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun modelSetupDone(): Boolean = prefs.getBoolean(MODEL_DONE, false)

    fun markModelSetupDone() {
        prefs.edit().putBoolean(MODEL_DONE, true).apply()
    }

    private companion object {
        const val PREFS = "onboarding"
        const val MODEL_DONE = "model_setup_done"
    }
}
