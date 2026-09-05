package com.gtacore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Forward-driving Hybrid A* for MTS police vehicles.
 *
 * Unlike a normal block grid, each search state includes position AND
 * heading. Successors are produced by physically plausible left, straight,
 * and right arcs. The output is only a waypoint list; GTACore's validated
 * MTS steering/throttle/transmission controller remains responsible for
 * moving the real vehicle.
 */
public final class HybridVehiclePathfinder {

    private static final double MOTION_STEP = 4.0;
    private static final double TURN_RADIUS = 10.0;
    private static final double TURN_ANGLE = MOTION_STEP / TURN_RADIUS;

    private static final double POSITION_CELL = 2.0;
    private static final int HEADING_BINS = 24;
    private static final double GOAL_DISTANCE = 5.5;
    private static final double MAX_SEARCH_RADIUS = 96.0;
    private static final int MAX_EXPANDED_STATES = 6500;

    private static final int VEHICLE_HALF_WIDTH_BLOCKS = 1;
    private static final int REQUIRED_CLEARANCE_BLOCKS = 3;
    private static final int MAX_GROUND_STEP = 1;
    private static final double COLLISION_SAMPLE_SPACING = 1.0;

    private HybridVehiclePathfinder() {
    }

    public static List<BlockPos> findPath(
        ServerLevel level,
        double startX,
        double startZ,
        double forwardX,
        double forwardZ,
        double goalX,
        double goalZ
    ) {
        double forwardLength =
            Math.hypot(forwardX, forwardZ);

        if (forwardLength < 0.001) {
            return Collections.emptyList();
        }

        double startHeading =
            Math.atan2(
                forwardZ / forwardLength,
                forwardX / forwardLength
            );

        int startY =
            surfaceY(level, startX, startZ);

        if (
            startY == Integer.MIN_VALUE ||
            !isDrivableAt(level, startX, startY, startZ)
        ) {
            return Collections.emptyList();
        }

        SearchState start =
            new SearchState(
                startX,
                startY,
                startZ,
                normalizeAngle(startHeading),
                0.0,
                null
            );

        StateKey startKey =
            keyFor(start);

        PriorityQueue<QueueEntry> open =
            new PriorityQueue<>(
                Comparator.comparingDouble(
                    entry -> entry.fScore
                )
            );

        Map<StateKey, Double> bestCost =
            new HashMap<>();

        Map<StateKey, SearchState> states =
            new HashMap<>();

        Set<StateKey> closed =
            new HashSet<>();

        bestCost.put(startKey, 0.0);
        states.put(startKey, start);
        open.add(
            new QueueEntry(
                startKey,
                heuristic(startX, startZ, goalX, goalZ)
            )
        );

        SearchState closest = start;
        double closestDistance =
            heuristic(startX, startZ, goalX, goalZ);

        while (
            !open.isEmpty() &&
            closed.size() < MAX_EXPANDED_STATES
        ) {
            QueueEntry entry = open.poll();

            if (!closed.add(entry.key)) {
                continue;
            }

            SearchState current =
                states.get(entry.key);

            if (current == null) {
                continue;
            }

            double distanceToGoal =
                heuristic(
                    current.x,
                    current.z,
                    goalX,
                    goalZ
                );

            if (distanceToGoal < closestDistance) {
                closestDistance = distanceToGoal;
                closest = current;
            }

            if (distanceToGoal <= GOAL_DISTANCE) {
                return reconstruct(current);
            }

            for (int steering = -1; steering <= 1; steering++) {
                SearchState next =
                    advance(
                        level,
                        current,
                        steering
                    );

                if (next == null) {
                    continue;
                }

                if (
                    heuristic(
                        next.x,
                        next.z,
                        startX,
                        startZ
                    ) > MAX_SEARCH_RADIUS
                ) {
                    continue;
                }

                StateKey nextKey =
                    keyFor(next);

                double turnCost =
                    steering == 0
                        ? 0.0
                        : 0.65;

                double tentative =
                    current.cost +
                    MOTION_STEP +
                    turnCost;

                if (
                    tentative >=
                    bestCost.getOrDefault(
                        nextKey,
                        Double.POSITIVE_INFINITY
                    )
                ) {
                    continue;
                }

                SearchState accepted =
                    new SearchState(
                        next.x,
                        next.y,
                        next.z,
                        next.heading,
                        tentative,
                        current
                    );

                bestCost.put(nextKey, tentative);
                states.put(nextKey, accepted);

                double fScore =
                    tentative +
                    heuristic(
                        next.x,
                        next.z,
                        goalX,
                        goalZ
                    );

                open.add(
                    new QueueEntry(
                        nextKey,
                        fScore
                    )
                );
            }
        }

        /*
         * A partial path is useful only when it made meaningful progress.
         * Otherwise direct pursuit remains safer than following a search
         * fragment that leads nowhere.
         */
        if (
            closest != start &&
            closestDistance + 8.0 <
                heuristic(startX, startZ, goalX, goalZ)
        ) {
            return reconstruct(closest);
        }

        return Collections.emptyList();
    }

    private static SearchState advance(
        ServerLevel level,
        SearchState current,
        int steering
    ) {
        double nextHeading =
            normalizeAngle(
                current.heading +
                steering * TURN_ANGLE
            );

        double nextX =
            current.x +
            Math.cos(nextHeading) *
                MOTION_STEP;

        double nextZ =
            current.z +
            Math.sin(nextHeading) *
                MOTION_STEP;

        int samples =
            Math.max(
                2,
                (int) Math.ceil(
                    MOTION_STEP /
                        COLLISION_SAMPLE_SPACING
                )
            );

        int previousY = current.y;

        for (int index = 1; index <= samples; index++) {
            double fraction =
                index / (double) samples;

            double sampleHeading =
                normalizeAngle(
                    current.heading +
                    steering *
                        TURN_ANGLE *
                        fraction
                );

            double radius =
                steering == 0
                    ? 0.0
                    : TURN_RADIUS;

            double sampleX;
            double sampleZ;

            if (steering == 0) {
                sampleX =
                    current.x +
                    Math.cos(current.heading) *
                        MOTION_STEP *
                        fraction;

                sampleZ =
                    current.z +
                    Math.sin(current.heading) *
                        MOTION_STEP *
                        fraction;

            } else {
                double signedRadius =
                    radius * steering;

                double centerX =
                    current.x -
                    Math.sin(current.heading) *
                        signedRadius;

                double centerZ =
                    current.z +
                    Math.cos(current.heading) *
                        signedRadius;

                sampleX =
                    centerX +
                    Math.sin(sampleHeading) *
                        signedRadius;

                sampleZ =
                    centerZ -
                    Math.cos(sampleHeading) *
                        signedRadius;
            }

            int sampleY =
                surfaceY(
                    level,
                    sampleX,
                    sampleZ
                );

            if (
                sampleY == Integer.MIN_VALUE ||
                Math.abs(sampleY - previousY) >
                    MAX_GROUND_STEP ||
                !isDrivableAt(
                    level,
                    sampleX,
                    sampleY,
                    sampleZ
                )
            ) {
                return null;
            }

            previousY = sampleY;

            if (index == samples) {
                nextX = sampleX;
                nextZ = sampleZ;
            }
        }

        return new SearchState(
            nextX,
            previousY,
            nextZ,
            nextHeading,
            0.0,
            null
        );
    }

    private static boolean isDrivableAt(
        ServerLevel level,
        double centerX,
        int centerY,
        double centerZ
    ) {
        int blockX =
            (int) Math.floor(centerX);

        int blockZ =
            (int) Math.floor(centerZ);

        for (
            int offsetX = -VEHICLE_HALF_WIDTH_BLOCKS;
            offsetX <= VEHICLE_HALF_WIDTH_BLOCKS;
            offsetX++
        ) {
            for (
                int offsetZ = -VEHICLE_HALF_WIDTH_BLOCKS;
                offsetZ <= VEHICLE_HALF_WIDTH_BLOCKS;
                offsetZ++
            ) {
                int columnX = blockX + offsetX;
                int columnZ = blockZ + offsetZ;

                int columnY =
                    level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        columnX,
                        columnZ
                    );

                if (
                    Math.abs(columnY - centerY) >
                        MAX_GROUND_STEP
                ) {
                    return false;
                }

                BlockPos ground =
                    new BlockPos(
                        columnX,
                        columnY - 1,
                        columnZ
                    );

                if (
                    level.getBlockState(ground).isAir() ||
                    !level.getFluidState(ground).isEmpty()
                ) {
                    return false;
                }

                for (
                    int clearance = 0;
                    clearance <
                        REQUIRED_CLEARANCE_BLOCKS;
                    clearance++
                ) {
                    BlockPos space =
                        new BlockPos(
                            columnX,
                            columnY + clearance,
                            columnZ
                        );

                    if (
                        !level.getBlockState(space).isAir() ||
                        !level.getFluidState(space).isEmpty()
                    ) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private static int surfaceY(
        ServerLevel level,
        double x,
        double z
    ) {
        int blockX =
            (int) Math.floor(x);

        int blockZ =
            (int) Math.floor(z);

        int y =
            level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                blockX,
                blockZ
            );

        return y <= level.getMinBuildHeight()
            ? Integer.MIN_VALUE
            : y;
    }

    private static List<BlockPos> reconstruct(
        SearchState goal
    ) {
        LinkedList<BlockPos> reversed =
            new LinkedList<>();

        SearchState cursor = goal;

        while (
            cursor != null &&
            cursor.parent != null
        ) {
            BlockPos point =
                BlockPos.containing(
                    cursor.x,
                    cursor.y,
                    cursor.z
                );

            if (
                reversed.isEmpty() ||
                !reversed.getFirst().equals(point)
            ) {
                reversed.addFirst(point);
            }

            cursor = cursor.parent;
        }

        return new ArrayList<>(reversed);
    }

    private static StateKey keyFor(
        SearchState state
    ) {
        int cellX =
            (int) Math.round(
                state.x / POSITION_CELL
            );

        int cellZ =
            (int) Math.round(
                state.z / POSITION_CELL
            );

        double normalized =
            normalizeAngle(state.heading);

        int headingBin =
            Math.floorMod(
                (int) Math.round(
                    normalized /
                    (Math.PI * 2.0) *
                    HEADING_BINS
                ),
                HEADING_BINS
            );

        return new StateKey(
            cellX,
            cellZ,
            headingBin
        );
    }

    private static double heuristic(
        double x,
        double z,
        double goalX,
        double goalZ
    ) {
        return Math.hypot(
            goalX - x,
            goalZ - z
        );
    }

    private static double normalizeAngle(
        double angle
    ) {
        double result =
            angle %
            (Math.PI * 2.0);

        if (result < 0.0) {
            result += Math.PI * 2.0;
        }

        return result;
    }

    private static final class SearchState {
        private final double x;
        private final int y;
        private final double z;
        private final double heading;
        private final double cost;
        private final SearchState parent;

        private SearchState(
            double x,
            int y,
            double z,
            double heading,
            double cost,
            SearchState parent
        ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.heading = heading;
            this.cost = cost;
            this.parent = parent;
        }
    }

    private static final class QueueEntry {
        private final StateKey key;
        private final double fScore;

        private QueueEntry(
            StateKey key,
            double fScore
        ) {
            this.key = key;
            this.fScore = fScore;
        }
    }

    private static final class StateKey {
        private final int x;
        private final int z;
        private final int heading;

        private StateKey(
            int x,
            int z,
            int heading
        ) {
            this.x = x;
            this.z = z;
            this.heading = heading;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }

            if (!(object instanceof StateKey)) {
                return false;
            }

            StateKey other =
                (StateKey) object;

            return x == other.x &&
                z == other.z &&
                heading == other.heading;
        }

        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + z;
            result = 31 * result + heading;
            return result;
        }
    }
}
