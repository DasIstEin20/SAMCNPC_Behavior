package io.samcnpc.behavior.lumberjack.model

import io.samcnpc.behavior.task.WorkArea
import io.samcnpc.behavior.task.WoodSelection

/** Supplied by the immutable operation definition; not serialized inside execution checkpoints. */
internal data class LumberjackWorkSelection(val area: WorkArea, val wood: WoodSelection)
