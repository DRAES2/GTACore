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
 * Builds a temporary A* navigation grid from the blocks that are currently
 * loaded around a police cruiser. Nothing is recorded by hand or saved.
 *
 * This class only chooses waypoints. GTACore's existing MTS controller still
 * owns steering, throttle, braking, hard turns, and transmission.
 */
public final class TerrainPathfinder {

    private static final int GRID_STEP = 4;
    private static final int MAX_SEARCH_DISTANCE = 96;
    private static final int MAX_VISITED_NODES = 5000;
    private static final int REQUIRED_CLEARANCE = 3;
    private static final int FOOTPRINT_RADIUS = 1;
    private static final int MAX_STEP_HEIGHT = 1;

    private static final int[][] DIRECTIONS = {
        { GRID_STEP, 0 },
        {-GRID_STEP, 0 },
        {0,  GRID_STEP },
        {0, -GRID_STEP },
        { GRID_STEP,  GRID_STEP },
        { GRID_STEP, -GRID_STEP },
        {-GRID_STEP,  GRID_STEP },
        {-GRID_STEP, -GRID_STEP }
    };

    private TerrainPathfinder() {
    }

    public static List<BlockPos> findPath(
        ServerLevel level,
        double startX,
        double startZ,
        double goalX,
        double goalZ
    ) {
        int originX = snap((int) Math.floor(startX));
        int originZ = snap((int) Math.floor(startZ));
        int goalGridX = snapRelative((int) Math.floor(goalX), originX);
        int goalGridZ = snapRelative((int) Math.floor(goalZ), originZ);

        TerrainNode start = sample(level, originX, originZ);
        TerrainNode goal = sample(level, goalGridX, goalGridZ);

        if (start == null || goal == null) {
            return Collections.emptyList();
        }

        PriorityQueue<SearchEntry> open = new PriorityQueue<>(
            Comparator.comparingDouble(entry -> entry.fScore)
        );
        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, TerrainNode> knownNodes = new HashMap<>();
        Set<Long> closed = new HashSet<>();

        long startKey = key(start.x, start.z);
        long goalKey = key(goal.x, goal.z);

        knownNodes.put(startKey, start);
        knownNodes.put(goalKey, goal);
        gScore.put(startKey, 0.0);
        open.add(new SearchEntry(startKey, heuristic(start, goal)));

        while (!open.isEmpty() && closed.size() < MAX_VISITED_NODES) {
            SearchEntry currentEntry = open.poll();
            long currentKey = currentEntry.key;

            if (!closed.add(currentKey)) {
                continue;
            }

            TerrainNode current = knownNodes.get(currentKey);
            if (currentKey == goalKey) {
                return reconstruct(cameFrom, knownNodes, currentKey);
            }

            for (int[] direction : DIRECTIONS) {
                int nextX = current.x + direction[0];
                int nextZ = current.z + direction[1];

                if (
                    horizontalDistance(nextX, nextZ, originX, originZ) >
                        MAX_SEARCH_DISTANCE ||
                    horizontalDistance(nextX, nextZ, goal.x, goal.z) >
                        MAX_SEARCH_DISTANCE
                ) {
                    continue;
                }

                TerrainNode neighbor = sample(level, nextX, nextZ);
                if (
                    neighbor == null ||
                    Math.abs(neighbor.y - current.y) > MAX_STEP_HEIGHT
                ) {
                    continue;
                }

                long neighborKey = key(neighbor.x, neighbor.z);
                knownNodes.put(neighborKey, neighbor);

                double segmentCost = Math.hypot(
                    neighbor.x - current.x,
                    neighbor.z - current.z
                );

                // Small slope cost encourages smooth, easily driven routes.
                segmentCost += Math.abs(neighbor.y - current.y) * 2.0;

                double tentative =
                    gScore.getOrDefault(currentKey, Double.POSITIVE_INFINITY) +
                    segmentCost;

                if (
                    tentative <
                    gScore.getOrDefault(neighborKey, Double.POSITIVE_INFINITY)
                ) {
                    cameFrom.put(neighborKey, currentKey);
                    gScore.put(neighborKey, tentative);
                    open.add(new SearchEntry(
                        neighborKey,
                        tentative + heuristic(neighbor, goal)
                    ));
                }
            }
        }

        return Collections.emptyList();
    }

    private static TerrainNode sample(ServerLevel level, int x, int z) {
        int y = level.getHeight(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            x,
            z
        );

        if (y <= level.getMinBuildHeight()) {
            return null;
        }

        // Approximate a three-block-wide, three-block-tall vehicle corridor.
        for (int offsetX = -FOOTPRINT_RADIUS; offsetX <= FOOTPRINT_RADIUS; offsetX++) {
            for (int offsetZ = -FOOTPRINT_RADIUS; offsetZ <= FOOTPRINT_RADIUS; offsetZ++) {
                int columnY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    x + offsetX,
                    z + offsetZ
                );

                if (Math.abs(columnY - y) > MAX_STEP_HEIGHT) {
                    return null;
                }

                BlockPos ground = new BlockPos(
                    x + offsetX,
                    columnY - 1,
                    z + offsetZ
                );

                if (
                    level.getBlockState(ground).isAir() ||
                    !level.getFluidState(ground).isEmpty()
                ) {
                    return null;
                }

                for (int clearance = 0; clearance < REQUIRED_CLEARANCE; clearance++) {
                    BlockPos space = new BlockPos(
                        x + offsetX,
                        columnY + clearance,
                        z + offsetZ
                    );

                    if (
                        !level.getBlockState(space).isAir() ||
                        !level.getFluidState(space).isEmpty()
                    ) {
                        return null;
                    }
                }
            }
        }

        return new TerrainNode(x, y, z);
    }

    private static List<BlockPos> reconstruct(
        Map<Long, Long> cameFrom,
        Map<Long, TerrainNode> knownNodes,
        long currentKey
    ) {
        LinkedList<BlockPos> result = new LinkedList<>();

        while (true) {
            TerrainNode node = knownNodes.get(currentKey);
            if (node == null) {
                return Collections.emptyList();
            }

            result.addFirst(new BlockPos(node.x, node.y, node.z));
            Long previous = cameFrom.get(currentKey);
            if (previous == null) {
                break;
            }
            currentKey = previous;
        }

        // The first point is under the cruiser and is already reached.
        if (result.size() > 1) {
            result.removeFirst();
        }

        return new ArrayList<>(result);
    }

    private static int snap(int coordinate) {
        return Math.floorDiv(coordinate, GRID_STEP) * GRID_STEP;
    }

    private static int snapRelative(int coordinate, int origin) {
        return origin +
            (int) Math.round((coordinate - origin) / (double) GRID_STEP) *
                GRID_STEP;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static double heuristic(TerrainNode a, TerrainNode b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static double horizontalDistance(
        int ax,
        int az,
        int bx,
        int bz
    ) {
        return Math.hypot(ax - bx, az - bz);
    }

    private static final class TerrainNode {
        private final int x;
        private final int y;
        private final int z;

        private TerrainNode(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class SearchEntry {
        private final long key;
        private final double fScore;

        private SearchEntry(long key, double fScore) {
            this.key = key;
            this.fScore = fScore;
        }
    }
}
