package com.gtacore;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Persistent road graph + A* pathfinder.
 *
 * The graph is intentionally independent from MTS.  GTACore's driving
 * controller only receives the next road waypoint; steering/throttle
 * remain in the already-tested vehicle controller.
 *
 * File format:
 *
 *   N|id|dimension|x|y|z
 *   E|nodeA|nodeB
 *
 * Roads are undirected for now.  One-way streets can be added later.
 */
public final class RoadNetworkStore {

    private static final RoadNetworkStore INSTANCE =
        new RoadNetworkStore();

    private final Map<Integer, RoadNode> nodes =
        new LinkedHashMap<>();

    private final Map<Integer, Set<Integer>> edges =
        new HashMap<>();

    private int nextNodeId = 1;
    private boolean loaded = false;

    private RoadNetworkStore() {
    }

    public static RoadNetworkStore get() {

        INSTANCE.ensureLoaded();

        return INSTANCE;
    }

    public synchronized RoadNode addNode(
        String dimension,
        double x,
        double y,
        double z
    ) {

        ensureLoaded();

        RoadNode node =
            new RoadNode(
                nextNodeId++,
                dimension,
                x,
                y,
                z
            );

        nodes.put(
            node.id,
            node
        );

        edges.putIfAbsent(
            node.id,
            new HashSet<>()
        );

        return node;
    }

    public synchronized boolean connect(
        int nodeA,
        int nodeB
    ) {

        ensureLoaded();

        if (
            nodeA == nodeB ||
            !nodes.containsKey(nodeA) ||
            !nodes.containsKey(nodeB)
        ) {

            return false;
        }

        RoadNode a =
            nodes.get(
                nodeA
            );

        RoadNode b =
            nodes.get(
                nodeB
            );

        if (
            !a.dimension.equals(
                b.dimension
            )
        ) {

            return false;
        }

        edges.computeIfAbsent(
            nodeA,
            ignored ->
                new HashSet<>()
        ).add(
            nodeB
        );

        edges.computeIfAbsent(
            nodeB,
            ignored ->
                new HashSet<>()
        ).add(
            nodeA
        );

        return true;
    }

    public synchronized RoadNode getNode(
        int id
    ) {

        ensureLoaded();

        return nodes.get(
            id
        );
    }

    public synchronized Collection<RoadNode>
        getNodesForDimension(
            String dimension
        ) {

        ensureLoaded();

        List<RoadNode> result =
            new ArrayList<>();

        for (
            RoadNode node :
            nodes.values()
        ) {

            if (
                node.dimension.equals(
                    dimension
                )
            ) {

                result.add(
                    node
                );
            }
        }

        return result;
    }

    public synchronized Set<Integer> getNeighborIds(
        int nodeId
    ) {

        ensureLoaded();

        Set<Integer> neighbors =
            edges.get(
                nodeId
            );

        if (neighbors == null) {

            return Collections.emptySet();
        }

        return new HashSet<>(
            neighbors
        );
    }

    public synchronized RoadNode findNearestNode(
        String dimension,
        double x,
        double y,
        double z,
        double maximumDistance
    ) {

        ensureLoaded();

        RoadNode best = null;

        double bestDistanceSquared =
            maximumDistance *
                maximumDistance;

        for (
            RoadNode node :
            nodes.values()
        ) {

            if (
                !node.dimension.equals(
                    dimension
                )
            ) {

                continue;
            }

            double dx =
                node.x -
                x;

            double dy =
                node.y -
                y;

            double dz =
                node.z -
                z;

            double distanceSquared =
                dx * dx +
                dy * dy +
                dz * dz;

            if (
                distanceSquared <=
                    bestDistanceSquared
            ) {

                bestDistanceSquared =
                    distanceSquared;

                best =
                    node;
            }
        }

        return best;
    }

    /**
     * Standard A* over the road graph.
     */
    public synchronized List<Integer> findPath(
        int startId,
        int goalId
    ) {

        return findPathAvoiding(
            startId,
            goalId,
            -1
        );
    }

    /**
     * A* with one blocked node.
     *
     * GTACore uses this for the first segment of a pursuit route: if a
     * police car has just passed the target, we can prefer a forward
     * road connection and prevent the path from immediately reversing
     * through the node it just came from.
     */
    public synchronized List<Integer> findPathAvoiding(
        int startId,
        int goalId,
        int blockedNodeId
    ) {

        ensureLoaded();

        RoadNode start =
            nodes.get(
                startId
            );

        RoadNode goal =
            nodes.get(
                goalId
            );

        if (
            start == null ||
            goal == null ||
            !start.dimension.equals(
                goal.dimension
            )
        ) {

            return Collections.emptyList();
        }

        if (startId == goalId) {

            List<Integer> same =
                new ArrayList<>();

            same.add(
                startId
            );

            return same;
        }

        PriorityQueue<SearchNode> open =
            new PriorityQueue<>(
                Comparator.comparingDouble(
                    node ->
                        node.fScore
                )
            );

        Map<Integer, Double> gScore =
            new HashMap<>();

        Map<Integer, Integer> cameFrom =
            new HashMap<>();

        Set<Integer> closed =
            new HashSet<>();

        gScore.put(
            startId,
            0.0
        );

        open.add(
            new SearchNode(
                startId,
                heuristic(
                    start,
                    goal
                )
            )
        );

        while (!open.isEmpty()) {

            SearchNode currentSearch =
                open.poll();

            int currentId =
                currentSearch.id;

            if (
                !closed.add(
                    currentId
                )
            ) {

                continue;
            }

            if (
                currentId ==
                    goalId
            ) {

                return reconstructPath(
                    cameFrom,
                    currentId
                );
            }

            Set<Integer> neighbors =
                edges.get(
                    currentId
                );

            if (neighbors == null) {

                continue;
            }

            RoadNode current =
                nodes.get(
                    currentId
                );

            for (
                int neighborId :
                neighbors
            ) {

                if (
                    neighborId ==
                        blockedNodeId
                ) {

                    continue;
                }

                RoadNode neighbor =
                    nodes.get(
                        neighborId
                    );

                if (
                    neighbor == null ||
                    !neighbor.dimension.equals(
                        start.dimension
                    )
                ) {

                    continue;
                }

                double tentative =
                    gScore.getOrDefault(
                        currentId,
                        Double.POSITIVE_INFINITY
                    ) +
                    distance(
                        current,
                        neighbor
                    );

                if (
                    tentative <
                        gScore.getOrDefault(
                            neighborId,
                            Double.POSITIVE_INFINITY
                        )
                ) {

                    cameFrom.put(
                        neighborId,
                        currentId
                    );

                    gScore.put(
                        neighborId,
                        tentative
                    );

                    double f =
                        tentative +
                        heuristic(
                            neighbor,
                            goal
                        );

                    open.add(
                        new SearchNode(
                            neighborId,
                            f
                        )
                    );
                }
            }
        }

        return Collections.emptyList();
    }

    public synchronized double getPathLength(
        List<Integer> path
    ) {

        ensureLoaded();

        if (
            path == null ||
            path.size() < 2
        ) {

            return 0.0;
        }

        double total =
            0.0;

        for (
            int i = 1;
            i < path.size();
            i++
        ) {

            RoadNode a =
                nodes.get(
                    path.get(
                        i - 1
                    )
                );

            RoadNode b =
                nodes.get(
                    path.get(
                        i
                    )
                );

            if (
                a == null ||
                b == null
            ) {

                return Double.POSITIVE_INFINITY;
            }

            total +=
                distance(
                    a,
                    b
                );
        }

        return total;
    }

    public synchronized int size() {

        ensureLoaded();

        return nodes.size();
    }

    public synchronized int connectionCount() {

        ensureLoaded();

        int directedCount =
            0;

        for (
            Set<Integer> neighbors :
            edges.values()
        ) {

            directedCount +=
                neighbors.size();
        }

        return directedCount / 2;
    }

    public synchronized void clear() {

        nodes.clear();
        edges.clear();
        nextNodeId = 1;
        loaded = true;
    }

    public synchronized void save()
        throws IOException {

        ensureLoaded();

        Path path =
            getRoadFile();

        Files.createDirectories(
            path.getParent()
        );

        List<String> lines =
            new ArrayList<>();

        lines.add(
            "# GTACore road network v1"
        );

        for (
            RoadNode node :
            nodes.values()
        ) {

            lines.add(
                "N|"
                    + node.id
                    + "|"
                    + node.dimension
                    + "|"
                    + node.x
                    + "|"
                    + node.y
                    + "|"
                    + node.z
            );
        }

        Set<String> writtenEdges =
            new HashSet<>();

        for (
            Map.Entry<Integer, Set<Integer>> entry :
            edges.entrySet()
        ) {

            int a =
                entry.getKey();

            for (
                int b :
                entry.getValue()
            ) {

                int low =
                    Math.min(
                        a,
                        b
                    );

                int high =
                    Math.max(
                        a,
                        b
                    );

                String key =
                    low +
                    ":" +
                    high;

                if (
                    writtenEdges.add(
                        key
                    )
                ) {

                    lines.add(
                        "E|"
                            + low
                            + "|"
                            + high
                    );
                }
            }
        }

        Files.write(
            path,
            lines,
            StandardCharsets.UTF_8
        );
    }

    public Path getRoadFile() {

        return FMLPaths.CONFIGDIR
            .get()
            .resolve(
                "gtacore"
            )
            .resolve(
                "roads.txt"
            );
    }

    private synchronized void ensureLoaded() {

        if (loaded) {
            return;
        }

        loaded = true;

        Path path =
            getRoadFile();

        if (
            !Files.exists(
                path
            )
        ) {

            return;
        }

        try {

            List<String> lines =
                Files.readAllLines(
                    path,
                    StandardCharsets.UTF_8
                );

            List<int[]> pendingEdges =
                new ArrayList<>();

            int highestId = 0;

            for (
                String raw :
                lines
            ) {

                String line =
                    raw.trim();

                if (
                    line.isEmpty() ||
                    line.startsWith(
                        "#"
                    )
                ) {

                    continue;
                }

                String[] parts =
                    line.split(
                        "\\|"
                    );

                if (
                    parts.length >= 6 &&
                    "N".equals(
                        parts[0]
                    )
                ) {

                    int id =
                        Integer.parseInt(
                            parts[1]
                        );

                    RoadNode node =
                        new RoadNode(
                            id,
                            parts[2],
                            Double.parseDouble(
                                parts[3]
                            ),
                            Double.parseDouble(
                                parts[4]
                            ),
                            Double.parseDouble(
                                parts[5]
                            )
                        );

                    nodes.put(
                        id,
                        node
                    );

                    edges.putIfAbsent(
                        id,
                        new HashSet<>()
                    );

                    highestId =
                        Math.max(
                            highestId,
                            id
                        );

                } else if (
                    parts.length >= 3 &&
                    "E".equals(
                        parts[0]
                    )
                ) {

                    pendingEdges.add(
                        new int[] {
                            Integer.parseInt(
                                parts[1]
                            ),
                            Integer.parseInt(
                                parts[2]
                            )
                        }
                    );
                }
            }

            nextNodeId =
                highestId + 1;

            for (
                int[] edge :
                pendingEdges
            ) {

                connect(
                    edge[0],
                    edge[1]
                );
            }

        } catch (Exception e) {

            System.err.println(
                "[GTACore] Could not load road network: "
                    + e.getMessage()
            );

            e.printStackTrace();

            nodes.clear();
            edges.clear();
            nextNodeId = 1;
        }
    }

    private List<Integer> reconstructPath(
        Map<Integer, Integer> cameFrom,
        int currentId
    ) {

        LinkedList<Integer> path =
            new LinkedList<>();

        path.addFirst(
            currentId
        );

        while (
            cameFrom.containsKey(
                currentId
            )
        ) {

            currentId =
                cameFrom.get(
                    currentId
                );

            path.addFirst(
                currentId
            );
        }

        return path;
    }

    private static double heuristic(
        RoadNode a,
        RoadNode b
    ) {

        return distance(
            a,
            b
        );
    }

    private static double distance(
        RoadNode a,
        RoadNode b
    ) {

        double dx =
            a.x -
            b.x;

        double dy =
            a.y -
            b.y;

        double dz =
            a.z -
            b.z;

        return Math.sqrt(
            dx * dx +
            dy * dy +
            dz * dz
        );
    }

    private static final class SearchNode {

        private final int id;
        private final double fScore;

        private SearchNode(
            int id,
            double fScore
        ) {

            this.id =
                id;

            this.fScore =
                fScore;
        }
    }

    public static final class RoadNode {

        public final int id;
        public final String dimension;
        public final double x;
        public final double y;
        public final double z;

        private RoadNode(
            int id,
            String dimension,
            double x,
            double y,
            double z
        ) {

            this.id =
                id;

            this.dimension =
                dimension;

            this.x =
                x;

            this.y =
                y;

            this.z =
                z;
        }

        public double horizontalDistanceTo(
            double otherX,
            double otherZ
        ) {

            double dx =
                x -
                otherX;

            double dz =
                z -
                otherZ;

            return Math.sqrt(
                dx * dx +
                dz * dz
            );
        }
    }
}
