package ebf.tim.entities;

import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.registry.IEntityAdditionalSpawnData;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ebf.tim.TrainsInMotion;
import ebf.tim.items.ItemKey;
import ebf.tim.models.Bogie;
import ebf.tim.models.ParticleFX;
import ebf.tim.models.TransportRenderData;
import fexcraft.tmt.slim.Vec3d;
import ebf.tim.networking.PacketRemove;
import ebf.tim.registry.NBTKeys;
import ebf.tim.utility.*;
import io.netty.buffer.ByteBuf;
import mods.railcraft.api.carts.IFluidCart;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.IEntityMultiPart;
import net.minecraft.entity.boss.EntityDragonPart;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBucket;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.*;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.*;
import fexcraft.tmt.slim.ModelBase;

import javax.annotation.Nullable;
import java.util.*;

import static ebf.tim.TrainsInMotion.MODID;
import static ebf.tim.utility.RailUtility.rotatePoint;

/**
 * <h1>Generic Rail Transport</h1>
 * this is the base for all trains and rollingstock.
 * @author Eternal Blue Flame
 */
public class GenericRailTransport extends EntityMinecart implements IEntityAdditionalSpawnData, IEntityMultiPart, IInventory, IFluidHandler, IFluidCart {

    /*
     * <h2>variables</h2>
     */
    /**defines the lamp, and it's management*/
    public LampHandler lamp = new LampHandler();
    /**defines the colors, the outer array is for each different color, and the inner int[] is for the RGB color*/
    public int[][] colors = new int[][]{{0,0,0},{0,0,0},{0,0,0}};
    /**the server-sided persistent UUID of the owner*/
    private UUID owner = null;
    /**the front entity bogie*/
    public EntityBogie frontBogie = null;
    /**the back entity bogie*/
    public EntityBogie backBogie = null;
    /**the list of seat entities*/
    public List<EntitySeat> seats = new ArrayList<>();
    /**the list of hitboxes and the class that manages them*/
    public HitboxHandler hitboxHandler = new HitboxHandler();
    /**the server-sided persistent UUID of the transport linked to the front of this,*/
    public UUID frontLinkedTransport = null;
    /**the id of the rollingstock linked to the front*/
    public Integer frontLinkedID =null;
    /**the server-sided persistent UUID of the transport linked to the back of this,*/
    public UUID backLinkedTransport = null;
    /**the id of the rollingstock linked to the back*/
    public Integer backLinkedID = null;
    /**the ID of the owner*/
    public String ownerName ="";
    /**the destination for routing*/
    public String destination ="";
    /**the key item for the entity*/
    public ItemStackSlot key;
    /**the ticket item for the transport*/
    public ItemStackSlot ticket;
    /**used to initialize a laege number of variables that are used to calculate everything from movement to linking.
     * this is so we don't have to initialize each of these variables every tick, saves CPU.*/
    protected double[][] vectorCache = new double[8][3];
    /**the health of the entity, similar to that of EntityLiving*/
    private int health = 20;
    /**the fluidTank tank*/
    private FluidStack fluidTank = null;
    /**the list of items used for the inventory and crafting slots.*/
    public List<ItemStackSlot> inventory = null;
    /**whether or not this needs to update the datawatchers*/
    public boolean updateWatchers = false;
    /**the RF battery for rollingstock.*/
    public int battery=0;
    /**the ticket that gives the entity permission to load chunks.*/
    private ForgeChunkManager.Ticket chunkTicket;
    /**a cached list of the loaded chunks*/
    public List<ChunkCoordIntPair> chunkLocations = new ArrayList<>();
    /**The X velocity of the front bogie*/
    public double frontVelocityX=0;
    /**The Z velocity of the front bogie*/
    public double frontVelocityZ=0;
    /**The X velocity of the back bogie*/
    public double backVelocityX=0;
    /**The Z velocity of the back bogie*/
    public double backVelocityZ=0;
    /**a list of the particles the transport is managing*/
    public List<ParticleFX> particles = new ArrayList<>();
    /**Used to be sure we only say once that the transport has been derailed*/
    private boolean displayDerail = false;
    /*this is cached so we can keep the hitbox handler running even when derailed.*/
    private boolean collision;
    /**/
    float prevRotationRoll;
    /**/
    float rotationRoll;
    /***/
    private List<ResourceLocation> transportSkins = new ArrayList<ResourceLocation>();
    public int forceBackupTimer =0;

    public boolean hasTrain=false;

    //@SideOnly(Side.CLIENT)
    public TransportRenderData renderData = new TransportRenderData();

    /**the array of booleans, defined as bits
     * 0- brake: defines the brake
     * 1- locked: defines if transport is locked to owner and key holders
     * 2- lamp: defines if lamp is on
     * 3- creative: defines if the transport should consume fuels and be able to derail.
     * 4- coupling: defines if the transport is looking to couple with other transports.
     * 5- inventory whitelist: defines if the inventory is a whitelist
     * 6- running: defines if te transport is running (usually only on trains).
     * 7-15 are unused.
     * for use see
     * @see #getBoolean(boolValues)
     * @see #setBoolean(boolValues, boolean)
     */
    private BitList bools = new BitList();
    public enum boolValues{BRAKE(0), LOCKED(1), LAMP(2), CREATIVE(3), COUPLINGFRONT(4), COUPLINGBACK(5), WHITELIST(6), RUNNING(7), DERAILED(8), PARKING(9);
        public int index;
        boolValues(int index){this.index = index;}
    }

    public boolean getBoolean(boolValues var){
        if(worldObj.isRemote) {
            return bools.getFromInt(var.index, this.dataWatcher.getWatchableObjectInt(17));
        } else {
            return bools.get(var.index);
        }
    }

    public void setBoolean(boolValues var, boolean value){
        if (getBoolean(var) != value) {
            bools.set(var.index, value);
            updateWatchers = true;
        }
    }


    public GenericRailTransport(World world){
        super(world);
        setSize(1,2);
        ignoreFrustumCheck = true;
    }
    public GenericRailTransport(UUID owner, World world, double xPos, double yPos, double zPos){
        super(world);

        posY = yPos;
        posX = xPos;
        posZ = zPos;
        this.owner = owner;
        key = new ItemStackSlot(this, -1);
        key.setSlotContents(new ItemStack(new ItemKey(entityUniqueID, getItem().getUnlocalizedName())));
        setSize(1,2);
        ignoreFrustumCheck = true;
    }

    /**
     * <h2>Entity initialization</h2>
     * Entity init runs right before the first tick.
     * This is useful for registering the datawatchers and inventory before we actually use them.
     * NOTE: slot 0 and 1 of the datawatcher are used by vanilla. It may be wise to leave the first 5 empty.
     */
    @Override
    public void entityInit(){
        this.dataWatcher.addObject(13, 0);//train fuel consumption current
        this.dataWatcher.addObject(14, 0);//train steam, or rollingstock fluid ID (so client can show fluid name)
        this.dataWatcher.addObject(15, 0);//train heat
        this.dataWatcher.addObject(17, 0);//booleans
        //18 is used by EntityTrainCore
        //19 is used by the core minecart
        this.dataWatcher.addObject(23, "");//owner
        this.dataWatcher.addObject(20, 0);//tankA
        this.dataWatcher.addObject(21, 0);//front linked transport
        this.dataWatcher.addObject(22, 0);//back linked transport
        this.dataWatcher.addObject(24, 0);//currently used texture
        if (getSizeInventory()>0 && inventory == null) {
            inventory = new ArrayList<ItemStackSlot>();
            int slot=0;
            while (inventory.size()<getSizeInventory()){
                inventory.add(new ItemStackSlot(this, slot));
                slot++;
            }
        }
    }



    /*
     * <h2>base entity overrides</h2>
     * modify basic entity variables to give different uses/values.
     */
    /**returns if the player can push this, we actually use our own systems for this, so we return false*/
    @Override
    public boolean canBePushed() {return false;}
    @Override
    public int getMinecartType(){return 10002;}
    /**returns the world object for IEntityMultipart*/
    @Override
    public World func_82194_d(){return worldObj;}
    /**returns the hitboxes of the entity as an array rather than a list*/
    @Override
    public Entity[] getParts(){return hitboxHandler.hitboxList.toArray(new HitboxHandler.MultipartHitbox[hitboxHandler.hitboxList.size()]);}
    /**returns the hitbox of this entity, we dont need that so return null*/
    @Override
    public AxisAlignedBB getBoundingBox(){return null;}
    /**returns the hitbox of this entity, we dont need that so return null*/
    @Override
    public AxisAlignedBB getCollisionBox(Entity collidedWith){return null;}
    /**returns if this can be collided with, we don't use this so return false*/
    @Override
    public boolean canBeCollidedWith() {return false;}
    /**client only positioning of the transport, this should help to smooth the movement*/
    @SideOnly(Side.CLIENT)
    public void setPositionAndRotation2(double p_70056_1_, double p_70056_3_, double p_70056_5_, float p_70056_7_, float p_70056_8_, int p_70056_9_) {
        if (frontBogie!=null && backBogie!= null){
            setPosition(
                    (frontBogie.posX + backBogie.posX) * 0.5D,
                    (frontBogie.posY + backBogie.posY) * 0.5D,
                    (frontBogie.posZ + backBogie.posZ) * 0.5D);

            setRotation((float)Math.toDegrees(Math.atan2(
                    frontBogie.posZ - backBogie.posZ,
                    frontBogie.posX - backBogie.posX)),
                    MathHelper.floor_double(Math.acos(frontBogie.posY / backBogie.posY)*RailUtility.degreesD));
        }else {
            this.setPosition(p_70056_1_, p_70056_3_, p_70056_5_);
        }
        this.setRotation(p_70056_7_, p_70056_8_);
    }


    /**
     * <h2>damage and destruction</h2>
     * attackEntityFromPart is called when one of the hitboxes of the entity has taken damage of some form.
     * the damage done is handled manually so we can compensate for basically everything, and if health is 0 or lower, we destroy the entity part by part, leaving the main part of the entity for last.
     */
    @Override
    public boolean attackEntityFromPart(EntityDragonPart part, DamageSource damageSource, float damage){
        return attackEntityFrom(damageSource, damage);
    }
    @Override
    public boolean attackEntityFrom(DamageSource damageSource, float p_70097_2_){
        if (damageSource.getEntity() instanceof GenericRailTransport){
            return false;
        }
        //if its a creative player, destroy instantly
        if (damageSource.getEntity() instanceof EntityPlayer && ((EntityPlayer) damageSource.getEntity()).capabilities.isCreativeMode && !damageSource.isProjectile()){
            health -=20;
            //if its reinforced and its not an explosion
        } else if (isReinforced() && !damageSource.isProjectile() && !damageSource.isExplosion()){
            health -=1;
            //if it is an explosion and it's reinforced, or it's not an explosion and isn't reinforced
        } else if ((damageSource.isExplosion() && isReinforced()) || (!isReinforced() && !damageSource.isProjectile())){
            health -=5;
            //if it isn't reinforced and is an explosion
        } else if (damageSource.isExplosion() && !isReinforced()){
            health-=20;
        }
        //cover overheating, or other damage to self.
        if (damageSource.getSourceOfDamage() == this){
            health-=20;
        }

        //on Destruction
        if (health<1){
            //remove this
            if (damageSource.getEntity() instanceof EntityPlayer) {
                TrainsInMotion.keyChannel.sendToServer(new PacketRemove(getEntityId(), !((EntityPlayer) damageSource.getEntity()).capabilities.isCreativeMode));
            } else {
                TrainsInMotion.keyChannel.sendToServer(new PacketRemove(getEntityId(),false));
            }
            setDead();
            return true;
        }
        return false;
    }
    public void setDead() {
        super.setDead();
        //remove bogies
        if (frontBogie != null) {
            frontBogie.setDead();
            TrainsInMotion.keyChannel.sendToServer(new PacketRemove(frontBogie.getEntityId(), false));
            worldObj.removeEntity(frontBogie);
        }
        if (backBogie != null) {
            backBogie.setDead();
            TrainsInMotion.keyChannel.sendToServer(new PacketRemove(backBogie.getEntityId(), false));
            worldObj.removeEntity(backBogie);
        }
        //remove seats
        for (EntitySeat seat : seats) {
            seat.setDead();
            TrainsInMotion.keyChannel.sendToServer(new PacketRemove(seat.getEntityId(),false));
            seat.worldObj.removeEntity(seat);
        }
        //remove hitboxes
        for (EntityDragonPart hitbox : hitboxHandler.hitboxList){
            hitbox.setDead();
            TrainsInMotion.keyChannel.sendToServer(new PacketRemove(hitbox.getEntityId(),false));
            hitbox.worldObj.removeEntity(hitbox);
        }
        //be sure the front and back links are removed in the case of this entity being removed from the world.
        if (frontLinkedID != null){
            GenericRailTransport front = ((GenericRailTransport)worldObj.getEntityByID(frontLinkedID));
            if(front != null && front.frontLinkedID != null && front.frontLinkedID == this.getEntityId()){
                front.frontLinkedID = null;
                front.frontLinkedTransport = null;
            } else if(front != null && front.backLinkedID != null && front.backLinkedID == this.getEntityId()){
                front.backLinkedID = null;
                front.backLinkedTransport = null;
            }
        }
        if (backLinkedID != null){
            GenericRailTransport back = ((GenericRailTransport)worldObj.getEntityByID(backLinkedID));
            if(back != null && back.frontLinkedID != null && back.frontLinkedID == this.getEntityId()){
                back.frontLinkedID = null;
                back.frontLinkedTransport = null;
            } else if(back != null && back.backLinkedID != null && back.backLinkedID == this.getEntityId()){
                back.backLinkedID = null;
                back.backLinkedTransport = null;
            }
        }

    }


    /*
     * <h3>add bogies and seats</h3>
     */
    /** this is called by the seats and seats on their spawn to add them to this entity's list of seats, we only do it on client because that's the only side that seems to lose track.
     * @see EntitySeat#readSpawnData(ByteBuf)*/
    @SideOnly(Side.CLIENT)
    public void setseats(EntitySeat seat, int seatNumber){
        if (seats.size() <= seatNumber) {
            seats.add(seat);
        } else {
            seats.set(seatNumber, seat);
        }
    }

    /** this is called by the bogies on their spawn to add them to this entity's list of bogies, we only do it on client because that's the only side that seems to lose track.
     * @see EntityBogie#readSpawnData(ByteBuf)*/
    @SideOnly(Side.CLIENT)
    public void setBogie(EntityBogie cart, boolean isFront){
        if(isFront){
            frontBogie = cart;
        } else {
            backBogie = cart;
        }
    }

    /*
     * <h2> Data Syncing and Saving </h2>
     * SpawnData is mainly used for data that has to be created on client then sent to the server, like data processed on item use.
     * NBT is save data, which only happens on server.
     */

    /**reads the data sent from client on entity spawn*/
    @Override
    public void readSpawnData(ByteBuf additionalData) {
        owner = new UUID(additionalData.readLong(), additionalData.readLong());
        rotationYaw = additionalData.readFloat();
    }
    /**sends the data to server from client*/
    @Override
    public void writeSpawnData(ByteBuf buffer) {
        buffer.writeLong(owner.getMostSignificantBits());
        buffer.writeLong(owner.getLeastSignificantBits());
        buffer.writeFloat(rotationYaw);
    }
    /**loads the entity's save file*/
    @Override
    protected void readEntityFromNBT(NBTTagCompound tag) {
        bools.set(tag.getInteger(NBTKeys.bools));
        isDead = tag.getBoolean(NBTKeys.dead);
        //load links
        if (tag.hasKey(NBTKeys.frontLinkMost)) {
            frontLinkedTransport = new UUID(tag.getLong(NBTKeys.frontLinkMost), tag.getLong(NBTKeys.frontLinkLeast));
        }
        if (tag.hasKey(NBTKeys.backLinkMost)) {
            backLinkedTransport = new UUID(tag.getLong(NBTKeys.backLinkMost), tag.getLong(NBTKeys.backLinkLeast));
        }
        //load owner
        owner = new UUID(tag.getLong(NBTKeys.ownerMost),tag.getLong(NBTKeys.ownerLeast));
        //load bogie velocities
        frontVelocityX = tag.getDouble(NBTKeys.frontBogieX);
        frontVelocityZ = tag.getDouble(NBTKeys.frontBogieZ);
        backVelocityX = tag.getDouble(NBTKeys.backBogieX);
        backVelocityZ = tag.getDouble(NBTKeys.backBogieZ);

        rotationRoll = tag.getFloat(NBTKeys.rotationRoll);
        prevRotationRoll = tag.getFloat(NBTKeys.prevRotationRoll);
        //load tanks
        if (getTankCapacity() >0) {
            if(tag.hasKey("FluidName")) {
                fluidTank = FluidStack.loadFluidStackFromNBT(tag);
            } else {
                fluidTank = null;
            }
        }

        if (getSizeInventory()>0) {
            for (int i = 0; i < getSizeInventory(); i++) {
                NBTTagCompound tagCompound = tag.getCompoundTag(NBTKeys.inventoryItem +i);
                if (tagCompound != null){
                    setInventorySlotContents(i, ItemStack.loadItemStackFromNBT(tagCompound));
                }
            }
        }

        updateWatchers = true;
    }
    /**saves the entity to server world*/
    @Override
    protected void writeEntityToNBT(NBTTagCompound tag) {
        tag.setInteger(NBTKeys.bools, bools.toInt());
        tag.setBoolean(NBTKeys.dead, isDead);
        //frontLinkedTransport and backLinkedTransport bogies
        if (frontLinkedTransport != null){
            tag.setLong(NBTKeys.frontLinkMost, frontLinkedTransport.getMostSignificantBits());
            tag.setLong(NBTKeys.frontLinkLeast, frontLinkedTransport.getLeastSignificantBits());
        }
        if (backLinkedTransport != null){
            tag.setLong(NBTKeys.backLinkMost, backLinkedTransport.getMostSignificantBits());
            tag.setLong(NBTKeys.backLinkLeast, backLinkedTransport.getLeastSignificantBits());
        }
        //owner
        tag.setLong(NBTKeys.ownerMost, owner.getMostSignificantBits());
        tag.setLong(NBTKeys.ownerLeast, owner.getLeastSignificantBits());

        //bogie velocities
        tag.setDouble(NBTKeys.frontBogieX, frontVelocityX);
        tag.setDouble(NBTKeys.frontBogieZ, frontVelocityZ);
        tag.setDouble(NBTKeys.backBogieX, backVelocityX);
        tag.setDouble(NBTKeys.backBogieZ, backVelocityZ);

        tag.setFloat(NBTKeys.rotationRoll, rotationRoll);
        tag.setFloat(NBTKeys.prevRotationRoll, prevRotationRoll);


        //tanks
        if (getTankCapacity() >0){
            if (fluidTank != null) {
                fluidTank.writeToNBT(tag);
            }
        }

        if (getSizeInventory()>0 && inventory == null) {
            inventory = new ArrayList<ItemStackSlot>();
            int slot=0;
            while (inventory.size()<getSizeInventory()){
                inventory.add(new ItemStackSlot(this, slot));
                slot++;
            }
        }
        if (getSizeInventory()>0 && inventory.size()>0) {
            for (int i =0; i< getSizeInventory(); i++) {
                if (inventory.get(i).getHasStack()) {
                    tag.setTag(NBTKeys.inventoryItem + i, inventory.get(i).writeToNBT());
                }
            }
        }

    }

    /**plays a sound during entity movement*/
    @Override
    protected void func_145780_a(int p_145780_1_, int p_145780_2_, int p_145780_3_, Block p_145780_4_) {
    }


    public void updatePosition(){
        if (collision) {
            frontBogie.setVelocity(-frontBogie.motionX,-frontBogie.motionY,-frontBogie.motionZ);
            backBogie.setVelocity(-backBogie.motionX,-backBogie.motionY,-backBogie.motionZ);
        }
        setBoolean(boolValues.DERAILED, frontBogie.minecartMove(rotationPitch, rotationYaw, getBoolean(boolValues.RUNNING), getType().isTrain() || hasTrain, getBoolean(boolValues.PARKING),
                weightKg() * (frontBogie.isOnSlope?1.5f:1) * (backBogie.isOnSlope?2:1), frontLinkedID!=null || backLinkedID!=null));
        setBoolean(boolValues.DERAILED, backBogie.minecartMove(rotationPitch, rotationYaw, getBoolean(boolValues.RUNNING), getType().isTrain() || hasTrain, getBoolean(boolValues.PARKING),
                weightKg() * (frontBogie.isOnSlope?1.5f:1) * (backBogie.isOnSlope?2:1), frontLinkedID!=null || backLinkedID!=null));
        motionX = frontVelocityX = frontBogie.motionX;
        motionZ = frontVelocityZ = frontBogie.motionZ;
        backVelocityX = backBogie.motionX;
        backVelocityZ = backBogie.motionZ;


        //position this
        setPosition(
                ((frontBogie.posX + backBogie.posX) * 0.5D),
                ((frontBogie.posY + backBogie.posY) * 0.5D),
                ((frontBogie.posZ + backBogie.posZ) * 0.5D));

        setRotation((float)Math.toDegrees(Math.atan2(
                frontBogie.posZ - backBogie.posZ,
                frontBogie.posX - backBogie.posX)),
                MathHelper.floor_double(Math.acos(frontBogie.posY / backBogie.posY)*RailUtility.degreesD),
                rotationYaw-prevRotationYaw);
    }



    /**
     * <h2> on entity update </h2>
     *
     * defines what should be done every tick
     * used for:
     * managing the list of bogies and seats, respawning them if they disappear.
     * managing speed, acceleration. and direction.
     * managing rotationYaw and rotationPitch.
     * updating rider entity positions if there is no one riding the core seat.
     * calling on link management.
     * @see #manageLinks(GenericRailTransport)
     * syncing the owner entity ID with client.
     * being sure the transport is listed in the main class (for lighting management).
     * @see ClientProxy#onTick(TickEvent.ClientTickEvent)
     * and updating the lighting block.
     */
    @Override
    public void onUpdate() {
        if (!worldObj.isRemote) {
            if (forceBackupTimer > 0) {
                forceBackupTimer--;
            } else if (forceBackupTimer == 0) {
                ServerLogger.writeWagonToFolder(this);
                forceBackupTimer--;
            }
        }

        //if the cart has fallen out of the map, destroy it.
        if (posY < -64.0D & isDead){
            worldObj.removeEntity(this);
        }

        if(this.chunkTicket == null) {
            this.requestTicket();
        }

        //be sure bogies exist

        //always be sure the bogies exist on client and server.
        if (!worldObj.isRemote && (frontBogie == null || backBogie == null)) {
            //spawn frontLinkedTransport bogie
            vectorCache[1][0] = getLengthFromCenter();
            vectorCache[0] = RailUtility.rotatePoint(vectorCache[1],rotationPitch, rotationYaw,0);
            frontBogie = new EntityBogie(worldObj, posX + vectorCache[0][0], posY + vectorCache[0][1], posZ + vectorCache[0][2], getEntityId(), true);
            frontBogie.setVelocity(frontVelocityX,0,frontVelocityZ);
            //spawn backLinkedTransport bogie
            vectorCache[1][0] = -getLengthFromCenter();
            vectorCache[0] = RailUtility.rotatePoint(vectorCache[1],rotationPitch, rotationYaw,0);
            backBogie = new EntityBogie(worldObj, posX + vectorCache[0][0], posY + vectorCache[0][1], posZ + vectorCache[0][2], getEntityId(), false);
            backBogie.setVelocity(backVelocityX, 0, backVelocityZ);
            worldObj.spawnEntityInWorld(frontBogie);
            worldObj.spawnEntityInWorld(backBogie);

            if (getRiderOffsets() != null && getRiderOffsets().length >1 && seats.size()<getRiderOffsets().length) {
                for (int i = 0; i < getRiderOffsets().length - 1; i++) {
                    EntitySeat seat = new EntitySeat(worldObj, posX, posY, posZ, getEntityId(), i);
                    worldObj.spawnEntityInWorld(seat);
                    seats.add(seat);
                }
            }
        }

        /*
         * run the hitbox check whether or not the bogies exist so we can ensure interaction even during severe client-sided error.
         *check if the bogies exist, because they may not yet, and if they do, check if they are actually moving or colliding.
         * no point in processing movement if they aren't moving or if the train hit something.
         * if it is clear however, then we need to add velocity to the bogies based on the current state of the train's speed and fuel, and reposition the train.
         * but either way we have to position the bogies around the train, just to be sure they don't accidentally fly off at some point.
         *
         * this stops updating if the transport derails. Why update positions of something that doesn't move? We compensate for first tick to be sure hitboxes, bogies, etc, spawn on join.
         */
        collision = hitboxHandler.getCollision(this);
        if (frontBogie!=null && backBogie != null && (!getBoolean(boolValues.DERAILED) || ticksExisted==1)){
            //handle movement.
            if (!worldObj.isRemote && !(this instanceof EntityTrainCore)) {
                if (frontLinkedID != null && worldObj.getEntityByID(frontLinkedID) instanceof GenericRailTransport) {
                    manageLinks((GenericRailTransport) worldObj.getEntityByID(frontLinkedID));
                }
                if (backLinkedID != null && worldObj.getEntityByID(backLinkedID) instanceof GenericRailTransport) {
                    manageLinks((GenericRailTransport) worldObj.getEntityByID(backLinkedID));
                }
            }
            updatePosition();

            if (ticksExisted %2 ==0) {
                //align bogies
                vectorCache[1][0]= getLengthFromCenter();
                vectorCache[0] = rotatePoint(vectorCache[1], rotationPitch, rotationYaw, 0.0f);
                frontBogie.setPosition(vectorCache[0][0] + posX, frontBogie.posY, vectorCache[0][2] + posZ);
                vectorCache[1][0]= -getLengthFromCenter();
                vectorCache[0] = rotatePoint(vectorCache[1], rotationPitch, rotationYaw, 0.0f);
                backBogie.setPosition(vectorCache[0][0] + posX, backBogie.posY, vectorCache[0][2] + posZ);
            }
        }

        //rider updating isn't called if there's no driver/conductor, so just in case of that, we reposition the seats here too.
        if (riddenByEntity == null && getRiderOffsets() != null) {
            for (int i = 0; i < seats.size(); i++) {
                vectorCache[0] = rotatePoint(getRiderOffsets()[i], rotationPitch, rotationYaw, 0);
                vectorCache[0][0] += posX;
                vectorCache[0][1] += posY;
                vectorCache[0][2] += posZ;
                seats.get(i).setPosition(vectorCache[0][0], vectorCache[0][1], vectorCache[0][2]);
            }
        }

        //be sure the owner entityID is currently loaded, this variable is dynamic so we don't save it to NBT.
        if (!worldObj.isRemote &&ticksExisted %10==0){

            manageFuel();

            @Nullable
            Entity player = CommonProxy.getEntityFromUuid(owner);
            if (player instanceof EntityPlayer) {
                ownerName = ((EntityPlayer) player).getDisplayName();
            }
            //sync the linked transports with client, and on server, easier to use an ID than a UUID.
            Entity linkedTransport = CommonProxy.getEntityFromUuid(frontLinkedTransport);
            if (linkedTransport instanceof GenericRailTransport && (frontLinkedID == null || linkedTransport.getEntityId() != frontLinkedID)) {
                frontLinkedID = linkedTransport.getEntityId();
                updateWatchers = true;
            }
            linkedTransport = CommonProxy.getEntityFromUuid(backLinkedTransport);
            if (linkedTransport instanceof GenericRailTransport && (backLinkedID == null || linkedTransport.getEntityId() != backLinkedID)) {
                backLinkedID = linkedTransport.getEntityId();
                updateWatchers = true;
            }

            if (!worldObj.isRemote && getBoolean(boolValues.DERAILED) && !displayDerail){
                //MinecraftServer.getServer().addChatMessage(new ChatComponentText(getOwner().getName()+"'s " + StatCollector.translateToLocal(getItem().getUnlocalizedName()) + " has derailed!"));
                displayDerail = true;
            }

            if(updateWatchers){
                dataWatcher.updateObject(20, fluidTank==null?0:fluidTank.amount);
                if (!getType().isTrain()) {
                    dataWatcher.updateObject(14, fluidTank == null ? -1 : fluidTank.getFluidID());
                }
                this.dataWatcher.updateObject(23, ownerName);
                this.dataWatcher.updateObject(17, bools.toInt());
                this.dataWatcher.updateObject(21, frontLinkedID!=null?frontLinkedID:-1);
                this.dataWatcher.updateObject(22, backLinkedID!=null?backLinkedID:-1);
            }
        }

        if (!(this instanceof EntityTrainCore) && ticksExisted %60 ==0){
            GenericRailTransport front = null;
            boolean hasReversed = false;
            List<GenericRailTransport> checked = new ArrayList<>();
            if (frontLinkedID!=null){
                front = (GenericRailTransport) worldObj.getEntityByID(frontLinkedID);
            } else if (backLinkedID != null){
                front = (GenericRailTransport) worldObj.getEntityByID(backLinkedID);
                hasReversed = true;
            }
            hasTrain = false;

            while(front != null){
                @Nullable
                Entity test = front.frontLinkedID!=null?worldObj.getEntityByID(front.frontLinkedID):null;
                //if it's null and we haven't reversed yet, start the loop over from the back. if we have reversed though, end the loop.
                if(test == null){
                    if (!hasReversed){
                        front = backLinkedID!=null?(GenericRailTransport)worldObj.getEntityByID(backLinkedID):null;
                        hasReversed = true;
                    } else {
                        front = null;
                    }
                    //if the list of checked transports doesn't contain the new one then check if it's an instance of a train, if it is, end the loop and set the values, otherwise continue to the next entry
                } else if (!checked.contains(test)) {
                    if (front instanceof EntityTrainCore){
                        hasTrain = true;
                        front=null; hasReversed = true;
                    } else {
                        front = (GenericRailTransport) test;
                    }
                    //if the list does contain the checked one, and we haven't reversed yet,  start the loop over from the back. if we have reversed though, end the loop.
                } else {
                    if (!hasReversed){
                        front = backLinkedID!=null?(GenericRailTransport)worldObj.getEntityByID(backLinkedID):null;
                        hasReversed = true;
                    } else {
                        front = null;
                    }
                }
            }
        }

        /*
         * be sure the client proxy has a reference to this so the lamps can be updated, and then every other tick, attempt to update the lamp position if it's necessary.
         */
        if (backBogie!=null && !isDead && worldObj.isRemote) {
            if (ClientProxy.EnableLights && !ClientProxy.carts.contains(this)) {
                ClientProxy.carts.add(this);
            }
            if (lamp.Y >1 && ticksExisted %2 ==0){
                vectorCache[0][0] =this.posX + getLampOffset().xCoord;
                vectorCache[0][1] =this.posY + getLampOffset().yCoord;
                vectorCache[0][2] =this.posZ + getLampOffset().zCoord;
                lamp.ShouldUpdate(worldObj, RailUtility.rotatePoint(vectorCache[0], rotationPitch, rotationYaw, 0));
            }

            //update the particles here rather than on render tick, it's not as smooth, but close enough and far less CPU use.
            if (ClientProxy.EnableSmokeAndSteam && getSmokeOffset() != null) {
                int itteration = 0;
                int maxSpawnThisTick = 0;
                for (float[] smoke : getSmokeOffset()) {
                    for (int i = 0; i < smoke[4]; i++) {
                        //we only want to spawn at most 5 particles per tick.
                        //this helps make them spawn more evenly rather than all at once. It also helps prevent a lot of lag.
                        if (getBoolean(boolValues.RUNNING) && particles.size() <= itteration && maxSpawnThisTick < 5) {
                            particles.add(new ParticleFX(this, smoke[3], smoke));
                            maxSpawnThisTick++;
                        } else if (maxSpawnThisTick == 0 && particles.size() > itteration) {
                            //if the particles have finished spawning in, move them.
                            particles.get(itteration).onUpdate(getBoolean(boolValues.RUNNING));
                        }
                        itteration++;
                    }
                }
            }
        }
    }


    /**
     * <h2>Rider offset</h2>
     * this runs every tick to be sure the riders, and seats, are in the correct positions.
     * NOTE: this only happens while there is an entity riding this, entities riding seats do not activate this function.
     */
    @Override
    public void updateRiderPosition() {
        if (getRiderOffsets() != null) {
            if (riddenByEntity != null) {
                vectorCache[2] = rotatePoint(getRiderOffsets()[0], rotationPitch, rotationYaw, 0);
                riddenByEntity.setPosition(vectorCache[2][0] + this.posX, vectorCache[2][1] + this.posY, vectorCache[2][2] + this.posZ);
            }

            for (int i = 0; i < seats.size(); i++) {
                vectorCache[2] = rotatePoint(getRiderOffsets()[i], rotationPitch, rotationYaw, 0);
                vectorCache[2][0] += posX;
                vectorCache[2][1] += posY;
                vectorCache[2][2] += posZ;
                seats.get(i).setPosition(vectorCache[2][0], vectorCache[2][1], vectorCache[2][2]);
            }
        }

    }


    /**
     * <h2>manage links</h2>
     * this is used to reposition the transport based on the linked transports.
     * If coupling is on then it will check sides without linked transports for anything to link to.
     */
    public void manageLinks(GenericRailTransport linkedTransport) {
        vectorCache[4][0]=linkedTransport.posX - this.posX;
        vectorCache[4][2]=linkedTransport.posZ - this.posZ;
        if (Math.abs(vectorCache[4][0]) + Math.abs(vectorCache[4][2]) >0.05) {

            double distance = MathHelper.sqrt_double((vectorCache[4][0] * vectorCache[4][0]) + (vectorCache[4][2] * vectorCache[4][2]));

            vectorCache[5][0] = vectorCache[4][0] / distance;
            vectorCache[5][2] = vectorCache[4][2] / distance;

            if (linkedTransport.frontLinkedID != null && linkedTransport.frontLinkedID == this.getEntityId()) {
                distance -= Math.abs(linkedTransport.getHitboxPositions()[0][0]);
            } else {
                distance -= Math.abs(linkedTransport.getHitboxPositions()[linkedTransport.getHitboxPositions().length - 1][0]);
            }
            if (this.frontLinkedID != null && this.frontLinkedID == linkedTransport.getEntityId()) {
                distance -= Math.abs(this.getHitboxPositions()[0][0]);
            } else {
                distance -= Math.abs(this.getHitboxPositions()[this.getHitboxPositions().length - 1][0]);
            }

            vectorCache[3][0] = 0.3 * distance * vectorCache[4][0];
            vectorCache[3][2] = 0.3 * distance * vectorCache[4][2];

            distance = (-vectorCache[3][0] - vectorCache[3][0]) * vectorCache[5][0] + (-vectorCache[3][2] - vectorCache[3][2]) * vectorCache[5][2];

            vectorCache[3][0] -= 0.4 * distance * vectorCache[5][0] * -1;
            vectorCache[3][2] -= 0.4 * distance * vectorCache[5][2] * -1;


            this.frontBogie.addVelocity(vectorCache[3][0], 0, vectorCache[3][2]);
            this.backBogie.addVelocity(vectorCache[3][0], 0, vectorCache[3][2]);
        }

    }


    /**
     * <h2>RF storage transfer</h2>
     * this is used to figure out how much RF this transport can take from another transport.
     * this is intended for rollingstock that can store power for an electric train.
     * @return the amount of RF this can accept
     */
    public int RequestPower(){
        if(getRFCapacity() - battery >20){
            return 20;
        } else if (getRFCapacity() - battery >0){
            return getRFCapacity() - battery;
        }
        return 0;
    }

    /**
     * <h2>Packet use</h2>
     * used to run functionality on server defined by a key press on the client.
     * sent to server by
     * @see ebf.tim.networking.PacketKeyPress
     * through
     * @see EventManager#onClientKeyPress(InputEvent.KeyInputEvent)
     */
    public boolean ProcessPacket(int functionID){
        switch (functionID){
            case 4:{ //Toggle brake
                setBoolean(boolValues.PARKING, !getBoolean(boolValues.PARKING));
                return true;
            }case 15: {
                setBoolean(boolValues.BRAKE, false);
                return true;
            }case 16: {
                setBoolean(boolValues.BRAKE, true);
                return true;
            }case 5: { //Toggle lamp
                setBoolean(boolValues.LAMP, !getBoolean(boolValues.LAMP));
                return true;
            }case 6:{ //Toggle locked
                setBoolean(boolValues.LOCKED, !getBoolean(boolValues.LOCKED));
                return true;
            }case 7:{ //Toggle coupling
                boolean toset = !getBoolean(boolValues.COUPLINGFRONT);
                setBoolean(boolValues.COUPLINGFRONT, toset);
                setBoolean(boolValues.COUPLINGBACK, toset);
                return true;
            }case 10:{ //Toggle transport creative mode
                setBoolean(boolValues.CREATIVE, !getBoolean(boolValues.CREATIVE));
                return true;
            }case 12:{ //drop key to transport
                entityDropItem(key.getStack(), 1);
                return true;
            }case 13:{ //unlink transports
                Entity transport;
                //frontLinkedTransport
                if (frontLinkedID != null){
                    transport = worldObj.getEntityByID(frontLinkedID);
                    if (transport instanceof GenericRailTransport){
                        if(((GenericRailTransport) transport).frontLinkedID == this.getEntityId()){
                            ((GenericRailTransport) transport).frontLinkedTransport = null;
                            ((GenericRailTransport) transport).frontLinkedID = null;
                        } else {
                            ((GenericRailTransport) transport).backLinkedTransport = null;
                            ((GenericRailTransport) transport).backLinkedID = null;
                        }
                        frontLinkedTransport = null;
                        frontLinkedID = null;
                        ((GenericRailTransport) transport).updateWatchers = true;
                    }
                }
                //backLinkedTransport
                if (backLinkedID != null){
                    transport = worldObj.getEntityByID(backLinkedID);
                    if (transport instanceof GenericRailTransport){
                        if(((GenericRailTransport) transport).frontLinkedID == this.getEntityId()){
                            ((GenericRailTransport) transport).frontLinkedTransport = null;
                            ((GenericRailTransport) transport).frontLinkedID = null;
                        } else {
                            ((GenericRailTransport) transport).backLinkedTransport = null;
                            ((GenericRailTransport) transport).backLinkedID = null;
                        }
                        backLinkedTransport = null;
                        backLinkedID = null;
                        ((GenericRailTransport) transport).updateWatchers = true;
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * <h2>Permissions handler</h2>
     * Used to check if the player has permission to do whatever it is the player is trying to do. Yes I could be more vague with that.
     *
     * @param player the player attenpting to interact.
     * @param driverOnly can this action only be done by the driver/conductor?
     * @return if the player has permission to continue
     */
    public boolean getPermissions(EntityPlayer player, boolean driverOnly) {
        //if this requires the player to be the driver, and they aren't, just return false before we even go any further.
        if (driverOnly && player.getEntityId() != this.riddenByEntity.getEntityId()){
            return false;
        }

        //be sure operators and owners can do whatever
        if ((player.capabilities.isCreativeMode && player.canCommandSenderUseCommand(2, "")) || ownerName.equals(player.getDisplayName())) {
            return true;
        }

        //if the key is needed, like for trains and freight
        if (getBoolean(boolValues.LOCKED) && player.inventory.hasItem(key.getItem())) {
            return true;
        }
        //if a ticket is needed like for passenger cars
        if(getBoolean(boolValues.LOCKED) && getRiderOffsets().length>1){
            for(ItemStack stack : player.inventory.mainInventory){
                if(ticket.equals(stack)){
                    return true;
                }
            }
            return false;
        }

        //all else fails, just return if this is locked.
        return !getBoolean(boolValues.LOCKED);

    }

    @Override
    protected void setRotation(float p_70101_1_, float p_70101_2_) {
        this.prevRotationYaw = this.rotationYaw = p_70101_1_;
        this. prevRotationPitch = this.rotationPitch = p_70101_2_;
    }

    protected void setRotation(float yaw, float pitch, float roll){
        setRotation(yaw, pitch);
        this.prevRotationRoll = this.rotationRoll = roll;
    }


    public GameProfile getOwner(){
        if (ownerName != null && !ownerName.equals("") && worldObj.getPlayerEntityByName(ownerName) !=null){
            return (worldObj.getPlayerEntityByName(ownerName)).getGameProfile();
        }
        return null;
    }


    @Override
    public boolean canPassFluidRequests(Fluid fluid){
        return getTankCapacity() !=0;
    }

    @Override
    public boolean canAcceptPushedFluid(EntityMinecart requester, Fluid fluid){
        return false;
    }

    @Override
    public boolean canProvidePulledFluid(EntityMinecart requester, Fluid fluid){
        return getTankCapacity()!=0 && canDrain(ForgeDirection.UNKNOWN, fluid);
    }

    @Override
    public void setFilling(boolean filling){}

    /*
     * <h2>Inherited variables</h2>
     * these functions are overridden by classes that extend this so that way the values can be changed indirectly.
     */
    /**returns the lengths from center that represent the offset each bogie should render at*/
    public List<Double> getRenderBogieOffsets(){return new ArrayList<>();}
    /**returns the type of transport, for a list of options:
     * @see TrainsInMotion.transportTypes */
    @Deprecated
    public TrainsInMotion.transportTypes getType(){return null;}
    /**returns the rider offsets, each of the outer arrays represents a new rider seat,
     * the first value of the double[] inside that represents length from center in blocks.
     * the second represents height offset in blocks
     * the third value is for the horizontal offset*/
    public double[][] getRiderOffsets(){return new double[][]{{0,0,0}};}
    /**returns the positions for the hitbox, they are defined by length from center.
     * must have at least 4 hitboxes, the first and last values are used for coupling positions*/
    @Deprecated
    public double[][] getHitboxPositions(){return new double[][]{{-1.6,0,0},{1,0,0},{0,0,0},{1,0,0},{1.6,0,0}};}
    /**returns the item of the transport, this should be a static value in the transport's class.*/
    @Deprecated
    public Item getItem(){return null;}
    /**defines the size of the inventory, not counting any special slots like for fuel.
     * @see TrainsInMotion.inventorySizes*/
    public TrainsInMotion.inventorySizes getInventorySize(){return TrainsInMotion.inventorySizes.FREIGHT_ONE;}
    /**defines the offset for the lamp in X/Y/Z*/
    @Deprecated
    public Vec3d getLampOffset(){return new Vec3d(0,0,0);}
    /**defines the radius in microblocks that the pistons animate*/
    public float getPistonOffset(){return 0;}
    /**defines smoke positions, the outer array defines each new smoke point, the inner arrays define the X/Y/Z*/
    @Deprecated
    public float[][] getSmokeOffset(){return new float[0][0];}
    /**defines the length from center of the transport, thus is used for the motion calculation*/
    @Deprecated
    public int getLengthFromCenter(){return 1;}
    /**defines the render scale, minecraft's default is 0.0625*/
    public float getRenderScale(){return 0.0625f;}
    /**defines if the transport is explosion resistant*/
    public boolean isReinforced(){return false;}
    /**defines the capacity of the fluidTank tank.
     * Usually value is 10,000 *the cubic meter capacity, so 242 gallons, is 0.9161 cubic meters, which is 9161 tank capacity
     *NOTE if this is used for a train, minimum value should be 1100, which is just a little over a single bucket to allow prevention of overheating.*/
    public int getTankCapacity(){return 0;}
    /**defines the capacity of the RF storage, intended for electric rollingstock that store power for the train.*/
    public int getRFCapacity(){return 0;}
    /**defines the ID of the owner*/
    public String getOwnerName(){return ownerName.equals("")?this.dataWatcher.getWatchableObjectString(23):ownerName;}
    /**this function allows individual trains and rollingstock to implement custom fuel consumption and management
     * @see FuelHandler#manageSteam(EntityTrainCore) */
    public void manageFuel(){}
    /**defines the weight of the transport.*/
    public float weightKg(){return 907.1847f;}
    /** returns the max fuel.
     * for steam trains this is cubic meters of the firebox size. (1.5 on average)
     * for diesel this is cubic meters. (11.3 on average)
     * for electric this is Kw. (400 on average)
     * for nuclear this is the number of fusion cores, rounded down. (usually 1)*/
    public float getMaxFuel(){return 1;}
    /**defines the functionality to run when rendering a gui on client*/
    public void guiClient(){}
    /**defines the functionality to run when setting up the GUI on server*/
    public void guiServer(){}


    public Bogie[] getBogieModels(){return null;}

    public ResourceLocation getTexture(int index){
        return (index> transportSkins.size() || transportSkins.size()==0?new ResourceLocation(MODID, "null.png"):transportSkins.get(index));
    }

    public void addTransportSkins(ResourceLocation[] skins){
        for(ResourceLocation skin : skins){
            if (skin != null && skin.getResourceDomain() == null){
                transportSkins.add(new ResourceLocation(MODID, skin.getResourcePath()));
            } else if (skin!= null) {
                if (transportSkins == null){
                    transportSkins = Collections.singletonList(skin);
                } else {
                    transportSkins.add(skin);
                }
            }
        }
    }

    public List<? extends ModelBase> getModel(){return new ArrayList<ModelBase>();}


    /*
     * <h1>Inventory management</h1>
     */

    /**
     * <h2>inventory size</h2>
     * @return the number of slots the inventory should have.
     * if it's a train we have to calculate the size based on the type and the size of inventory its supposed to have.
     * trains get 1 extra slot by default for fuel, steam and nuclear steam get another slot, and if it can take passengers there's another slot, this is added to the base inventory size.
     * if it's not a train or rollingstock, then just return the base amount for a crafting table.
     */
    @Override
    public int getSizeInventory() {
        int size =0;
        switch (getType()){
            case NUCLEAR_STEAM: case STEAM:case TANKER:{
                size=2;break;
            }
            case DIESEL:case ELECTRIC:case NUCLEAR_ELECTRIC:case TENDER:{
                size=1;break;
            }
        }
        if (getRiderOffsets() != null && getRiderOffsets().length >1){
            size++;
        }
        return 1+ size+ (9 * getInventorySize().getRow());
    }

    /**
     * <h2>get item</h2>
     * @return the item in the requested slot
     */
    @Override
    public ItemStack getStackInSlot(int slot) {
        if (inventory == null || slot <0 || slot >= inventory.size()){
            return null;
        } else {
            return inventory.get(slot).getStack();
        }
    }

    /**
     * <h2>decrease stack size</h2>
     * @return the itemstack with the decreased size. If the decreased size is equal to or less than the current stack size it returns null.
     */
    @Override
    public ItemStack decrStackSize(int slot, int stackSize) {
        if (inventory!= null && getSizeInventory()>=slot) {
            return inventory.get(slot).decrStackSize(stackSize);
        } else {
            return null;
        }
    }

    /**
     * <h2>Set slot</h2>
     * sets the slot contents, this is a direct override so we don't have to compensate for anything.
     */
    @Override
    public void setInventorySlotContents(int slot, ItemStack itemStack) {
        if (inventory != null && slot >=0 && slot <= getSizeInventory()) {
            inventory.get(slot).setSlotContents(itemStack);
        }
    }

    /**
     * <h2>name and stack limit</h2>
     * These are grouped together because they are pretty self-explanatory.
     */
    @Override
    public String getInventoryName() {return getItem().getUnlocalizedName() + ".storage";}
    @Override
    public boolean hasCustomInventoryName() {return inventory != null;}
    @Override
    public int getInventoryStackLimit() {return getType().isTanker()?1:inventory!=null?64:0;}

    /**
     * <h2>is Locked</h2>
     * returns if the entity is locked, and if it is, if the player is the owner.
     * This makes sure the inventory can be accessed by anyone if its unlocked and only by the owner when it is locked.
     * if it's a tile entity, it's just another null check to be sure no one crashes.
     */
    @Override
    public boolean isUseableByPlayer(EntityPlayer p_70300_1_) {return getPermissions(p_70300_1_, false);}

    /**
     * <h2>filter slots</h2>
     * used to filter inventory slots for specific items or data.
     * @param slot the slot that yis being interacted with
     * @param itemStack the stack that's being added
     * @return whether or not it can be added
     */
    @Override
    public boolean isItemValidForSlot(int slot, ItemStack itemStack) {
        if (itemStack == null){return true;}
        //compensate for specific rollingstock
        switch (getType()) {
            case LOGCAR: {
                return RailUtility.isLog(itemStack) || RailUtility.isPlank(itemStack);
            }
            case COALHOPPER: {
                return RailUtility.isCoal(itemStack);
            }
            case STEAM: {
                if (slot == 36) {
                    return TileEntityFurnace.getItemBurnTime(itemStack) > 0;
                } else if (slot ==37) {
                    return itemStack.getItem() instanceof ItemBucket || FuelHandler.isUseableFluid(itemStack, this) != null;
                } else {
                    return true;
                }
            }
            case ELECTRIC: case DIESEL: case B_UNIT: case TANKER:{return slot==0 && FuelHandler.isUseableFluid(itemStack, this) != null;}
        }
        return true;
    }

    /**
     * <h2>Add item to train inventory</h2>
     * custom function for adding items to the train's inventory.
     * similar to a container's TransferStackInSlot function, this will automatically sort an item into the inventory.
     * if there is no room in the inventory for the item, it will drop on the ground.
     */
    public void addItem(ItemStack item){
        for(ItemStackSlot slot : inventory){
            item = slot.mergeStack(item);
            if (item == null){
                return;
            }
        }
        entityDropItem(item, item.stackSize);
    }

    /**
     * <h2>inventory percentage count</h2>
     * calculates percentage of inventory used then returns a value based on the intervals.
     * for example if the inventory is half full and the intervals are 100, it returns 50. or if the intervals were 90 it would return 45.
     */
    public int calculatePercentageOfSlotsUsed(int indexes){
        if (inventory == null){
            return 0;
        }
        float i=0;
        for (ItemStackSlot item : inventory){
            if (item.getHasStack()){
                i++;
            }
        }
        return i>0?MathHelper.floor_double(((i / getSizeInventory()) *indexes)+0.5):0;
    }


    /**
     * <h2>get an item from inventory to render</h2>
     * cycles through the items in the inventory and returns the first non-null item that's index is greater than the provided number.
     * if it fails to find one it subtracts one from the index and tries again, and keeps trying until the index is negative, in which case it returns 0.
     */
    public ItemStack getFirstBlock(int index){
        for (int i=0; i<getSizeInventory(); i++){
            if (i>= index && inventory.get(i) != null && inventory.get(i).getHasStack() && inventory.get(i).getItem() instanceof ItemBlock){
                return inventory.get(i).getStack();
            }
        }
        return getFirstBlock(index>0?index-1:0);
    }

    /*
     * <h2>unused</h2>
     * we have to initialize these values, but due to the design of the entity we don't actually use them.
     */
    /**used to sync the inventory on close.*/
    @Override
    public ItemStack getStackInSlotOnClosing(int p_70304_1_) {
        return inventory==null || inventory.size()<p_70304_1_?null:inventory.get(p_70304_1_).getStack();
    }
    @Override
    public void markDirty() {forceBackupTimer = 30;}
    /**called when the inventory GUI is opened*/
    @Override
    public void openInventory() {}
    /**called when the inventory GUI is closed*/
    @Override
    public void closeInventory() {
        if (!worldObj.isRemote){
            ServerLogger.writeWagonToFolder(this);
        }
    }

    public void dropAllItems() {
        if (inventory != null) {
            for (ItemStackSlot slot : inventory) {
                if (slot.getStack() != null) {
                    this.entityDropItem(slot.getStack(), 1);
                    slot.setSlotContents(null);
                }
            }
        }
    }


    /*
     * <h1>Fluid Management</h1>
     */
    /**Returns true if the given fluid can be extracted.*/
    @Override
    public boolean canDrain(@Nullable ForgeDirection from, Fluid resource){return fluidTank != null && getTankAmount()>0 && (fluidTank.getFluid() == resource || resource == null);}
    /**Returns true if the given fluid can be inserted into the fluid tank.*/
    @Override
    public boolean canFill(@Nullable ForgeDirection from, Fluid resource){return getTankCapacity()>0 && resource!=null && (fluidTank == null || fluidTank.getFluid() == resource);}
    //attempt to drain a set amount
    @Override
    public FluidStack drain(@Nullable ForgeDirection from, int drain, boolean doDrain){
        if (!canDrain(null,null)){
            return null;
        } else {
            int amountToDrain = getTankAmount() < drain?getTankAmount():drain;
            if (doDrain){
                if (amountToDrain == getTankAmount()) {
                    fluidTank = null;
                    updateWatchers=true;
                } else {
                    fluidTank.amount -= amountToDrain;
                    updateWatchers=true;
                }
            }
            return fluidTank!=null?new FluidStack(fluidTank.getFluid(), amountToDrain):null;
        }
    }

    /**drain with a fluidStack, this is mostly a redirect to
     * @see #drain(ForgeDirection, int, boolean) but with added filtering for fluid type.
     */
    @Override
    public FluidStack drain(@Nullable ForgeDirection from, FluidStack resource, boolean doDrain){
        return drain(from, resource.amount,doDrain);
    }
    /**returns the amount of fluid in the tank. 0 if the tank is null*/
    public int getTankAmount(){
        if(worldObj.isRemote){
            return this.dataWatcher.getWatchableObjectInt(20);
        } else {
            return fluidTank != null ? fluidTank.amount : 0;
        }
    }

    /**checks if the fluid can be put into the tank, and if doFill is true, will actually attempt to add the fluid to the tank.
     * @return the amount of fluid that was or could be put into the tank.*/
    @Override
    public int fill(@Nullable ForgeDirection from, FluidStack resource, boolean doFill){
        //if the tank has no capacity, or the filter prevents this fluid, or the fluid in the tank already isn't the same.
        if (resource==null || !canFill(null, resource.getFluid())){
            return 0;
        }
        int amountToFill;
        //if the tank is null, figure out how much fluid to add based on tank capacity.
        if (fluidTank == null){
            amountToFill = getTankCapacity() < resource.amount?getTankCapacity():resource.amount;
            if (doFill) {
                fluidTank = new FluidStack(resource.getFluid(), amountToFill);
                updateWatchers=true;
            }
            //if the tank isn't null, we also have to check the amount already in the tank
        } else {
            amountToFill = getTankCapacity() -getTankAmount() < resource.amount?getTankCapacity()-getTankAmount():resource.amount;
            if (doFill){
                fluidTank.amount += amountToFill;
                updateWatchers=true;
            }
        }
        return amountToFill;
    }
    /**returns the list of fluid tanks and their capacity.*/
    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from){
        return new FluidTankInfo[]{new FluidTankInfo(fluidTank!=null?fluidTank:new FluidStack(FluidRegistry.WATER,0), getTankCapacity())};
    }

    /*
     * <h3> chunk management</h3>
     * small chunk management for the entity, most all of it is handled in
     * @see ChunkHandler
     */

    /**@return the chunk ticket of this entity*/
    public ForgeChunkManager.Ticket getChunkTicket(){return chunkTicket;}
    /**sets the chunk ticket of this entity to the one provided.*/
    public void setChunkTicket(ForgeChunkManager.Ticket ticket){chunkTicket = ticket;}

    /**attempts to get a ticket for chunkloading, sets the ticket's values*/
    private void requestTicket() {
        ForgeChunkManager.Ticket ticket = ForgeChunkManager.requestTicket(TrainsInMotion.instance, worldObj , ForgeChunkManager.Type.ENTITY);
        if(ticket != null) {
            ticket.bindEntity(this);
            setChunkTicket(ticket);
        }
    }

}
