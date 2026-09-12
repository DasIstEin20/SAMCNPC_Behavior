package io.samcnpc.behavior.operations

import com.google.gson.JsonParser
import io.samcnpc.behavior.command.TaskMachineCommands
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.TagParser
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Items
import net.minecraftforge.fml.ModList
import net.minecraftforge.registries.ForgeRegistries

/** Fixture configuration is data; both vanilla and external machines run the exact same task. */
internal object OperationMachineCase {
    private data class Fixture(val block: String,val feeds: String,val output: String,val settings: CompoundTag?,val mod: String,val version: String?)
    private val fixture: Fixture by lazy {
        val id=System.getProperty("samcnpc.machineFixture","vanilla-furnace")
        require(id in setOf("vanilla-furnace","iron-furnaces-4.1.8"))
        val resource=checkNotNull(OperationMachineCase::class.java.getResourceAsStream("/machines/$id.json"))
        val json=resource.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        Fixture(json["block"].asString,json["feeds"].asString,json["output"].asString,
            json["configuration"]?.let { TagParser.parseTag(it.asString) },json["mod"].asString,json["version"]?.asString)
    }
    fun prepare(s: OperationScene) {
        val f=fixture
        check(ModList.get().isLoaded(f.mod)) { "Fixture mod ${f.mod} is not loaded" }
        val version=ModList.get().getModContainerById(f.mod).orElseThrow().modInfo.version.toString()
        if (f.version != null) check(version == f.version) { "Fixture mod version differs: $version" }
        val block=checkNotNull(ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(f.block)))
        check(ForgeRegistries.BLOCKS.getKey(block)?.toString() == f.block)
        s.level.setBlock(s.pos(10,1,0),block.defaultBlockState(),3)
        val body=checkNotNull(s.level.getBlockEntity(s.pos(10,1,0)))
        // The external fixture represents a player-configured automation interface. Only
        // setup loads this data; the task cannot edit settings or bypass a denied face.
        if (f.settings != null) { val data=body.saveWithoutMetadata();data.merge(f.settings.copy());body.load(data);body.setChanged() }
        s.give(Items.RAW_IRON,4);s.give(Items.COAL,1);s.give(Items.IRON_INGOT,3)
        val endpoint=NpcContainerEndpoint(s.npc.snapshot().dimensionId,s.block(10,1,0))
        val inputs=TaskMachineCommands.parsePorts(f.feeds,endpoint,4);val output=TaskMachineCommands.parsePorts(f.output,endpoint,1).single()
        val definition=MachineTaskDefinition(endpoint.dimensionId,MachineFeeds(inputs),output,s.start,returnTo=s.start,pollTicks=10,budget=TaskBudget(3000))
        s.assign(definition)
        com.mojang.logging.LogUtils.getLogger().info("MACHINE_FIXTURE block={} mod={} version={} shapes={}",f.block,f.mod,version,checkNotNull(s.record.primary.machine).shapes)
    }
    fun checkpoint(s: OperationScene): Boolean {
        val state=s.record.primary.machine ?: return false
        return state.supplied.toList() == listOf(4,1) && state.collected == 0 && state.phase == MachinePhase.WORK
    }
    fun stock(s: OperationScene): CompoundTag {
        val definition=s.record.primary.definition as MachineTaskDefinition
        val world=s.npc.worldView();val tag=CompoundTag()
        for ((index,port) in definition.ports.withIndex()) {
            val observation=checkNotNull(world.observeContainer(port.endpoint))
            check(observation.blockId == fixture.block)
            val stack=observation.slots[port.slot].stack
            check(stack.isEmpty || stack.itemId == port.itemId)
            tag.putInt("port$index",stack.count)
        }
        return tag
    }
    fun verifyLoaded(s: OperationScene,saved: CompoundTag) {
        val actual=stock(s)
        // World processing is allowed while the task is paused. It may move material
        // from ore to ingot, but cannot refill inputs or credit an NPC extraction.
        check(actual.getInt("port0")+actual.getInt("port2") == saved.getInt("port0")+saved.getInt("port2"))
        check(actual.getInt("port0") <= saved.getInt("port0") && actual.getInt("port2") >= saved.getInt("port2"))
        check(actual.getInt("port1") <= saved.getInt("port1"))
        check(checkpoint(s) && s.carried("minecraft:iron_ingot") == 3)
    }
    fun verify(s: OperationScene) {
        s.requireCompleted();s.requireReturned()
        val state=checkNotNull(s.record.primary.machine)
        check(state.supplied.toList() == listOf(4,1) && state.collected == 4)
        check(s.carried("minecraft:raw_iron") == 0 && s.carried("minecraft:coal") == 0 && s.carried("minecraft:iron_ingot") == 7)
        val actual=stock(s);check((0..2).all { actual.getInt("port$it") == 0 })
        check(state.resources.entries["minecraft:iron_ingot"]?.initial == 3 && state.resources.entries["minecraft:iron_ingot"]?.supplied == 4)
    }
}
