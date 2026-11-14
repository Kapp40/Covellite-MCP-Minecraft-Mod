package com.covelliteai.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Server-side A* over walkable "feet" positions, inspired by Baritone.
 *
 * <p>A node is the block the companion's feet occupy. Moves: 4 cardinal and 4
 * diagonal steps, one-block step-ups (jump), drops of up to {@value #MAX_DROP}
 * blocks and vertical movement on climbable blocks. Lava, fire, cobwebs, berry
 * bushes, magma and unloaded chunks are never entered; water is allowed at a
 * higher cost, and cells next to lava or fire are penalised so the companion
 * does not hug a hazard.</p>
 *
 * <p>Wooden doors and fence gates count as passable at a higher cost: the
 * companion opens them while walking ({@link #openPassage}). Iron doors stay
 * solid. Cells passed in {@code avoid} — obstacles remembered by the caller —
 * are never expanded and are penalised when adjacent.</p>
 *
 * <p>The search is capped by {@code maxNodes}; when the goal is not reached it
 * returns the path to the explored node closest to the goal, so callers can
 * walk it and replan. Must run on the server thread; it only reads the world,
 * except for {@link #openPassage} which is an explicit interaction.</p>
 */
public final class Pathfinder {

    /** {@code path} starts at the start block; {@code reached} is true when the goal predicate matched. */
    public record Result(List<BlockPos> path, boolean reached, int explored) {}

    private record Move(BlockPos pos, double cost) {}

    private static final int MAX_DROP = 3;
    private static final double HEURISTIC_WEIGHT = 1.2D;
    private static final double DOOR_COST = 2.0D;
    private static final double CLIMB_UP_COST = 2.6D;
    private static final double HAZARD_COST = 1.4D;
    private static final double AVOID_NEAR_COST = 1.0D;
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static final class Node {
        final BlockPos pos;
        final Node parent;
        final double g;
        final double h;
        boolean closed;

        Node(BlockPos pos, Node parent, double g, double h) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.h = h;
        }

        double f() {
            return this.g + HEURISTIC_WEIGHT * this.h;
        }
    }

    private final ServerLevel level;
    private final Map<Long, BlockState> cache = new HashMap<>();
    private Set<Long> avoid = Set.of();
    private double targetX;
    private double targetY;
    private double targetZ;

    private Pathfinder(ServerLevel level) {
        this.level = level;
    }

    /**
     * @param goal      true for any node that counts as arrived
     * @param heuristic world point the search leans towards
     * @param maxNodes  expansion cap (a few thousand is plenty and keeps the tick cheap)
     */
    public static Result find(ServerLevel level, BlockPos start, Predicate<BlockPos> goal,
                              Vec3 heuristic, int maxNodes) {
        return find(level, start, goal, heuristic, maxNodes, Set.of());
    }

    /**
     * Same search, but never routing through the given cells (packed
     * {@link BlockPos#asLong()}). Used to remember obstacles that blocked a
     * previous attempt.
     */
    public static Result find(ServerLevel level, BlockPos start, Predicate<BlockPos> goal,
                              Vec3 heuristic, int maxNodes, Set<Long> avoid) {
        Pathfinder finder = new Pathfinder(level);
        finder.targetX = heuristic.x;
        finder.targetY = heuristic.y;
        finder.targetZ = heuristic.z;
        finder.avoid = avoid == null ? Set.of() : new HashSet<>(avoid);
        return finder.search(start.immutable(), goal, maxNodes);
    }

    /**
     * Opens a wooden door or fence gate so the companion can walk through it.
     * Iron doors are deliberately ignored: they need a button or lever.
     *
     * @return true when a block was actually opened
     */
    public static boolean openPassage(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.IRON_DOOR) || state.is(Blocks.IRON_TRAPDOOR)) {
            return false;
        }
        if (state.getBlock() instanceof DoorBlock && !state.getValue(DoorBlock.OPEN)) {
            level.setBlock(pos, state.setValue(DoorBlock.OPEN, Boolean.TRUE), Block.UPDATE_ALL);
            return true;
        }
        if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
            level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, Boolean.TRUE), Block.UPDATE_ALL);
            return true;
        }
        return false;
    }

    private Result search(BlockPos start, Predicate<BlockPos> goal, int maxNodes) {
        Map<Long, Node> nodes = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Node root = new Node(start, null, 0.0D, heuristic(start));
        nodes.put(start.asLong(), root);
        open.add(root);
        Node best = root;
        int explored = 0;

        while (!open.isEmpty() && explored < maxNodes) {
            Node current = open.poll();
            if (current.closed) {
                continue;
            }
            current.closed = true;
            explored++;
            if (goal.test(current.pos)) {
                return new Result(trace(current), true, explored);
            }
            if (current.h < best.h) {
                best = current;
            }
            for (Move move : neighbors(current.pos)) {
                long key = move.pos().asLong();
                double g = current.g + move.cost();
                Node known = nodes.get(key);
                if (known != null) {
                    if (known.closed || g >= known.g) {
                        continue;
                    }
                    // Cheaper route found: retire the stale queue entry.
                    known.closed = true;
                }
                Node next = new Node(move.pos(), current, g, heuristic(move.pos()));
                nodes.put(key, next);
                open.add(next);
            }
        }
        return new Result(trace(best), false, explored);
    }

    private static List<BlockPos> trace(Node end) {
        List<BlockPos> path = new ArrayList<>();
        for (Node node = end; node != null; node = node.parent) {
            path.add(node.pos);
        }
        Collections.reverse(path);
        return path;
    }

    private double heuristic(BlockPos pos) {
        double dx = Math.abs(pos.getX() + 0.5D - this.targetX);
        double dz = Math.abs(pos.getZ() + 0.5D - this.targetZ);
        double dy = Math.abs(pos.getY() - this.targetY);
        return Math.max(dx, dz) + 0.414D * Math.min(dx, dz) + dy * 0.5D;
    }

    // ------------------------------------------------------------------
    // Moves
    // ------------------------------------------------------------------

    private List<Move> neighbors(BlockPos pos) {
        List<Move> moves = new ArrayList<>(16);
        for (int[] d : CARDINAL) {
            BlockPos same = pos.offset(d[0], 0, d[1]);
            if (isAvoided(same)) {
                continue;
            }
            if (standable(same)) {
                moves.add(new Move(same, 1.0D + extraCost(same)));
                continue;
            }
            if (!passable(same)) {
                // Solid in the way: try to jump onto it.
                BlockPos up = same.above();
                if (!isAvoided(up) && passable(pos.above(2)) && standable(up)) {
                    moves.add(new Move(up, 2.0D + extraCost(up)));
                }
                continue;
            }
            if (!passable(same.above())) {
                continue;
            }
            // Open air without ground: walk off the edge if the fall is short.
            for (int k = 1; k <= MAX_DROP; k++) {
                BlockPos cell = same.below(k);
                if (isAvoided(cell)) {
                    break;
                }
                if (standable(cell)) {
                    moves.add(new Move(cell, 1.0D + 0.6D * k + extraCost(cell)));
                    break;
                }
                if (!passable(cell)) {
                    break;
                }
            }
        }
        for (int[] d : DIAGONAL) {
            BlockPos a = pos.offset(d[0], 0, 0);
            BlockPos b = pos.offset(0, 0, d[1]);
            if (!(passable(a) && passable(a.above()) && passable(b) && passable(b.above()))) {
                continue;
            }
            BlockPos next = pos.offset(d[0], 0, d[1]);
            if (isAvoided(next)) {
                continue;
            }
            if (standable(next)) {
                moves.add(new Move(next, 1.4142D + extraCost(next)));
            }
        }

        // Vertical movement on ladders, vines and scaffolding.
        boolean climbHere = climbable(pos) || climbable(pos.above());
        if (climbHere) {
            BlockPos up = pos.above();
            if (!isAvoided(up) && passable(up) && passable(up.above())) {
                moves.add(new Move(up, CLIMB_UP_COST + extraCost(up)));
            }
            BlockPos down = pos.below();
            if (!isAvoided(down) && passable(down)) {
                moves.add(new Move(down, 1.2D + extraCost(down)));
            }
        }
        return moves;
    }

    private boolean isAvoided(BlockPos pos) {
        return !this.avoid.isEmpty() && this.avoid.contains(pos.asLong());
    }

    private boolean nearAvoided(BlockPos pos) {
        if (this.avoid.isEmpty()) {
            return false;
        }
        for (int[] d : CARDINAL) {
            if (this.avoid.contains(pos.offset(d[0], 0, d[1]).asLong())) {
                return true;
            }
        }
        return false;
    }

    private double extraCost(BlockPos pos) {
        double cost = waterCost(pos);
        BlockState state = state(pos);
        if (openable(state)) {
            cost += DOOR_COST;
        }
        if (climbable(pos)) {
            cost += 0.4D;
        }
        if (hazardAdjacent(pos)) {
            cost += HAZARD_COST;
        }
        if (nearAvoided(pos)) {
            cost += AVOID_NEAR_COST;
        }
        return cost;
    }

    private double waterCost(BlockPos pos) {
        return inWater(pos) ? 1.0D : 0.0D;
    }

    // ------------------------------------------------------------------
    // World queries (cached per search)
    // ------------------------------------------------------------------

    private BlockState state(BlockPos pos) {
        Long key = pos.asLong();
        BlockState cached = this.cache.get(key);
        if (cached == null) {
            cached = this.level.getBlockState(pos);
            this.cache.put(key, cached);
        }
        return cached;
    }

    private boolean inBounds(BlockPos pos) {
        return pos.getY() >= this.level.getMinBuildHeight()
                && pos.getY() < this.level.getMaxBuildHeight()
                && this.level.isLoaded(pos);
    }

    /**
     * A cell the body can occupy: no collision and nothing that hurts. A closed
     * wooden door or fence gate counts as passable because the companion opens
     * it before walking through.
     */
    private boolean passable(BlockPos pos) {
        if (!inBounds(pos)) {
            return false;
        }
        BlockState state = state(pos);
        if (!state.getCollisionShape(this.level, pos).isEmpty() && !openable(state)) {
            return false;
        }
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return false;
        }
        return !harmful(state);
    }

    private static boolean harmful(BlockState state) {
        return state.is(BlockTags.FIRE) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.COBWEB) || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK);
    }

    /** Wooden door or fence gate that a player can simply open. */
    private static boolean openable(BlockState state) {
        if (state.is(Blocks.IRON_DOOR) || state.is(Blocks.IRON_TRAPDOOR)) {
            return false;
        }
        if (state.getBlock() instanceof DoorBlock) {
            return !state.getValue(DoorBlock.OPEN);
        }
        if (state.getBlock() instanceof FenceGateBlock) {
            return !state.getValue(FenceGateBlock.OPEN);
        }
        return false;
    }

    private boolean climbable(BlockPos pos) {
        return inBounds(pos) && state(pos).is(BlockTags.CLIMBABLE);
    }

    private boolean inWater(BlockPos pos) {
        return state(pos).getFluidState().is(FluidTags.WATER);
    }

    /** True when a neighbouring cell holds lava or fire: standing here is risky. */
    private boolean hazardAdjacent(BlockPos pos) {
        for (int[] d : CARDINAL) {
            BlockPos side = pos.offset(d[0], 0, d[1]);
            if (!inBounds(side)) {
                continue;
            }
            BlockState state = state(side);
            if (state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE)
                    || state.is(Blocks.MAGMA_BLOCK)) {
                return true;
            }
        }
        return false;
    }

    /** Solid, full-height-ish floor (no fences, no hazards). */
    private boolean solidGround(BlockPos below) {
        if (!inBounds(below)) {
            return false;
        }
        BlockState state = state(below);
        VoxelShape shape = state.getCollisionShape(this.level, below);
        if (shape.isEmpty() || harmful(state) || state.is(BlockTags.CAMPFIRES)) {
            return false;
        }
        double top = shape.max(Direction.Axis.Y);
        return top >= 0.5D && top <= 1.0D;
    }

    private boolean standable(BlockPos pos) {
        return passable(pos) && passable(pos.above())
                && (solidGround(pos.below()) || inWater(pos));
    }
}
