package mrtjp.projectred.transportation

import codechicken.lib.data.{MCDataInput, MCDataOutput}
import codechicken.lib.raytracer.IndexedCuboid6
import codechicken.lib.render.{CCRenderState, TextureUtils}
import codechicken.lib.vec.{BlockCoord, Cuboid6, Rotation, Vector3}
import codechicken.microblock.ISidedHollowConnect
import codechicken.multipart._
import cpw.mods.fml.relauncher.{Side, SideOnly}
import mrtjp.projectred.api.IConnectable
import mrtjp.projectred.core._
import mrtjp.projectred.core.libmc.inventory.InvWrapper
import net.minecraft.client.renderer.RenderBlocks
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.nbt.{NBTTagCompound, NBTTagList}
import net.minecraft.util.MovingObjectPosition
import net.minecraftforge.common.util.ForgeDirection

import scala.collection.JavaConversions._

abstract class SubcorePipePart extends TMultiPart with TCenterConnectable with TSwitchPacket with TNormalOcclusion with ISidedHollowConnect
{
    var meta:Byte = 0

    def preparePlacement(side:Int, meta:Int)
    {
        this.meta = meta.asInstanceOf[Byte]
    }

    override def save(tag:NBTTagCompound)
    {
        tag.setInteger("connMap", connMap)
        tag.setByte("meta", meta)
    }

    override def load(tag:NBTTagCompound)
    {
        connMap = tag.getInteger("connMap")
        meta = tag.getByte("meta")
    }

    override def writeDesc(packet:MCDataOutput)
    {
        packet.writeByte(clientConnMap)
        packet.writeByte(meta)
    }

    override def readDesc(packet:MCDataInput)
    {
        connMap = packet.readUByte()
        meta = packet.readByte()
    }

    override def read(packet:MCDataInput, key:Int) = key match
    {
        case 1 =>
            connMap = packet.readUByte()
            tile.markRender()
        case _ =>
    }

    def clientConnMap = connMap&0x3F|connMap>>6&0x3F

    def sendConnUpdate()
    {
        getWriteStreamOf(1).writeByte(clientConnMap)
    }

    override def discoverOpen(s:Int) = getInternal(s) match
    {
        case null => true
        case _ =>
            PipeBoxes.expandBounds = s
            val fits = tile.canReplacePart(this, this)
            PipeBoxes.expandBounds = -1
            fits
    }

    override def discoverInternal(s:Int) = false

    override def onPartChanged(part:TMultiPart)
    {
        if (!world.isRemote) if (updateOutward()) sendConnUpdate()
    }

    override def onNeighborChanged()
    {
        if (!world.isRemote) if (updateExternalConns()) sendConnUpdate()
    }

    override def onAdded()
    {
        super.onAdded()
        if (!world.isRemote) if (updateInward()) sendConnUpdate()
    }

    override def onRemoved()
    {
        super.onRemoved()
        if (!world.isRemote) notifyAllExternals()
    }

    override def onChunkLoad()
    {
        if ((connMap&0x80000000) != 0) // converter flag
        {
            connMap = 0
            updateOutward()
            tile.markDirty()
        }
    }

    override def onMaskChanged()
    {
        sendConnUpdate()
    }

    def getItem = getPipeType.makeStack
    def getPipeType = PipeDefs.values(meta)

    def getType = getPipeType.partname

    override def getStrength(hit:MovingObjectPosition, player:EntityPlayer)  = 2

    override def getDrops = Seq(getItem)

    override def pickItem(hit:MovingObjectPosition) = getItem

    override def getHollowSize(side:Int) = 8

    override def getSubParts =
    {
        val b = getCollisionBoxes
        var i = Seq[IndexedCuboid6]()
        for (c <- b) i :+= new IndexedCuboid6(0, c)
        i
    }

    override def getOcclusionBoxes =
    {
        import mrtjp.projectred.transportation.PipeBoxes._
        if (expandBounds >= 0) Seq(oBounds(expandBounds))
        else Seq(oBounds(6))
    }

    override def getCollisionBoxes =
    {
        import mrtjp.projectred.transportation.PipeBoxes._
        var boxes = Seq(oBounds(6))
        for (s <- 0 until 6) if (maskConnects(s)) boxes :+= oBounds(s)
        boxes
    }
}

object PipeBoxes
{
    var oBounds =
    {
        val boxes = new Array[Cuboid6](7)
        val w = 2/8D
        boxes(6) = new Cuboid6(0.5-w, 0.5-w, 0.5-w, 0.5+w, 0.5+w, 0.5+w)
        for (s <- 0 until 6)
            boxes(s) = new Cuboid6(0.5-w, 0, 0.5-w, 0.5+w, 0.5-w, 0.5+w).apply(Rotation.sideRotations(s).at(Vector3.center))
        boxes
    }
    var expandBounds = -1
}

trait TPipeTravelConditions
{
    /**
     * Filter used only on network pipes for their network flags
     * and item filters.
     */
    def networkFilter = PathFilter.default

    /**
     * Filter used on every pipe.  Specifies the path flags, color filters,
     * and item filters.
     * @param inputDir Direction of input on pipe
     * @param outputDir Direction of output on pipe
     * @return The path filter
     */
    def routeFilter(inputDir:Int, outputDir:Int) = PathFilter.default

    def routeWeight = 1
}

class PayloadPipePart extends SubcorePipePart with TPipeTravelConditions
{
    val itemFlow = new PayloadMovement
    var initialized = false

    override def save(tag:NBTTagCompound)
    {
        super.save(tag)
        val nbttaglist = new NBTTagList
        for (r <- itemFlow.it)
        {
            val payloadData = new NBTTagCompound
            nbttaglist.appendTag(payloadData)
            r.save(payloadData)
        }
        tag.setTag("itemFlow", nbttaglist)
    }

    override def load(tag:NBTTagCompound)
    {
        super.load(tag)
        val nbttaglist = tag.getTagList("itemFlow", 0)

        for (j <- 0 until nbttaglist.tagCount)
        {
            try
            {
                val payloadData = nbttaglist.getCompoundTagAt(j)
                val r = RoutedPayload()
                r.bind(this)
                r.load(payloadData)
                if (!r.isCorrupted) itemFlow.scheduleLoad(r)
            }
            catch {case t:Throwable =>}
        }
    }

    override def read(packet:MCDataInput, key:Int) = key match
    {
        case 4 => handleItemUpdatePacket(packet)
        case _ => super.read(packet, key)
    }

    override def update()
    {
        super.update()
        if (!initialized) initialized = true
        pushItemFlow()
    }

    def pushItemFlow()
    {
        itemFlow.executeLoad()
        itemFlow.exececuteRemove()
        for (r <- itemFlow.it) if (r.isCorrupted) itemFlow.scheduleRemoval(r)
        else
        {
            r.moveProgress(r.speed)
            if (r.isEntering && hasReachedMiddle(r))
            {
                r.isEntering = false
                if (r.output == ForgeDirection.UNKNOWN) handleDrop(r)
                else centerReached(r)
            }
            else if (!r.isEntering && hasReachedEnd(r) && itemFlow.scheduleRemoval(r)) endReached(r)
        }
        itemFlow.exececuteRemove()
    }

    def handleDrop(r:RoutedPayload)
    {
        if (itemFlow.scheduleRemoval(r)) if (!world.isRemote)
        {
            r.resetTrip
            world.spawnEntityInWorld(r.getEntityForDrop(x, y, z))
        }
    }

    def resolveDestination(r:RoutedPayload)
    {
        chooseRandomDestination(r)
    }

    def chooseRandomDestination(r:RoutedPayload)
    {
        var moves = Seq[ForgeDirection]()
        for (i <- 0 until 6) if((connMap&1<<i) != 0 && i != r.input.getOpposite.ordinal)
        {
            val t = getStraight(i)
            if (t.isInstanceOf[PayloadPipePart]) moves :+= ForgeDirection.getOrientation(i)
        }

        if (moves.isEmpty) r.output = r.input.getOpposite
        else r.output = moves(world.rand.nextInt(moves.size))
    }

    def endReached(r:RoutedPayload)
    {
        if (!world.isRemote) if (!maskConnects(r.output.ordinal) || !passToNextPipe(r))
        {
            val inv = InvWrapper.getInventory(world, new BlockCoord(tile).offset(r.output.ordinal))
            if (inv != null)
            {
                val w = InvWrapper.wrap(inv).setSlotsFromSide(r.output.getOpposite.ordinal)
                r.payload.stackSize -= w.injectItem(r.payload.makeStack, true)
            }
            if (r.payload.stackSize > 0) bounceStack(r)
        }
    }

    def bounceStack(r:RoutedPayload)
    {
        itemFlow.unscheduleRemoval(r)
        r.isEntering = true
        r.input = r.output.getOpposite
        resolveDestination(r)
        adjustSpeed(r)
        sendItemUpdate(r)
    }

    def centerReached(r:RoutedPayload)
    {
        if (!maskConnects(r.output.ordinal)) resolveDestination(r)
    }

    def passToNextPipe(r:RoutedPayload) =
    {
        getStraight(r.output.ordinal()) match
        {
            case pipe:PayloadPipePart =>
                pipe.injectPayload(r, r.output)
                true
            case _ => false
        }
    }

    def adjustSpeed(r:RoutedPayload)
    {
        r.speed = Math.max(r.speed-0.01f, r.priority.speed)
    }

    protected def hasReachedMiddle(r:RoutedPayload) = r.progress >= 0.5F

    protected def hasReachedEnd(r:RoutedPayload) = r.progress >= 1.0F

    def injectPayload(r:RoutedPayload, in:ForgeDirection)
    {
        if (r.isCorrupted) return
        if (itemFlow.delegate.contains(r)) return
        r.bind(this)
        r.reset()
        r.input = in
        itemFlow.add(r)

        adjustSpeed(r)
        if (r.progress > 0.0F) r.progress = Math.max(0, r.progress-1.0F)

        if (!world.isRemote)
        {
            resolveDestination(r)
            sendItemUpdate(r)
        }
    }

    override def onNeighborChanged()
    {
        super.onNeighborChanged()
        val connCount = Integer.bitCount(connMap)

        if (connCount == 0) if (!world.isRemote) for (r <- itemFlow.it) if (itemFlow.scheduleRemoval(r))
        {
            r.resetTrip
            world.spawnEntityInWorld(r.getEntityForDrop(x, y, z))
        }
    }

    override def onRemoved()
    {
        super.onRemoved()
        if (!world.isRemote) for (r <- itemFlow.it)
        {
            r.resetTrip
            world.spawnEntityInWorld(r.getEntityForDrop(x, y, z))
        }
    }

    def sendItemUpdate(r:RoutedPayload)
    {
        val out = getWriteStreamOf(4)
        out.writeShort(r.payloadID)
        out.writeFloat(r.progress)
        out.writeItemStack(r.getItemStack)
        out.writeByte(r.input.ordinal.asInstanceOf[Byte])
        out.writeByte(r.output.ordinal.asInstanceOf[Byte])
        out.writeFloat(r.speed)
        out.writeByte(r.priority.ordinal)
    }

    def handleItemUpdatePacket(packet:MCDataInput)
    {
        val id = packet.readShort
        val progress = packet.readFloat
        var r = itemFlow.get(id)
        if (r == null)
        {
            r = RoutedPayload(id)
            r.progress = progress
            itemFlow.add(r)
        }
        r.setItemStack(packet.readItemStack)
        r.input = ForgeDirection.getOrientation(packet.readByte)
        r.output = ForgeDirection.getOrientation(packet.readByte)
        r.speed = packet.readFloat
        r.setPriority(SendPriorities(packet.readUByte))
    }

    @SideOnly(Side.CLIENT)
    override def drawBreaking(r:RenderBlocks)
    {
        RenderPipe.renderBreakingOverlay(r.overrideBlockTexture, this)
    }

    @SideOnly(Side.CLIENT)
    override def renderStatic(pos:Vector3, pass:Int) =
    {
        if (pass == 0)
        {
            TextureUtils.bindAtlas(0)
            CCRenderState.setBrightness(world, x, y, z)
            RenderPipe.render(this, pos)
            true
        }
        else false
    }

    @SideOnly(Side.CLIENT)
    override def renderDynamic(pos:Vector3, frame:Float, pass:Int)
    {
        if (pass == 0)
        {
            TextureUtils.bindAtlas(0)
            CCRenderState.reset()
            CCRenderState.setBrightness(world, x, y, z)
            RenderPipe.renderItemFlow(this, pos, frame)
        }
    }

    @SideOnly(Side.CLIENT)
    def getIcon(side:Int) = getPipeType.sprites(0)

    override def canConnectPart(part:IConnectable, s:Int) = part match
    {
        case p:PayloadPipePart => true
        case _ => false
    }
}