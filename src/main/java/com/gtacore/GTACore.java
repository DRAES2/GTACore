package com.gtacore;

import com.mojang.brigadier.Command;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Mod(GTACore.MOD_ID)
public class GTACore {

    public static final String MOD_ID = "gtacore";

    private static UUID selectedCar = null;

    /*
     * Temporary AI driving inputs.
     *
     * driveForward = our virtual W key.
     */
    private static boolean driveForward = false;
    private static boolean driveReverse = false;
    private static double throttleCommand = 1.0;

    /*
     * Prototype home-return system.
     *
     * This is intentionally simple point-to-point navigation for
     * open areas.  It does not yet know roads or avoid obstacles.
     * Later the police station/road-graph system will replace the
     * straight-line homing controller.
     */
    private static boolean returningHome = false;
    private static boolean homeSet = false;
    private static double homeX = 0.0;
    private static double homeY = 0.0;
    private static double homeZ = 0.0;
    private static String homeDimension = "";

    /*
     * Instead of trying to drive in one straight line through the
     * world, GTACore records a breadcrumb trail while the car moves
     * away from home.  /gta home follows those points in reverse.
     *
     * This is still a prototype, but it is much closer to how a
     * police car should return through streets than the original
     * direct-to-home steering.
     */
    private static final List<HomeWaypoint> homeTrail =
        new ArrayList<>();

    private static int homeTrailTickCounter = 0;
    private static int homeRouteIndex = -1;

    /*
     * A return route starts with the previous breadcrumb behind the
     * car.  If we always command forward, the car has to attempt a
     * huge U-turn.  Instead, use a short reversing turn until the
     * nose is reasonably aligned with the route, then shift back
     * into forward.
     *
     * The two thresholds provide hysteresis so the controller does
     * not bounce rapidly between forward and reverse.
     */
    private static final int HOME_TURN_FOLLOW = 0;
    private static final int HOME_TURN_REVERSE = 1;
    private static final int HOME_TURN_FORWARD = 2;

    private static int homeTurnPhase =
        HOME_TURN_FOLLOW;

    /*
     * Direction is latched for the whole turnaround.
     * +1 means turn the nose right, -1 means left.
     *
     * This prevents the previous controller from changing its mind
     * every time the target crossed from one side of the car to the
     * other while reversing.
     */
    private static double homeTurnDirection = 1.0;
    private static int homeTurnTicks = 0;
    private static int homeTurnCooldownTicks = 0;

    private static final int HOME_REVERSE_MAX_TICKS = 14;
    private static final int HOME_FORWARD_TURN_MAX_TICKS = 55;
    private static final int HOME_TURN_COOLDOWN_TICKS = 60;

    private static final double HOME_STOP_DISTANCE = 3.0;
    private static final double HOME_WAYPOINT_SPACING = 5.0;
    private static final double HOME_WAYPOINT_REACHED = 4.0;
    private static final double HOME_LOOKAHEAD_DISTANCE = 14.0;
    private static final double HOME_STEERING_GAIN = 0.65;
    private static final double HOME_TURN_START_ANGLE = 100.0;
    private static final double HOME_REVERSE_TO_FORWARD_ANGLE = 105.0;
    private static final double HOME_TURN_FINISH_ANGLE = 22.0;
    private static final double HOME_TURN_STEERING = 30.0;

    // ============================================================
    // FOLLOW SYSTEM
    // ============================================================

    /*
     * /gta follow makes the selected car follow the player who
     * issued the command.  This is the first moving-target version
     * of the driver AI.
     */
    private static UUID followTargetId = null;

    private static final int FOLLOW_TURN_FOLLOW = 0;
    private static final int FOLLOW_TURN_REVERSE = 1;
    private static final int FOLLOW_TURN_FORWARD = 2;

    private static int followTurnPhase =
        FOLLOW_TURN_FOLLOW;

    private static double followTurnDirection = 1.0;
    private static int followTurnTicks = 0;
    private static int followTurnCooldownTicks = 0;

    /*
     * Reverse is only a brief repositioning move.  The old controller
     * could remain in reverse indefinitely because its exit condition
     * depended only on the heading error.  On dirt that created the
     * left/right reversing crawl the player observed.
     */
    private static final int FOLLOW_REVERSE_MAX_TICKS = 14;
    private static final int FOLLOW_FORWARD_TURN_MAX_TICKS = 55;
    private static final int FOLLOW_TURN_COOLDOWN_TICKS = 60;

    private static final double FOLLOW_STOP_DISTANCE = 7.0;
    private static final double FOLLOW_RESUME_DISTANCE = 9.5;
    private static final double FOLLOW_TURN_START_ANGLE = 105.0;
    private static final double FOLLOW_REVERSE_TO_FORWARD_ANGLE = 100.0;
    private static final double FOLLOW_TURN_FINISH_ANGLE = 24.0;
    private static final double FOLLOW_STEERING_GAIN = 0.60;
    private static final double FOLLOW_TURN_STEERING = 28.0;

    private static final class HomeWaypoint {

        private final double x;
        private final double y;
        private final double z;

        private HomeWaypoint(
            double x,
            double y,
            double z
        ) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /*
     * Steering is expressed in MTS rudder-input degrees.
     *
     * MTS ground vehicles use rudderInputVar for steering and
     * normally clamp it to +/-45 degrees.
     *
     * We keep a target plus a smoothed current value so later AI
     * navigation can steer progressively rather than snapping the
     * wheels instantly from lock to lock.
     */
    private static final double MAX_STEERING_INPUT = 45.0;
    private static final double STEERING_STEP_PER_TICK = 2.5;

    private static double steeringTarget = 0.0;
    private static double steeringCurrent = 0.0;

    // ============================================================
    // SERVICE VEHICLE SYSTEM
    // ============================================================

    /*
     * Vehicles registered here are GTACore-managed service vehicles.
     *
     * For now:
     * - fuel is automatically kept full
     *
     * Later this system will also handle:
     * - police vehicle templates
     * - automatic spawning
     * - NPC assignment
     * - station dispatch
     */
    private static final Set<UUID> serviceVehicles =
        new HashSet<>();

    private static int serviceFuelTickCounter = 0;

    public GTACore() {
        MinecraftForge.EVENT_BUS.register(this);
        System.out.println("[GTACore] Loaded!");
    }

    // ============================================================
    // COMMANDS
    // ============================================================

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {

        event.getDispatcher().register(

            Commands.literal("gta")

                // ------------------------------------------------
                // /gta test
                // ------------------------------------------------
                .then(
                    Commands.literal("test")
                        .executes(context -> {

                            context.getSource().sendSuccess(
                                () -> Component.literal(
                                    "GTACore is working."
                                ),
                                false
                            );

                            return Command.SINGLE_SUCCESS;
                        })
                )

                // ------------------------------------------------
                // /gta carselect
                // ------------------------------------------------
                .then(
                    Commands.literal("carselect")
                        .executes(context -> {

                            ServerPlayer player =
                                context.getSource()
                                    .getPlayerOrException();

                            ServerLevel level =
                                player.serverLevel();

                            List<Entity> nearby =
                                level.getEntities(
                                    player,
                                    player.getBoundingBox()
                                        .inflate(10.0),
                                    entity ->
                                        entity.getClass()
                                            .getName()
                                            .equals(
                                                "mcinterface1201.BuilderEntityExisting"
                                            )
                                );

                            Entity closestVehicle = null;

                            double closestDistance =
                                Double.MAX_VALUE;

                            for (Entity wrapper : nearby) {

                                try {

                                    Object internal =
                                        getInternalMTSEntity(
                                            wrapper
                                        );

                                    if (internal == null) {
                                        continue;
                                    }

                                    if (!internal
                                        .getClass()
                                        .getName()
                                        .contains(
                                            "EntityVehicleF_Physics"
                                        )) {

                                        continue;
                                    }

                                    double distance =
                                        wrapper.distanceToSqr(
                                            player
                                        );

                                    if (
                                        distance <
                                        closestDistance
                                    ) {

                                        closestDistance =
                                            distance;

                                        closestVehicle =
                                            wrapper;
                                    }

                                } catch (Exception ignored) {
                                }
                            }

                            if (
                                closestVehicle ==
                                null
                            ) {

                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "No MTS vehicle found within 10 blocks."
                                        )
                                    );

                                return 0;
                            }

                            selectedCar =
                                closestVehicle
                                    .getUUID();

                            driveForward = false;
                            driveReverse = false;
                            returningHome = false;
                            followTargetId = null;
                            followTurnPhase = FOLLOW_TURN_FOLLOW;
                            followTurnDirection = 1.0;
                            homeTurnPhase = HOME_TURN_FOLLOW;
                            homeTurnDirection = 1.0;
                            throttleCommand = 1.0;
                            steeringTarget = 0.0;
                            steeringCurrent = 0.0;

                            context.getSource()
                                .sendSuccess(
                                    () ->
                                        Component.literal(
                                            "MTS vehicle selected."
                                        ),
                                    false
                                );

                            return Command.SINGLE_SUCCESS;
                        })
                )

                // ------------------------------------------------
                // /gta start
                //
                // Recreates the important parts of MTS auto-start
                // without requiring a player in the driver's seat.
                // ------------------------------------------------
                .then(
                    Commands.literal("start")
                        .executes(context -> {

                            if (selectedCar == null) {

                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Select a car first with /gta carselect"
                                        )
                                    );

                                return 0;
                            }

                            try {

                                Object vehicle =
                                    getSelectedVehicle(
                                        context
                                            .getSource()
                                            .getServer()
                                    );

                                if (vehicle == null) {

                                    context.getSource()
                                        .sendFailure(
                                            Component.literal(
                                                "Selected MTS car is not loaded."
                                            )
                                        );

                                    return 0;
                                }

                                startVehicle(vehicle);

                                context.getSource()
                                    .sendSuccess(
                                        () ->
                                            Component.literal(
                                                "GTACore requested engine start."
                                            ),
                                        false
                                    );

                            } catch (Exception e) {

                                e.printStackTrace();

                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "GTACore failed to start the car. Check console."
                                        )
                                    );

                                return 0;
                            }

                            return Command.SINGLE_SUCCESS;
                        })
                )

                // ------------------------------------------------
                // /gta forward
                //
                // Virtual W key.
                // ------------------------------------------------
                .then(
                    Commands.literal("forward")
                        .executes(context -> {

                            if (selectedCar == null) {

                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Select a car first."
                                        )
                                    );

                                return 0;
                            }

                            returningHome = false;
                            followTargetId = null;
                            requestAutoStart(
                                context.getSource().getServer()
                            );
                            driveReverse = false;
                            driveForward = true;
                            throttleCommand = 1.0;

                            context.getSource()
                                .sendSuccess(
                                    () ->
                                        Component.literal(
                                            "Virtual W: ON"
                                        ),
                                    false
                                );

                            return Command.SINGLE_SUCCESS;
                        })
                )

                // ------------------------------------------------
                // /gta left
                // /gta right
                // /gta straight
                //
                // Manual steering tests.  These set a steering
                // target; the tick loop smoothly moves the MTS
                // rudder input toward that target.
                // ------------------------------------------------
                .then(
                    Commands.literal("left")
                        .executes(context -> {

                            if (selectedCar == null) {
                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Select a car first."
                                        )
                                    );
                                return 0;
                            }

                            returningHome = false;
                            followTargetId = null;
                            requestAutoStart(
                                context.getSource().getServer()
                            );
                            steeringTarget =
                                -MAX_STEERING_INPUT;

                            context.getSource()
                                .sendSuccess(
                                    () -> Component.literal(
                                        "Steering target: LEFT"
                                    ),
                                    false
                                );

                            return Command.SINGLE_SUCCESS;
                        })
                )

                .then(
                    Commands.literal("right")
                        .executes(context -> {

                            if (selectedCar == null) {
                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Select a car first."
                                        )
                                    );
                                return 0;
                            }

                            returningHome = false;
                            followTargetId = null;
                            requestAutoStart(
                                context.getSource().getServer()
                            );
                            steeringTarget =
                                MAX_STEERING_INPUT;

                            context.getSource()
                                .sendSuccess(
                                    () -> Component.literal(
                                        "Steering target: RIGHT"
                                    ),
                                    false
                                );

                            return Command.SINGLE_SUCCESS;
                        })
                )

                .then(
                    Commands.literal("straight")
                        .executes(context -> {

                            if (selectedCar == null) {
                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Select a car first."
                                        )
                                    );
                                return 0;
                            }

                            returningHome = false;
                            followTargetId = null;
                            requestAutoStart(
                                context.getSource().getServer()
                            );
                            steeringTarget = 0.0;

                            context.getSource()
                                .sendSuccess(
                                    () -> Component.literal(
                                        "Steering target: STRAIGHT"
                                    ),
                                    false
                                );

                            return Command.SINGLE_SUCCESS;
                        })
                )

                // ------------------------------------------------
                // /gta reverse
                //
                // Virtual reverse input.  GTACore shifts the MTS
                // transmission through neutral into reverse before
                // applying throttle.
                // ------------------------------------------------
                .then(
                    Commands.literal("reverse")
                        .executes(context -> {

                            if (selectedCar == null) {
                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Select a car first."
                                        )
                                    );
                                return 0;
                            }

                            returningHome = false;
                            followTargetId = null;
                            requestAutoStart(
                                context.getSource().getServer()
                            );
                            driveForward = false;
                            driveReverse = true;
                            throttleCommand = 1.0;

                            context.getSource()
                                .sendSuccess(
                                    () -> Component.literal(
                                        "Virtual reverse: ON"
                                    ),
                                    false
                                );

                            return Command.SINGLE_SUCCESS;
                        })
                )

                // ------------------------------------------------
                // /gta sethome
                //
                // Saves the selected vehicle's current location as
                // its prototype home/station position.
                // ------------------------------------------------
                .then(
                    Commands.literal("sethome")
                        .executes(context -> {

                            if (selectedCar == null) {
                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Select a car first."
                                        )
                                    );
                                return 0;
                            }

                            Entity wrapper =
                                getSelectedWrapper(
                                    context.getSource()
                                        .getServer()
                                );

                            if (wrapper == null) {
                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Selected car is not loaded."
                                        )
                                    );
                                return 0;
                            }

                            homeX = wrapper.getX();
                            homeY = wrapper.getY();
                            homeZ = wrapper.getZ();
                            homeDimension =
                                wrapper.level()
                                    .dimension()
                                    .location()
                                    .toString();
                            homeSet = true;
                            returningHome = false;
                            homeTurnPhase = HOME_TURN_FOLLOW;
                            homeTurnDirection = 1.0;
                            homeRouteIndex = -1;

                            homeTrail.clear();
                            homeTrail.add(
                                new HomeWaypoint(
                                    homeX,
                                    homeY,
                                    homeZ
                                )
                            );

                            String homeText =
                                String.format(
                                    "Home set: %.1f, %.1f, %.1f",
                                    homeX,
                                    homeY,
                                    homeZ
                                );

                            context.getSource()
                                .sendSuccess(
                                    () -> Component.literal(
                                        homeText
                                    ),
                                    false
                                );

                            return Command.SINGLE_SUCCESS;
                        })
                )

                // ------------------------------------------------
                // /gta home
                //
                // Starts the selected car and drives it back toward
                // the saved home point.
                // ------------------------------------------------
                .then(
                    Commands.literal("home")
                        .executes(context -> {

                            if (selectedCar == null) {
                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Select a car first."
                                        )
                                    );
                                return 0;
                            }

                            if (!homeSet) {
                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Set a home first with /gta sethome"
                                        )
                                    );
                                return 0;
                            }

                            try {
                                MinecraftServer server =
                                    context.getSource()
                                        .getServer();

                                Object vehicle =
                                    getSelectedVehicle(server);

                                Entity wrapper =
                                    getSelectedWrapper(server);

                                if (
                                    vehicle == null ||
                                    wrapper == null
                                ) {
                                    context.getSource()
                                        .sendFailure(
                                            Component.literal(
                                                "Selected car is not loaded."
                                            )
                                        );
                                    return 0;
                                }

                                String currentDimension =
                                    wrapper.level()
                                        .dimension()
                                        .location()
                                        .toString();

                                if (
                                    !homeDimension.equals(
                                        currentDimension
                                    )
                                ) {
                                    context.getSource()
                                        .sendFailure(
                                            Component.literal(
                                                "The car is not in its home dimension."
                                            )
                                        );
                                    return 0;
                                }

                                recordHomeBreadcrumb(
                                    wrapper,
                                    true
                                );

                                followTargetId = null;
                                requestAutoStart(server);

                                /*
                                 * The final breadcrumb is the car's
                                 * present location, so begin with the
                                 * point immediately before it.
                                 */
                                homeRouteIndex =
                                    Math.max(
                                        0,
                                        homeTrail.size() - 2
                                    );

                                homeTurnPhase = HOME_TURN_FOLLOW;
                            homeTurnDirection = 1.0;
                                driveReverse = false;
                                driveForward = true;
                                throttleCommand = 0.25;
                                returningHome = true;

                                int routePoints =
                                    homeTrail.size();

                                context.getSource()
                                    .sendSuccess(
                                        () -> Component.literal(
                                            "Returning home along "
                                                + routePoints
                                                + " recorded points."
                                        ),
                                        false
                                    );

                            } catch (Exception e) {
                                e.printStackTrace();
                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Could not start home return. Check console."
                                        )
                                    );
                                return 0;
                            }

                            return Command.SINGLE_SUCCESS;
                        })
                )

                .then(
                    Commands.literal("cancelhome")
                        .executes(context -> {

                            returningHome = false;
                            homeTurnPhase = HOME_TURN_FOLLOW;
                            homeTurnDirection = 1.0;
                            homeRouteIndex = -1;
                            driveForward = false;
                            driveReverse = false;
                            throttleCommand = 1.0;
                            steeringTarget = 0.0;

                            context.getSource()
                                .sendSuccess(
                                    () -> Component.literal(
                                        "Home return cancelled."
                                    ),
                                    false
                                );

                            return Command.SINGLE_SUCCESS;
                        })
                )

                // ------------------------------------------------
                // /gta follow
                //
                // Makes the selected car autonomously follow the
                // player who issued the command.
                // ------------------------------------------------
                .then(
                    Commands.literal("follow")
                        .executes(context -> {

                            if (selectedCar == null) {
                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Select a car first."
                                        )
                                    );
                                return 0;
                            }

                            ServerPlayer target =
                                context.getSource()
                                    .getPlayerOrException();

                            returningHome = false;
                            homeTurnPhase = HOME_TURN_FOLLOW;
                            homeTurnDirection = 1.0;
                            homeRouteIndex = -1;

                            followTargetId =
                                target.getUUID();

                            followTurnPhase =
                                FOLLOW_TURN_FOLLOW;

                            followTurnDirection = 1.0;
                            followTurnTicks = 0;
                            followTurnCooldownTicks = 0;

                            requestAutoStart(
                                context.getSource().getServer()
                            );

                            context.getSource()
                                .sendSuccess(
                                    () -> Component.literal(
                                        "Selected car is now following you."
                                    ),
                                    false
                                );

                            return Command.SINGLE_SUCCESS;
                        })
                )

                .then(
                    Commands.literal("unfollow")
                        .executes(context -> {

                            followTargetId = null;
                            followTurnPhase =
                                FOLLOW_TURN_FOLLOW;
                            followTurnDirection = 1.0;
                            followTurnTicks = 0;
                            followTurnCooldownTicks = 0;

                            driveForward = false;
                            driveReverse = false;
                            throttleCommand = 1.0;
                            steeringTarget = 0.0;

                            context.getSource()
                                .sendSuccess(
                                    () -> Component.literal(
                                        "Follow cancelled."
                                    ),
                                    false
                                );

                            return Command.SINGLE_SUCCESS;
                        })
                )

                // ------------------------------------------------
                // /gta stop
                //
                // Release W and apply the normal brake.
                // ------------------------------------------------
                .then(
                    Commands.literal("stop")
                        .executes(context -> {

                            returningHome = false;
                            homeTurnPhase = HOME_TURN_FOLLOW;
                            homeTurnDirection = 1.0;
                            homeRouteIndex = -1;
                            driveForward = false;
                            driveReverse = false;
                            throttleCommand = 1.0;
                            steeringTarget = 0.0;

                            context.getSource()
                                .sendSuccess(
                                    () ->
                                        Component.literal(
                                            "Drive: OFF | Brake: ON"
                                        ),
                                    false
                                );

                            return Command.SINGLE_SUCCESS;
                        })
                )

                // ------------------------------------------------
                // /gta service
                //
                // Marks the selected MTS vehicle as a GTACore
                // service vehicle and keeps its fuel tank full.
                // ------------------------------------------------
                .then(
                    Commands.literal("service")
                        .executes(context -> {

                            if (selectedCar == null) {

                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Select a car first with /gta carselect"
                                        )
                                    );

                                return 0;
                            }

                            try {

                                Object vehicle =
                                    getSelectedVehicle(
                                        context
                                            .getSource()
                                            .getServer()
                                    );

                                if (vehicle == null) {

                                    context.getSource()
                                        .sendFailure(
                                            Component.literal(
                                                "Selected MTS car is not loaded."
                                            )
                                        );

                                    return 0;
                                }

                                /*
                                 * We intentionally require the vehicle
                                 * to already contain fuel the first time.
                                 *
                                 * This lets GTACore learn the correct
                                 * MTS fuel type for that particular car.
                                 */
                                refillServiceFuel(vehicle);

                                serviceVehicles.add(
                                    selectedCar
                                );

                                context.getSource()
                                    .sendSuccess(
                                        () ->
                                            Component.literal(
                                                "Service vehicle enabled. Fuel will remain full."
                                            ),
                                        false
                                    );

                            } catch (Exception e) {

                                e.printStackTrace();

                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Could not enable service vehicle. Make sure the car currently has fuel."
                                        )
                                    );

                                return 0;
                            }

                            return Command.SINGLE_SUCCESS;
                        })
                )

                // ------------------------------------------------
                // /gta status
                // ------------------------------------------------
                .then(
                    Commands.literal("status")
                        .executes(context -> {

                            if (selectedCar == null) {

                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "No selected car."
                                        )
                                    );

                                return 0;
                            }

                            try {

                                Object vehicle =
                                    getSelectedVehicle(
                                        context
                                            .getSource()
                                            .getServer()
                                    );

                                if (vehicle == null) {

                                    context.getSource()
                                        .sendFailure(
                                            Component.literal(
                                                "Selected car is not loaded."
                                            )
                                        );

                                    return 0;
                                }

                                sendStatus(
                                    context.getSource(),
                                    vehicle
                                );

                            } catch (Exception e) {

                                e.printStackTrace();

                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Could not read MTS status."
                                        )
                                    );

                                return 0;
                            }

                            return Command.SINGLE_SUCCESS;
                        })
                )
        );
    }

    // ============================================================
    // AI DRIVING LOOP
    // ============================================================

    @SubscribeEvent
    public void serverTick(
        TickEvent.ServerTickEvent event
    ) {

        if (
            event.phase !=
            TickEvent.Phase.END
        ) {
            return;
        }

        /*
         * MTS runs at 20 ticks/second.
         *
         * Service vehicles do not need their fuel checked every
         * single tick, so we maintain them once per second.
         */
        serviceFuelTickCounter++;

        if (serviceFuelTickCounter >= 20) {

            serviceFuelTickCounter = 0;

            maintainServiceVehicles(
                event.getServer()
            );
        }

        if (selectedCar == null) {
            return;
        }

        try {

            Object vehicle =
                getSelectedVehicle(
                    event.getServer()
                );

            Entity wrapper =
                getSelectedWrapper(
                    event.getServer()
                );

            if (
                vehicle == null ||
                wrapper == null
            ) {
                return;
            }

            if (
                homeSet &&
                !returningHome
            ) {

                homeTrailTickCounter++;

                if (
                    homeTrailTickCounter >= 10
                ) {
                    homeTrailTickCounter = 0;

                    String currentDimension =
                        wrapper.level()
                            .dimension()
                            .location()
                            .toString();

                    if (
                        homeDimension.equals(
                            currentDimension
                        )
                    ) {
                        recordHomeBreadcrumb(
                            wrapper,
                            false
                        );
                    }
                }
            }

            if (followTargetId != null) {

                updateFollowNavigation(
                    event.getServer(),
                    vehicle,
                    wrapper
                );

            } else if (returningHome) {

                updateHomeNavigation(
                    vehicle,
                    wrapper
                );
            }

            /*
             * Any driving action automatically requests engine
             * startup if the car is currently off.
             */
            if (
                driveForward ||
                driveReverse
            ) {
                ensureVehicleStarted(
                    vehicle
                );
            }

            updateSteering(vehicle);

            if (driveForward) {

                /*
                 * W behavior:
                 *
                 * 1. Release parking brake.
                 * 2. Enable Simple Throttle behavior.
                 * 3. Get transmission into forward gear.
                 * 4. Release normal brake.
                 * 5. Apply forward power.
                 */

                setMTSVariable(
                    vehicle,
                    "parkingBrakeVar",
                    0.0
                );

                boolean forwardGearReady =
                    ensureForwardGear(
                        vehicle
                    );

                if (forwardGearReady) {

                    // Equivalent to brake pedal lifting.
                    setMTSVariable(
                        vehicle,
                        "brakeVar",
                        0.0
                    );

                    // Equivalent to W/gas pedal being down.
                    setMTSVariable(
                        vehicle,
                        "throttleVar",
                        throttleCommand
                    );

                } else {

                    /*
                     * Hold the car still while the
                     * transmission moves toward 1st.
                     */

                    setMTSVariable(
                        vehicle,
                        "throttleVar",
                        0.0
                    );

                    setMTSVariable(
                        vehicle,
                        "brakeVar",
                        1.0
                    );
                }

            } else if (driveReverse) {

                setMTSVariable(
                    vehicle,
                    "parkingBrakeVar",
                    0.0
                );

                boolean reverseGearReady =
                    ensureReverseGear(
                        vehicle
                    );

                if (reverseGearReady) {

                    setMTSVariable(
                        vehicle,
                        "brakeVar",
                        0.0
                    );

                    setMTSVariable(
                        vehicle,
                        "throttleVar",
                        throttleCommand
                    );

                } else {

                    setMTSVariable(
                        vehicle,
                        "throttleVar",
                        0.0
                    );

                    setMTSVariable(
                        vehicle,
                        "brakeVar",
                        1.0
                    );
                }

            } else {

                /*
                 * Same behavior you observed while
                 * sitting still in Simple Throttle:
                 *
                 * no W = throttle released +
                 * normal brake applied.
                 */

                setMTSVariable(
                    vehicle,
                    "throttleVar",
                    0.0
                );

                setMTSVariable(
                    vehicle,
                    "brakeVar",
                    1.0
                );
            }

        } catch (Exception e) {

            driveForward = false;
            driveReverse = false;
            returningHome = false;
            followTargetId = null;

            System.err.println(
                "[GTACore] Vehicle control failed:"
            );

            e.printStackTrace();
        }
    }

    // ============================================================
    // FOLLOW NAVIGATION
    // ============================================================

    private static void updateFollowNavigation(
        MinecraftServer server,
        Object vehicle,
        Entity wrapper
    ) throws Exception {

        ServerPlayer target =
            server.getPlayerList()
                .getPlayer(
                    followTargetId
                );

        if (target == null) {

            followTargetId = null;
            followTurnPhase =
                FOLLOW_TURN_FOLLOW;
            followTurnTicks = 0;
            followTurnCooldownTicks = 0;

            driveForward = false;
            driveReverse = false;
            steeringTarget = 0.0;

            return;
        }

        if (
            target.level().dimension() !=
            wrapper.level().dimension()
        ) {

            driveForward = false;
            driveReverse = false;
            steeringTarget = 0.0;

            return;
        }

        double dx =
            target.getX() -
            wrapper.getX();

        double dz =
            target.getZ() -
            wrapper.getZ();

        double distance =
            Math.sqrt(
                dx * dx +
                dz * dz
            );

        /*
         * Maintain a gap rather than trying to occupy the player's
         * exact block.
         */
        if (
            distance <=
                FOLLOW_STOP_DISTANCE
        ) {

            followTurnPhase =
                FOLLOW_TURN_FOLLOW;
            followTurnTicks = 0;

            driveForward = false;
            driveReverse = false;
            throttleCommand = 1.0;
            steeringTarget = 0.0;

            return;
        }

        double headingError =
            getHeadingErrorToTarget(
                vehicle,
                dx,
                dz
            );

        double absoluteError =
            Math.abs(
                headingError
            );

        if (followTurnCooldownTicks > 0) {
            followTurnCooldownTicks--;
        }

        if (
            followTurnPhase ==
                FOLLOW_TURN_FOLLOW &&
            followTurnCooldownTicks == 0 &&
            absoluteError >=
                FOLLOW_TURN_START_ANGLE
        ) {

            followTurnPhase =
                FOLLOW_TURN_REVERSE;

            followTurnTicks = 0;

            followTurnDirection =
                Math.abs(headingError) > 175.0
                    ? 1.0
                    : Math.copySign(
                        1.0,
                        headingError
                    );
        }

        if (
            followTurnPhase ==
                FOLLOW_TURN_REVERSE
        ) {

            followTurnTicks++;

            steeringTarget =
                -followTurnDirection *
                FOLLOW_TURN_STEERING;

            throttleCommand = 0.10;
            driveForward = false;
            driveReverse = true;

            /*
             * Reverse only long enough to create room for the U-turn.
             * Never use reverse as the actual way of reaching the
             * player.
             */
            if (
                followTurnTicks >=
                    FOLLOW_REVERSE_MAX_TICKS ||
                absoluteError <=
                    FOLLOW_REVERSE_TO_FORWARD_ANGLE
            ) {

                followTurnPhase =
                    FOLLOW_TURN_FORWARD;

                followTurnTicks = 0;
            }

            return;
        }

        if (
            followTurnPhase ==
                FOLLOW_TURN_FORWARD
        ) {

            followTurnTicks++;

            boolean crossedTargetHeading =
                Math.signum(
                    headingError
                ) !=
                Math.signum(
                    followTurnDirection
                );

            if (
                absoluteError <=
                    FOLLOW_TURN_FINISH_ANGLE ||
                crossedTargetHeading ||
                followTurnTicks >=
                    FOLLOW_FORWARD_TURN_MAX_TICKS
            ) {

                followTurnPhase =
                    FOLLOW_TURN_FOLLOW;

                followTurnTicks = 0;
                followTurnCooldownTicks =
                    FOLLOW_TURN_COOLDOWN_TICKS;

                steeringTarget =
                    clamp(
                        headingError *
                            FOLLOW_STEERING_GAIN,
                        -FOLLOW_TURN_STEERING,
                        FOLLOW_TURN_STEERING
                    );

                throttleCommand = 0.10;
                driveReverse = false;
                driveForward = true;

                return;
            }

            steeringTarget =
                followTurnDirection *
                FOLLOW_TURN_STEERING;

            throttleCommand = 0.11;
            driveReverse = false;
            driveForward = true;

            return;
        }

        steeringTarget =
            clamp(
                headingError *
                    FOLLOW_STEERING_GAIN,
                -FOLLOW_TURN_STEERING,
                FOLLOW_TURN_STEERING
            );

        /*
         * Slow down when correcting a large angle and when getting
         * close to the player.  This matters a lot on dirt where a
         * full-lock turn at speed causes the MTS car to slide.
         */
        if (absoluteError > 55.0) {

            throttleCommand = 0.11;

        } else if (
            absoluteError > 30.0
        ) {

            throttleCommand = 0.17;

        } else if (
            distance < 14.0
        ) {

            throttleCommand = 0.16;

        } else {

            throttleCommand = 0.30;
        }

        if (
            distance < FOLLOW_RESUME_DISTANCE
        ) {
            throttleCommand =
                Math.min(
                    throttleCommand,
                    0.13
                );
        }

        driveReverse = false;
        driveForward = true;
    }

    // ============================================================
    // HOME NAVIGATION
    // ============================================================

    private static void recordHomeBreadcrumb(
        Entity wrapper,
        boolean force
    ) {

        double x = wrapper.getX();
        double y = wrapper.getY();
        double z = wrapper.getZ();

        if (homeTrail.isEmpty()) {

            homeTrail.add(
                new HomeWaypoint(
                    x,
                    y,
                    z
                )
            );

            return;
        }

        HomeWaypoint last =
            homeTrail.get(
                homeTrail.size() - 1
            );

        double dx =
            x - last.x;

        double dz =
            z - last.z;

        double planarDistance =
            Math.sqrt(
                dx * dx +
                dz * dz
            );

        if (
            force ||
            planarDistance >=
                HOME_WAYPOINT_SPACING
        ) {

            homeTrail.add(
                new HomeWaypoint(
                    x,
                    y,
                    z
                )
            );
        }
    }

    private static void updateHomeNavigation(
        Object vehicle,
        Entity wrapper
    ) throws Exception {

        if (homeTrail.isEmpty()) {
            returningHome = false;
            driveForward = false;
            return;
        }

        if (homeRouteIndex < 0) {
            homeRouteIndex = 0;
        }

        HomeWaypoint target =
            homeTrail.get(
                Math.min(
                    homeRouteIndex,
                    homeTrail.size() - 1
                )
            );

        double dx =
            target.x -
            wrapper.getX();

        double dz =
            target.z -
            wrapper.getZ();

        double distance =
            Math.sqrt(
                dx * dx +
                dz * dz
            );

        /*
         * When we reach a breadcrumb, move to the previous one.
         * Index 0 is the actual home position.
         */
        while (
            homeRouteIndex > 0 &&
            distance <=
                HOME_WAYPOINT_REACHED
        ) {

            homeRouteIndex--;

            target =
                homeTrail.get(
                    homeRouteIndex
                );

            dx =
                target.x -
                wrapper.getX();

            dz =
                target.z -
                wrapper.getZ();

            distance =
                Math.sqrt(
                    dx * dx +
                    dz * dz
                );
        }

        if (
            homeRouteIndex == 0 &&
            distance <=
                HOME_STOP_DISTANCE
        ) {

            returningHome = false;
            homeTurnPhase = HOME_TURN_FOLLOW;
            homeTurnDirection = 1.0;
            homeTurnTicks = 0;
            homeTurnCooldownTicks = 0;
            driveForward = false;
            driveReverse = false;
            throttleCommand = 1.0;
            steeringTarget = 0.0;
            homeRouteIndex = -1;

            /*
             * We are back at the station.  Start a fresh outbound
             * trail the next time this car leaves.
             */
            homeTrail.clear();
            homeTrail.add(
                new HomeWaypoint(
                    homeX,
                    homeY,
                    homeZ
                )
            );

            System.out.println(
                "[GTACore] Vehicle arrived home."
            );

            return;
        }

        /*
         * Pure-pursuit style look-ahead:
         * do not aim at every breadcrumb individually.  Pick a point
         * farther down the recorded route so the car chooses one
         * smooth arc instead of zig-zagging point-to-point.
         */
        HomeWaypoint steeringTargetPoint =
            chooseHomeLookahead(
                wrapper
            );

        double steeringDx =
            steeringTargetPoint.x -
            wrapper.getX();

        double steeringDz =
            steeringTargetPoint.z -
            wrapper.getZ();

        double headingError =
            getHeadingErrorToTarget(
                vehicle,
                steeringDx,
                steeringDz
            );

        double absoluteError =
            Math.abs(
                headingError
            );

        /*
         * Begin ONE deliberate turnaround if the return route starts
         * mostly behind the vehicle.
         */
        if (homeTurnCooldownTicks > 0) {
            homeTurnCooldownTicks--;
        }

        if (
            homeTurnPhase ==
                HOME_TURN_FOLLOW &&
            homeTurnCooldownTicks == 0 &&
            absoluteError >=
                HOME_TURN_START_ANGLE
        ) {

            homeTurnPhase =
                HOME_TURN_REVERSE;

            homeTurnTicks = 0;

            /*
             * At almost exactly 180 degrees, left/right is
             * mathematically ambiguous.  Pick right consistently.
             */
            homeTurnDirection =
                Math.abs(headingError) > 175.0
                    ? 1.0
                    : Math.copySign(
                        1.0,
                        headingError
                    );
        }

        if (
            homeTurnPhase ==
                HOME_TURN_REVERSE
        ) {

            homeTurnTicks++;

            steeringTarget =
                -homeTurnDirection *
                HOME_TURN_STEERING;

            throttleCommand = 0.10;
            driveForward = false;
            driveReverse = true;

            if (
                homeTurnTicks >=
                    HOME_REVERSE_MAX_TICKS ||
                absoluteError <=
                    HOME_REVERSE_TO_FORWARD_ANGLE
            ) {

                homeTurnPhase =
                    HOME_TURN_FORWARD;

                homeTurnTicks = 0;
            }

            return;
        }

        if (
            homeTurnPhase ==
                HOME_TURN_FORWARD
        ) {

            homeTurnTicks++;

            /*
             * Complete the turn going forward, but DO NOT keep
             * holding the original steering direction after the
             * nose crosses the desired path.
             *
             * On low-grip surfaces the old version would drift past
             * the target heading, keep steering right, and perform a
             * full 360-degree circle.  As soon as the heading error
             * changes sign, hand control back to the proportional
             * route follower so it can counter-steer.
             */
            boolean crossedTargetHeading =
                Math.signum(
                    headingError
                ) !=
                Math.signum(
                    homeTurnDirection
                );

            if (
                absoluteError <=
                    HOME_TURN_FINISH_ANGLE ||
                crossedTargetHeading ||
                homeTurnTicks >=
                    HOME_FORWARD_TURN_MAX_TICKS
            ) {

                homeTurnPhase =
                    HOME_TURN_FOLLOW;

                homeTurnTicks = 0;
                homeTurnCooldownTicks =
                    HOME_TURN_COOLDOWN_TICKS;

                steeringTarget =
                    clamp(
                        headingError *
                            HOME_STEERING_GAIN,
                        -HOME_TURN_STEERING,
                        HOME_TURN_STEERING
                    );

                throttleCommand = 0.10;
                driveReverse = false;
                driveForward = true;

                return;
            }

            steeringTarget =
                homeTurnDirection *
                HOME_TURN_STEERING;

            throttleCommand = 0.11;
            driveReverse = false;
            driveForward = true;

            return;
        }

        /*
         * Normal route following after the nose is aligned.
         */
        steeringTarget =
            clamp(
                headingError *
                    HOME_STEERING_GAIN,
                -HOME_TURN_STEERING,
                HOME_TURN_STEERING
            );

        if (absoluteError > 65.0) {

            throttleCommand = 0.12;

        } else if (
            absoluteError > 35.0
        ) {

            throttleCommand = 0.18;

        } else if (
            absoluteError > 18.0
        ) {

            throttleCommand = 0.24;

        } else {

            throttleCommand = 0.32;
        }

        if (
            homeRouteIndex == 0 &&
            distance < 10.0
        ) {

            throttleCommand =
                Math.min(
                    throttleCommand,
                    0.16
                );
        }

        driveReverse = false;
        driveForward = true;
    }

    private static HomeWaypoint chooseHomeLookahead(
        Entity wrapper
    ) {

        int index =
            Math.max(
                0,
                Math.min(
                    homeRouteIndex,
                    homeTrail.size() - 1
                )
            );

        HomeWaypoint chosen =
            homeTrail.get(index);

        /*
         * Walk farther toward home until we have roughly the desired
         * look-ahead distance.  This is the "path choice" layer that
         * was missing before: steering is based on the route shape,
         * not only the single breadcrumb directly behind the car.
         */
        for (
            int candidateIndex =
                index - 1;
            candidateIndex >= 0;
            candidateIndex--
        ) {

            HomeWaypoint candidate =
                homeTrail.get(
                    candidateIndex
                );

            double dx =
                candidate.x -
                wrapper.getX();

            double dz =
                candidate.z -
                wrapper.getZ();

            chosen = candidate;

            if (
                Math.sqrt(
                    dx * dx +
                    dz * dz
                ) >=
                    HOME_LOOKAHEAD_DISTANCE
            ) {

                break;
            }
        }

        return chosen;
    }

    private static double getHeadingErrorToTarget(
        Object vehicle,
        double dx,
        double dz
    ) throws Exception {

        double targetLength =
            Math.sqrt(
                dx * dx +
                dz * dz
            );

        if (targetLength < 0.001) {
            return 0.0;
        }

        double targetX =
            dx / targetLength;

        double targetZ =
            dz / targetLength;

        Object orientation =
            getFieldValue(
                vehicle,
                "orientation"
            );

        /*
         * MTS uses local +Z as the vehicle's forward vector.
         * For a rotation matrix, the transformed +Z vector is
         * matrix column 2: (m02, m12, m22).
         *
         * Reading this vector directly avoids relying on Euler yaw
         * conversion and its sign/convention edge cases.
         */
        double forwardX =
            getDoubleField(
                orientation,
                "m02"
            );

        double forwardZ =
            getDoubleField(
                orientation,
                "m22"
            );

        double forwardLength =
            Math.sqrt(
                forwardX * forwardX +
                forwardZ * forwardZ
            );

        if (forwardLength < 0.001) {
            return 0.0;
        }

        forwardX /= forwardLength;
        forwardZ /= forwardLength;

        double dot =
            clamp(
                forwardX * targetX +
                forwardZ * targetZ,
                -1.0,
                1.0
            );

        /*
         * Positive angle = target is to the vehicle's right.
         * That matches our tested positive MTS steering input.
         */
        double cross =
            forwardZ * targetX -
            forwardX * targetZ;

        return Math.toDegrees(
            Math.atan2(
                cross,
                dot
            )
        );
    }

    private static double getDoubleField(
        Object owner,
        String fieldName
    ) throws Exception {

        Field field =
            findField(
                owner.getClass(),
                fieldName
            );

        if (field == null) {
            throw new NoSuchFieldException(
                fieldName
            );
        }

        field.setAccessible(true);

        return field.getDouble(
            owner
        );
    }

    private static double clamp(
        double value,
        double minimum,
        double maximum
    ) {

        return Math.max(
            minimum,
            Math.min(
                maximum,
                value
            )
        );
    }

    // ============================================================
    // STEERING
    // ============================================================

    private static void updateSteering(
        Object vehicle
    ) throws Exception {

        double difference =
            steeringTarget -
            steeringCurrent;

        if (
            Math.abs(difference) <=
            STEERING_STEP_PER_TICK
        ) {

            steeringCurrent =
                steeringTarget;

        } else {

            steeringCurrent +=
                Math.copySign(
                    STEERING_STEP_PER_TICK,
                    difference
                );
        }

        /*
         * This is the same MTS variable used by normal
         * ground-vehicle steering controls.
         */
        setMTSVariable(
            vehicle,
            "rudderInputVar",
            steeringCurrent
        );
    }

    // ============================================================
    // SERVICE VEHICLES
    // ============================================================

    private static void maintainServiceVehicles(
        MinecraftServer server
    ) {

        /*
         * Copy the set before iterating it so future dispatch code
         * can safely add/remove vehicles without causing a
         * ConcurrentModificationException.
         */
        for (
            UUID vehicleId :
            new HashSet<>(serviceVehicles)
        ) {

            try {

                Object vehicle =
                    getVehicleByUUID(
                        server,
                        vehicleId
                    );

                if (vehicle == null) {
                    continue;
                }

                refillServiceFuel(vehicle);

            } catch (Exception e) {

                System.err.println(
                    "[GTACore] Could not maintain service vehicle "
                        + vehicleId
                );

                e.printStackTrace();
            }
        }
    }

    private static void refillServiceFuel(
        Object vehicle
    ) throws Exception {

        Object fuelTank =
            getFieldValue(
                vehicle,
                "fuelTank"
            );

        if (fuelTank == null) {

            throw new IllegalStateException(
                "MTS vehicle has no fuelTank."
            );
        }

        Method getFluidLevel =
            fuelTank
                .getClass()
                .getMethod(
                    "getFluidLevel"
                );

        Method getMaxLevel =
            fuelTank
                .getClass()
                .getMethod(
                    "getMaxLevel"
                );

        Method getFluid =
            fuelTank
                .getClass()
                .getMethod(
                    "getFluid"
                );

        Method getFluidMod =
            fuelTank
                .getClass()
                .getMethod(
                    "getFluidMod"
                );

        double currentLevel =
            ((Number)
                getFluidLevel.invoke(
                    fuelTank
                )
            ).doubleValue();

        double maxLevel =
            ((Number)
                getMaxLevel.invoke(
                    fuelTank
                )
            ).doubleValue();

        String fluid =
            (String)
                getFluid.invoke(
                    fuelTank
                );

        String fluidMod =
            (String)
                getFluidMod.invoke(
                    fuelTank
                );

        /*
         * The first version of this system learns the fuel type
         * from an already-fueled vehicle.
         *
         * Once we add the police vehicle template factory,
         * spawned cruisers will already contain their correct fuel.
         */
        if (
            fluid == null ||
            fluid.isEmpty()
        ) {

            throw new IllegalStateException(
                "Fuel tank is empty, so GTACore cannot determine the correct fuel type."
            );
        }

        double missingFuel =
            maxLevel - currentLevel;

        if (missingFuel <= 0.001) {
            return;
        }

        Method fill =
            fuelTank
                .getClass()
                .getMethod(
                    "fill",
                    String.class,
                    String.class,
                    double.class,
                    boolean.class
                );

        fill.invoke(
            fuelTank,
            fluid,
            fluidMod,
            missingFuel,
            true
        );
    }

    private static Object getVehicleByUUID(
        MinecraftServer server,
        UUID vehicleId
    ) throws Exception {

        for (
            ServerLevel level :
            server.getAllLevels()
        ) {

            Entity wrapper =
                level.getEntity(
                    vehicleId
                );

            if (wrapper != null) {

                return getInternalMTSEntity(
                    wrapper
                );
            }
        }

        return null;
    }

    private static void requestAutoStart(
        MinecraftServer server
    ) {

        try {

            Object vehicle =
                getSelectedVehicle(
                    server
                );

            if (vehicle != null) {
                ensureVehicleStarted(
                    vehicle
                );
            }

        } catch (Exception e) {

            System.err.println(
                "[GTACore] Automatic vehicle startup failed:"
            );

            e.printStackTrace();
        }
    }

    private static void ensureVehicleStarted(
        Object vehicle
    ) throws Exception {

        boolean running =
            getBooleanField(
                vehicle,
                "enginesRunning"
            );

        boolean starting =
            getBooleanField(
                vehicle,
                "enginesStarting"
            );

        if (
            running ||
            starting
        ) {
            return;
        }

        setMTSVariable(
            vehicle,
            "throttleVar",
            0.0
        );

        setMTSVariable(
            vehicle,
            "brakeVar",
            1.0
        );

        setMTSVariable(
            vehicle,
            "parkingBrakeVar",
            0.0
        );

        List<?> engines =
            getEngines(vehicle);

        if (engines.isEmpty()) {
            throw new IllegalStateException(
                "This MTS vehicle has no engines."
            );
        }

        for (Object engine : engines) {

            invokeNoArg(
                engine,
                "shiftNeutral"
            );

            invokeNoArg(
                engine,
                "autoStartEngine"
            );
        }

        System.out.println(
            "[GTACore] Automatic engine start requested."
        );
    }

    // ============================================================
    // STARTUP
    // ============================================================

    private static void startVehicle(
        Object vehicle
    ) throws Exception {

        /*
         * MTS player auto-start normally does roughly:
         *
         * transmission -> neutral
         * engine -> autoStartEngine()
         * parking brake -> released
         *
         * We reproduce that here.
         */

        driveForward = false;
        driveReverse = false;

        setMTSVariable(
            vehicle,
            "throttleVar",
            0.0
        );

        setMTSVariable(
            vehicle,
            "brakeVar",
            1.0
        );

        setMTSVariable(
            vehicle,
            "parkingBrakeVar",
            0.0
        );

        List<?> engines =
            getEngines(vehicle);

        if (engines.isEmpty()) {
            throw new IllegalStateException(
                "This MTS vehicle has no engines."
            );
        }

        for (Object engine : engines) {

            /*
             * This is MTS's actual public transmission
             * method, rather than faking the gear value.
             */
            invokeNoArg(
                engine,
                "shiftNeutral"
            );

            /*
             * This is MTS's actual auto-starter.
             *
             * It handles magneto/starter/fuel checks.
             */
            invokeNoArg(
                engine,
                "autoStartEngine"
            );
        }

        System.out.println(
            "[GTACore] MTS startup sequence requested."
        );
    }

    // ============================================================
    // TRANSMISSION
    // ============================================================

    private static boolean ensureForwardGear(
        Object vehicle
    ) throws Exception {

        List<?> engines =
            getEngines(vehicle);

        if (engines.isEmpty()) {
            return false;
        }

        boolean allForward =
            true;

        for (Object engine : engines) {

            double gear =
                getMTSVariableValue(
                    engine,
                    "currentGearVar"
                );

            if (gear <= 0.0) {

                allForward =
                    false;

                /*
                 * MTS shiftUp():
                 *
                 * reverse -> neutral
                 * neutral -> first
                 */
                invokeNoArg(
                    engine,
                    "shiftUp"
                );
            }
        }

        return allForward;
    }

    private static boolean ensureReverseGear(
        Object vehicle
    ) throws Exception {

        List<?> engines =
            getEngines(vehicle);

        if (engines.isEmpty()) {
            return false;
        }

        boolean allReverse =
            true;

        for (Object engine : engines) {

            double gear =
                getMTSVariableValue(
                    engine,
                    "currentGearVar"
                );

            if (gear > 0.0) {

                /*
                 * Going directly from a forward gear into reverse
                 * is undesirable.  Put the transmission in neutral
                 * first, then the next tick can select reverse.
                 */
                allReverse = false;

                invokeNoArg(
                    engine,
                    "shiftNeutral"
                );

            } else if (gear == 0.0) {

                allReverse = false;

                invokeNoArg(
                    engine,
                    "shiftDown"
                );
            }
        }

        return allReverse;
    }

    // ============================================================
    // STATUS
    // ============================================================

    private static void sendStatus(
        net.minecraft.commands.CommandSourceStack source,
        Object vehicle
    ) throws Exception {

        double throttle =
            getMTSVariableValue(
                vehicle,
                "throttleVar"
            );

        double brake =
            getMTSVariableValue(
                vehicle,
                "brakeVar"
            );

        double parkingBrake =
            getMTSVariableValue(
                vehicle,
                "parkingBrakeVar"
            );

        double steering =
            getMTSVariableValue(
                vehicle,
                "rudderInputVar"
            );

        boolean enginesRunning =
            getBooleanField(
                vehicle,
                "enginesRunning"
            );

        boolean enginesStarting =
            getBooleanField(
                vehicle,
                "enginesStarting"
            );

        List<?> engines =
            getEngines(vehicle);

        String gearText = "none";

        if (!engines.isEmpty()) {

            double gear =
                getMTSVariableValue(
                    engines.get(0),
                    "currentGearVar"
                );

            gearText =
                String.valueOf(
                    (int) gear
                );
        }

        String finalGearText =
            gearText;

        source.sendSuccess(
            () -> Component.literal(
                "----- GTACore Car Status -----"
            ),
            false
        );

        source.sendSuccess(
            () -> Component.literal(
                "Engine running: "
                    + enginesRunning
            ),
            false
        );

        source.sendSuccess(
            () -> Component.literal(
                "Engine starting: "
                    + enginesStarting
            ),
            false
        );

        source.sendSuccess(
            () -> Component.literal(
                "Gear: "
                    + finalGearText
            ),
            false
        );

        source.sendSuccess(
            () -> Component.literal(
                "Forward/W: "
                    + driveForward
                    + " | Reverse: "
                    + driveReverse
            ),
            false
        );

        source.sendSuccess(
            () -> Component.literal(
                "Returning home: "
                    + returningHome
                    + " | turn phase: "
                    + homeTurnPhase
            ),
            false
        );

        source.sendSuccess(
            () -> Component.literal(
                "Following: "
                    + (followTargetId != null)
                    + " | follow turn phase: "
                    + followTurnPhase
                    + " | cooldown: "
                    + followTurnCooldownTicks
            ),
            false
        );

        if (homeSet) {
            source.sendSuccess(
                () -> Component.literal(
                    String.format(
                        "Home: %.1f, %.1f, %.1f",
                        homeX,
                        homeY,
                        homeZ
                    )
                ),
                false
            );

            source.sendSuccess(
                () -> Component.literal(
                    "Home trail points: "
                        + homeTrail.size()
                        + " | route index: "
                        + homeRouteIndex
                ),
                false
            );
        }

        source.sendSuccess(
            () -> Component.literal(
                "Throttle request: "
                    + throttle
            ),
            false
        );

        source.sendSuccess(
            () -> Component.literal(
                "Brake: "
                    + brake
            ),
            false
        );

        source.sendSuccess(
            () -> Component.literal(
                "Parking brake: "
                    + parkingBrake
            ),
            false
        );

        source.sendSuccess(
            () -> Component.literal(
                "Steering: "
                    + steering
                    + " / target "
                    + steeringTarget
            ),
            false
        );
    }

    // ============================================================
    // MTS REFLECTION BRIDGE
    // ============================================================

    private static Entity getSelectedWrapper(
        MinecraftServer server
    ) {

        if (selectedCar == null) {
            return null;
        }

        for (
            ServerLevel level :
            server.getAllLevels()
        ) {

            Entity wrapper =
                level.getEntity(
                    selectedCar
                );

            if (wrapper != null) {
                return wrapper;
            }
        }

        return null;
    }

    private static Object getSelectedVehicle(
        MinecraftServer server
    ) throws Exception {

        if (selectedCar == null) {
            return null;
        }

        for (
            ServerLevel level :
            server.getAllLevels()
        ) {

            Entity wrapper =
                level.getEntity(
                    selectedCar
                );

            if (wrapper != null) {

                return getInternalMTSEntity(
                    wrapper
                );
            }
        }

        return null;
    }

    private static Object getInternalMTSEntity(
        Entity wrapper
    ) throws Exception {

        Field field =
            findField(
                wrapper.getClass(),
                "entity"
            );

        if (field == null) {

            throw new NoSuchFieldException(
                "Could not find MTS wrapper field: entity"
            );
        }

        field.setAccessible(true);

        return field.get(wrapper);
    }

    private static List<?> getEngines(
        Object vehicle
    ) throws Exception {

        Field field =
            findField(
                vehicle.getClass(),
                "engines"
            );

        if (field == null) {

            throw new NoSuchFieldException(
                "Could not find MTS engines list."
            );
        }

        field.setAccessible(true);

        Object value =
            field.get(vehicle);

        if (value instanceof List<?>) {
            return (List<?>) value;
        }

        return Collections.emptyList();
    }

    private static void setMTSVariable(
        Object owner,
        String fieldName,
        double value
    ) throws Exception {

        Object variable =
            getFieldValue(
                owner,
                fieldName
            );

        if (variable == null) {

            throw new IllegalStateException(
                "MTS variable is null: "
                    + fieldName
            );
        }

        Method method =
            variable
                .getClass()
                .getMethod(
                    "setTo",
                    double.class,
                    boolean.class
                );

        /*
         * true = tell MTS to sync this change
         * to clients.
         */
        method.invoke(
            variable,
            value,
            true
        );
    }

    private static double getMTSVariableValue(
        Object owner,
        String fieldName
    ) throws Exception {

        Object variable =
            getFieldValue(
                owner,
                fieldName
            );

        Field valueField =
            findField(
                variable.getClass(),
                "currentValue"
            );

        if (valueField == null) {

            throw new NoSuchFieldException(
                "currentValue"
            );
        }

        valueField.setAccessible(true);

        return valueField
            .getDouble(variable);
    }

    private static boolean getBooleanField(
        Object owner,
        String fieldName
    ) throws Exception {

        Field field =
            findField(
                owner.getClass(),
                fieldName
            );

        if (field == null) {

            throw new NoSuchFieldException(
                fieldName
            );
        }

        field.setAccessible(true);

        return field.getBoolean(owner);
    }

    private static Object getFieldValue(
        Object owner,
        String fieldName
    ) throws Exception {

        Field field =
            findField(
                owner.getClass(),
                fieldName
            );

        if (field == null) {

            throw new NoSuchFieldException(
                fieldName
            );
        }

        field.setAccessible(true);

        return field.get(owner);
    }

    private static void invokeNoArg(
        Object owner,
        String methodName
    ) throws Exception {

        Method method =
            owner
                .getClass()
                .getMethod(
                    methodName
                );

        method.invoke(owner);
    }

    private static Field findField(
        Class<?> clazz,
        String fieldName
    ) {

        Class<?> current =
            clazz;

        while (current != null) {

            try {

                return current
                    .getDeclaredField(
                        fieldName
                    );

            } catch (
                NoSuchFieldException ignored
            ) {

                current =
                    current.getSuperclass();
            }
        }

        return null;
    }
}
