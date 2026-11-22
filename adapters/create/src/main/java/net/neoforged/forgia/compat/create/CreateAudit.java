/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsBlockEntity;
import com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity;
import com.simibubi.create.content.contraptions.chassis.ChassisBlockEntity;
import com.simibubi.create.content.decoration.bracket.BracketedBlockEntityBehaviour;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import com.simibubi.create.content.decoration.slidingDoor.DoorControlBehaviour;
import com.simibubi.create.content.fluids.FlowSource;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.SmartFluidPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.StraightPipeBlockEntity;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.fluids.transfer.FluidManipulationBehaviour;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.TorquePropagator;
import com.simibubi.create.content.kinetics.base.DirectionalShaftHalvesBlockEntity;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticEffectHandler;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.crank.HandCrankBlockEntity;
import com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.flywheel.FlywheelBlockEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlockEntity;
import com.simibubi.create.content.kinetics.gearbox.GearboxBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity;
import com.simibubi.create.content.kinetics.motor.KineticScrollValueBehaviour;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.kinetics.transmission.GearshiftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import com.simibubi.create.content.kinetics.turntable.TurntableBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.content.logistics.crate.CrateBlockEntity;
import com.simibubi.create.content.logistics.crate.CreativeCrateBlockEntity;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import com.simibubi.create.content.logistics.itemHatch.ItemHatchBlockEntity;
import com.simibubi.create.content.logistics.tableCloth.TableClothFilteringBehaviour;
import com.simibubi.create.content.redstone.diodes.BrassDiodeScrollValueBehaviour;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.foundation.advancement.AdvancementBehaviour;
import com.simibubi.create.foundation.blockEntity.CachedRenderBBBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntityTicker;
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.edgeInteraction.EdgeInteractionBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.CapManipulationBehaviourBase;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.VersionedInventoryTrackerBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.BulkScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.forgia.compat.create.mixin.AirCurrentAccessor;
import net.neoforged.forgia.compat.create.mixin.ArmBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.BeltBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.BeltInventoryAccessor;
import net.neoforged.forgia.compat.create.mixin.CapManipulationBehaviourBaseAccessor;
import net.neoforged.forgia.compat.create.mixin.DeployerBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.DeployerFakePlayerAccessor;
import net.neoforged.forgia.compat.create.mixin.EncasedFanBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.FluidTankBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.FluidTransportBehaviourAccessor;
import net.neoforged.forgia.compat.create.mixin.FunnelBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.ItemVaultBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.KineticBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.KineticEffectHandlerAccessor;
import net.neoforged.forgia.compat.create.mixin.MechanicalCrafterBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.PipeConnectionAccessor;
import net.neoforged.forgia.compat.create.mixin.PumpBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.SmartBlockEntityAccessor;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.server.threading.api.AuditedBlockEntityTickAdapter;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

/** Pinned original bytecode plus bounded inspection of merged Mixin provenance. No upstream code is bundled. */
public final class CreateAudit {
    static final Class<?> CHASSIS_SCROLL_BEHAVIOUR = loadInternalClass(
            "com.simibubi.create.content.contraptions.chassis.ChassisBlockEntity$ChassisScrollValueBehaviour");
    static final Class<?> VALVE_HANDLE_SCROLL_BEHAVIOUR = loadInternalClass(
            "com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity$ValveHandleScrollValueBehaviour");
    static final Class<?> STANDARD_PIPE_FLUID_TRANSPORT_BEHAVIOUR = loadInternalClass(
            "com.simibubi.create.content.fluids.pipes.FluidPipeBlockEntity$StandardPipeFluidTransportBehaviour");
    static final Class<?> PUMP_FLUID_TRANSFER_BEHAVIOUR = loadInternalClass(
            "com.simibubi.create.content.fluids.pump.PumpBlockEntity$PumpFluidTransferBehaviour");

    private static final Map<Class<?>, String> OPTIONAL_LEAF_AUDITED = Map.ofEntries(
            Map.entry(ArmBlockEntity.class, "983bf6da7afd388d02f2b5b48a8d52543eb447337baebf8559f85429f2bb46b9"),
            Map.entry(GeneratingKineticBlockEntity.class, "e914e3ab8d1a310fddc2c8a4884d4120d46cc4202ad20d10f76e900242eb64df"),
            Map.entry(WaterWheelBlockEntity.class, "1219e362c15d1368645706a537a3632226f7f360f3d4524e9c1e783c103578da"),
            Map.entry(LargeWaterWheelBlockEntity.class, "0a955bf423919058afeff4009519a4352dd7b8686f421c0bc2b83db66fd4f104"),
            Map.entry(FluidTankBlockEntity.class, "62b3559c300f951ae404ab4d23dda419947d5413c18d6995bd3529858193eb6a"),
            Map.entry(EncasedFanBlockEntity.class, "3d6407a683bddfe04e6923561d431d0cdc91aa5d2336ecb838e4d4114858c00b"),
            Map.entry(AirCurrent.class, "16f2642ec5aee301fa4ab5f3663d4226d08ce8c01f61733abfef4ff90bbd8ba6"),
            Map.entry(BeltBlockEntity.class, "57d28560063ce67ef08c077cb32a859efbbf1dc47bcf62e4f43199c6cf9fbd0d"),
            Map.entry(BeltInventory.class, "e35a3f7ddd316e5901c1e57a0f2e5f00c4bf46dd296287096387591117b4c2ef"),
            Map.entry(FluidPipeBlockEntity.class, "29607d89f3c5bf33bbadfddb581b7c74e4310c0c8a32ce40e675a4b4483c7c76"),
            Map.entry(STANDARD_PIPE_FLUID_TRANSPORT_BEHAVIOUR, "b79b01e5bc7fdff16779f566bbefaa54723b172e42b3402ba697309ee62b719f"),
            Map.entry(StraightPipeBlockEntity.class, "6d8d253df39a0e3bdbc42d5e9e2780586c7a8bbc5e3b5036e636355690d08ada"),
            Map.entry(StraightPipeBlockEntity.StraightPipeFluidTransportBehaviour.class, "998baa53ca358484fc7f78944f11fcdc473e5a4042c2de1b262a1dd5ef0cb713"),
            Map.entry(FluidTransportBehaviour.class, "3833e0855760f46dd5576b82ed1a91e5ab683b1598756d340a0078768a408eea"),
            Map.entry(PipeConnection.class, "ad781981ba11ee2e5534cd803a0da72bb2199105a6df33cb9175591f467602a2"),
            Map.entry(FlowSource.class, "59dae260f86c9dd1171a0e9828dbfafb6d2d0be6c510a5b1e867c9c3a8339784"),
            Map.entry(FlowSource.Blocked.class, "02ec32e0bac75a1e450258af7c1c854501ff72e68648f122af78edf5fda7a8fb"),
            Map.entry(FlowSource.OtherPipe.class, "bd8a409234fd4942f5ad156352e9822798dc164eb945373d3bb7830b34fb5047"),
            Map.entry(FlowSource.FluidHandler.class, "c464f51ba8bf5bea535078ad6f16b0d0ca6ef6e4406d64166adfccc4afdb249c"),
            Map.entry(PumpBlockEntity.class, "6f3751a4fde1fac118fe2c5823149736017c1b6f42925b766a42e9b327b69107"),
            Map.entry(PUMP_FLUID_TRANSFER_BEHAVIOUR, "a85ce83b851411d9b200b383787b6c1c38dd988c8e0e1b7ca31925e29d1a9cf8"),
            Map.entry(FunnelBlockEntity.class, "c3a2707ffd32a6a42c8f50da04c947f0c3e6d02c41d344290134cb540d45b0ce"));

    private static final Map<Class<?>, String> AUDITED = Map.ofEntries(
            Map.entry(TorquePropagator.class, "2c3e376f0ebe57589f9bd4b238156b3016f272a91a2710a3d6fe152b25eceee9"),
            Map.entry(KineticNetwork.class, "bab6c531d5b916cbc231fb53083283cc049a0d58884cd669a8000adc474127f9"),
            Map.entry(SmartBlockEntity.class, "6e5272cffba116187a6c37a36321e1e76e67bb206f8d8804d60f9a92ee1ef2f9"),
            Map.entry(CachedRenderBBBlockEntity.class, "5b07ba1e45c9275415f44d5a690411a7f99b6bb406f6031caa335c631d9725bf"),
            Map.entry(SyncedBlockEntity.class, "aec47e832c36cbfc8b33c31af0ed3e93288cc77c3d86f7868f40bedd2eb750b7"),
            Map.entry(KineticBlockEntity.class, "3bce80f18d09ccff5e6c1b5cdbeeb2793f0a507006879b267148cde0df443572"),
            Map.entry(KineticEffectHandler.class, "dfe10be2e0eadc4367e441b09af3d925902901ea5098fcf4be354c44b8a20ae9"),
            Map.entry(DirectionalShaftHalvesBlockEntity.class, "eec965ed4713c9a417c6fd704e85f688b0e5798c419a3b0bb7b4ff156a17a636"),
            Map.entry(SimpleKineticBlockEntity.class, "44697e40f4b6d98fd4c91909a21a206f7d047c47f430a9c34fb18f47c57b60dc"),
            Map.entry(BracketedKineticBlockEntity.class, "8284d796d3b24a9526de58ec91aaaecf364766c6519a8f4ae8d900ef8e7fa4f7"),
            Map.entry(GearboxBlockEntity.class, "fe7ec69e1d207251109632835f2208e9937e3599521c63a05f8af0ae0edc8cc8"),
            Map.entry(SplitShaftBlockEntity.class, "a8a7c78cff60c5d65cbba9f4027c067946d92a67c09d7c1b2c8b1e6152d8f7e6"),
            Map.entry(ClutchBlockEntity.class, "cfd2f54cdcb2368c4ba1f614aba10905b6e718118b8f16a01c419aa3e88726c3"),
            Map.entry(GearshiftBlockEntity.class, "1b4913d1b690134949d4d37cb4ce21339e1aeca2088f5dc322bef099fd058a71"),
            Map.entry(GantryShaftBlockEntity.class, "02b658fff7feb780533fedccf67c035d3aa483355933b993fbc547a414f73908"),
            Map.entry(TurntableBlockEntity.class, "1e2ba2173739190b7bc3e230f99ee611d0788ed7fce538ce76f4f2e62c250072"),
            Map.entry(PoweredShaftBlockEntity.class, "0490727998a1be2deb813956a4f79822771d48de27631b487e9239e8f1258972"),
            Map.entry(DeployerBlockEntity.class, "1a80f6c0ce5cb57231314fe516013bf02cd6c74d83d2ca77b01023996b68dec7"),
            Map.entry(DeployerFakePlayer.class, "d748bd8c170b28fb43f8e0e7b21eb7fb0e83ff4700003aa460eb570a2acc77d5"),
            Map.entry(RollerBlockEntity.class, "0bc7aed843f98362c09c426bb06ea19dd9798a7811dc6503a21c873820c5a731"),
            Map.entry(ChassisBlockEntity.class, "8f76d896b4ea71a414aa0d82e7bcb5d0f6d88aaacde10d604ab532c7f82b3c69"),
            Map.entry(ContraptionControlsBlockEntity.class, "cf6f91a4e22ad5b18a29c3e43b1cfa55d9c6c6267ceeeff3121752ff8186bb28"),
            Map.entry(CopycatBlockEntity.class, "5569c6045082c1cea3a3737902c81e5c33ddb4c815011e64387e61b776d57c9f"),
            Map.entry(CrateBlockEntity.class, "ea099d5fb5c8b97ce53cd662813f4a7e2d2481b510bc18afdeb399504aa4c019"),
            Map.entry(CreativeCrateBlockEntity.class, "ca2c1f43fd33cdc352a49550ffa1bfb2e77338bc95e8a08f78564b77c24047c6"),
            Map.entry(ItemHatchBlockEntity.class, "5e074b7ba34ea181aae08ea71b235e474f86115c3a97f7cbc8d90cc75a99379e"),
            Map.entry(ItemVaultBlockEntity.class, "cbdd0eb66d576cab0e34551b0df19d32ce818d0e62179b8cb343ea2fd09c1333"),
            Map.entry(MechanicalCrafterBlockEntity.class, "70f2a84a2223541342cc50ee28803fa24636d1f97027146eeb0e6002793e52f6"),
            Map.entry(CapManipulationBehaviourBase.class, "264ea884c3bc7d5e9661423918a1ee390205486a22792d90d6f8450ef56a1ce6"),
            Map.entry(InvManipulationBehaviour.class, "f1ec2458f1da210132fffc01d21e33d26d3a89c96b4eeb82164d592a86d7c76c"),
            Map.entry(SmartBlockEntityTicker.class, "dce72d0f51ec909dc482c9e601e3f9bfcd80c01091fb2f618e2c40a4cbe2c64c"),
            Map.entry(MillstoneBlockEntity.class, "1b3558a8d881f68f1d5decdae731f9e93386f1dcc72f444bc39484172ca06573"),
            Map.entry(BlockEntityBehaviour.class, "2088a6dc96882f59f4df86430fe1c0f0bd277535ba282e341755f24060e162fc"),
            Map.entry(AdvancementBehaviour.class, "cd8a7214dcde3b891bea587426887dd8aa8a404fdc192cbf72a89ccaa6c65cc3"),
            Map.entry(DirectBeltInputBehaviour.class, "1fef40a6d01c4b8b65fc5e0ae554a7732e01c446d3fef392647a010e9fad6d9b"),
            Map.entry(BracketedBlockEntityBehaviour.class, "9ae1a461672e7edea503bc2e31bea11e05c724e053f383363a6db7e3d69c599e"),
            Map.entry(DoorControlBehaviour.class, "8e77f41d61efa2afadc9a960760b15f9cb9a75005be719edddc0562e084c5abb"),
            Map.entry(FluidManipulationBehaviour.class, "21bbd3e63fa4b25c43633af807a2d8f8b701535a5ed492ddad88dec0d78b7ae9"),
            Map.entry(BeltProcessingBehaviour.class, "892b5fb20e0de82a64be0d211eac3e199b87b9408a4253315b8685db503ce824"),
            Map.entry(TransportedItemStackHandlerBehaviour.class, "7e6fa760c44822fa595d6b5cc44faa1b1177b6ed608b0a4702d5b3a831fee7dc"),
            Map.entry(KineticScrollValueBehaviour.class, "b15c1c8bd210203ee4eb572e8dd139304891c2484572f009a94812ce9d348597"),
            Map.entry(TableClothFilteringBehaviour.class, "9dadda4567aa19a1c553721ffd36e7638ebdb13245fd1aac07dc8fce441869ef"),
            Map.entry(BrassDiodeScrollValueBehaviour.class, "a41cac890314238af8c44557328f483dede019719c96a273dcf4f9c0a899b3af"),
            Map.entry(LinkBehaviour.class, "05d882427e56f490cc29a50d62e30f659edb27af5ed16d632cdd1386af291800"),
            Map.entry(EdgeInteractionBehaviour.class, "1c3ce07203f02103b7282fe8da57f729ddd54006a62a1197745e22e44ad7687f"),
            Map.entry(FilteringBehaviour.class, "bb687031098b35cda9e69501c105f1e3f45f955a5ec04f697f888d4de4dc06bb"),
            Map.entry(VersionedInventoryTrackerBehaviour.class, "a1345909218019da7d9f8f07fc8d18e79915c59886943da9c62512ca9a371667"),
            Map.entry(BulkScrollValueBehaviour.class, "20382ea29507354751c35bf00bbc2c5be18a3d113cf329a662ac926fe0c1368c"),
            Map.entry(ScrollOptionBehaviour.class, "777833c32af6034a3316fda9902d352ed3ba6c7ed70d01118a3c9182df3f741b"),
            Map.entry(ScrollValueBehaviour.class, "d552962b5b1ede1e57e120eed7051cac0fa8b1fce8c91bb524641079802454b8"),
            Map.entry(CHASSIS_SCROLL_BEHAVIOUR, "5b8c7549bb9772bcbb34fbbc4d8b0e3a8abd9ce91936ea013563b5870be6aadf"));
    private static final Set<String> ACCESSORS = Set.of(
            "net.neoforged.forgia.compat.create.mixin.SmartBlockEntityAccessor",
            "net.neoforged.forgia.compat.create.mixin.KineticBlockEntityAccessor",
            "net.neoforged.forgia.compat.create.mixin.KineticEffectHandlerAccessor",
            "net.neoforged.forgia.compat.create.mixin.TorquePropagatorAccessor",
            "net.neoforged.forgia.compat.create.mixin.DeployerBlockEntityAccessor",
            "net.neoforged.forgia.compat.create.mixin.DeployerFakePlayerAccessor",
            "net.neoforged.forgia.compat.create.mixin.ArmBlockEntityAccessor",
            "net.neoforged.forgia.compat.create.mixin.ItemVaultBlockEntityAccessor",
            "net.neoforged.forgia.compat.create.mixin.FluidTankBlockEntityAccessor",
            "net.neoforged.forgia.compat.create.mixin.EncasedFanBlockEntityAccessor",
            "net.neoforged.forgia.compat.create.mixin.AirCurrentAccessor",
            "net.neoforged.forgia.compat.create.mixin.BeltBlockEntityAccessor",
            "net.neoforged.forgia.compat.create.mixin.BeltInventoryAccessor",
            "net.neoforged.forgia.compat.create.mixin.BeltBlockEntityTickMixin",
            "net.neoforged.forgia.compat.create.mixin.MechanicalCrafterBlockEntityAccessor",
            "net.neoforged.forgia.compat.create.mixin.MechanicalCrafterBlockEntityTickMixin",
            "net.neoforged.forgia.compat.create.mixin.FunnelBlockEntityAccessor",
            "net.neoforged.forgia.compat.create.mixin.FluidTransportBehaviourAccessor",
            "net.neoforged.forgia.compat.create.mixin.PipeConnectionAccessor",
            "net.neoforged.forgia.compat.create.mixin.PumpBlockEntityAccessor",
            "net.neoforged.forgia.compat.create.mixin.CapManipulationBehaviourBaseAccessor",
            "dev.ryanhcode.sable.neoforge.mixin.compatibility.create.inventory_manipulation.CapManipulationBehaviourBaseMixin",
            "dev.ryanhcode.sable.neoforge.mixin.compatibility.create.belt.BeltBlockEntityMixin",
            "cn.laowu.mod.mixin.CatBeltStyleMixin",
            "com.simibubi.create.foundation.mixin.accessor.ItemStackHandlerAccessor",
            // Audited third-party Mixins verified not to mutate steady-state tick() / lazyTick() paths:
            "dev.ryanhcode.sable.neoforge.mixin.compatibility.create.behaviour_compatibility.BlockEntityBehaviourMixin",
            "dev.ryanhcode.sable.neoforge.mixin.compatibility.create.deployer.DeployerBlockEntityMixin",
            "dev.ryanhcode.sable.neoforge.mixin.compatibility.create.mechnical_arm.MechanicalArmBlockEntity",
            "com.cake.azimuth.mixin.super_behavior.SmartBlockEntityMixin",
            "com.cake.azimuth.mixin.super_behavior.CachedRenderBBBlockEntityMixin",
            "com.kipti.bnb.mixin.KineticBlockEntityMixin",
            "com.kipti.bnb.mixin.flywheel_bearing.KineticNetworkMixin",
            "dev.latvian.mods.kubejs.core.mixin.ItemStackHandlerMixin",
            "plus.dragons.createdragonsplus.mixin.create.BlockEntityBehaviourMixin",
            "plus.dragons.createenchantmentindustry.mixin.SmartBlockEntityMixin",
            "plus.dragons.createenchantmentindustry.mixin.DeployerFakePlayerMixin",
            "com.nobodiiiii.createbiotech.mixin.SmartBlockEntityLegacyRefreshMixin",
            "com.cainiao1053.cbcmoreshells.mixin.FilteringBehaviourAccessor",
            "dev.simulated_team.simulated.mixin.extra_kinetics.KineticNetworkMixin",
            "dev.simulated_team.simulated.mixin.extra_kinetics.KineticBlockEntityMixin",
            "com.copycatsplus.copycats.mixin.foundation.copycat.CopycatBlockEntityMixin",
            "com.copycatsplus.copycats.mixin.copycat.SyncedBlockEntityMixin",
            "com.github.wallev.maidsoulkitchen.mixin.core.forge.ItemStackHandlerAccessor",
            "rbasamoyai.createbigcannons.mixin.compat.create.rotation_propagation.KineticNetworkMixin",
            "dev.simulated_team.simulated.mixin.extra_kinetics.goggle_tooltips.KineticBlockEntityMixin",
            "dev.simulated_team.simulated.mixin.extra_kinetics.GeneratingKineticBlockEntityMixin",
            "com.hlysine.create_connected.mixin.kineticbattery.KineticNetworkAccessor",
            "rbasamoyai.createbigcannons.mixin.compat.create.rotation_propagation.KineticBlockEntityMixin",
            "rbasamoyai.createbigcannons.mixin.compat.create.ChassisBlockEntityMixin",
            "com.sch246.muhc.mixin.KineticBlockEntityMixin",
            "io.github.forgestove.create_cyber_goggles.mixin.goggles.DeployerBlockEntityMixin",
            "io.github.forgestove.create_cyber_goggles.mixin.goggles.GeneratingKineticBlockEntityMixin",
            "io.github.forgestove.create_cyber_goggles.mixin.goggles.KineticBlockEntityMixin",
            "io.github.forgestove.create_cyber_goggles.mixin.goggles.PoweredShaftBlockEntityMixin",
            "io.github.forgestove.create_cyber_goggles.mixin.provider.ArmBlockEntityMixin",
            "io.github.forgestove.create_cyber_goggles.mixin.provider.DeployerBlockEntityMixin",
            "io.github.forgestove.create_cyber_goggles.mixin.provider.MillstoneBlockEntityMixin",
            "com.copycatsplus.copycats.neoforge.mixin.foundation.copycat.CopycatBlockEntityMixin",
            "com.copycatsplus.copycats.neoforge.mixin.foundation.copycat.multistate.MultiStateCopycatBlockEntityMixin",
            "com.hlysine.create_connected.mixin.compat.CopycatBlockEntityMixin",
            "net.pinkcats.createlazytick.mixin.Create.SmartBlockEntityControlMixin",
            "net.pinkcats.createlazytick.mixin.Create.ScrollOptionBehaviourGuardMixin",
            "net.pinkcats.createlazytick.mixin.OptElement.DeployerRecipeMixin",
            // Audited third-party Mixins on optional leaves (FluidTank, EncasedFan, Arm, Belt, Funnel, FluidTransport, Crafter):
            "com.gly091020.netMusicListNeoforge.mixin.create.ArmDancingMixin",
            "com.hlysine.create_connected.mixin.fluidvessel.FluidTankBlockEntityMixin",
            "com.nobodiiiii.createbiotech.mixin.FluidTankBlockEntityMixin",
            "com.kipti.bnb.mixin.dyeable.fluid_tank.FluidTankBlockEntityMixin",
            "dev.ryanhcode.sable.neoforge.mixin.compatibility.create.fans_provide_force.EncasedFanBlockEntityMixin",
            "io.github.forgestove.create_cyber_goggles.mixin.provider.EncasedFanBlockEntityMixin",
            "io.github.forgestove.create_cyber_goggles.mixin.provider.BeltBlockEntityMixin",
            "io.github.forgestove.create_cyber_goggles.mixin.provider.MechanicalCrafterBlockEntityMixin",
            "cn.laowu.mod.mixin.CatBeltCasingMixin",
            "com.nobodiiiii.createbiotech.mixin.FunnelBlockEntityMixin",
            "com.kipti.bnb.mixin.truss_pipe.FluidTransportBehaviourMixin",
            "dev.ryanhcode.sable.neoforge.mixin.compatibility.create.fluid_handling.PipeConnectionMixin",
            "dev.ryanhcode.sable.neoforge.mixin.compatibility.create.airflow.AirCurrentMixin",
            "plus.dragons.createdragonsplus.mixin.create.AirCurrentMixin",
            "com.copycatsplus.copycats.mixin.foundation.copycat.multistate.AirCurrentMixin",
            "dev.simulated_team.simulated.mixin.diving_boots.AirCurrentMixin",
            "cn.laowu.mod.mixin.AirCurrentFluidParticleMixin",
            "plus.dragons.createenchantmentindustry.integration.apotheosis.mixin.create.BeltInventoryMixin");
    /**
     * Audited Create Core base classes whose steady-state tick body is completely closed and audited.
     * Addon subclasses that extend one of these classes without overriding any tick-reachable method
     * inherit the exact audited tick closure.
     */
    private static final Set<Class<?>> INHERITABLE_BASES = Set.of(
            SmartBlockEntity.class,
            KineticBlockEntity.class,
            GeneratingKineticBlockEntity.class,
            DirectionalShaftHalvesBlockEntity.class,
            SimpleKineticBlockEntity.class,
            BracketedKineticBlockEntity.class,
            GearboxBlockEntity.class,
            SplitShaftBlockEntity.class,
            ClutchBlockEntity.class,
            GearshiftBlockEntity.class,
            GantryShaftBlockEntity.class,
            TurntableBlockEntity.class,
            RollerBlockEntity.class,
            ChassisBlockEntity.class,
            CopycatBlockEntity.class,
            CrateBlockEntity.class,
            CreativeCrateBlockEntity.class,
            ItemHatchBlockEntity.class,
            FluidPipeBlockEntity.class,
            StraightPipeBlockEntity.class);
    /**
     * Methods reachable during a steady-state SmartBlockEntity / KineticBlockEntity tick.
     * Any addon subclass overriding any of these methods cannot inherit the base tick audit.
     */
    private static final Set<String> TICK_REACHABLE_METHODS = Set.of(
            "tick",
            "lazyTick",
            "initialize",
            "needsSpeedUpdate",
            "attachKinetics",
            "validateKinetics",
            "updateGeneratedRotation",
            "forEachBehaviour",
            "getAllBehaviours",
            "getFlickerScore",
            "hasNetwork",
            "getOrCreateNetwork",
            "getSpeed",
            "getTheoreticalSpeed",
            "isOverStressed",
            "getLevel",
            "getBlockPos",
            "getBlockState",
            "getType",
            "isRemoved",
            "hasLevel",
            "hasAttachments");

    private CreateAudit() {}

    private static Class<?> loadInternalClass(String name) {
        try {
            return Class.forName(name, false, CreateAudit.class.getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Missing Create class: " + name, failure);
        }
    }

    public static boolean verify() {
        try {
            if (!SmartBlockEntityAccessor.class.isAssignableFrom(SmartBlockEntity.class)
                    || !KineticBlockEntityAccessor.class.isAssignableFrom(KineticBlockEntity.class)
                    || !KineticEffectHandlerAccessor.class.isAssignableFrom(KineticEffectHandler.class)
                    || !DeployerBlockEntityAccessor.class.isAssignableFrom(DeployerBlockEntity.class)
                    || !DeployerFakePlayerAccessor.class.isAssignableFrom(DeployerFakePlayer.class)
                    || !ArmBlockEntityAccessor.class.isAssignableFrom(ArmBlockEntity.class)
                    || !ItemVaultBlockEntityAccessor.class.isAssignableFrom(ItemVaultBlockEntity.class)
                    || !FluidTankBlockEntityAccessor.class.isAssignableFrom(FluidTankBlockEntity.class)
                    || !EncasedFanBlockEntityAccessor.class.isAssignableFrom(EncasedFanBlockEntity.class)
                    || !AirCurrentAccessor.class.isAssignableFrom(AirCurrent.class)
                    || !BeltBlockEntityAccessor.class.isAssignableFrom(BeltBlockEntity.class)
                    || !BeltInventoryAccessor.class.isAssignableFrom(BeltInventory.class)
                    || !MechanicalCrafterBlockEntityAccessor.class.isAssignableFrom(MechanicalCrafterBlockEntity.class)
                    || !FunnelBlockEntityAccessor.class.isAssignableFrom(FunnelBlockEntity.class)
                    || !FluidTransportBehaviourAccessor.class.isAssignableFrom(FluidTransportBehaviour.class)
                    || !PipeConnectionAccessor.class.isAssignableFrom(PipeConnection.class)
                    || !PumpBlockEntityAccessor.class.isAssignableFrom(PumpBlockEntity.class)
                    || !CapManipulationBehaviourBaseAccessor.class.isAssignableFrom(CapManipulationBehaviourBase.class)) {
                LogUtils.getLogger().warn("Forgia Create accessors missing; Create ticks remain serial");
                return false;
            }
            boolean allOk = true;
            for (final var entry : AUDITED.entrySet()) {
                final Class<?> type = entry.getKey();
                try (final var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                    if (input == null || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())).equals(entry.getValue())) {
                        LogUtils.getLogger().warn("Unaudited Create bytecode {}; Create ticks remain serial", type.getName());
                        allOk = false;
                        continue;
                    }
                }
                if (!knownTransformations(type)) allOk = false;
            }
            if (!knownTransformations(ItemStackHandler.class)) allOk = false;
            return allOk;
        } catch (IOException | NoSuchAlgorithmException | RuntimeException | LinkageError failure) {
            LogUtils.getLogger().warn("Cannot verify Create tick implementation; Create ticks remain serial", failure);
            return false;
        }
    }

    public static boolean isOptionalLeafVerified(Class<?> type) {
        final String expectedHash = OPTIONAL_LEAF_AUDITED.get(type);
        if (expectedHash == null) return false;
        try (final var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (input == null || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())).equals(expectedHash)) {
                return false;
            }
        } catch (IOException | NoSuchAlgorithmException | RuntimeException | LinkageError failure) {
            return false;
        }
        return knownTransformations(type);
    }

    /**
     * Verifies whether a concrete addon {@link SmartBlockEntity} subclass inherits an audited Create Core
     * steady-state tick closure without overriding any tick-reachable method or carrying unknown Mixin merges.
     * Anonymous, local, synthetic, and abstract classes are always rejected.
     */
    public static boolean isPureInheritedSmartBlockEntity(Class<?> type) {
        if (type == null || !SmartBlockEntity.class.isAssignableFrom(type)
                || Modifier.isAbstract(type.getModifiers())
                || type.isAnonymousClass() || type.isLocalClass() || type.isSynthetic()) {
            return false;
        }
        if (INHERITABLE_BASES.contains(type)) {
            return true;
        }
        try {
            Class<?> current = type;
            while (current != null && current != BlockEntity.class) {
                if (INHERITABLE_BASES.contains(current)) {
                    return true;
                }
                if (AUDITED.containsKey(current) || OPTIONAL_LEAF_AUDITED.containsKey(current)) {
                    // Audited non-inheritable classes (such as MillstoneBlockEntity or WaterWheelBlockEntity)
                    // have custom tick bodies and cannot be inherited as pure base closures.
                    return false;
                }
                if (current.isAnonymousClass() || current.isLocalClass() || current.isSynthetic()) {
                    return false;
                }
                for (final var method : current.getDeclaredMethods()) {
                    if (TICK_REACHABLE_METHODS.contains(method.getName())) {
                        return false;
                    }
                }
                if (!knownTransformations(current)) {
                    return false;
                }
                try (final var input = current.getResourceAsStream("/" + current.getName().replace('.', '/') + ".class")) {
                    if (input == null) {
                        return false;
                    }
                }
                current = current.getSuperclass();
            }
            return false;
        } catch (IOException | RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** Target-mod knowledge lives here; Core consumes only the generic frozen audit contract. */
    public static AuditedBlockEntityTickAdapter.Audit nativeAudit(Set<Class<?>> sources) {
        final var implementations = new ArrayList<AuditedBlockEntityTickAdapter.Implementation>();
        final Set<Class<?>> included = Collections.newSetFromMap(new IdentityHashMap<>());
        AUDITED.forEach((type, hash) -> {
            included.add(type);
            implementations.add(new AuditedBlockEntityTickAdapter.Implementation(type, hash, allowedMixinsFor(type)));
        });
        for (final Class<?> type : Set.of(CreateTickAdmission.class, CreateAudit.class, SmartBlockEntityAccessor.class,
                KineticBlockEntityAccessor.class, KineticEffectHandlerAccessor.class,
                DeployerBlockEntityAccessor.class, DeployerFakePlayerAccessor.class, ArmBlockEntityAccessor.class)) {
            if (included.add(type)) {
                implementations.add(new AuditedBlockEntityTickAdapter.Implementation(type, ownFingerprint(type), Set.of()));
            }
        }
        for (final Class<?> source : sources) {
            for (Class<?> current = source; current != null && current != BlockEntity.class; current = current.getSuperclass()) {
                if (included.add(current)) {
                    final String hash = OPTIONAL_LEAF_AUDITED.getOrDefault(current, ownFingerprint(current));
                    implementations.add(new AuditedBlockEntityTickAdapter.Implementation(current, hash, allowedMixinsFor(current)));
                }
            }
        }
        return new AuditedBlockEntityTickAdapter.Audit("region-threading/CREATE-OPTIMIZATION-PLAN.md", "create-6.0.10-local-resource-v2",
                sources, Set.of(SmartBlockEntityTicker.class), implementations);
    }

    private static Set<String> allowedMixinsFor(Class<?> type) {
        final Set<String> allowed = new HashSet<>();
        for (final var method : type.getDeclaredMethods()) {
            final MixinMerged merged = method.getAnnotation(MixinMerged.class);
            if (merged != null && ACCESSORS.contains(merged.mixin())) {
                allowed.add(merged.mixin());
            }
        }
        return Set.copyOf(allowed);
    }

    private static String ownFingerprint(Class<?> type) {
        try (final var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (input == null) throw new IllegalStateException("Missing adapter implementation: " + type.getName());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Cannot describe adapter implementation", unavailable);
        }
    }

    private static boolean knownTransformations(Class<?> type) {
        boolean ok = true;
        for (final var method : type.getDeclaredMethods()) {
            final MixinMerged merged = method.getAnnotation(MixinMerged.class);
            if (merged != null && !ACCESSORS.contains(merged.mixin())) {
                LogUtils.getLogger().warn("Unverified Create tick transformation {} on {}; Create ticks remain serial", merged.mixin(), type.getName());
                ok = false;
            }
        }
        return ok;
    }
}
