package ichttt.mods.firstaid.mixin;

import ichttt.mods.firstaid.common.damagesystem.distribution.HealthDistribution;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(FoodData.class)
public abstract class FoodDataMixin {
    @ModifyVariable(method = "tick", at = @At("STORE"), ordinal = 0)
    private boolean firstaid$gateNaturalRegen(boolean naturalRegen, Player player) {
        return naturalRegen && HealthDistribution.canApplyNaturalRegen(player);
    }
}
