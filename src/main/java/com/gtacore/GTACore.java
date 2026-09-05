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
import java.util.Map;
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
    // FOLLOW SYSTEM - DIGITAL STEERING BASELINE
    // ============================================================

    private static UUID followTargetId = null;

    /*
     * WANTED SYSTEM FOUNDATION
     *
     * Level 0 = not wanted.
     * Level 1 = selected police unit pursues the player.
     * Later this can expand into GTA-style wanted levels, dispatch,
     * multiple units, search radius, roadblocks, helicopters, etc.
     */
    private static int wantedLevel = 0;
    private static UUID wantedTargetId = null;

    /*
     * FOLLOW_DIGITAL_STEER
     *
     * Important MTS behavior learned from testing:
     * steering is treated like a digital LEFT/RIGHT control.
     *
     * - full left  = -45
     * - straight   = 0
     * - full right = +45
     *
     * Smaller turns are NOT made by asking for "12 degrees".
     * They are made by pulsing full steering for short periods and
     * releasing back to center between pulses.
     */
    private static final int FOLLOW_STRAIGHT = 0;
    private static final int FOLLOW_TURNING = 1;

    private static int followBaselineMode =
        FOLLOW_TURNING;

    private static double followTurnDirection = 0.0;
    private static double followHeadingError = 0.0;
    private static int followMisalignmentTicks = 0;
    private static int followSteerPulseTick = 0;
    private static boolean followDigitalSteeringActive = false;

    private static final double FOLLOW_STOP_DISTANCE = 7.0;

    // Good enough; once inside this band, release the wheel.
    private static final double FOLLOW_ALIGN_DONE_DEGREES = 12.0;

    // Straight mode ignores normal movement until a real turn is needed.
    private static final double FOLLOW_REALIGN_START_DEGREES = 24.0;
    private static final int FOLLOW_REALIGN_CONFIRM_TICKS = 5;

    // MTS tested steering endpoints.
    private static final double FOLLOW_FULL_STEER = 45.0;

    /*
     * FOLLOW_FEEDBACK_TAP_CYCLE
     *
     * We do NOT guess a total turning duration.
     *
     * Every tick we measure the car's real heading relative to the
     * target.  While it is not aligned, the driver repeats:
     *
     *   1 tick full LEFT/RIGHT
     *   1 tick wheel released
     *   measure heading again
     *
     * The heading measurement decides when turning ends.
     * Timing only controls how aggressively we rotate.
     */
    private static final int FOLLOW_TAP_ON_TICKS = 1;
    private static final int FOLLOW_TAP_OFF_TICKS = 1;

    /*
     * HARD TURN MODE
     *
     * Normal pursuit is already working, so this mode is deliberately
     * isolated and hard to trigger.
     *
     * It only activates when:
     * - the target is far outside the normal forward arc,
     * - that condition persists for several ticks,
     * - and the car is actually moving fast.
     *
     * Once active, steering is HELD instead of tapped and the car
     * brakes hard before powering through the rotation.
     */
    private static final double FOLLOW_HARD_TURN_START_DEGREES = 85.0;
    private static final double FOLLOW_HARD_TURN_RETURN_DEGREES = 35.0;
    private static final double FOLLOW_HARD_TURN_MIN_SPEED = 3.0;
    private static final double FOLLOW_HARD_TURN_RELEASE_BRAKE_SPEED = 1.7;
    private static final int FOLLOW_HARD_TURN_CONFIRM_TICKS = 4;
    private static final double FOLLOW_HARD_TURN_BRAKE = 1.0;
    private static final double FOLLOW_HARD_TURN_THROTTLE = 0.42;

    private static boolean followHardTurnActive = false;
    private static int followHardTurnConfirmTicks = 0;


    /*
     * FORWARD_ONLY_AUTONOMY
     *
     * Back to basics: autonomous follow/home never use reverse.
     * The car only uses:
     * - forward throttle
     * - brake
     * - left/right steering
     *
     * Reverse remains available as a manual debug command, but is
     * intentionally excluded from driver AI for now.
     */
    /*
     * Precision steering controller.
     *
     * Small heading errors become small wheel angles, large errors
     * become larger wheel angles, and an actual dead-zone gives the
     * AI a true STRAIGHT state instead of constantly twitching left
     * and right.
     */
    /*
     * Good alignment is enough.  The AI deliberately stops correcting
     * once it is within this many degrees of the target heading.
     */
    private static final double AI_STEERING_DEADZONE = 6.0;
    private static final double AI_STEERING_GAIN = 0.34;
    private static final double AI_LOW_SPEED_MAX_STEERING = 22.0;
    private static final double AI_HIGH_SPEED_MAX_STEERING = 8.0;
    private static final double AI_STEERING_SPEED_REDUCTION = 2.0;

    /*
     * Fine corrections are taps, not held steering.
     *
     * Below STEERING_TAP_MAX_ERROR the AI briefly nudges the wheel,
     * releases it back to center, observes the new heading, and only
     * then decides whether another tap is needed.
     */
    private static final double STEERING_TAP_MAX_ERROR = 28.0;
    private static int steeringTapTicksRemaining = 0;
    private static int steeringTapRestTicksRemaining = 0;
    private static double steeringTapDirection = 0.0;

    /*
     * Speed controller values are in approximate blocks/second.
     * MTS axialVelocity is blocks/tick, so GTACore multiplies it by
     * 20 for a convenient world-speed value.
     */
    private static final double AI_MAX_SPEED = 5.0;
    private static final double AI_SPEED_DEADZONE = 0.30;

    private static double aiTargetSpeed = 0.0;
    private static double aiCurrentSpeed = 0.0;
    private static double brakeCommand = 0.0;
    private static double parkingBrakeCommand = 0.0;

    /*
     * Manual-transmission fallback.  Automatic MTS engines are left
     * alone so their own transmission logic can choose gears from
     * the vehicle pack's RPM definitions.
     */
    private static int transmissionTickCounter = 0;
    private static final int TRANSMISSION_CHECK_TICKS = 8;
    private static final double MANUAL_UPSHIFT_RPM_FRACTION = 0.82;
    private static final double MANUAL_DOWNSHIFT_RPM_FRACTION = 0.36;

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

    private static void resetFollowBaseline() {

        followBaselineMode =
            FOLLOW_TURNING;

        followTurnDirection = 0.0;
        followHeadingError = 0.0;
        followMisalignmentTicks = 0;
        followSteerPulseTick = 0;
        followDigitalSteeringActive = false;
        followHardTurnActive = false;
        followHardTurnConfirmTicks = 0;
        parkingBrakeCommand = 0.0;
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
                            resetFollowBaseline();
                            homeTurnPhase = HOME_TURN_FOLLOW;
                            homeTurnDirection = 1.0;
                            throttleCommand = 0.0;
                            brakeCommand = 1.0;
                            steeringTarget = 0.0;
                            steeringCurrent = 0.0;
                            steeringTapTicksRemaining = 0;
                            steeringTapRestTicksRemaining = 0;
                            steeringTapDirection = 0.0;

                            try {

                                Object selectedVehicle =
                                    getInternalMTSEntity(
                                        closestVehicle
                                    );

                                /*
                                 * Selection automatically converts the
                                 * car into a GTACore service vehicle:
                                 * fuel it completely now and keep it full.
                                 */
                                refillServiceFuel(
                                    selectedVehicle
                                );

                                serviceVehicles.add(
                                    selectedCar
                                );

                            } catch (Exception fuelError) {

                                selectedCar = null;

                                fuelError.printStackTrace();

                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Vehicle found, but GTACore could not determine/fill its fuel."
                                        )
                                    );

                                return 0;
                            }

                            context.getSource()
                                .sendSuccess(
                                    () ->
                                        Component.literal(
                                            "MTS vehicle selected. Fuel locked at 100%."
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
                            brakeCommand = 0.0;
                            aiTargetSpeed = 0.0;

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
                            brakeCommand = 0.0;
                            aiTargetSpeed = 0.0;

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
                // /gta wanted
                //
                // Wanted level 1 foundation:
                // - target the player who issued the command
                // - prepare/start the selected police car
                // - enable siren/emergency lights
                // - reuse the proven follow-driving controller
                // ------------------------------------------------
                .then(
                    Commands.literal("wanted")
                        .executes(context -> {

                            if (selectedCar == null) {

                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Select a police car first."
                                        )
                                    );

                                return 0;
                            }

                            ServerPlayer target =
                                context.getSource()
                                    .getPlayerOrException();

                            try {

                                prepareSelectedVehicleForAction(
                                    context.getSource()
                                        .getServer()
                                );

                                Object vehicle =
                                    getSelectedVehicle(
                                        context.getSource()
                                            .getServer()
                                    );

                                if (vehicle == null) {

                                    throw new IllegalStateException(
                                        "Selected police car is not loaded."
                                    );
                                }

                                setPoliceEmergencyMode(
                                    vehicle,
                                    true
                                );

                            } catch (Exception e) {

                                e.printStackTrace();

                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Could not start wanted pursuit."
                                        )
                                    );

                                return 0;
                            }

                            wantedLevel = 1;
                            wantedTargetId =
                                target.getUUID();

                            followTargetId =
                                wantedTargetId;

                            returningHome = false;
                            homeRouteIndex = -1;
                            driveReverse = false;

                            resetFollowBaseline();

                            context.getSource()
                                .sendSuccess(
                                    () -> Component.literal(
                                        "WANTED level 1: police pursuit started."
                                    ),
                                    false
                                );

                            return Command.SINGLE_SUCCESS;
                        })
                )

                // ------------------------------------------------
                // /gta clearwanted
                // ------------------------------------------------
                .then(
                    Commands.literal("clearwanted")
                        .executes(context -> {

                            try {

                                Object vehicle =
                                    getSelectedVehicle(
                                        context.getSource()
                                            .getServer()
                                    );

                                if (vehicle != null) {

                                    setPoliceEmergencyMode(
                                        vehicle,
                                        false
                                    );
                                }

                            } catch (Exception e) {

                                e.printStackTrace();
                            }

                            wantedLevel = 0;
                            wantedTargetId = null;
                            followTargetId = null;

                            resetFollowBaseline();

                            driveForward = false;
                            driveReverse = false;
                            throttleCommand = 0.0;
                            brakeCommand = 1.0;
                            parkingBrakeCommand = 0.0;

                            context.getSource()
                                .sendSuccess(
                                    () -> Component.literal(
                                        "Wanted level cleared."
                                    ),
                                    false
                                );

                            return Command.SINGLE_SUCCESS;
                        })
                )

                // ------------------------------------------------
                // /gta emergencyscan
                //
                // Diagnostic command.  "siren=true" by itself only
                // proves that a variable named siren exists; MTS will
                // happily create a variable even if the vehicle pack
                // never uses it.  This scans the actual vehicle/parts
                // definitions for emergency-looking custom variables
                // and sound-animation variables.
                // ------------------------------------------------
                .then(
                    Commands.literal("emergencyscan")
                        .executes(context -> {

                            if (selectedCar == null) {

                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Select the police car first."
                                        )
                                    );

                                return 0;
                            }

                            try {

                                Object vehicle =
                                    getSelectedVehicle(
                                        context.getSource()
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

                                List<String> results =
                                    scanEmergencyVariables(
                                        vehicle
                                    );

                                if (results.isEmpty()) {

                                    context.getSource()
                                        .sendSuccess(
                                            () -> Component.literal(
                                                "Emergency scan: no obvious siren/emergency variables found."
                                            ),
                                            false
                                        );

                                } else {

                                    context.getSource()
                                        .sendSuccess(
                                            () -> Component.literal(
                                                "Emergency scan candidates:"
                                            ),
                                            false
                                        );

                                    for (
                                        String result :
                                        results
                                    ) {

                                        context.getSource()
                                            .sendSuccess(
                                                () ->
                                                    Component.literal(
                                                        result
                                                    ),
                                                false
                                            );
                                    }
                                }

                            } catch (Exception e) {

                                e.printStackTrace();

                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Emergency variable scan failed."
                                        )
                                    );

                                return 0;
                            }

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

                            /*
                             * Preparation happens before follow mode is
                             * enabled: full fuel first, then engine start.
                             */
                            try {

                                prepareSelectedVehicleForAction(
                                    context.getSource()
                                        .getServer()
                                );

                            } catch (Exception startError) {

                                startError.printStackTrace();

                                context.getSource()
                                    .sendFailure(
                                        Component.literal(
                                            "Could not fuel/start the selected vehicle."
                                        )
                                    );

                                return 0;
                            }

                            returningHome = false;
                            homeTurnPhase = HOME_TURN_FOLLOW;
                            homeTurnDirection = 1.0;
                            homeRouteIndex = -1;

                            followTargetId =
                                target.getUUID();

                            driveReverse = false;

                            resetFollowBaseline();

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
                            resetFollowBaseline();

                            driveForward = false;
                            driveReverse = false;
                            throttleCommand = 0.0;
                            brakeCommand = 1.0;
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

                            try {

                                Object vehicle =
                                    getSelectedVehicle(
                                        context.getSource()
                                            .getServer()
                                    );

                                if (vehicle != null) {

                                    setPoliceEmergencyMode(
                                        vehicle,
                                        false
                                    );
                                }

                            } catch (Exception e) {

                                e.printStackTrace();
                            }

                            wantedLevel = 0;
                            wantedTargetId = null;
                            followTargetId = null;
                            resetFollowBaseline();
                            returningHome = false;
                            homeTurnPhase = HOME_TURN_FOLLOW;
                            homeTurnDirection = 1.0;
                            homeRouteIndex = -1;
                            driveForward = false;
                            driveReverse = false;
                            throttleCommand = 0.0;
                            brakeCommand = 1.0;
                            parkingBrakeCommand = 0.0;
                            aiTargetSpeed = 0.0;
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

            followDigitalSteeringActive = false;

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

            /*
             * Headlights/running lights follow Minecraft time
             * automatically for the selected GTACore vehicle.
             */
            updateAutomaticLights(
                vehicle,
                wrapper
            );

            /*
             * The selected GTACore car is kept physically full every
             * tick.  This happens before any engine-start or AI logic.
             */
            if (
                serviceVehicles.contains(
                    selectedCar
                )
            ) {
                refillServiceFuel(
                    vehicle
                );
            }

            boolean autonomousAction =
                followTargetId != null ||
                returningHome;

            if (autonomousAction) {

                ensureVehicleStarted(
                    vehicle
                );

                boolean engineReady =
                    getBooleanField(
                        vehicle,
                        "enginesRunning"
                    );

                if (!engineReady) {

                    driveForward = false;
                    driveReverse = false;
                    throttleCommand = 0.0;
                    brakeCommand = 1.0;
                    steeringTarget = 0.0;

                    updateSteering(
                        vehicle
                    );

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

                    return;
                }
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

            /*
             * Follow steering is digital and writes directly to MTS.
             * Do not let the generic analog smoother overwrite those
             * left/right taps afterward.
             */
            if (!followDigitalSteeringActive) {

                updateSteering(
                    vehicle
                );
            }

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
                    parkingBrakeCommand
                );

                boolean forwardGearReady =
                    ensureForwardGear(
                        vehicle
                    );

                if (forwardGearReady) {

                    transmissionTickCounter++;

                    if (
                        transmissionTickCounter >=
                            TRANSMISSION_CHECK_TICKS
                    ) {

                        transmissionTickCounter = 0;

                        manageForwardTransmission(
                            vehicle
                        );
                    }

                    setMTSVariable(
                        vehicle,
                        "brakeVar",
                        brakeCommand
                    );

                    /*
                     * Never command power against the brake.
                     */
                    setMTSVariable(
                        vehicle,
                        "throttleVar",
                        (
                            brakeCommand > 0.01 ||
                            parkingBrakeCommand > 0.01
                        )
                            ? 0.0
                            : throttleCommand
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

                    brakeCommand = 0.0;

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

                throttleCommand = 0.0;
                brakeCommand = 1.0;
                parkingBrakeCommand = 0.0;
                aiTargetSpeed = 0.0;

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

        followDigitalSteeringActive = true;

        ServerPlayer target =
            server.getPlayerList()
                .getPlayer(
                    followTargetId
                );

        if (
            target == null ||
            target.level().dimension() !=
                wrapper.level().dimension()
        ) {

            followTargetId = null;

            if (
                wantedTargetId != null
            ) {
                wantedLevel = 0;
                wantedTargetId = null;

                setPoliceEmergencyMode(
                    vehicle,
                    false
                );
            }

            resetFollowBaseline();

            driveForward = false;
            driveReverse = false;
            throttleCommand = 0.0;
            brakeCommand = 1.0;

            setFollowDigitalSteering(
                vehicle,
                0.0
            );

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

        aiCurrentSpeed =
            getVehicleSpeedBlocksPerSecond(
                vehicle
            );

        if (
            distance <=
                FOLLOW_STOP_DISTANCE
        ) {

            driveForward = false;
            driveReverse = false;
            throttleCommand = 0.0;
            brakeCommand = 1.0;
            parkingBrakeCommand = 0.0;

            followBaselineMode =
                FOLLOW_STRAIGHT;

            followTurnDirection = 0.0;
            followSteerPulseTick = 0;

            setFollowDigitalSteering(
                vehicle,
                0.0
            );

            return;
        }

        followHeadingError =
            getHeadingErrorToTarget(
                vehicle,
                dx,
                dz
            );

        double absoluteError =
            Math.abs(
                followHeadingError
            );

        /*
         * Detect a turn that normal tap-steering cannot physically
         * make at the current speed.
         *
         * 85 degrees is intentionally much higher than the normal
         * realignment threshold.  This keeps the working normal
         * driving behavior completely separate from hard turns.
         */
        if (!followHardTurnActive) {

            if (
                absoluteError >=
                    FOLLOW_HARD_TURN_START_DEGREES &&
                aiCurrentSpeed >=
                    FOLLOW_HARD_TURN_MIN_SPEED
            ) {

                followHardTurnConfirmTicks++;

            } else {

                followHardTurnConfirmTicks = 0;
            }

            if (
                followHardTurnConfirmTicks >=
                    FOLLOW_HARD_TURN_CONFIRM_TICKS
            ) {

                followHardTurnActive = true;
                followHardTurnConfirmTicks = 0;

                followBaselineMode =
                    FOLLOW_TURNING;

                followTurnDirection =
                    Math.signum(
                        followHeadingError
                    );

                if (
                    followTurnDirection == 0.0
                ) {
                    followTurnDirection = 1.0;
                }

                followSteerPulseTick = 0;
                followMisalignmentTicks = 0;
            }
        }

        if (followHardTurnActive) {

            /*
             * HARD TURN steering is a held key, not a tap.
             *
             * Keep measuring the real heading every tick; there is
             * no guessed turn duration.
             */
            setFollowDigitalSteering(
                vehicle,
                followTurnDirection *
                    FOLLOW_FULL_STEER
            );

            boolean crossedTargetHeading =
                Math.signum(
                    followHeadingError
                ) !=
                    followTurnDirection;

            if (crossedTargetHeading) {

                /*
                 * We have rotated through the target direction.
                 * Immediately release steering rather than starting
                 * an opposite correction.
                 */
                followHardTurnActive = false;
                followBaselineMode =
                    FOLLOW_STRAIGHT;

                followTurnDirection = 0.0;
                followSteerPulseTick = 0;
                followMisalignmentTicks = 0;

                setFollowDigitalSteering(
                    vehicle,
                    0.0
                );

                throttleCommand = 1.0;
                brakeCommand = 0.0;
                parkingBrakeCommand = 0.0;

                driveReverse = false;
                driveForward = true;

                return;
            }

            if (
                absoluteError <=
                    FOLLOW_HARD_TURN_RETURN_DEGREES
            ) {

                /*
                 * Hard part of the corner is complete.
                 * Hand control back to the proven tap-steering logic
                 * for the final alignment rather than holding lock too
                 * long and overshooting.
                 */
                followHardTurnActive = false;
                followBaselineMode =
                    FOLLOW_TURNING;

                followSteerPulseTick = 0;

                throttleCommand = 1.0;
                brakeCommand = 0.0;
                parkingBrakeCommand = 0.0;

                driveReverse = false;
                driveForward = true;

                return;
            }

            /*
             * At speed: full service brake + held steering.
             * Once the car slows enough to rotate tightly:
             * release brake and feed in moderate throttle while still
             * holding the same steering direction.
             */
            if (
                aiCurrentSpeed >
                    FOLLOW_HARD_TURN_RELEASE_BRAKE_SPEED
            ) {

                throttleCommand = 0.0;
                brakeCommand =
                    FOLLOW_HARD_TURN_BRAKE;

            } else {

                throttleCommand =
                    FOLLOW_HARD_TURN_THROTTLE;

                brakeCommand = 0.0;
            }

            parkingBrakeCommand = 0.0;

            driveReverse = false;
            driveForward = true;

            return;
        }

        // ========================================================
        // STRAIGHT
        // ========================================================
        if (
            followBaselineMode ==
                FOLLOW_STRAIGHT
        ) {

            /*
             * Straight means truly no steering input.
             */
            setFollowDigitalSteering(
                vehicle,
                0.0
            );

            /*
             * Ignore ordinary target movement.  A new correction is
             * allowed only after a meaningful error persists.
             */
            if (
                absoluteError >=
                    FOLLOW_REALIGN_START_DEGREES
            ) {

                followMisalignmentTicks++;

            } else {

                followMisalignmentTicks = 0;
            }

            if (
                followMisalignmentTicks >=
                    FOLLOW_REALIGN_CONFIRM_TICKS
            ) {

                followBaselineMode =
                    FOLLOW_TURNING;

                followTurnDirection =
                    Math.signum(
                        followHeadingError
                    );

                if (
                    followTurnDirection == 0.0
                ) {
                    followTurnDirection = 1.0;
                }

                followSteerPulseTick = 0;
                followMisalignmentTicks = 0;
            }

            /*
             * Same proven acceleration path as /gta forward.
             */
            throttleCommand = 1.0;
            brakeCommand = 0.0;
            parkingBrakeCommand = 0.0;

            driveReverse = false;
            driveForward = true;

            return;
        }

        // ========================================================
        // TURNING
        // ========================================================

        if (
            followTurnDirection == 0.0
        ) {

            followTurnDirection =
                Math.signum(
                    followHeadingError
                );

            if (
                followTurnDirection == 0.0
            ) {
                followTurnDirection = 1.0;
            }

            followSteerPulseTick = 0;
        }

        /*
         * Keep one direction for the entire maneuver.
         *
         * We do not know or predict how many ticks the turn will
         * require.  Every tick, the real heading tells us whether
         * the car is now going in the same general direction as the
         * target.  If the nose crosses that direction, STOP steering
         * rather than instantly commanding the opposite side.
         */
        boolean crossedTargetHeading =
            Math.signum(
                followHeadingError
            ) !=
                followTurnDirection;

        if (
            absoluteError <=
                FOLLOW_ALIGN_DONE_DEGREES ||
            crossedTargetHeading
        ) {

            followBaselineMode =
                FOLLOW_STRAIGHT;

            followTurnDirection = 0.0;
            followSteerPulseTick = 0;
            followMisalignmentTicks = 0;

            setFollowDigitalSteering(
                vehicle,
                0.0
            );

            throttleCommand = 1.0;
            brakeCommand = 0.0;
            parkingBrakeCommand = 0.0;

            driveReverse = false;
            driveForward = true;

            return;
        }

        /*
         * No predicted turn duration.
         *
         * The pulse is just an actuator command.  Alignment is
         * determined from followHeadingError, which is recalculated
         * from the car's actual orientation every server tick.
         */
        int tapCycleLength =
            FOLLOW_TAP_ON_TICKS +
            FOLLOW_TAP_OFF_TICKS;

        boolean steeringOn =
            (
                followSteerPulseTick %
                    tapCycleLength
            ) <
                FOLLOW_TAP_ON_TICKS;

        if (steeringOn) {

            setFollowDigitalSteering(
                vehicle,
                followTurnDirection *
                    FOLLOW_FULL_STEER
            );

        } else {

            /*
             * Release the wheel between taps.  This lets the car
             * physically respond before the next correction.
             */
            setFollowDigitalSteering(
                vehicle,
                0.0
            );
        }

        followSteerPulseTick++;

        /*
         * Steering and acceleration are independent controls.
         *
         * A steering tap must NEVER release the accelerator.  The AI
         * can hold virtual W while independently pressing/releasing
         * LEFT or RIGHT.  This matches how the car is actually driven
         * by a player.
         */
        throttleCommand = 1.0;
        brakeCommand = 0.0;
        parkingBrakeCommand = 0.0;

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
            driveReverse = false;
            steeringTarget = 0.0;

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
         * Progress backward through the recorded route as each
         * breadcrumb is reached.
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

            homeTurnPhase =
                HOME_TURN_FOLLOW;

            homeTurnDirection = 1.0;
            homeTurnTicks = 0;
            homeTurnCooldownTicks = 0;

            driveForward = false;
            driveReverse = false;
            throttleCommand = 1.0;
            steeringTarget = 0.0;
            homeRouteIndex = -1;

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
         * Aim farther down the breadcrumb trail rather than at the
         * closest point.  This gives the car one smooth direction to
         * follow through bends.
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

        double targetSpeed =
            calculateAITargetSpeed(
                distance,
                headingError,
                HOME_STOP_DISTANCE
            );

        /*
         * Final approach gets an extra-low target speed so braking
         * can place the car near the actual home point instead of
         * coasting through it.
         */
        if (
            homeRouteIndex == 0 &&
            distance < 10.0
        ) {
            targetSpeed =
                Math.min(
                    targetSpeed,
                    1.4
                );
        }

        applyPrecisionAIControl(
            vehicle,
            headingError,
            targetSpeed
        );

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

    private static double calculateAITargetSpeed(
        double distance,
        double headingError,
        double stopDistance
    ) {

        double absoluteError =
            Math.abs(
                headingError
            );

        /*
         * Heading determines the safe cornering speed.
         * The car can travel quickly only when it is substantially
         * lined up with its target.
         */
        double targetSpeed;

        if (absoluteError >= 120.0) {

            targetSpeed = 0.9;

        } else if (
            absoluteError >= 80.0
        ) {

            targetSpeed = 1.3;

        } else if (
            absoluteError >= 50.0
        ) {

            targetSpeed = 2.0;

        } else if (
            absoluteError >= 25.0
        ) {

            targetSpeed = 3.0;

        } else if (
            absoluteError >= 10.0
        ) {

            targetSpeed = 4.0;

        } else {

            targetSpeed = AI_MAX_SPEED;
        }

        /*
         * Distance controls the approach speed independently of the
         * turn angle.
         */
        double remaining =
            distance -
            stopDistance;

        if (remaining <= 2.0) {

            targetSpeed =
                Math.min(
                    targetSpeed,
                    0.8
                );

        } else if (
            remaining <= 5.0
        ) {

            targetSpeed =
                Math.min(
                    targetSpeed,
                    1.4
                );

        } else if (
            remaining <= 10.0
        ) {

            targetSpeed =
                Math.min(
                    targetSpeed,
                    2.2
                );

        } else if (
            remaining <= 18.0
        ) {

            targetSpeed =
                Math.min(
                    targetSpeed,
                    3.2
                );
        }

        return targetSpeed;
    }

    private static void applyPrecisionAIControl(
        Object vehicle,
        double headingError,
        double targetSpeed
    ) throws Exception {

        aiCurrentSpeed =
            getVehicleSpeedBlocksPerSecond(
                vehicle
            );

        aiTargetSpeed =
            targetSpeed;

        steeringTarget =
            calculatePerfectSteeringAngle(
                headingError,
                aiCurrentSpeed
            );

        updateAISpeedController(
            aiCurrentSpeed,
            targetSpeed
        );
    }

    private static double calculatePerfectSteeringAngle(
        double headingError,
        double speed
    ) {

        double absoluteError =
            Math.abs(
                headingError
            );

        /*
         * Do not demand mathematical perfection.  If we are within
         * the alignment band, release the wheel completely.
         */
        if (
            absoluteError <=
                AI_STEERING_DEADZONE
        ) {

            resetSteeringTap();

            return 0.0;
        }

        double direction =
            Math.signum(
                headingError
            );

        /*
         * If we just crossed the desired heading, center the wheel
         * for one decision cycle instead of immediately holding the
         * opposite correction.  This is the key anti-oscillation
         * behavior for small corrections.
         */
        if (
            steeringTapDirection != 0.0 &&
            direction !=
                steeringTapDirection
        ) {

            resetSteeringTap();

            steeringTapDirection =
                direction;

            steeringTapRestTicksRemaining = 1;

            return 0.0;
        }

        double speedLimitedMaximum =
            clamp(
                AI_LOW_SPEED_MAX_STEERING -
                    speed *
                    AI_STEERING_SPEED_REDUCTION,
                AI_HIGH_SPEED_MAX_STEERING,
                AI_LOW_SPEED_MAX_STEERING
            );

        double requested =
            clamp(
                headingError *
                    AI_STEERING_GAIN,
                -speedLimitedMaximum,
                speedLimitedMaximum
            );

        /*
         * Large turns may be held because the car genuinely needs a
         * sustained arc.  Fine/medium corrections are tapped.
         */
        if (
            absoluteError >
                STEERING_TAP_MAX_ERROR
        ) {

            resetSteeringTap();

            steeringTapDirection =
                direction;

            return requested;
        }

        steeringTapDirection =
            direction;

        if (
            steeringTapRestTicksRemaining > 0
        ) {

            steeringTapRestTicksRemaining--;

            return 0.0;
        }

        if (
            steeringTapTicksRemaining <= 0
        ) {

            /*
             * Tiny error: one-tick nudge, then a long coast.
             * Medium error: two-tick nudge, then a shorter coast.
             */
            if (absoluteError < 14.0) {

                steeringTapTicksRemaining = 1;

            } else {

                steeringTapTicksRemaining = 2;
            }
        }

        steeringTapTicksRemaining--;

        if (
            steeringTapTicksRemaining <= 0
        ) {

            steeringTapRestTicksRemaining =
                absoluteError < 14.0
                    ? 4
                    : 2;
        }

        return requested;
    }

    private static void resetSteeringTap() {

        steeringTapTicksRemaining = 0;
        steeringTapRestTicksRemaining = 0;
        steeringTapDirection = 0.0;
    }

    private static double getVehicleSpeedBlocksPerSecond(
        Object vehicle
    ) throws Exception {

        return Math.abs(
            getDoubleField(
                vehicle,
                "axialVelocity"
            ) *
            20.0
        );
    }

    private static void updateAISpeedController(
        double currentSpeed,
        double targetSpeed
    ) {

        double speedError =
            targetSpeed -
            currentSpeed;

        /*
         * Too fast: release throttle first, then proportionally apply
         * the service brake.  This is what lets the AI actually hold
         * a cornering/approach speed instead of only varying throttle.
         */
        if (
            speedError <
                -AI_SPEED_DEADZONE
        ) {

            throttleCommand = 0.0;

            brakeCommand =
                clamp(
                    (-speedError) *
                        0.18,
                    0.08,
                    0.70
                );

            return;
        }

        /*
         * Too slow: release brake and proportionally add throttle.
         */
        if (
            speedError >
                AI_SPEED_DEADZONE
        ) {

            brakeCommand = 0.0;

            throttleCommand =
                clamp(
                    0.10 +
                        speedError *
                        0.08,
                    0.10,
                    0.55
                );

            return;
        }

        /*
         * Within the target-speed band, use only a tiny maintenance
         * throttle.  At very low target speeds, coast instead.
         */
        brakeCommand = 0.0;

        throttleCommand =
            targetSpeed < 1.0
                ? 0.0
                : 0.08;
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
         * Read the vehicle orientation directly from its rotation
         * matrix rather than relying on Euler yaw conversion.
         *
         * For this MTS vehicle setup, the physical nose points along
         * local -Z, so the matrix +Z column is negated below.
         */
        /*
         * Important MTS orientation detail for the vehicle pack we
         * are testing: the matrix's local +Z axis points opposite
         * the car's physical driving direction.
         *
         * The previous controller treated +Z as the nose of the car,
         * which made follow/home consistently choose the direction
         * 180 degrees away from the target.
         *
         * Negate the matrix forward axis so the AI heading matches
         * the direction the car actually drives when throttle is
         * applied.
         */
        double forwardX =
            -getDoubleField(
                orientation,
                "m02"
            );

        double forwardZ =
            -getDoubleField(
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

    private static double getNumericField(
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

        Object value =
            field.get(
                owner
            );

        if (!(value instanceof Number)) {
            throw new IllegalStateException(
                fieldName +
                    " is not numeric."
            );
        }

        return ((Number) value)
            .doubleValue();
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

    private static List<String> scanEmergencyVariables(
        Object vehicle
    ) {

        List<String> results =
            new ArrayList<>();

        scanEmergencyVariablesOnObject(
            vehicle,
            "vehicle",
            results
        );

        try {

            Object allParts =
                getFieldValue(
                    vehicle,
                    "allParts"
                );

            if (
                allParts instanceof Iterable<?>
            ) {

                int partIndex = 0;

                for (
                    Object part :
                    (Iterable<?>) allParts
                ) {

                    if (part == null) {
                        continue;
                    }

                    String label =
                        "part "
                            + partIndex
                            + " "
                            + part.getClass()
                                .getSimpleName();

                    scanEmergencyVariablesOnObject(
                        part,
                        label,
                        results
                    );

                    partIndex++;
                }
            }

        } catch (Exception ignored) {
        }

        return results;
    }

    private static void scanEmergencyVariablesOnObject(
        Object owner,
        String label,
        List<String> results
    ) {

        try {

            Object definition =
                getFieldValue(
                    owner,
                    "definition"
                );

            if (definition == null) {
                return;
            }

            Object rendering =
                getFieldValue(
                    definition,
                    "rendering"
                );

            if (rendering == null) {
                return;
            }

            /*
             * First inspect variables the pack explicitly declares.
             */
            try {

                Object customVariables =
                    getFieldValue(
                        rendering,
                        "customVariables"
                    );

                if (
                    customVariables instanceof Iterable<?>
                ) {

                    for (
                        Object variable :
                        (Iterable<?>) customVariables
                    ) {

                        if (
                            variable != null &&
                            looksLikeEmergencyVariable(
                                variable.toString()
                            )
                        ) {

                            results.add(
                                label
                                    + " custom: "
                                    + variable
                            );
                        }
                    }
                }

            } catch (Exception ignored) {
            }

            /*
             * More importantly, inspect sound definitions and the
             * variables that actually make those sounds visible/active.
             * This tells us what the siren audio is really listening to.
             */
            try {

                Object sounds =
                    getFieldValue(
                        rendering,
                        "sounds"
                    );

                if (
                    sounds instanceof Iterable<?>
                ) {

                    for (
                        Object sound :
                        (Iterable<?>) sounds
                    ) {

                        if (sound == null) {
                            continue;
                        }

                        String soundName = "";

                        try {

                            Object name =
                                getFieldValue(
                                    sound,
                                    "name"
                                );

                            if (name != null) {
                                soundName =
                                    name.toString();
                            }

                        } catch (Exception ignored) {
                        }

                        Object activeAnimations =
                            null;

                        try {

                            activeAnimations =
                                getFieldValue(
                                    sound,
                                    "activeAnimations"
                                );

                        } catch (Exception ignored) {
                        }

                        if (
                            activeAnimations
                                instanceof Iterable<?>
                        ) {

                            for (
                                Object animation :
                                (Iterable<?>) activeAnimations
                            ) {

                                if (
                                    animation == null
                                ) {
                                    continue;
                                }

                                try {

                                    Object variable =
                                        getFieldValue(
                                            animation,
                                            "variable"
                                        );

                                    if (variable == null) {
                                        continue;
                                    }

                                    String variableName =
                                        variable.toString();

                                    if (
                                        looksLikeEmergencyVariable(
                                            variableName
                                        ) ||
                                        looksLikeEmergencyVariable(
                                            soundName
                                        )
                                    ) {

                                        results.add(
                                            label
                                                + " sound "
                                                + soundName
                                                + " <- "
                                                + variableName
                                        );
                                    }

                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }
                }

            } catch (Exception ignored) {
            }

        } catch (Exception ignored) {
        }
    }

    private static boolean looksLikeEmergencyVariable(
        String value
    ) {

        if (value == null) {
            return false;
        }

        String lower =
            value.toLowerCase();

        return (
            lower.contains("siren") ||
            lower.contains("emerg") ||
            lower.contains("police") ||
            lower.contains("wail") ||
            lower.contains("yelp") ||
            lower.contains("tone") ||
            lower.contains("code") ||
            lower.contains("lightbar") ||
            lower.contains("auxlt") ||
            lower.contains("emerlt")
        );
    }

    // ============================================================
    // POLICE EMERGENCY EQUIPMENT
    // ============================================================

    private static void setPoliceEmergencyMode(
        Object vehicle,
        boolean enabled
    ) throws Exception {

        double value =
            enabled
                ? 1.0
                : 0.0;

        /*
         * Emergency equipment may be defined on the vehicle itself OR
         * on an installed part such as a lightbar/siren assembly.
         *
         * Set the official/common variables everywhere in the MTS
         * multipart tree so the police pack can react wherever it
         * defines the sound/lights.
         */
        setEmergencyVariableEverywhere(
            vehicle,
            "siren",
            value
        );

        setEmergencyVariableEverywhere(
            vehicle,
            "EMERLTS",
            value
        );

        setEmergencyVariableEverywhere(
            vehicle,
            "AUXLTS",
            value
        );
    }

    private static void setEmergencyVariableEverywhere(
        Object vehicle,
        String variableName,
        double value
    ) throws Exception {

        setMTSCustomVariable(
            vehicle,
            variableName,
            value
        );

        Object allParts =
            getFieldValue(
                vehicle,
                "allParts"
            );

        if (
            allParts instanceof Iterable<?>
        ) {

            for (
                Object part :
                (Iterable<?>) allParts
            ) {

                if (part == null) {
                    continue;
                }

                try {

                    setMTSCustomVariable(
                        part,
                        variableName,
                        value
                    );

                } catch (Exception ignored) {

                    /*
                     * Some unusual MTS parts may not expose the normal
                     * definable-variable API.  Do not let one such part
                     * prevent the rest of the police equipment from
                     * being enabled.
                     */
                }
            }
        }
    }

    private static void setMTSCustomVariable(
        Object owner,
        String variableName,
        double value
    ) throws Exception {

        Method getOrCreateVariable =
            owner.getClass()
                .getMethod(
                    "getOrCreateVariable",
                    String.class
                );

        Object variable =
            getOrCreateVariable.invoke(
                owner,
                variableName
            );

        if (variable == null) {

            throw new IllegalStateException(
                "Could not create MTS variable: "
                    + variableName
            );
        }

        Method setTo =
            variable.getClass()
                .getMethod(
                    "setTo",
                    double.class,
                    boolean.class
                );

        setTo.invoke(
            variable,
            value,
            true
        );
    }

    private static double getMTSCustomVariableValue(
        Object owner,
        String variableName
    ) throws Exception {

        Method getOrCreateVariable =
            owner.getClass()
                .getMethod(
                    "getOrCreateVariable",
                    String.class
                );

        Object variable =
            getOrCreateVariable.invoke(
                owner,
                variableName
            );

        Field currentValue =
            findField(
                variable.getClass(),
                "currentValue"
            );

        if (currentValue == null) {

            throw new NoSuchFieldException(
                "currentValue"
            );
        }

        currentValue.setAccessible(true);

        return currentValue.getDouble(
            variable
        );
    }

    private static double getMTSCustomVariableValueAnywhere(
        Object vehicle,
        String variableName
    ) throws Exception {

        double maximum =
            getMTSCustomVariableValue(
                vehicle,
                variableName
            );

        Object allParts =
            getFieldValue(
                vehicle,
                "allParts"
            );

        if (
            allParts instanceof Iterable<?>
        ) {

            for (
                Object part :
                (Iterable<?>) allParts
            ) {

                if (part == null) {
                    continue;
                }

                try {

                    maximum =
                        Math.max(
                            maximum,
                            getMTSCustomVariableValue(
                                part,
                                variableName
                            )
                        );

                } catch (Exception ignored) {
                }
            }
        }

        return maximum;
    }

    // ============================================================
    // AUTOMATIC LIGHTS
    // ============================================================

    private static void updateAutomaticLights(
        Object vehicle,
        Entity wrapper
    ) throws Exception {

        /*
         * Minecraft day time:
         * 0      = sunrise
         * 6000   = noon
         * 12000  = sunset
         * 18000  = midnight
         *
         * Turn vehicle running lights + headlights on from shortly
         * after sunset until shortly before sunrise.
         */
        long dayTime =
            wrapper.level()
                .getDayTime() %
                24000L;

        boolean night =
            dayTime >= 13000L &&
            dayTime < 23000L;

        double lightValue =
            night
                ? 1.0
                : 0.0;

        /*
         * These are MTS's native car-light variables used by its
         * normal keyboard control:
         * runningLightVar and headLightVar.
         */
        if (
            Math.abs(
                getMTSVariableValue(
                    vehicle,
                    "runningLightVar"
                ) -
                lightValue
            ) >
                0.001
        ) {

            setMTSVariable(
                vehicle,
                "runningLightVar",
                lightValue
            );
        }

        if (
            Math.abs(
                getMTSVariableValue(
                    vehicle,
                    "headLightVar"
                ) -
                lightValue
            ) >
                0.001
        ) {

            setMTSVariable(
                vehicle,
                "headLightVar",
                lightValue
            );
        }
    }

    // ============================================================
    // STEERING
    // ============================================================

    private static void setFollowDigitalSteering(
        Object vehicle,
        double steering
    ) throws Exception {

        /*
         * FOLLOW bypasses the analog smoothing controller entirely.
         * It sends only tested digital values:
         * -45, 0, +45.
         */
        double digitalValue =
            steering > 0.0
                ? FOLLOW_FULL_STEER
                : (
                    steering < 0.0
                        ? -FOLLOW_FULL_STEER
                        : 0.0
                );

        steeringTarget =
            digitalValue;

        steeringCurrent =
            digitalValue;

        setMTSVariable(
            vehicle,
            "rudderInputVar",
            digitalValue
        );
    }

    private static void centerSteeringImmediately(
        Object vehicle
    ) throws Exception {

        /*
         * 0 is not a "turn toward zero" command.  It is the physical
         * centered-wheel position.  Set both our controller state and
         * MTS's rudder variable immediately so the previous turn is
         * not held for several extra ticks.
         */
        steeringTarget = 0.0;
        steeringCurrent = 0.0;

        resetSteeringTap();

        setMTSVariable(
            vehicle,
            "rudderInputVar",
            0.0
        );
    }

    private static void updateSteering(
        Object vehicle
    ) throws Exception {

        double difference =
            steeringTarget -
            steeringCurrent;

        /*
         * Return to center faster than we add steering lock.  This
         * helps the car straighten itself immediately after a turn.
         */
        double step =
            Math.abs(steeringTarget) < 0.5
                ? 5.0
                : STEERING_STEP_PER_TICK;

        if (
            Math.abs(difference) <=
            step
        ) {

            steeringCurrent =
                steeringTarget;

        } else {

            steeringCurrent +=
                Math.copySign(
                    step,
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
         * If the tank is completely empty, infer the correct fuel
         * from the installed MTS engine and MTS's own fuel config.
         */
        if (
            fluid == null ||
            fluid.isEmpty()
        ) {

            fluid =
                determineVehicleFuel(
                    vehicle
                );

            fluidMod = "";
        }

        double missingFuel =
            maxLevel -
            currentLevel;

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

        double amountFilled =
            ((Number)
                fill.invoke(
                    fuelTank,
                    fluid,
                    fluidMod,
                    missingFuel,
                    true
                )
            ).doubleValue();

        if (
            amountFilled <= 0.0 &&
            missingFuel > 0.001
        ) {

            throw new IllegalStateException(
                "MTS rejected automatic fuel: "
                    + fluid
            );
        }
    }

    private static String determineVehicleFuel(
        Object vehicle
    ) throws Exception {

        List<?> engines =
            getEngines(
                vehicle
            );

        if (engines.isEmpty()) {

            throw new IllegalStateException(
                "Cannot determine fuel: vehicle has no engine."
            );
        }

        Object engine =
            engines.get(0);

        Object definition =
            getFieldValue(
                engine,
                "definition"
            );

        Object engineDefinition =
            getFieldValue(
                definition,
                "engine"
            );

        String fuelType =
            String.valueOf(
                getFieldValue(
                    engineDefinition,
                    "fuelType"
                )
            );

        Object engineType =
            getFieldValue(
                engineDefinition,
                "type"
            );

        if (
            engineType != null &&
            "ELECTRIC".equalsIgnoreCase(
                engineType.toString()
            )
        ) {

            return "electricity";
        }

        Class<?> configSystem =
            Class.forName(
                "minecrafttransportsimulator.systems.ConfigSystem"
            );

        Field settingsField =
            findField(
                configSystem,
                "settings"
            );

        if (settingsField == null) {

            throw new NoSuchFieldException(
                "ConfigSystem.settings"
            );
        }

        settingsField.setAccessible(true);

        Object settings =
            settingsField.get(
                null
            );

        Object fuelSettings =
            getFieldValue(
                settings,
                "fuel"
            );

        Object fuelsObject =
            getFieldValue(
                fuelSettings,
                "fuels"
            );

        if (!(fuelsObject instanceof Map)) {

            throw new IllegalStateException(
                "MTS fuel config is not a map."
            );
        }

        Map<?, ?> fuelTypes =
            (Map<?, ?>)
                fuelsObject;

        Object candidatesObject =
            fuelTypes.get(
                fuelType
            );

        if (!(candidatesObject instanceof Map)) {

            throw new IllegalStateException(
                "No MTS fuels configured for engine type "
                    + fuelType
            );
        }

        Map<?, ?> candidates =
            (Map<?, ?>)
                candidatesObject;

        /*
         * Prefer the canonical fluid matching the engine name
         * (gasoline -> gasoline, diesel -> diesel) rather than a
         * fallback such as lava that may have equal configured
         * potency.
         */
        String canonical =
            fuelType.toLowerCase();

        if (
            candidates.containsKey(
                canonical
            )
        ) {

            return canonical;
        }

        String bestFluid = null;
        double bestPotency =
            -Double.MAX_VALUE;

        for (
            Map.Entry<?, ?> entry :
            candidates.entrySet()
        ) {

            String candidate =
                String.valueOf(
                    entry.getKey()
                );

            if (
                "lava".equalsIgnoreCase(
                    candidate
                )
            ) {
                continue;
            }

            if (!(entry.getValue() instanceof Number)) {
                continue;
            }

            double potency =
                ((Number)
                    entry.getValue()
                ).doubleValue();

            if (
                bestFluid == null ||
                potency > bestPotency
            ) {

                bestFluid = candidate;
                bestPotency = potency;
            }
        }

        if (bestFluid != null) {
            return bestFluid;
        }

        /*
         * Last fallback: if lava is literally the only configured
         * valid option, use the first configured entry.
         */
        for (Object candidate : candidates.keySet()) {
            return String.valueOf(candidate);
        }

        throw new IllegalStateException(
            "No fuel candidates configured for "
                + fuelType
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

    private static void prepareSelectedVehicleForAction(
        MinecraftServer server
    ) throws Exception {

        Object vehicle =
            getSelectedVehicle(
                server
            );

        if (vehicle == null) {

            throw new IllegalStateException(
                "Selected vehicle is not loaded."
            );
        }

        /*
         * Order matters:
         * 1. Make sure the tank is full.
         * 2. Mark it as permanently serviced.
         * 3. Start the engine.
         */
        refillServiceFuel(
            vehicle
        );

        serviceVehicles.add(
            selectedCar
        );

        ensureVehicleStarted(
            vehicle
        );
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

                refillServiceFuel(
                    vehicle
                );

                if (selectedCar != null) {
                    serviceVehicles.add(
                        selectedCar
                    );
                }

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

            /*
             * MTS's normal player-start path sends state changes to
             * clients.  Calling autoStartEngine() directly on the
             * server starts the mechanics, but the client can miss
             * the magneto/starter animation state.
             *
             * Sync those visible start variables first, then engage
             * MTS's real auto-starter logic.
             */
            syncEngineStartState(
                engine
            );

            invokeNoArg(
                engine,
                "autoStartEngine"
            );
        }

        System.out.println(
            "[GTACore] Automatic engine start requested + synced."
        );
    }

    private static void syncEngineStartState(
        Object engine
    ) throws Exception {

        /*
         * Magneto is part of the visible/running engine state for
         * every MTS engine type.
         */
        setMTSVariable(
            engine,
            "magnetoVar",
            1.0
        );

        /*
         * Only normal combustion engines use the electric starter
         * animation/state.  Electric/magic/etc. engines should not be
         * forced into a combustion-starter animation.
         */
        Object definition =
            getFieldValue(
                engine,
                "definition"
            );

        Object engineDefinition =
            getFieldValue(
                definition,
                "engine"
            );

        Object engineType =
            getFieldValue(
                engineDefinition,
                "type"
            );

        if (
            engineType != null &&
            "NORMAL".equalsIgnoreCase(
                engineType.toString()
            )
        ) {

            setMTSVariable(
                engine,
                "electricStarterVar",
                1.0
            );
        }
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

            invokeNoArg(
                engine,
                "shiftNeutral"
            );

            syncEngineStartState(
                engine
            );

            invokeNoArg(
                engine,
                "autoStartEngine"
            );
        }

        System.out.println(
            "[GTACore] MTS startup sequence requested + synced."
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

    private static void manageForwardTransmission(
        Object vehicle
    ) {

        try {

            List<?> engines =
                getEngines(vehicle);

            for (Object engine : engines) {

                double gear =
                    getMTSVariableValue(
                        engine,
                        "currentGearVar"
                    );

                if (gear < 1.0) {
                    continue;
                }

                /*
                 * Automatic engines already have MTS's native
                 * RPM-based shifting.  Do not fight that system.
                 */
                double automatic =
                    getMTSVariableValue(
                        engine,
                        "isAutomaticVar"
                    );

                if (automatic > 0.5) {
                    continue;
                }

                double rpm =
                    getNumericField(
                        engine,
                        "rpm"
                    );

                double maxSafeRPM =
                    getMTSVariableValue(
                        engine,
                        "maxSafeRPMVar"
                    );

                int forwardGears =
                    (int) getNumericField(
                        engine,
                        "forwardsGears"
                    );

                if (
                    gear < forwardGears &&
                    rpm >
                        maxSafeRPM *
                        MANUAL_UPSHIFT_RPM_FRACTION
                ) {

                    invokeNoArg(
                        engine,
                        "shiftUp"
                    );

                } else if (
                    gear > 1.0 &&
                    rpm <
                        maxSafeRPM *
                        MANUAL_DOWNSHIFT_RPM_FRACTION
                ) {

                    invokeNoArg(
                        engine,
                        "shiftDown"
                    );
                }
            }

        } catch (Exception e) {

            /*
             * Transmission tuning is an enhancement, not something
             * that should disable steering/throttle if a particular
             * MTS version exposes a field differently.  In that case
             * MTS keeps handling the current gear itself.
             */
            System.err.println(
                "[GTACore] Transmission manager fallback: "
                    + e.getMessage()
            );
        }
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

        if (!engines.isEmpty()) {

            Object firstEngine =
                engines.get(0);

            double magneto =
                getMTSVariableValue(
                    firstEngine,
                    "magnetoVar"
                );

            double starter =
                getMTSVariableValue(
                    firstEngine,
                    "electricStarterVar"
                );

            double rpm =
                getNumericField(
                    firstEngine,
                    "rpm"
                );

            source.sendSuccess(
                () -> Component.literal(
                    String.format(
                        "Engine sync: magneto %.0f | starter %.0f | RPM %.0f",
                        magneto,
                        starter,
                        rpm
                    )
                ),
                false
            );
        }

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
                    + " | AI mode: forward-only"
            ),
            false
        );

        source.sendSuccess(
            () -> Component.literal(
                String.format(
                    "Following: %s | mode: %s | hard turn: %s | heading error: %.1f deg | turn side: %.0f | feedback tap: %d",
                    followTargetId != null,
                    followBaselineMode ==
                        FOLLOW_STRAIGHT
                            ? "STRAIGHT"
                            : "TURNING",
                    followHardTurnActive,
                    followHeadingError,
                    followTurnDirection,
                    followSteerPulseTick
                )
            ),
            false
        );

        double sirenValue =
            getMTSCustomVariableValueAnywhere(
                vehicle,
                "siren"
            );

        double emergencyLightsValue =
            getMTSCustomVariableValueAnywhere(
                vehicle,
                "EMERLTS"
            );

        source.sendSuccess(
            () -> Component.literal(
                "Wanted: "
                    + wantedLevel
                    + " | siren="
                    + (sirenValue > 0.5)
                    + " | emergency lights="
                    + (emergencyLightsValue > 0.5)
            ),
            false
        );

        source.sendSuccess(
            () -> Component.literal(
                "Fuel lock: "
                    + (
                        selectedCar != null &&
                        serviceVehicles.contains(
                            selectedCar
                        )
                    )
            ),
            false
        );

        double runningLights =
            getMTSVariableValue(
                vehicle,
                "runningLightVar"
            );

        double headlights =
            getMTSVariableValue(
                vehicle,
                "headLightVar"
            );

        source.sendSuccess(
            () -> Component.literal(
                "Auto lights: running="
                    + (runningLights > 0.5)
                    + " | headlights="
                    + (headlights > 0.5)
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
                String.format(
                    "AI speed: %.2f / target %.2f blocks/s",
                    aiCurrentSpeed,
                    aiTargetSpeed
                )
            ),
            false
        );

        source.sendSuccess(
            () -> Component.literal(
                "Throttle request: "
                    + throttle
                    + " | AI brake request: "
                    + brakeCommand
                    + " | parking brake request: "
                    + parkingBrakeCommand
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
