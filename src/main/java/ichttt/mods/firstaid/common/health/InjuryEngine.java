// AGENT-DONE(claude): tarkov-health-engine
package ichttt.mods.firstaid.common.health;

import ichttt.mods.firstaid.FirstAid;
import ichttt.mods.firstaid.FirstAidConfig;
import ichttt.mods.firstaid.api.damage.HitProfile;
import ichttt.mods.firstaid.api.damage.HitProfiles;
import ichttt.mods.firstaid.api.damagesystem.AbstractDamageablePart;
import ichttt.mods.firstaid.api.damagesystem.AbstractPlayerDamageModel;
import ichttt.mods.firstaid.api.enums.EnumPlayerPart;
import ichttt.mods.firstaid.common.RegistryObjects;
import ichttt.mods.firstaid.common.damagesystem.PlayerDamageModel;
import ichttt.mods.firstaid.common.util.CommonUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * EFT-style injuries on top of the plain limb hit points: bleeds (light/heavy) that drain a limb every second and
 * fractures that cripple arms and legs until splinted.
 * <p>
 * The decision functions are static and free of game state so they can be tested in isolation.
 */
public final class InjuryEngine {
    public static final TagKey<DamageType> CAUSES_BLEEDING = TagKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(FirstAid.MODID, "causes_bleeding"));
    public static final ResourceKey<DamageType> BLEED_OUT = ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(FirstAid.MODID, "bleed_out"));

    private static final ResourceLocation ATTR_FRACTURE_SPEED = ResourceLocation.fromNamespaceAndPath(FirstAid.MODID, "fracture_speed");
    private static final ResourceLocation ATTR_FRACTURE_JUMP = ResourceLocation.fromNamespaceAndPath(FirstAid.MODID, "fracture_jump");
    private static final ResourceLocation ATTR_FRACTURE_ATTACK = ResourceLocation.fromNamespaceAndPath(FirstAid.MODID, "fracture_attack_speed");
    private static final ResourceLocation ATTR_FRACTURE_BREAK = ResourceLocation.fromNamespaceAndPath(FirstAid.MODID, "fracture_break_speed");

    private static final double PAINKILLER_FRACTURE_FACTOR = 0.4D;
    private static final double MAX_SPEED_PENALTY = 0.85D;
    private static final double MAX_JUMP_PENALTY = 0.6D;
    private static final double LEG_JUMP_PENALTY = 0.30D;
    private static final double ARM_ATTACK_PENALTY = 0.25D;
    private static final double ARM_BREAK_PENALTY = 0.30D;
    private static final double MAX_ARM_PENALTY = 0.7D;
    private static final double MAX_BLEED_CHANCE = 0.95D;

    private InjuryEngine() {
    }

    public enum FractureCause {
        FALL, EXPLOSION, PENETRATING, OTHER
    }

    // ---------------------------------------------------------------- pure decision functions

    public static double bleedChance(double base, double perHitFraction, double hitFraction) {
        return Mth.clamp(base + perHitFraction * Math.max(0.0D, hitFraction), 0.0D, MAX_BLEED_CHANCE);
    }

    public static byte bleedLevelFor(double hitFraction, double heavyThreshold) {
        return hitFraction >= heavyThreshold ? AbstractDamageablePart.BLEED_HEAVY : AbstractDamageablePart.BLEED_LIGHT;
    }

    public static double fractureChance(FractureCause cause, double hitFraction) {
        return switch (cause) {
            case FALL -> Mth.clamp((hitFraction - 0.25D) / 0.5D, 0.0D, 1.0D);
            case EXPLOSION -> Mth.clamp(hitFraction * 1.2D, 0.0D, 0.6D);
            case PENETRATING -> hitFraction >= 0.4D ? Mth.clamp(0.1D + (hitFraction - 0.4D), 0.0D, 0.5D) : 0.0D;
            case OTHER -> 0.0D;
        };
    }

    /** Hit points a bleed of the given level drains from a part with {@code maxHealth} in one second. */
    public static float bleedDrainPerSecond(int maxHealth, byte level, double lightPercent, double heavyPercent) {
        double percent = switch (level) {
            case AbstractDamageablePart.BLEED_LIGHT -> lightPercent;
            case AbstractDamageablePart.BLEED_HEAVY -> heavyPercent;
            default -> 0.0D;
        };
        return (float) (maxHealth * percent / 100.0D);
    }

    public static boolean canFracture(EnumPlayerPart part) {
        return part != EnumPlayerPart.HEAD && part != EnumPlayerPart.BODY;
    }

    public static boolean isLegPart(EnumPlayerPart part) {
        return part == EnumPlayerPart.LEFT_LEG || part == EnumPlayerPart.RIGHT_LEG
                || part == EnumPlayerPart.LEFT_FOOT || part == EnumPlayerPart.RIGHT_FOOT;
    }

    public static boolean isArmPart(EnumPlayerPart part) {
        return part == EnumPlayerPart.LEFT_ARM || part == EnumPlayerPart.RIGHT_ARM;
    }

    /** Tourniquets only work where they can actually be tied. */
    public static boolean isLimb(EnumPlayerPart part) {
        return canFracture(part);
    }

    // ---------------------------------------------------------------- treatment helpers

    public static boolean stopBleed(AbstractDamageablePart part, byte strongestLevelStopped) {
        if (part.bleedLevel == AbstractDamageablePart.BLEED_NONE || part.bleedLevel > strongestLevelStopped) {
            return false;
        }
        part.bleedLevel = AbstractDamageablePart.BLEED_NONE;
        return true;
    }

    public static boolean fixFracture(AbstractDamageablePart part) {
        if (!part.fractured) {
            return false;
        }
        part.fractured = false;
        return true;
    }

    // ---------------------------------------------------------------- game hooks

    /**
     * Rolls bleeds and fractures for every part that lost hit points between {@code before} and {@code after}.
     */
    public static void onDamaged(Player player, AbstractPlayerDamageModel after, AbstractPlayerDamageModel before, DamageSource source) {
        if (player.level().isClientSide()) {
            return;
        }
        HitProfile profile = HitProfiles.current();
        boolean bleedSource = isBleedingSource(source) || profile != HitProfile.NEUTRAL;
        FractureCause cause = profile.explosive() ? FractureCause.EXPLOSION : fractureCause(source, bleedSource);
        RandomSource random = player.getRandom();
        FirstAidConfig.Server config = FirstAidConfig.SERVER;
        boolean changed = false;
        for (EnumPlayerPart partId : EnumPlayerPart.VALUES) {
            AbstractDamageablePart now = after.getFromEnum(partId);
            AbstractDamageablePart previous = before.getFromEnum(partId);
            float lost = previous.currentHealth - now.currentHealth;
            if (lost <= 0.001F || now.getMaxHealth() <= 0) {
                continue;
            }
            double hitFraction = lost / (double) now.getMaxHealth();
            if (bleedSource && config.bleedingEnabled.get()) {
                double chance = Math.min(MAX_BLEED_CHANCE, bleedChance(config.bleedChanceBase.get(), config.bleedChancePerHitFraction.get(), hitFraction) + profile.bleedChanceBonus());
                if (random.nextDouble() < chance) {
                    byte level = bleedLevelFor(hitFraction, profile.heavyBleedHitFractionOr(config.heavyBleedHitFraction.get().floatValue()));
                    if (level > now.bleedLevel) {
                        now.bleedLevel = level;
                        changed = true;
                    }
                }
            }
            if (!now.fractured && canFracture(partId) && config.fracturesEnabled.get()) {
                if (random.nextDouble() < Math.min(1.0D, fractureChance(cause, hitFraction) + profile.fractureChanceBonus())) {
                    now.fractured = true;
                    changed = true;
                }
            }
        }
        if (changed) {
            after.scheduleResync();
        }
    }

    public static boolean isBleedingSource(DamageSource source) {
        return source.is(CAUSES_BLEEDING) || source.getDirectEntity() instanceof Projectile;
    }

    private static FractureCause fractureCause(DamageSource source, boolean penetrating) {
        if (source.is(DamageTypes.FALL)) {
            return FractureCause.FALL;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            return FractureCause.EXPLOSION;
        }
        return penetrating ? FractureCause.PENETRATING : FractureCause.OTHER;
    }

    /** Server tick: drains bleeding parts once per second and keeps the fracture penalties applied. */
    public static void tick(Player player, PlayerDamageModel model) {
        if (player.level().isClientSide() || !player.isAlive()) {
            return;
        }
        if (player.tickCount % 20 == 0) {
            tickBleeding(player, model);
        }
        updateFractureEffects(player, model);
    }

    private static void tickBleeding(Player player, PlayerDamageModel model) {
        FirstAidConfig.Server config = FirstAidConfig.SERVER;
        boolean anyBleed = false;
        boolean drained = false;
        float drainedTotal = 0.0F;
        boolean stateChanged = false;
        for (AbstractDamageablePart part : model) {
            if (part.bleedLevel == AbstractDamageablePart.BLEED_NONE) {
                continue;
            }
            anyBleed = true;
            if (!config.bleedingEnabled.get() || (part.currentHealth <= 0.0F && !part.canCauseDeath)) {
                part.bleedLevel = AbstractDamageablePart.BLEED_NONE;
                stateChanged = true;
                continue;
            }
            float drain = bleedDrainPerSecond(part.getMaxHealth(), part.bleedLevel, config.lightBleedPercentPerSecond.get(), config.heavyBleedPercentPerSecond.get());
            drain *= model.getIncomingPartDamageMultiplier(part);
            if (drain > 0.0F && part.currentHealth > 0.0F) {
                part.damage(drain, player, false, 0.0F);
                drained = true;
                drainedTotal += drain;
            }
        }
        if (!anyBleed) {
            return;
        }
        if (drained) {
            DamageSource source = bleedSource(player);
            // Without a combat entry a bleed-out would be reported with the generic death message.
            player.getCombatTracker().recordDamage(source, HealthUnits.toVanilla(drainedTotal, player, model));
            model.handlePostDamage(player, source);
            model.syncVanillaHealth(player);
            if (model.isDead(player)) {
                CommonUtils.killPlayer(model, player, source);
            }
        }
        if ((drained || stateChanged) && player instanceof ServerPlayer serverPlayer) {
            CommonUtils.syncDamageModel(serverPlayer);
        }
    }

    public static DamageSource bleedSource(Player player) {
        Registry<DamageType> registry = player.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return registry.getHolder(BLEED_OUT).map(DamageSource::new).orElseGet(() -> player.damageSources().generic());
    }

    private static void updateFractureEffects(Player player, PlayerDamageModel model) {
        int legFractures = 0;
        int armFractures = 0;
        for (AbstractDamageablePart part : model) {
            if (!part.fractured) {
                continue;
            }
            if (isLegPart(part.part)) {
                legFractures++;
            } else if (isArmPart(part.part)) {
                armFractures++;
            }
        }
        boolean suppressed = player.hasEffect(RegistryObjects.PAINKILLER_EFFECT) || player.hasEffect(RegistryObjects.MORPHINE_EFFECT);
        int signature = legFractures | (armFractures << 4) | ((suppressed ? 1 : 0) << 8);
        if (signature == model.fractureEffectSignature) {
            return;
        }
        model.fractureEffectSignature = signature;
        double factor = suppressed ? PAINKILLER_FRACTURE_FACTOR : 1.0D;
        AttributeMap attributes = player.getAttributes();
        double speed = Math.min(MAX_SPEED_PENALTY, legFractures * FirstAidConfig.SERVER.fractureSpeedPenalty.get() * factor);
        double jump = Math.min(MAX_JUMP_PENALTY, legFractures * LEG_JUMP_PENALTY * factor);
        double attack = Math.min(MAX_ARM_PENALTY, armFractures * ARM_ATTACK_PENALTY * factor);
        double dig = Math.min(MAX_ARM_PENALTY, armFractures * ARM_BREAK_PENALTY * factor);
        applyModifier(attributes, Attributes.MOVEMENT_SPEED, ATTR_FRACTURE_SPEED, -speed);
        applyModifier(attributes, Attributes.JUMP_STRENGTH, ATTR_FRACTURE_JUMP, -jump);
        applyModifier(attributes, Attributes.ATTACK_SPEED, ATTR_FRACTURE_ATTACK, -attack);
        applyModifier(attributes, Attributes.BLOCK_BREAK_SPEED, ATTR_FRACTURE_BREAK, -dig);
    }

    private static void applyModifier(AttributeMap map, Holder<Attribute> attribute, ResourceLocation id, double amount) {
        AttributeInstance instance = map.getInstance(attribute);
        if (instance == null) {
            return;
        }
        if (amount == 0.0D) {
            if (instance.hasModifier(id)) {
                instance.removeModifier(id);
            }
            return;
        }
        AttributeModifier existing = instance.getModifier(id);
        if (existing == null || Double.compare(existing.amount(), amount) != 0) {
            instance.removeModifier(id);
            instance.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }
}
