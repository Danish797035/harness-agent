package com.meenai.mina.core.router.picker

/**
 * Routes tasks to appropriate model roles based on difficulty classification and runtime escalation.
 *
 * - Heavy -> AutoModelPicker Main role (sabse strong free model)
 * - Light -> AutoModelPicker Light role (sabse tez)
 * - Escalation: When a Light task experiences 2 failures or repeats the same step,
 *   it escalates to Heavy on the next step boundary. Once escalated, it does not downgrade back to Light within the same task.
 * - Pinned: When a pinned model is configured, difficulty routing is bypassed and the pinned model is used directly.
 */
class DifficultyRouter(
    val picker: AutoModelPicker,
    val classifier: TaskDifficultyClassifier = TaskDifficultyClassifier,
    val pinnedModelId: String? = null,
) {
    var currentRole: ModelRole = ModelRole.Light
        private set

    var currentDifficulty: TaskDifficulty = TaskDifficulty.Light
        private set

    var failureCount: Int = 0
        private set

    var lastStep: String? = null
        private set

    var isEscalated: Boolean = false
        private set

    /**
     * Initializes or resets state for a new task.
     */
    fun startTask(
        task: String,
        hasImage: Boolean = false,
        plannedSteps: Int? = null,
        charCount: Int = 0,
    ): TaskDifficulty {
        currentRole = classifier.classifyToRole(task, hasImage, plannedSteps, charCount)
        currentDifficulty = if (currentRole == ModelRole.Main || currentRole == ModelRole.Coding || currentRole == ModelRole.Vision) {
            TaskDifficulty.Heavy
        } else {
            TaskDifficulty.Light
        }
        failureCount = 0
        lastStep = null
        isEscalated = (currentDifficulty == TaskDifficulty.Heavy)
        return currentDifficulty
    }

    /**
     * Records a step execution failure.
     * Escalates from Light to Heavy if 2 or more failures occur.
     */
    fun recordFailure() {
        failureCount++
        if (currentDifficulty == TaskDifficulty.Light && failureCount >= 2) {
            escalate()
        }
    }

    /**
     * Records a successful step.
     * Note: Does NOT downgrade from Heavy back to Light ("Wapas Light mat jao usi task mein").
     */
    fun recordSuccess() {
        // Preserves currentDifficulty for the task lifetime
    }

    /**
     * Records a step boundary with its action signature.
     * If the same step is repeated consecutively, escalates to Heavy on the next boundary.
     */
    fun recordStep(step: String) {
        if (currentDifficulty == TaskDifficulty.Light && lastStep != null && lastStep == step) {
            escalate()
        }
        lastStep = step
    }

    /**
     * Force escalates the task to Heavy difficulty.
     */
    fun escalate() {
        currentDifficulty = TaskDifficulty.Heavy
        currentRole = ModelRole.Main
        isEscalated = true
    }

    /**
     * Returns the target [ModelRole] for the current task state.
     * - If image input is present, returns [ModelRole.Vision].
     * - If escalated, returns [ModelRole.Main].
     * - Otherwise returns [currentRole].
     */
    fun getRole(hasImage: Boolean = false): ModelRole {
        if (hasImage) return ModelRole.Vision
        if (isEscalated) return ModelRole.Main
        return currentRole
    }

    /**
     * Resolves the best model candidate using [AutoModelPicker].
     * If [pinnedModelId] is present, routing is bypassed and the pinned model is chosen directly.
     */
    suspend fun resolveModel(
        candidates: List<ModelCandidate>? = null,
        hasImage: Boolean = false,
    ): ModelCandidate? {
        // Pinned model ho to routing band, wahi model
        if (!pinnedModelId.isNullOrBlank()) {
            return if (candidates != null) {
                picker.pickFromCandidates(candidates, ModelRole.Main, pinnedModelId = pinnedModelId)
            } else {
                picker.pick(ModelRole.Main, pinnedModelId = pinnedModelId)
            }
        }

        val role = getRole(hasImage)
        return if (candidates != null) {
            picker.pickFromCandidates(candidates, role)
        } else {
            picker.pick(role)
        }
    }

    /**
     * Classifies a task and resolves the model in one call.
     */
    suspend fun route(
        task: String,
        hasImage: Boolean = false,
        plannedSteps: Int? = null,
        candidates: List<ModelCandidate>? = null,
        charCount: Int = 0,
    ): ModelCandidate? {
        startTask(task, hasImage, plannedSteps, charCount)
        return resolveModel(candidates, hasImage)
    }
}
