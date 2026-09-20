package io.samcnpc.behavior.api

import java.util.UUID

/** Transient observation identities; none is a task revision, action receipt or permission. */
data class OperationGenerations(val serverSession: UUID, val registry: UUID, val body: UUID)
