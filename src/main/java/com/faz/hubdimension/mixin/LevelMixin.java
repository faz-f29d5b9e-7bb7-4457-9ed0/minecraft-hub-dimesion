package com.faz.hubdimension.mixin;

import com.faz.hubdimension.HubConfig;
import com.faz.hubdimension.HubEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {
    // In the hub with timeMode = STATIC, the server itself reports the static time,
    // so vanilla's own time packets already contain the right value.
    @Inject(method = "getDayTime", at = @At("HEAD"), cancellable = true)
    private void hubdimension$staticTime(CallbackInfoReturnable<Long> cir) {
        Level self = (Level) (Object) this;
        if (self instanceof ServerLevel
                && HubEvents.isHub(self)
                && HubConfig.TIME_MODE.get() == HubConfig.TimeMode.STATIC) {
            cir.setReturnValue(HubConfig.STATIC_TIME.get().longValue());
        }
    }
}