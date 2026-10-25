package org.xianshen.mumirrorb.action;

import java.util.UUID;

/** After-commit request to finish an asynchronous, cancellable action-card preparation. */
public record ActionPreparationRequestedEvent(UUID userId, UUID actionId, int preparationVersion) {}
