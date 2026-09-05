package com.gtacore;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class VehicleTemplateStore {

    private static final String MTS_WRAPPER_NBT =
        "mcinterface1201.WrapperNBT";

    private static final String MTS_WRAPPER_WORLD =
        "mcinterface1201.WrapperWorld";

    private static final String MTS_VEHICLE =
        "minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics";

    private static final String MTS_BASE_EXISTING =
        "minecrafttransportsimulator.entities.components.AEntityB_Existing";

    private static final String MTS_WRAPPER_WORLD_BASE =
        "minecrafttransportsimulator.mcinterface.AWrapperWorld";

    private static final String MTS_WRAPPER_PLAYER =
        "minecrafttransportsimulator.mcinterface.IWrapperPlayer";

    private static final String MTS_ITEM_VEHICLE =
        "minecrafttransportsimulator.items.instances.ItemVehicle";

    private static final String MTS_WRAPPER_NBT_INTERFACE =
        "minecrafttransportsimulator.mcinterface.IWrapperNBT";

    private VehicleTemplateStore() {
    }

    static Path getTemplateDirectory() {

        return FMLPaths.CONFIGDIR
            .get()
            .resolve("gtacore")
            .resolve("templates");
    }

    static Path getTemplatePath(
        String templateName
    ) {

        String clean =
            validateTemplateName(
                templateName
            );

        return getTemplateDirectory()
            .resolve(
                clean + ".nbt"
            );
    }

    static Path saveTemplate(
        Object vehicle,
        String templateName
    ) throws Exception {

        if (vehicle == null) {

            throw new IllegalArgumentException(
                "No MTS vehicle supplied."
            );
        }

        Path directory =
            getTemplateDirectory();

        Files.createDirectories(
            directory
        );

        Object data =
            createEmptyMTSNBT();

        Method save =
            findMethodByNameAndCount(
                vehicle.getClass(),
                "save",
                1
            );

        if (save == null) {

            throw new NoSuchMethodException(
                "Could not find MTS vehicle save(IWrapperNBT)."
            );
        }

        save.setAccessible(
            true
        );

        save.invoke(
            vehicle,
            data
        );

        /*
         * A template must never preserve world entity UUIDs.
         *
         * MTS recursively removes the vehicle UUID plus UUIDs saved
         * inside its installed parts.  Every spawned cruiser will
         * therefore receive fresh identities.
         */
        Method deleteUUIDs =
            findMethodByNameAndCount(
                data.getClass(),
                "deleteAllUUIDTags",
                0
            );

        if (deleteUUIDs != null) {

            deleteUUIDs.setAccessible(
                true
            );

            deleteUUIDs.invoke(
                data
            );
        }

        CompoundTag tag =
            getCompoundTag(
                data
            ).copy();

        /*
         * Templates should describe the assembled vehicle, not the
         * velocity it happened to have while being saved.
         */
        tag.remove(
            "motionx"
        );

        tag.remove(
            "motiony"
        );

        tag.remove(
            "motionz"
        );

        Path path =
            getTemplatePath(
                templateName
            );

        try (
            OutputStream output =
                Files.newOutputStream(
                    path
                )
        ) {

            NbtIo.writeCompressed(
                tag,
                output
            );
        }

        return path;
    }

    static SpawnedVehicle spawnTemplate(
        ServerPlayer player,
        String templateName
    ) throws Exception {

        Path path =
            getTemplatePath(
                templateName
            );

        if (
            !Files.exists(
                path
            )
        ) {

            throw new IllegalStateException(
                "Template does not exist: "
                    + path.getFileName()
            );
        }

        CompoundTag tag;

        try (
            InputStream input =
                Files.newInputStream(
                    path
                )
        ) {

            tag =
                NbtIo.readCompressed(
                    input
                );
        }

        if (tag == null) {

            throw new IllegalStateException(
                "Template NBT was empty."
            );
        }

        /*
         * Work on a fresh copy for every spawn.
         */
        Object data =
            wrapCompoundTag(
                tag.copy()
            );

        Method deleteUUIDs =
            findMethodByNameAndCount(
                data.getClass(),
                "deleteAllUUIDTags",
                0
            );

        if (deleteUUIDs != null) {

            deleteUUIDs.setAccessible(
                true
            );

            deleteUUIDs.invoke(
                data
            );
        }

        ServerLevel level =
            player.serverLevel();

        Vec3 look =
            player.getLookAngle();

        double horizontalLength =
            Math.sqrt(
                look.x * look.x +
                look.z * look.z
            );

        double forwardX;
        double forwardZ;

        if (
            horizontalLength <
                0.001
        ) {

            double yaw =
                Math.toRadians(
                    player.getYRot()
                );

            forwardX =
                -Math.sin(
                    yaw
                );

            forwardZ =
                Math.cos(
                    yaw
                );

        } else {

            forwardX =
                look.x /
                horizontalLength;

            forwardZ =
                look.z /
                horizontalLength;
        }

        /*
         * First factory test: spawn seven blocks in front of the
         * command player, on roughly the same road surface.
         */
        double spawnX =
            player.getX() +
            forwardX * 7.0;

        double spawnY =
            player.getY() +
            0.15;

        double spawnZ =
            player.getZ() +
            forwardZ * 7.0;

        setNBTDouble(
            data,
            "positionx",
            spawnX
        );

        setNBTDouble(
            data,
            "positiony",
            spawnY
        );

        setNBTDouble(
            data,
            "positionz",
            spawnZ
        );

        setNBTDouble(
            data,
            "motionx",
            0.0
        );

        setNBTDouble(
            data,
            "motiony",
            0.0
        );

        setNBTDouble(
            data,
            "motionz",
            0.0
        );

        /*
         * MTS item placement uses player yaw + 90 degrees for a
         * vehicle's internal orientation, so mirror that convention.
         */
        setNBTDouble(
            data,
            "anglesx",
            0.0
        );

        setNBTDouble(
            data,
            "anglesy",
            player.getYRot() +
                90.0
        );

        setNBTDouble(
            data,
            "anglesz",
            0.0
        );

        Object packItem =
            invokeNoArgReturning(
                data,
                "getPackItem"
            );

        if (packItem == null) {

            throw new IllegalStateException(
                "MTS could not resolve the vehicle item stored in the template."
            );
        }

        Class<?> wrapperWorldClass =
            Class.forName(
                MTS_WRAPPER_WORLD
            );

        Method getWrapperFor =
            wrapperWorldClass
                .getMethod(
                    "getWrapperFor",
                    Level.class
                );

        Object worldWrapper =
            getWrapperFor.invoke(
                null,
                level
            );

        Class<?> vehicleClass =
            Class.forName(
                MTS_VEHICLE
            );

        Class<?> baseWorldClass =
            Class.forName(
                MTS_WRAPPER_WORLD_BASE
            );

        Class<?> wrapperPlayerClass =
            Class.forName(
                MTS_WRAPPER_PLAYER
            );

        Class<?> itemVehicleClass =
            Class.forName(
                MTS_ITEM_VEHICLE
            );

        Class<?> nbtInterfaceClass =
            Class.forName(
                MTS_WRAPPER_NBT_INTERFACE
            );

        Constructor<?> constructor =
            vehicleClass
                .getConstructor(
                    baseWorldClass,
                    wrapperPlayerClass,
                    itemVehicleClass,
                    nbtInterfaceClass
                );

        Object vehicle =
            constructor.newInstance(
                worldWrapper,
                null,
                packItem,
                data
            );

        /*
         * This is MTS's own placement order:
         *
         * 1. Spawn vehicle.
         * 2. Rebuild saved parts afterward.
         *
         * The second step is the critical one that restores the saved
         * engine, wheels, seats, lightbar, and every other installed
         * part from part_# NBT entries.
         */
        Class<?> baseExistingClass =
            Class.forName(
                MTS_BASE_EXISTING
            );

        Method spawnEntity =
            worldWrapper
                .getClass()
                .getMethod(
                    "spawnEntity",
                    baseExistingClass
                );

        spawnEntity.invoke(
            worldWrapper,
            vehicle
        );

        Method addParts =
            findMethodByNameAndCount(
                vehicle.getClass(),
                "addPartsPostAddition",
                2
            );

        if (addParts == null) {

            throw new NoSuchMethodException(
                "Could not find MTS addPartsPostAddition."
            );
        }

        addParts.setAccessible(
            true
        );

        addParts.invoke(
            vehicle,
            null,
            data
        );

        Entity minecraftWrapper =
            findMinecraftWrapper(
                level,
                player,
                vehicle
            );

        if (minecraftWrapper == null) {

            throw new IllegalStateException(
                "MTS spawned the internal vehicle, but GTACore could not find its Minecraft wrapper."
            );
        }

        return new SpawnedVehicle(
            vehicle,
            minecraftWrapper
        );
    }

    private static String validateTemplateName(
        String name
    ) {

        if (
            name == null ||
            !name.matches(
                "[A-Za-z0-9_-]{1,64}"
            )
        ) {

            throw new IllegalArgumentException(
                "Template names may only contain letters, numbers, _ and -."
            );
        }

        return name;
    }

    private static Object createEmptyMTSNBT()
        throws Exception {

        Class<?> interfaceManager =
            Class.forName(
                "minecrafttransportsimulator.mcinterface.InterfaceManager"
            );

        Field coreInterface =
            interfaceManager
                .getField(
                    "coreInterface"
                );

        Object core =
            coreInterface.get(
                null
            );

        Method getNewNBT =
            core.getClass()
                .getMethod(
                    "getNewNBTWrapper"
                );

        return getNewNBT.invoke(
            core
        );
    }

    private static Object wrapCompoundTag(
        CompoundTag tag
    ) throws Exception {

        Class<?> wrapperClass =
            Class.forName(
                MTS_WRAPPER_NBT
            );

        Constructor<?> constructor =
            wrapperClass
                .getDeclaredConstructor(
                    CompoundTag.class
                );

        constructor.setAccessible(
            true
        );

        return constructor.newInstance(
            tag
        );
    }

    private static CompoundTag getCompoundTag(
        Object wrapper
    ) throws Exception {

        Field tagField =
            findField(
                wrapper.getClass(),
                "tag"
            );

        if (tagField == null) {

            throw new NoSuchFieldException(
                "Could not access MTS WrapperNBT tag."
            );
        }

        tagField.setAccessible(
            true
        );

        Object value =
            tagField.get(
                wrapper
            );

        if (
            !(value instanceof CompoundTag)
        ) {

            throw new IllegalStateException(
                "MTS WrapperNBT did not contain a CompoundTag."
            );
        }

        return (CompoundTag) value;
    }

    private static void setNBTDouble(
        Object data,
        String key,
        double value
    ) throws Exception {

        Method method =
            findMethodByNameAndCount(
                data.getClass(),
                "setDouble",
                2
            );

        if (method == null) {

            throw new NoSuchMethodException(
                "Could not find MTS IWrapperNBT.setDouble."
            );
        }

        method.setAccessible(
            true
        );

        method.invoke(
            data,
            key,
            value
        );
    }

    private static Object invokeNoArgReturning(
        Object owner,
        String methodName
    ) throws Exception {

        Method method =
            findMethodByNameAndCount(
                owner.getClass(),
                methodName,
                0
            );

        if (method == null) {

            /*
             * Default interface methods may not appear in a declared
             * method walk, so try the public method table as well.
             */
            method =
                owner.getClass()
                    .getMethod(
                        methodName
                    );
        }

        method.setAccessible(
            true
        );

        return method.invoke(
            owner
        );
    }

    private static Entity findMinecraftWrapper(
        ServerLevel level,
        ServerPlayer player,
        Object internalVehicle
    ) {

        List<Entity> nearby =
            level.getEntities(
                player,
                player.getBoundingBox()
                    .inflate(
                        16.0
                    ),
                entity ->
                    entity.getClass()
                        .getName()
                        .equals(
                            "mcinterface1201.BuilderEntityExisting"
                        )
            );

        for (
            Entity wrapper :
            nearby
        ) {

            try {

                Field field =
                    findField(
                        wrapper.getClass(),
                        "entity"
                    );

                if (field == null) {
                    continue;
                }

                field.setAccessible(
                    true
                );

                if (
                    field.get(
                        wrapper
                    ) ==
                        internalVehicle
                ) {

                    return wrapper;
                }

            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private static Method findMethodByNameAndCount(
        Class<?> clazz,
        String name,
        int parameterCount
    ) {

        Class<?> current =
            clazz;

        while (
            current != null
        ) {

            for (
                Method method :
                current.getDeclaredMethods()
            ) {

                if (
                    method.getName()
                        .equals(
                            name
                        ) &&
                    method.getParameterCount() ==
                        parameterCount
                ) {

                    return method;
                }
            }

            current =
                current.getSuperclass();
        }

        /*
         * Include inherited default-interface methods.
         */
        for (
            Method method :
            clazz.getMethods()
        ) {

            if (
                method.getName()
                    .equals(
                        name
                    ) &&
                method.getParameterCount() ==
                    parameterCount
            ) {

                return method;
            }
        }

        return null;
    }

    private static Field findField(
        Class<?> clazz,
        String name
    ) {

        Class<?> current =
            clazz;

        while (
            current != null
        ) {

            try {

                return current
                    .getDeclaredField(
                        name
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

    static final class SpawnedVehicle {

        final Object internal;
        final Entity wrapper;

        private SpawnedVehicle(
            Object internal,
            Entity wrapper
        ) {

            this.internal =
                internal;

            this.wrapper =
                wrapper;
        }
    }
}
