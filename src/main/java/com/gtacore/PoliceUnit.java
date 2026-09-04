package com.gtacore;

import java.util.UUID;

/**
 * Runtime pairing between an officer, cruiser, target, and state.
 *
 * This deliberately contains no Minecraft/MTS implementation details.
 * Vehicle control, NPC behavior, dispatch, and spawning can therefore
 * evolve independently around the same police-unit state machine.
 */
public final class PoliceUnit {

    private final UUID officerId;
    private UUID vehicleId;
    private UUID targetId;
    private PoliceState state;

    public PoliceUnit(
        UUID officerId,
        UUID vehicleId
    ) {
        this.officerId = officerId;
        this.vehicleId = vehicleId;
        this.state = PoliceState.PATROL;
    }

    public UUID getOfficerId() {
        return officerId;
    }

    public UUID getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(UUID vehicleId) {
        this.vehicleId = vehicleId;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public void setTargetId(UUID targetId) {
        this.targetId = targetId;
    }

    public PoliceState getState() {
        return state;
    }

    public void setState(PoliceState state) {
        this.state = state;
    }
}
