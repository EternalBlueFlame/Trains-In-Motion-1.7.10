package ebf.tim.items;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.FillBucketEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * <h1>Bucket Handler, is this even needed?</h1>
 * theoretically this is intended to define the functionality for vanilla buckets to turn into our oil buckets.
 * but this class is unused, and given the bucket changes in 1.8+ is this sort of thing even necessary anyway?
 * @author Justice
 */
public class BucketHandler {

    public static BucketHandler INSTANCE = new BucketHandler();
    public Map<Block, Item> buckets = new HashMap<Block, Item>();

    public BucketHandler() {

    }
    @SubscribeEvent
    public void onBucketFill(FillBucketEvent event) {

        ItemStack result = fillCustomBucket(event.world, event.target);

        if (result == null) return;

        event.result = result; event.setResult(Event.Result.ALLOW); }

    private ItemStack fillCustomBucket(World world, MovingObjectPosition pos) {

        Block block = world.getBlock(pos.blockX, pos.blockY, pos.blockZ);

        Item bucket = buckets.get(block); if (bucket != null && world.getBlockMetadata(pos.blockX, pos.blockY, pos.blockZ) == 0) { world.setBlockToAir(pos.blockX, pos.blockY, pos.blockZ); return new ItemStack(bucket); } else return null;

    } }