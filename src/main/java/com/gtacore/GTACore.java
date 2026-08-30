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
import java.util.Collections;
import java.util.List;
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

                            driveForward = true;

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
                // /gta stop
                //
                // Release W and apply the normal brake.
                // ------------------------------------------------
                .then(
                    Commands.literal("stop")
                        .executes(context -> {

                            driveForward = false;

                            context.getSource()
                                .sendSuccess(
                                    () ->
                                        Component.literal(
                                            "Virtual W: OFF | Brake: ON"
                                        ),
                                    false
                                );

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

        if (selectedCar == null) {
            return;
        }

        try {

            Object vehicle =
                getSelectedVehicle(
                    event.getServer()
                );

            if (vehicle == null) {
                return;
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
                        1.0
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

            System.err.println(
                "[GTACore] Vehicle control failed:"
            );

            e.printStackTrace();
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
            ),
            false
        );

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
    }

    // ============================================================
    // MTS REFLECTION BRIDGE
    // ============================================================

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
