package trains.entities;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.audio.ISound;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import trains.utility.FuelHandler;
import trains.utility.InventoryHandler;

import java.util.UUID;

/**
 * <h2> Train core</h2>
 * this is the management core for all trains.
 */
public class EntityRollingStockCore extends GenericRailTransport {

    /**
     * <h3>variables</h3>
     * isRunning is used for non-steam based trains to define if it's actually on.
     * fuelHandler manages the items for fuel, and the fuel itself.
     * accelerator defines the speed percentage the user is attempting to apply.
     * destination is used for routing, railcraft and otherwise.
     * lastly the inventory defines the item storage of the train.
     */
    public String destination ="";
    public InventoryHandler inventory = new InventoryHandler(this);



    /**
    * <h2> Base train Constructor</h2>
    *
    * default constructor for all trains, the first one is server only, the second is client only.
    *
    * @param owner the owner profile, used to define owner of the entity,
    * @param world the world to spawn the entity in, used in super's super.
    * @param xPos the x position to spawn entity at, used in super's super.
    * @param yPos the y position to spawn entity at, used in super's super.
    * @param zPos the z position to spawn entity at, used in super's super.
    */
    public EntityRollingStockCore(UUID owner, World world, double xPos, double yPos, double zPos){
        super(world);
        posY = yPos;
        posX = xPos;
        posZ = zPos;

        this.owner = owner;

        setSize(1f,2);
        this.boundingBox.minX = 0;
        this.boundingBox.minY = 0;
        this.boundingBox.minZ = 0;
        this.boundingBox.maxX = 0;
        this.boundingBox.maxY = 0;
        this.boundingBox.maxZ = 0;
    }
    //this constructor is for client side spawning
    public EntityRollingStockCore(World world){
        super(world);
    }

    /**
     * <h2> Data Syncing and Saving </h2>
     * this is explained in
     * @see GenericRailTransport#readSpawnData(ByteBuf)
     */
    @Override
    public void readSpawnData(ByteBuf additionalData) {
    super.readSpawnData(additionalData);
    }
    @Override
    public void writeSpawnData(ByteBuf buffer) {
        super.writeSpawnData(buffer);
    }
    @Override
    protected void readEntityFromNBT(NBTTagCompound tag) {
        super.readEntityFromNBT(tag);

        inventory.readNBT(tag, "items");
    }
    @Override
    protected void writeEntityToNBT(NBTTagCompound tag) {
        super.writeEntityToNBT(tag);

        tag.setTag("items", inventory.writeNBT());
    }


    /**
     * <h2> process train movement</h2>
     * called by onUpdate to figure out the amount of movement to apply every tick
     * @see #onUpdate()
     *
     * currently this is only intended to provide a rather static amount.
     */
    private float processMovement(double X){
        float speed = (float) X * 0.9f;
        return speed;
    }

    /**
     * <h2> on entity update</h2>
     * first we have to manage our speed by updating our motion vector and applying it to the bogies.
     * after that we let the super handle things.
     * @see GenericRailTransport#onUpdate()
     * lastly we use a tick check to define if we should manage fuel, since we shouldn't be doing that every tick.
     */
    @Override
    public void onUpdate() {
        if (bogie.size()>0){
            boolean collision = !hitboxHandler.getCollision(this);
            //handle movement for trains, this will likely need to be different for rollingstock.
            for (EntityBogie currentBogie : bogie) {
                if (collision) {
                    //motion = rotatePoint(new double[]{this.processMovement(currentBogie.motionX, currentBogie.motionZ), (float) motionY, 0.0f}, 0.0f, rotationYaw, 0.0f);
                    motion[0] = processMovement(currentBogie.motionX);
                    motion[2] = processMovement(currentBogie.motionZ);
                    motion[1] = currentBogie.motionY;
                    currentBogie.setVelocity(motion[0], motion[1], motion[2]);
                    currentBogie.minecartMove();
                } else {
                    motion = new double[]{0d, 0d, 0d};
                }
            }
        }
        super.onUpdate();
    }

}