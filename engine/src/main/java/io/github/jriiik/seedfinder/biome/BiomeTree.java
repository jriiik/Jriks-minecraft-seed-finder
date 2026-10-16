package io.github.jriiik.seedfinder.biome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Minecraft's {@code Climate.RTree}: the search tree that finds the biome entry closest to a climate point. It is
 * built exactly as the game builds it, because when two entries are equally close the game keeps the one it meets
 * first in the tree (or the one it found last time), so the tree's shape decides ties.
 */
final class BiomeTree {

    static final int DIMENSIONS = 7;

    private final Node root;
    /** The leaves by entry index. */
    private final Node[] leaves;

    /**
     * @param min, max        per entry, the 7 bounds (6 climate parameters and the offset, as a point), in the list's order
     * @param childrenPerNode the most children a node of the game's tree has (its {@code CHILDREN_PER_NODE}, which
     *                        differs between versions)
     */
    BiomeTree(long[][] min, long[][] max, int childrenPerNode) {
        if (childrenPerNode < 2) {
            throw new IllegalArgumentException("a tree node needs at least 2 children, not " + childrenPerNode);
        }
        List<Node> leafList = new ArrayList<>();
        for (int i = 0; i < min.length; i++) {
            leafList.add(new Node(min[i], max[i], i));
        }
        root = build(leafList, childrenPerNode);
        this.leaves = new Node[min.length];
        collect(root, this.leaves);
    }

    /**
     * The index of the closest entry, as {@code RTree.search} finds it.
     *
     * @param previous the entry this search should keep if nothing is strictly closer (the game's last result on
     *                 this thread), or -1 for none
     */
    int search(long[] target, int previous) {
        return root.search(target, previous < 0 ? null : leaves[previous]).entry;
    }

    private static void collect(Node node, Node[] into) {
        if (node.children == null) {
            into[node.entry] = node;
        } else {
            for (Node child : node.children) {
                collect(child, into);
            }
        }
    }

    /** The squared distance from a point to an entry's box, as {@code ParameterPoint.fitness} (7 dimensions). */
    static long distance(long[] min, long[] max, long[] target) {
        long distance = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            long above = target[i] - max[i];
            long below = min[i] - target[i];
            long d = above > 0 ? above : Math.max(below, 0);
            distance += d * d;
        }
        return distance;
    }

    private static Node build(List<Node> children, int childrenPerNode) {
        if (children.size() == 1) {
            return children.get(0);
        }
        if (children.size() <= childrenPerNode) {
            children.sort(Comparator.comparingLong(node -> {
                long magnitude = 0;
                for (int d = 0; d < DIMENSIONS; d++) {
                    magnitude += Math.abs(node.center(d));
                }
                return magnitude;
            }));
            return new Node(children);
        }
        long minCost = Long.MAX_VALUE;
        int minDimension = -1;
        List<Node> minBuckets = null;
        for (int d = 0; d < DIMENSIONS; d++) {
            children.sort(order(d, false));
            List<Node> buckets = bucketize(children, childrenPerNode);
            long cost = 0;
            for (Node bucket : buckets) {
                for (int k = 0; k < DIMENSIONS; k++) {
                    cost += Math.abs(bucket.max[k] - bucket.min[k]);
                }
            }
            if (minCost > cost) {
                minCost = cost;
                minDimension = d;
                minBuckets = buckets;
            }
        }
        minBuckets.sort(order(minDimension, true));
        List<Node> subtrees = new ArrayList<>();
        for (Node bucket : minBuckets) {
            subtrees.add(build(new ArrayList<>(Arrays.asList(bucket.children)), childrenPerNode));
        }
        return new Node(subtrees);
    }

    /** By the center in one dimension, then in the following ones (wrapping around), as the game sorts. */
    private static Comparator<Node> order(int dimension, boolean absolute) {
        Comparator<Node> comparator = null;
        for (int k = 0; k < DIMENSIONS; k++) {
            int d = (dimension + k) % DIMENSIONS;
            Comparator<Node> next = Comparator.comparingLong(node -> absolute ? Math.abs(node.center(d)) : node.center(d));
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        return comparator;
    }

    private static List<Node> bucketize(List<Node> nodes, int childrenPerNode) {
        List<Node> buckets = new ArrayList<>();
        List<Node> children = new ArrayList<>();
        int expected = (int) Math.pow(childrenPerNode, Math.floor(Math.log(nodes.size() - 0.01) / Math.log(childrenPerNode)));
        for (Node node : nodes) {
            children.add(node);
            if (children.size() >= expected) {
                buckets.add(new Node(children));
                children = new ArrayList<>();
            }
        }
        if (!children.isEmpty()) {
            buckets.add(new Node(children));
        }
        return buckets;
    }

    private static final class Node {

        final long[] min = new long[DIMENSIONS];
        final long[] max = new long[DIMENSIONS];
        /** Null for a leaf. */
        final Node[] children;
        /** The entry of a leaf; -1 for an inner node. */
        final int entry;

        Node(long[] min, long[] max, int entry) {
            System.arraycopy(min, 0, this.min, 0, DIMENSIONS);
            System.arraycopy(max, 0, this.max, 0, DIMENSIONS);
            this.children = null;
            this.entry = entry;
        }

        Node(List<Node> children) {
            this.children = children.toArray(new Node[0]);
            this.entry = -1;
            for (int d = 0; d < DIMENSIONS; d++) {
                long low = Long.MAX_VALUE;
                long high = Long.MIN_VALUE;
                for (Node child : this.children) {
                    low = Math.min(low, child.min[d]);
                    high = Math.max(high, child.max[d]);
                }
                min[d] = low;
                max[d] = high;
            }
        }

        long center(int d) {
            return (min[d] + max[d]) / 2L;
        }

        Node search(long[] target, Node candidate) {
            if (children == null) {
                return this;
            }
            long minDistance = candidate == null ? Long.MAX_VALUE : distance(candidate.min, candidate.max, target);
            Node closest = candidate;
            for (Node child : children) {
                long childDistance = distance(child.min, child.max, target);
                if (minDistance > childDistance) {
                    Node leaf = child.search(target, closest);
                    long leafDistance = child == leaf ? childDistance : distance(leaf.min, leaf.max, target);
                    if (minDistance > leafDistance) {
                        minDistance = leafDistance;
                        closest = leaf;
                    }
                }
            }
            return closest;
        }
    }
}
