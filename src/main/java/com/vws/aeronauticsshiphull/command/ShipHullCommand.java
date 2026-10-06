package com.vws.aeronauticsshiphull.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.joml.Vector3d;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vws.aeronauticsshiphull.api.ShipCadence;
import com.vws.aeronauticsshiphull.hull.HullMode;
import com.vws.aeronauticsshiphull.hull.InteractiveCell;
import com.vws.aeronauticsshiphull.hull.ShipHull;
import com.vws.aeronauticsshiphull.hull.ShipHullManager;
import com.vws.aeronauticsshiphull.profile.HullProfiler;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

public final class ShipHullCommand {
    private ShipHullCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("shiphull")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("status").executes(ShipHullCommand::status))
                .then(Commands.literal("enable").executes(c -> setEnabled(c, true)))
                .then(Commands.literal("disable").executes(c -> setEnabled(c, false)))
                .then(Commands.literal("softsleep")
                        .then(Commands.literal("on").executes(c -> setSoftSleep(c, true)))
                        .then(Commands.literal("off").executes(c -> setSoftSleep(c, false)))
                        .executes(c -> {
                            c.getSource().sendSuccess(() -> Component.literal("softSleep=" + ShipHullManager.cheapSoftSleepEnabled()), false);
                            return 1;
                        }))
                .then(Commands.literal("phase6")
                        .then(Commands.literal("on").executes(c -> setPhase6(c, true)))
                        .then(Commands.literal("off").executes(c -> setPhase6(c, false)))
                        .executes(c -> { String l = mgr(c).phase6Status(); c.getSource().sendSuccess(() -> Component.literal(l), false); return 1; }))
                .then(Commands.literal("profile")
                        .executes(ShipHullCommand::profile)
                        .then(Commands.literal("reset").executes(c -> { HullProfiler.reset(); c.getSource().sendSuccess(() -> Component.literal("profile reset"), false); return 1; })))
                .then(Commands.literal("list").executes(ShipHullCommand::list))
                .then(Commands.literal("items")
                        .then(Commands.argument("uuid", StringArgumentType.string()).executes(ShipHullCommand::items)))
                .then(Commands.literal("rebuild")
                        .then(Commands.argument("uuid", StringArgumentType.string()).executes(ShipHullCommand::rebuild)))
                .then(Commands.literal("mode")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .then(Commands.argument("mode", StringArgumentType.word()).executes(ShipHullCommand::setMode))))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("uuid", StringArgumentType.string()).executes(ShipHullCommand::inspect)))
                .then(Commands.literal("lookup")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ShipHullCommand::lookup))))
                .then(Commands.literal("explode")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .then(Commands.literal("here")
                                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.5, 64))
                                                .executes(ShipHullCommand::explodeHere)))
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.5, 64))
                                                .executes(ShipHullCommand::explode)))))
                .then(Commands.literal("route")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .then(Commands.literal("clear").executes(ShipHullCommand::routeClear))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("pos", Vec3Argument.vec3()).executes(ShipHullCommand::routeAdd)))
                                .then(Commands.literal("start").executes(ShipHullCommand::routeStart))
                                .then(Commands.literal("impulse")
                                        .then(Commands.argument("vx", DoubleArgumentType.doubleArg(-1000, 1000))
                                                .then(Commands.argument("vy", DoubleArgumentType.doubleArg(-1000, 1000))
                                                        .then(Commands.argument("vz", DoubleArgumentType.doubleArg(-1000, 1000))
                                                                .executes(ShipHullCommand::impulse)))))))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("origin", BlockPosArgument.blockPos())
                                .then(Commands.argument("size", IntegerArgumentType.integer(2, 16))
                                        .executes(ShipHullCommand::spawn))))
                .then(Commands.literal("nest")
                        .then(Commands.argument("parent", StringArgumentType.string())
                                .then(Commands.argument("child", StringArgumentType.string())
                                        .then(Commands.argument("lx", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("ly", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("lz", DoubleArgumentType.doubleArg())
                                                                .executes(ShipHullCommand::nest)))))))
                .then(Commands.literal("sync").executes(ShipHullCommand::sync))
                .then(Commands.literal("cadence").executes(ShipHullCommand::cadence))
                .then(Commands.literal("assemble")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(ShipHullCommand::assemble))))
                .then(Commands.literal("where")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ShipHullCommand::where)))
        );
    }

    private static ShipHullManager mgr(CommandContext<CommandSourceStack> c) {
        return ShipHullManager.get(c.getSource().getLevel());
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        String line = mgr(c).statusLine();
        c.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int setSoftSleep(CommandContext<CommandSourceStack> c, boolean on) {
        mgr(c).setSoftSleep(on);
        c.getSource().sendSuccess(() -> Component.literal("softSleep=" + on + " (empty local bounds; no pipeline.remove)"), true);
        return 1;
    }

    private static int setPhase6(CommandContext<CommandSourceStack> c, boolean on) {
        ShipHullManager.phase6Override = on;
        if (!on) mgr(c).wakeAllParked("phase6-off");
        c.getSource().sendSuccess(() -> Component.literal("phase6=" + on + " (park / idle-step skip / distant substeps / dormant substep skip / collider batching)"), true);
        return 1;
    }

    private static int setEnabled(CommandContext<CommandSourceStack> c, boolean on) {
        mgr(c).setGloballyEnabled(on);
        c.getSource().sendSuccess(() -> Component.literal("shiphull " + (on ? "ENABLED" : "disabled")), true);
        return 1;
    }

    private static int profile(CommandContext<CommandSourceStack> c) {
        c.getSource().sendSuccess(() -> Component.literal(HullProfiler.summary()), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        ShipHullManager m = mgr(c);
        if (m.all().isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal("no ships"), false);
            return 1;
        }
        for (ShipHull h : m.all()) {
            var pp = h.subLevel() != null ? h.subLevel().logicalPose().position() : null;
            String line = String.format("%s mode=%s piloted=%s rapierSolve=%s voxels=%d interactive=%d cheap=%d rapierSteps=%d nested=%d still=%d wake=%s pos=%s",
                    h.id, h.mode, h.piloted, h.mode.usesRapier(), h.voxels.size(), h.interactive.size(),
                    h.cheapSteps, h.rapierSteps, h.nested.size(), h.stillTicks, h.lastWakeReason,
                    pp == null ? "-" : String.format("%.4f,%.4f,%.4f", pp.x, pp.y, pp.z));
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return m.all().size();
    }

    /** Phase 6 parity: total item count in container block entities on the ship (plot space). */
    private static int items(CommandContext<CommandSourceStack> c) {
        ShipHull h = requireHull(c, "uuid");
        if (h == null) return 0;
        var level = c.getSource().getLevel();
        long total = 0; int containers = 0;
        net.minecraft.core.BlockPos.MutableBlockPos p = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (long k : h.voxels) {
            p.set(k);
            var be = level.getBlockEntity(p);
            if (be instanceof net.minecraft.world.Container ct) {
                containers++;
                for (int i = 0; i < ct.getContainerSize(); i++) total += ct.getItem(i).getCount();
            }
        }
        final long t = total; final int n = containers;
        c.getSource().sendSuccess(() -> Component.literal("items ship=" + h.id + " containers=" + n + " total=" + t), false);
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private static ShipHull requireHull(CommandContext<CommandSourceStack> c, String arg) {
        UUID id;
        try { id = UUID.fromString(StringArgumentType.getString(c, arg)); }
        catch (Exception e) {
            c.getSource().sendFailure(Component.literal("bad uuid"));
            return null;
        }
        ShipHull h = mgr(c).get(id);
        if (h == null) c.getSource().sendFailure(Component.literal("unknown ship " + id));
        return h;
    }

    private static int rebuild(CommandContext<CommandSourceStack> c) {
        ShipHull h = requireHull(c, "uuid");
        if (h == null) return 0;
        h.rebuildFromPlot();
        c.getSource().sendSuccess(() -> Component.literal("rebuilt voxels=" + h.voxels.size() + " interactive=" + h.interactive.size()), false);
        return 1;
    }

    private static int setMode(CommandContext<CommandSourceStack> c) {
        ShipHull h = requireHull(c, "uuid");
        if (h == null) return 0;
        String m = StringArgumentType.getString(c, "mode").toUpperCase();
        HullMode mode;
        try { mode = HullMode.valueOf(m); }
        catch (Exception e) {
            c.getSource().sendFailure(Component.literal("mode must be CHEAP|PARKED|FULL|REBUILDING"));
            return 0;
        }
        long t = c.getSource().getLevel().getGameTime();
        if (mode == HullMode.PARKED) mgr(c).parkNow(h, t);
        else if (h.mode == HullMode.PARKED && mode.usesRapier()) mgr(c).wakeNow(h, "command", t);
        else if (mode.usesRapier()) mgr(c).promote(h, mode, t);
        else h.setMode(mode, t);
        c.getSource().sendSuccess(() -> Component.literal("mode=" + h.mode + " cadence=" + ShipCadence.get(h.id)), false);
        return 1;
    }

    private static int inspect(CommandContext<CommandSourceStack> c) {
        ShipHull h = requireHull(c, "uuid");
        if (h == null) return 0;
        Map<InteractiveCell.Kind, Integer> counts = h.interactiveCounts();
        PoseLine pose = poseOf(h);
        c.getSource().sendSuccess(() -> Component.literal(String.format(
                "id=%s mode=%s piloted=%s inRapier=%s voxels=%d hull=[%d,%d,%d]->[%d,%d,%d] interactive=%s nested=%d explosions=%d splits=%d pose=%s vel=%.2f,%.2f,%.2f",
                h.id, h.mode, h.piloted, h.inRapier, h.voxels.size(),
                h.minX, h.minY, h.minZ, h.maxX, h.maxY, h.maxZ, counts, h.nested.size(),
                h.explosionsHandled, h.splitsPerformed, pose,
                h.linearVel.x, h.linearVel.y, h.linearVel.z)), false);
        return 1;
    }

    private record PoseLine(String s) {
        @Override public String toString() { return s; }
    }

    private static PoseLine poseOf(ShipHull h) {
        var p = h.subLevel().logicalPose().position();
        return new PoseLine(String.format("%.1f,%.1f,%.1f", p.x(), p.y(), p.z()));
    }

    private static int lookup(CommandContext<CommandSourceStack> c) {
        ShipHull h = requireHull(c, "uuid");
        if (h == null) return 0;
        BlockPos pos;
        try { pos = BlockPosArgument.getLoadedBlockPos(c, "pos"); } catch (Exception e) { c.getSource().sendFailure(Component.literal("bad pos: " + e)); return 0; }
        InteractiveCell cell = h.lookupLocal(pos);
        if (cell == null) {
            // try world→local
            BlockPos local = h.worldToLocal(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            cell = h.lookupLocal(local);
            if (cell == null) {
                c.getSource().sendSuccess(() -> Component.literal("no interactive cell at " + pos + " (local try " + local + ")"), false);
                return 0;
            }
        }
        InteractiveCell found = cell;
        c.getSource().sendSuccess(() -> Component.literal("cell " + found.localPos() + " kind=" + found.kind() + " state=" + found.state()), false);
        return 1;
    }

    private static int explodeHere(CommandContext<CommandSourceStack> c) {
        ShipHull h = requireHull(c, "uuid");
        if (h == null) return 0;
        double r = DoubleArgumentType.getDouble(c, "radius");
        var pos = h.subLevel().logicalPose().position();
        int removed = mgr(c).handleExplosion(h, pos.x(), pos.y(), pos.z(), r);
        c.getSource().sendSuccess(() -> Component.literal("explosion@pose removed=" + removed + " mode=" + h.mode + " voxels=" + h.voxels.size() + " splits=" + h.splitsPerformed), true);
        return Math.max(removed, 1);
    }

    private static int explode(CommandContext<CommandSourceStack> c) {
        ShipHull h = requireHull(c, "uuid");
        if (h == null) return 0;
        Vec3 pos = Vec3Argument.getVec3(c, "pos");
        double r = DoubleArgumentType.getDouble(c, "radius");
        int removed = mgr(c).handleExplosion(h, pos.x, pos.y, pos.z, r);
        c.getSource().sendSuccess(() -> Component.literal("explosion removed=" + removed + " mode=" + h.mode + " voxels=" + h.voxels.size() + " splits=" + h.splitsPerformed), true);
        return removed;
    }

    private static int routeClear(CommandContext<CommandSourceStack> c) {
        ShipHull h = requireHull(c, "uuid");
        if (h == null) return 0;
        h.route.clear();
        h.routeIndex = 0;
        h.routeTarget.set(Double.NaN, Double.NaN, Double.NaN);
        c.getSource().sendSuccess(() -> Component.literal("route cleared"), false);
        return 1;
    }

    private static int routeAdd(CommandContext<CommandSourceStack> c) {
        ShipHull h = requireHull(c, "uuid");
        if (h == null) return 0;
        Vec3 p = Vec3Argument.getVec3(c, "pos");
        h.route.add(new Vector3d(p.x, p.y, p.z));
        c.getSource().sendSuccess(() -> Component.literal("route size=" + h.route.size()), false);
        return 1;
    }

    private static int routeStart(CommandContext<CommandSourceStack> c) {
        ShipHull h = requireHull(c, "uuid");
        if (h == null) return 0;
        if (h.route.isEmpty()) {
            c.getSource().sendFailure(Component.literal("empty route"));
            return 0;
        }
        h.routeIndex = 0;
        h.routeTarget.set(h.route.get(0));
        // Force CHEAP so routes exercise hull integrator
        h.setMode(HullMode.CHEAP, c.getSource().getLevel().getGameTime());
        c.getSource().sendSuccess(() -> Component.literal("route started → " + h.routeTarget), false);
        return 1;
    }

    private static int impulse(CommandContext<CommandSourceStack> c) {
        ShipHull h = requireHull(c, "uuid");
        if (h == null) return 0;
        h.linearVel.add(DoubleArgumentType.getDouble(c, "vx"),
                DoubleArgumentType.getDouble(c, "vy"),
                DoubleArgumentType.getDouble(c, "vz"));
        c.getSource().sendSuccess(() -> Component.literal("vel=" + h.linearVel), false);
        return 1;
    }

    private static int nest(CommandContext<CommandSourceStack> c) {
        ShipHull parent = requireHull(c, "parent");
        ShipHull child = requireHull(c, "child");
        if (parent == null || child == null) return 0;
        double lx = DoubleArgumentType.getDouble(c, "lx");
        double ly = DoubleArgumentType.getDouble(c, "ly");
        double lz = DoubleArgumentType.getDouble(c, "lz");
        mgr(c).attachNested(parent, child.id, lx, ly, lz, "bearing");
        c.getSource().sendSuccess(() -> Component.literal("nested " + child.id + " on " + parent.id), false);
        return 1;
    }

    private static int sync(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        ShipHullManager m = ShipHullManager.get(level);
        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            int n = 0;
            if (container != null) {
                for (ServerSubLevel sub : container.getAllSubLevels()) {
                    m.onSubLevelAdded(sub);
                    n++;
                }
            }
            int nn = n;
            c.getSource().sendSuccess(() -> Component.literal("synced " + nn + " sublevels; " + m.statusLine()), false);
            return n;
        } catch (Throwable t) {
            c.getSource().sendFailure(Component.literal("sync failed: " + t));
            return 0;
        }
    }

    private static int cadence(CommandContext<CommandSourceStack> c) {
        c.getSource().sendSuccess(() -> Component.literal("cadence " + ShipCadence.bridgeInfo()), false);
        for (String e : ShipCadence.recent()) c.getSource().sendSuccess(() -> Component.literal("  " + e), false);
        return 1;
    }

    /** Which ship (if any) owns a block position - the same lookup factory graphs use. */
    private static int where(CommandContext<CommandSourceStack> c) {
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        Object ship = com.vws.aeronauticsshiphull.api.ShipLocator.locate(c.getSource().getLevel(), pos.asLong());
        c.getSource().sendSuccess(() -> Component.literal(pos.toShortString() + " -> " + (ship == null ? "ground" : "ship " + ship)), false);
        return ship == null ? 0 : 1;
    }

    /**
     * Test helper: assemble every non-air block in a box (e.g. a Create belt rig on an iron deck)
     * into one Sable sub-level, then print where the box's min corner landed in the plot.
     */
    private static int assemble(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos a = BlockPosArgument.getBlockPos(c, "from");
        BlockPos b = BlockPosArgument.getBlockPos(c, "to");
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        List<BlockPos> blocks = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (!level.getBlockState(p).isAir()) blocks.add(p.immutable());
        }
        if (blocks.isEmpty() || blocks.size() > 4096) {
            c.getSource().sendFailure(Component.literal("need 1..4096 blocks, got " + blocks.size()));
            return 0;
        }
        ServerSubLevel sub;
        try {
            sub = SubLevelAssemblyHelper.assembleBlocks(level, min, blocks, BoundingBox3i.from(blocks));
        } catch (Throwable t) {
            c.getSource().sendFailure(Component.literal("assemble failed: " + t));
            return 0;
        }
        if (sub == null) {
            c.getSource().sendFailure(Component.literal("assemble returned null"));
            return 0;
        }
        ShipHullManager m = ShipHullManager.get(level);
        m.onSubLevelAdded(sub);
        ShipHull h = m.get(sub);
        if (h != null) h.rebuildFromPlot();
        var bb = sub.getPlot().getBoundingBox();
        String plotInfo = bb == null ? "?" : String.format("plot bb [%d,%d,%d]->[%d,%d,%d]", bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
        c.getSource().sendSuccess(() -> Component.literal("assembled ship " + sub.getUniqueId() + " blocks=" + blocks.size()
                + " voxels=" + (h == null ? "?" : h.voxels.size()) + " " + plotInfo + " worldMin=" + min.toShortString()), true);
        return 1;
    }

    /**
     * Build a small platform of iron blocks + a chest, assemble into a Sable sub-level.
     * Dedicated-server friendly smoke spawn.
     */
    private static int spawn(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos origin;
        try { origin = BlockPosArgument.getLoadedBlockPos(c, "origin"); } catch (Exception e) { c.getSource().sendFailure(Component.literal("bad origin: " + e)); return 0; }
        int size = IntegerArgumentType.getInteger(c, "size");
        List<BlockPos> blocks = new ArrayList<>();
        BlockState deck = Blocks.IRON_BLOCK.defaultBlockState();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                BlockPos p = origin.offset(x, 0, z);
                level.setBlock(p, deck, 3);
                blocks.add(p);
            }
        }
        // Mast + chest (interactive)
        BlockPos mast = origin.offset(size / 2, 1, size / 2);
        level.setBlock(mast, Blocks.OAK_FENCE.defaultBlockState(), 3);
        blocks.add(mast);
        BlockPos chest = origin.offset(1, 1, 1);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        blocks.add(chest);

        BoundingBox3i bb = BoundingBox3i.from(blocks);
        ServerSubLevel sub;
        try {
            sub = SubLevelAssemblyHelper.assembleBlocks(level, origin, blocks, bb);
        } catch (Throwable t) {
            c.getSource().sendFailure(Component.literal("assemble failed: " + t));
            return 0;
        }
        if (sub == null) {
            c.getSource().sendFailure(Component.literal("assemble returned null"));
            return 0;
        }
        ShipHullManager m = ShipHullManager.get(level);
        m.onSubLevelAdded(sub);
        ShipHull hull = m.get(sub);
        if (hull != null) {
            // Seed voxels from the block list we assembled (plot scan can lag / miss on first tick)
            hull.voxels.clear();
            hull.interactive.clear();
            for (BlockPos bp : blocks) {
                // After assemble, blocks live in the sub-level plot; local positions ≈ origin-relative
                // Prefer plot accessor scan, then fall back to recording the world positions we placed.
                long key = bp.asLong();
                hull.voxels.add(key);
            }
            // Chest / mast interactive
            long chestKey = chest.asLong();
            hull.interactive.put(chestKey, new InteractiveCell(chest, InteractiveCell.Kind.CHEST, Blocks.CHEST.defaultBlockState()));
            hull.recomputeHullBounds();
            // Also try plot rebuild (may refine)
            try { hull.rebuildFromPlot(); } catch (Throwable ignored) {}
            if (hull.voxels.isEmpty()) {
                for (BlockPos bp : blocks) hull.voxels.add(bp.asLong());
                hull.recomputeHullBounds();
            }
        }
        UUID id = sub.getUniqueId();
        ShipHull h2 = hull;
        c.getSource().sendSuccess(() -> Component.literal("spawned ship " + id + " voxels=" + (h2 == null ? "?" : h2.voxels.size())
                + " interactive=" + (h2 == null ? "?" : h2.interactive.size())), true);
        return 1;
    }
}
