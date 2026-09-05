package com.gtacore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime registry for police cruisers.
 *
 * Each vehicle gets its own PoliceUnit and its own independent driving
 * controller state.  That lets several cruisers use GTACore's existing
 * pursuit controller in the same server tick without sharing steering,
 * transmission, or hard-turn state.
 */
public final class PoliceUnitManager {

    private final Map<UUID, ManagedUnit>
        unitsByVehicle =
            new LinkedHashMap<>();

    public ManagedUnit registerVehicle(
        UUID vehicleId,
        String templateName
    ) {

        ManagedUnit existing =
            unitsByVehicle.get(
                vehicleId
            );

        if (existing != null) {
            return existing;
        }

        /*
         * The visible CustomNPC officer is not wired yet.
         * Use a stable runtime placeholder ID for now so the existing
         * PoliceUnit model remains ready for officer assignment later.
         */
        PoliceUnit unit =
            new PoliceUnit(
                UUID.randomUUID(),
                vehicleId
            );

        ManagedUnit managed =
            new ManagedUnit(
                unit,
                templateName
            );

        unitsByVehicle.put(
            vehicleId,
            managed
        );

        return managed;
    }

    public ManagedUnit getByVehicle(
        UUID vehicleId
    ) {

        return unitsByVehicle.get(
            vehicleId
        );
    }

    public boolean containsVehicle(
        UUID vehicleId
    ) {

        return unitsByVehicle.containsKey(
            vehicleId
        );
    }

    public boolean isVehicleInActivePursuit(
        UUID vehicleId
    ) {

        ManagedUnit managed =
            getByVehicle(
                vehicleId
            );

        return managed != null &&
            managed.unit.getState() ==
                PoliceState.VEHICLE_PURSUIT &&
            managed.unit.getTargetId() != null;
    }

    public Collection<ManagedUnit> getUnits() {

        return new ArrayList<>(
            unitsByVehicle.values()
        );
    }

    public List<ManagedUnit> getActivePursuitUnits() {

        List<ManagedUnit> active =
            new ArrayList<>();

        for (
            ManagedUnit managed :
            unitsByVehicle.values()
        ) {

            if (
                managed.unit.getState() ==
                    PoliceState.VEHICLE_PURSUIT &&
                managed.unit.getTargetId() != null
            ) {

                active.add(
                    managed
                );
            }
        }

        return active;
    }

    public int size() {

        return unitsByVehicle.size();
    }

    public int activePursuitCount() {

        return getActivePursuitUnits()
            .size();
    }

    public void dispatchAll(
        UUID targetId
    ) {

        for (
            ManagedUnit managed :
            unitsByVehicle.values()
        ) {

            managed.unit.setTargetId(
                targetId
            );

            managed.unit.setState(
                PoliceState.VEHICLE_PURSUIT
            );
        }
    }

    public void clearPursuits() {

        for (
            ManagedUnit managed :
            unitsByVehicle.values()
        ) {

            managed.unit.setTargetId(
                null
            );

            managed.unit.setState(
                PoliceState.PATROL
            );

            managed.drive.reset();
        }
    }

    public void removeVehicle(
        UUID vehicleId
    ) {

        unitsByVehicle.remove(
            vehicleId
        );
    }

    public static final class ManagedUnit {

        private final PoliceUnit unit;
        private final String templateName;
        private final DriveState drive;

        private ManagedUnit(
            PoliceUnit unit,
            String templateName
        ) {

            this.unit =
                unit;

            this.templateName =
                templateName == null
                    ? "unknown"
                    : templateName;

            this.drive =
                new DriveState();
        }

        public PoliceUnit getUnit() {
            return unit;
        }

        public String getTemplateName() {
            return templateName;
        }

        public DriveState getDrive() {
            return drive;
        }
    }

    /**
     * Per-cruiser copy of the mutable values used by GTACore's proven
     * follow controller.
     *
     * GTACore loads this state immediately before ticking one cruiser
     * and writes it back immediately afterward.  Therefore a Mustang
     * can be hard-turning while a Mercedes is driving straight without
     * either vehicle corrupting the other's controller state.
     */
    public static final class DriveState {

        boolean driveForward;
        boolean driveReverse;
        double throttleCommand;

        int followBaselineMode;
        double followTurnDirection;
        double followHeadingError;
        int followMisalignmentTicks;
        int followSteerPulseTick;
        boolean followDigitalSteeringActive;

        boolean followHardTurnActive;
        boolean followHardTurnCatchup;
        int followHardTurnConfirmTicks;
        int followHardTurnGrowthTicks;
        double followPreviousAbsoluteError;

        double aiTargetSpeed;
        double aiCurrentSpeed;
        double brakeCommand;
        double parkingBrakeCommand;

        double steeringTarget;
        double steeringCurrent;
        int steeringTapTicksRemaining;
        int steeringTapRestTicksRemaining;
        double steeringTapDirection;

        int transmissionTickCounter;

        /*
         * Global road-path state.  This sits ABOVE the normal steering
         * controller: the pathfinder chooses a waypoint, then GTACore's
         * existing steering code drives toward that waypoint.
         */
        final List<Integer> roadRoute =
            new ArrayList<>();

        int roadRouteIndex;
        int roadRepathTicks;
        int roadStartNodeId;
        int roadDestinationNodeId;
        boolean roadUsingPath;

        DriveState() {
            reset();
        }

        public void reset() {

            driveForward = false;
            driveReverse = false;
            throttleCommand = 0.0;

            /*
             * FOLLOW_TURNING in GTACore is 1.
             * We keep this class implementation-independent otherwise.
             */
            followBaselineMode = 1;
            followTurnDirection = 0.0;
            followHeadingError = 0.0;
            followMisalignmentTicks = 0;
            followSteerPulseTick = 0;
            followDigitalSteeringActive = false;

            followHardTurnActive = false;
            followHardTurnCatchup = false;
            followHardTurnConfirmTicks = 0;
            followHardTurnGrowthTicks = 0;
            followPreviousAbsoluteError = 0.0;

            aiTargetSpeed = 0.0;
            aiCurrentSpeed = 0.0;
            brakeCommand = 1.0;
            parkingBrakeCommand = 0.0;

            steeringTarget = 0.0;
            steeringCurrent = 0.0;
            steeringTapTicksRemaining = 0;
            steeringTapRestTicksRemaining = 0;
            steeringTapDirection = 0.0;

            transmissionTickCounter = 0;

            roadRoute.clear();
            roadRouteIndex = 0;
            roadRepathTicks = 0;
            roadStartNodeId = -1;
            roadDestinationNodeId = -1;
            roadUsingPath = false;
        }
    }
}
