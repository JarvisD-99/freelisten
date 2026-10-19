package app.tagplayer.core

import kotlin.random.Random

data class PlayQueue(
    val ids: List<String> = emptyList(),
    val index: Int = 0,
) {
    val currentId: String? get() = ids.getOrNull(index)
}

sealed interface QueueStep {
    data class Move(val queue: PlayQueue) : QueueStep
    data object RoundDone : QueueStep
    data object Empty : QueueStep
}

sealed interface PreviousStep {
    data class Move(val queue: PlayQueue) : PreviousStep
    data object Restart : PreviousStep
    data object Empty : PreviousStep
}

fun shuffledQueue(ids: List<String>, random: Random = Random.Default): List<String> {
    val list = ids.toMutableList()
    for (index in list.lastIndex downTo 1) {
        val swap = random.nextInt(index + 1)
        val tmp = list[index]
        list[index] = list[swap]
        list[swap] = tmp
    }
    return list
}

fun queueStartingWith(ids: List<String>, startId: String, random: Random = Random.Default): List<String> {
    if (startId !in ids) return shuffledQueue(ids, random)
    return listOf(startId) + shuffledQueue(ids.filterNot { it == startId }, random)
}

fun nextTrack(queue: PlayQueue): QueueStep {
    if (queue.ids.isEmpty()) return QueueStep.Empty
    if (queue.index >= queue.ids.lastIndex) return QueueStep.RoundDone
    return QueueStep.Move(queue.copy(index = queue.index + 1))
}

fun previousTrack(queue: PlayQueue, positionMs: Long): PreviousStep {
    if (queue.ids.isEmpty()) return PreviousStep.Empty
    if (positionMs > 3_000 || queue.index <= 0) return PreviousStep.Restart
    return PreviousStep.Move(queue.copy(index = queue.index - 1))
}
